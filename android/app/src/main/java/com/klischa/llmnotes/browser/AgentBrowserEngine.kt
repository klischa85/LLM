package com.klischa.llmnotes.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

enum class BrowserSearchEngine(
    val id: String,
    val displayName: String,
    val chipLabel: String
) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "🦆 DDG"),
    GOOGLE("google", "Google", "🔍 Google"),
    YANDEX("yandex", "Яндекс", "🇷🇺 Яндекс");

    fun buildSearchUrl(query: String): String {
        val encoded = try {
            URLEncoder.encode(query.trim(), "UTF-8")
        } catch (_: Exception) {
            query.trim().replace(" ", "+")
        }
        return when (this) {
            DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded&kl=ru-ru"
            GOOGLE -> "https://www.google.com/search?hl=ru&q=$encoded"
            YANDEX -> "https://ya.ru/search/?text=$encoded"
        }
    }

    companion object {
        fun fromString(raw: String?): BrowserSearchEngine? {
            val v = raw?.trim()?.lowercase(Locale.US) ?: return null
            return when {
                v in setOf("duckduckgo", "ddg", "duck") -> DUCKDUCKGO
                v in setOf("google", "g") -> GOOGLE
                v in setOf("yandex", "ya", "яндекс") -> YANDEX
                else -> values().find { it.name.equals(v, ignoreCase = true) || it.id.equals(v, ignoreCase = true) }
            }
        }
    }
}

data class BrowserUiState(
    val currentUrl: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val searchEngine: BrowserSearchEngine = BrowserSearchEngine.DUCKDUCKGO,
    val incognitoMode: Boolean = false,
    val desktopMode: Boolean = false,
    val agentActionLabel: String? = null,
    val lastError: String? = null,
    val hasLoadedPage: Boolean = false,
    val indexedElementsCount: Int = 0
) {
    val shortHost: String
        get() {
            if (currentUrl.isBlank() || currentUrl == "about:blank") return ""
            return try {
                Uri.parse(currentUrl).host?.removePrefix("www.").orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
}

data class BrowserElementInfo(
    val id: Int,
    val tag: String,
    val type: String,
    val label: String,
    val href: String,
    val value: String,
    val isSensitive: Boolean
)

data class BrowserActionResult(
    val ok: Boolean,
    val summary: String,
    val output: String,
    val screenshotDataUrl: String? = null,
    val savedScreenshotPath: String? = null
)

/**
 * Полноценный встроенный интерактивный браузер для ИИ-агента на базе Chromium Android WebView.
 *
 * Особенности:
 * - Работает синхронно с видимым окном браузера в UI: пользователь видит все переходы/клики агента
 *   в реальном времени и может перехватить управление (войти в аккаунт, пройти капчу).
 * - Продолжает работать и в свёрнутом состоянии (WebView остаётся живым в окне с реальными размерами).
 * - Поддерживает 3 поисковика (DuckDuckGo / Google / Яндекс), режим Инкогнито, ПК/Моб User-Agent,
 *   очистку кук, DOM-индексацию интерактивных элементов [1..80], React/Vue-совместимый ввод,
 *   снятие JPEG-скриншотов для Vision-моделей и безопасный фильтр URL-схем.
 */
object AgentBrowserEngine {
    private const val TAG = "AgentBrowserEngine"

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val NAV_TIMEOUT_MS = 22_000L
    private const val JS_TIMEOUT_MS = 10_000L
    private const val MAX_OUTPUT_CHARS = 14_000

    private val SENSITIVE_ACTION_KEYWORDS = listOf(
        "купить", "оплат", "заказ", "перевест", "списать", "удал", "стереть", "уничтож",
        "отправ", "опублик", "подтверд", "выйти", "выход", "деактив", "закрыть счёт", "закрыть счет",
        "buy", "pay", "payment", "checkout", "purchase", "order", "delete", "remove", "erase",
        "send", "submit", "publish", "post", "transfer", "confirm", "logout", "log out", "sign out", "deactivate"
    )

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val actionMutex = Mutex()

    @Volatile
    private var attachedWebView: WebView? = null

    @Volatile
    private var fallbackWebView: WebView? = null

    @Volatile
    private var pageLoadDeferred: CompletableDeferred<String?>? = null

    @Volatile
    private var lastIndexedElements: Map<Int, BrowserElementInfo> = emptyMap()

    // =========================================================================
    // Жизненный цикл и конфигурация WebView
    // =========================================================================

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(webView: WebView) {
        val current = _state.value
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = if (current.desktopMode) DESKTOP_UA else MOBILE_UA
        settings.cacheMode = if (current.incognitoMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT

        applyCookiePolicy(webView, current.incognitoMode)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUri = request?.url ?: return false
                val scheme = targetUri.scheme?.lowercase(Locale.US).orEmpty()
                // Разрешаем только безопасные веб-схемы внутри браузера агента
                if (scheme == "http" || scheme == "https" || scheme == "about") {
                    return false
                }
                Log.w(TAG, "Заблокирован переход по небезопасной схеме: $targetUri")
                _state.update { it.copy(lastError = "Заблокирована внешняя схема: $scheme://") }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                _state.update {
                    it.copy(
                        currentUrl = url ?: it.currentUrl,
                        isLoading = true,
                        progress = 10,
                        lastError = null,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val finalUrl = url ?: view?.url.orEmpty()
                val title = view?.title.orEmpty()
                _state.update {
                    it.copy(
                        currentUrl = finalUrl,
                        title = title,
                        isLoading = false,
                        progress = 100,
                        hasLoadedPage = finalUrl.isNotBlank() && finalUrl != "about:blank",
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true
                    )
                }
                if (!_state.value.incognitoMode) {
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {}
                }
                pageLoadDeferred?.complete(finalUrl)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString()?.take(180) ?: "Ошибка загрузки страницы"
                    _state.update { it.copy(lastError = desc, isLoading = false) }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                _state.update {
                    it.copy(
                        progress = newProgress.coerceIn(0, 100),
                        isLoading = newProgress < 100
                    )
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) {
                    _state.update { it.copy(title = title) }
                }
            }
        }
    }

    fun attachWebView(webView: WebView) {
        attachedWebView = webView
        configureWebView(webView)
        // Если ранее открывали страницу в fallbackWebView — перенесём текущий URL в UI WebView
        val prevUrl = _state.value.currentUrl
        if (prevUrl.startsWith("http://") || prevUrl.startsWith("https://")) {
            if (webView.url.isNullOrBlank()) {
                webView.loadUrl(prevUrl)
            }
        }
    }

    fun detachWebView(webView: WebView) {
        if (attachedWebView === webView) {
            attachedWebView = null
        }
    }

    private fun applyCookiePolicy(webView: WebView?, incognito: Boolean) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(!incognito)
            if (webView != null) {
                cm.setAcceptThirdPartyCookies(webView, !incognito)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка настройки CookieManager: ${e.message}")
        }
    }

    fun setSearchEngine(engine: BrowserSearchEngine) {
        _state.update { it.copy(searchEngine = engine) }
    }

    fun setIncognitoMode(enabled: Boolean) {
        val wasIncognito = _state.value.incognitoMode
        _state.update { it.copy(incognitoMode = enabled) }
        val wv = attachedWebView ?: fallbackWebView
        if (wv != null) {
            wv.post {
                wv.settings.cacheMode = if (enabled) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                applyCookiePolicy(wv, enabled)
                if (enabled || wasIncognito) {
                    clearDataOnMainThread(wv)
                }
            }
        } else if (enabled || wasIncognito) {
            clearCookiesOnly()
        }
    }

    fun setDesktopMode(enabled: Boolean) {
        _state.update { it.copy(desktopMode = enabled) }
        val wv = attachedWebView ?: fallbackWebView ?: return
        wv.post {
            wv.settings.userAgentString = if (enabled) DESKTOP_UA else MOBILE_UA
            if (!wv.url.isNullOrBlank() && wv.url != "about:blank") {
                wv.reload()
            }
        }
    }

    fun clearBrowsingData() {
        val wv = attachedWebView ?: fallbackWebView
        if (wv != null) {
            wv.post { clearDataOnMainThread(wv) }
        } else {
            clearCookiesOnly()
        }
        lastIndexedElements = emptyMap()
        _state.update {
            it.copy(
                indexedElementsCount = 0,
                lastError = null
            )
        }
    }

    private fun clearCookiesOnly() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка очистки кук: ${e.message}")
        }
    }

    private fun clearDataOnMainThread(wv: WebView) {
        try {
            clearCookiesOnly()
            wv.clearCache(true)
            wv.clearFormData()
            wv.clearHistory()
            _state.update { it.copy(canGoBack = false, canGoForward = false) }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка очистки WebView: ${e.message}")
        }
    }

    // =========================================================================
    // Ручное управление из интерфейса встроенного браузера
    // =========================================================================

    fun navigateUser(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val targetUrl = normalizeUserInputToUrl(trimmed, _state.value.searchEngine) ?: return
        val wv = attachedWebView ?: fallbackWebView ?: return
        wv.post {
            wv.loadUrl(targetUrl)
        }
    }

    fun goBackUser() {
        val wv = attachedWebView ?: fallbackWebView ?: return
        wv.post { if (wv.canGoBack()) wv.goBack() }
    }

    fun goForwardUser() {
        val wv = attachedWebView ?: fallbackWebView ?: return
        wv.post { if (wv.canGoForward()) wv.goForward() }
    }

    fun reloadOrStopUser() {
        val wv = attachedWebView ?: fallbackWebView ?: return
        wv.post {
            if (_state.value.isLoading) {
                wv.stopLoading()
                _state.update { it.copy(isLoading = false) }
            } else {
                wv.reload()
            }
        }
    }

    // =========================================================================
    // Вспомогательные функции проверки безопасности кликов
    // =========================================================================

    fun getElementInfo(elementId: Int): BrowserElementInfo? = lastIndexedElements[elementId]

    /**
     * Проверяет, выглядит ли целевой клик потенциально опасным (покупка, оплата, удаление, отправка формы, выход).
     */
    fun analyzeClickSensitivity(elementId: Int?, selector: String?, textQuery: String?): Pair<Boolean, String> {
        val info = elementId?.let { lastIndexedElements[it] }
        val targetDesc = when {
            info != null -> "[${info.id}] <${info.tag}> \"${info.label.ifBlank { info.href }.take(80)}\""
            !textQuery.isNullOrBlank() -> "текст \"${textQuery.take(80)}\""
            !selector.isNullOrBlank() -> "селектор \"${selector.take(80)}\""
            else -> "неизвестный элемент"
        }

        if (info?.isSensitive == true) return Pair(true, targetDesc)

        val combined = listOfNotNull(
            info?.label,
            info?.href,
            info?.type,
            textQuery,
            selector
        ).joinToString(" ").lowercase(Locale.ROOT)

        val isSensitive = info?.type?.equals("submit", ignoreCase = true) == true ||
            SENSITIVE_ACTION_KEYWORDS.any { combined.contains(it) }

        return Pair(isSensitive, targetDesc)
    }

    // =========================================================================
    // Основные инструменты агента (вызываются из AgentTools)
    // =========================================================================

    /**
     * 1. Веб-поиск через DuckDuckGo (быстрый HTTP + WebView fallback), Google или Яндекс.
     */
    suspend fun search(
        context: Context,
        query: String,
        engineOverride: String? = null,
        maxResults: Int = 8
    ): BrowserActionResult = actionMutex.withLock {
        val q = query.trim()
        if (q.isEmpty()) {
            return BrowserActionResult(false, "Пустой поисковый запрос", "Ошибка: укажите параметр query.")
        }
        val engine = BrowserSearchEngine.fromString(engineOverride) ?: _state.value.searchEngine
        val limit = maxResults.coerceIn(1, 15)

        setAgentAction("Поиск (${engine.displayName}): ${q.take(40)}")
        try {
            // Для DuckDuckGo пробуем сверхбыстрый HTTP HTML-поиск без лишнего рендеринга
            if (engine == BrowserSearchEngine.DUCKDUCKGO) {
                val fastResults = fetchDuckDuckGoHtmlResults(q, limit)
                if (fastResults.isNotEmpty()) {
                    val formatted = formatSearchResults(engine, q, fastResults)
                    return BrowserActionResult(
                        ok = true,
                        summary = "${engine.displayName}: найдено ${fastResults.size} по «${q.take(36)}»",
                        output = formatted
                    )
                }
            }

            // Для Google / Яндекс (или fallback DuckDuckGo) загружаем поиск прямо в живом WebView
            val searchUrl = engine.buildSearchUrl(q)
            val wv = ensureWebView(context)
            val loadErr = loadUrlAndWait(wv, searchUrl, waitAfterMs = 1500L)
            if (loadErr != null) {
                return BrowserActionResult(false, "Ошибка поиска: $loadErr", "Ошибка загрузки поиска ${engine.displayName}: $loadErr")
            }

            val parsed = extractSearchResultsFromWebView(wv, engine, limit)
            if (parsed.isNotEmpty()) {
                val formatted = formatSearchResults(engine, q, parsed)
                // Также индексируем страницу поиска, чтобы агент при желании мог кликнуть по элементу
                val snap = extractPageSnapshot(wv, maxChars = 1200, includeElements = true)
                return BrowserActionResult(
                    ok = true,
                    summary = "${engine.displayName}: найдено ${parsed.size} по «${q.take(36)}»",
                    output = formatted + "\n\n(Страница поиска открыта во встроенном браузере; URL: ${snap.url})"
                )
            }

            // Если поисковик показал капчу или нестандартную выдачу — отдаём снимок самой страницы
            val fallbackSnap = extractPageSnapshot(wv, maxChars = 5000, includeElements = true)
            val captchaHint = detectCaptchaOrConsent(fallbackSnap.title, fallbackSnap.url, fallbackSnap.text)
            val note = buildString {
                append("Поиск ${engine.displayName} по запросу «$q» открыт в браузере, но стандартные карточки результатов не распознаны.\n")
                if (captchaHint != null) {
                    append("⚠️ $captchaHint\n")
                }
                append("\n")
                append(formatSnapshotForLlm(fallbackSnap))
            }
            BrowserActionResult(
                ok = captchaHint == null,
                summary = if (captchaHint != null) "${engine.displayName}: требуется проверка/капча" else "${engine.displayName}: страница выдачи открыта",
                output = note
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 2. Открыть URL во встроенном WebView и сразу вернуть текст + карту интерактивных элементов.
     */
    suspend fun openUrl(
        context: Context,
        rawUrl: String,
        waitMs: Long = 1400L
    ): BrowserActionResult = actionMutex.withLock {
        val url = normalizeStrictWebUrl(rawUrl)
            ?: return BrowserActionResult(
                ok = false,
                summary = "Недопустимый URL: ${rawUrl.take(40)}",
                output = "Ошибка: допускаются только http:// и https:// URL. Получено: '$rawUrl'."
            )

        setAgentAction("Открытие: ${url.take(48)}")
        try {
            val wv = ensureWebView(context)
            val err = loadUrlAndWait(wv, url, waitAfterMs = waitMs.coerceIn(300L, 6000L))
            if (err != null) {
                return BrowserActionResult(false, "Ошибка загрузки: $err", "Не удалось открыть $url: $err")
            }
            val snap = extractPageSnapshot(wv, maxChars = 8000, includeElements = true)
            val host = try { Uri.parse(snap.url).host?.removePrefix("www.") ?: url.take(40) } catch (_: Exception) { url.take(40) }
            BrowserActionResult(
                ok = true,
                summary = "Открыто: $host (${snap.title.take(36).ifBlank { "без заголовка" }})",
                output = formatSnapshotForLlm(snap)
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 3. Прочитать текущую страницу во встроенном браузере (текст + пронумерованные элементы).
     */
    suspend fun readPage(
        context: Context,
        maxChars: Int = 8000,
        includeElements: Boolean = true
    ): BrowserActionResult = actionMutex.withLock {
        setAgentAction("Чтение страницы...")
        try {
            val wv = ensureWebView(context)
            val currentUrl = withContext(Dispatchers.Main) { wv.url.orEmpty() }
            if (currentUrl.isBlank() || currentUrl == "about:blank") {
                return BrowserActionResult(
                    ok = false,
                    summary = "В браузере не открыта страница",
                    output = "Встроенный браузер пока пуст (about:blank). Сначала открой сайт через browser_open или выполни browser_search."
                )
            }
            val snap = extractPageSnapshot(wv, maxChars = maxChars.coerceIn(1000, MAX_OUTPUT_CHARS), includeElements = includeElements)
            BrowserActionResult(
                ok = true,
                summary = "Прочитано: ${snap.title.take(42).ifBlank { snap.url.take(42) }}",
                output = formatSnapshotForLlm(snap)
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 4. Клик по элементу (по element_id, CSS-селектору или видимому тексту).
     */
    suspend fun clickElement(
        context: Context,
        elementId: Int?,
        selector: String?,
        textQuery: String?
    ): BrowserActionResult = actionMutex.withLock {
        if (elementId == null && selector.isNullOrBlank() && textQuery.isNullOrBlank()) {
            return BrowserActionResult(
                ok = false,
                summary = "Не указана цель клика",
                output = "Ошибка: укажите element_id (номер из browser_read), selector или text."
            )
        }

        val label = when {
            elementId != null -> "Клик по [$elementId]"
            !textQuery.isNullOrBlank() -> "Клик по «${textQuery.take(30)}»"
            else -> "Клик по ${selector!!.take(30)}"
        }
        setAgentAction(label)
        try {
            val wv = ensureWebView(context)
            val urlBefore = withContext(Dispatchers.Main) { wv.url.orEmpty() }

            val js = buildClickJs(elementId, selector, textQuery)
            val rawRes = evalJsInternal(wv, js)
            val json = try { JSONObject(rawRes) } catch (_: Exception) { null }
            if (json == null || !json.optBoolean("ok", false)) {
                val errMsg = json?.optString("error").orEmpty().ifBlank { "Элемент не найден на странице" }
                return BrowserActionResult(
                    ok = false,
                    summary = "Клик не удался: ${errMsg.take(48)}",
                    output = "Ошибка клика: $errMsg. Вызови browser_read, чтобы увидеть актуальные номера элементов."
                )
            }

            val clickedDesc = json.optString("clicked", label)
            val directHref = json.optString("directHref", "")

            // Если ссылка вела на новый URL через target="_blank", открываем прямо в этой вкладке
            if (directHref.startsWith("http://") || directHref.startsWith("https://")) {
                loadUrlAndWait(wv, directHref, waitAfterMs = 1200L)
            } else {
                waitForPotentialNavigation(wv, urlBefore, waitMs = 1500L)
            }

            val snap = extractPageSnapshot(wv, maxChars = 6500, includeElements = true)
            BrowserActionResult(
                ok = true,
                summary = "Клик: ${clickedDesc.take(48)}",
                output = "Выполнен клик по: $clickedDesc\n\n" + formatSnapshotForLlm(snap)
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 5. Ввод текста в поле ввода (React/Vue/HTML совместимый) + опциональный Enter/Submit.
     */
    suspend fun typeText(
        context: Context,
        elementId: Int?,
        selector: String?,
        text: String,
        pressEnter: Boolean = false,
        clear: Boolean = true
    ): BrowserActionResult = actionMutex.withLock {
        setAgentAction("Ввод текста${if (elementId != null) " в [$elementId]" else ""}...")
        try {
            val wv = ensureWebView(context)
            val urlBefore = withContext(Dispatchers.Main) { wv.url.orEmpty() }

            val js = buildTypeJs(elementId, selector, text, pressEnter, clear)
            val rawRes = evalJsInternal(wv, js)
            val json = try { JSONObject(rawRes) } catch (_: Exception) { null }
            if (json == null || !json.optBoolean("ok", false)) {
                val errMsg = json?.optString("error").orEmpty().ifBlank { "Поле ввода не найдено" }
                return BrowserActionResult(
                    ok = false,
                    summary = "Ввод не удался: ${errMsg.take(48)}",
                    output = "Ошибка ввода текста: $errMsg. Вызови browser_read, чтобы найти правильный element_id поля."
                )
            }

            if (pressEnter) {
                waitForPotentialNavigation(wv, urlBefore, waitMs = 1800L)
            } else {
                delay(400L)
            }

            val targetDesc = json.optString("target", "поле ввода")
            val snap = extractPageSnapshot(wv, maxChars = 6000, includeElements = true)
            BrowserActionResult(
                ok = true,
                summary = "Введено в $targetDesc${if (pressEnter) " + Enter" else ""}",
                output = "Текст успешно введён в $targetDesc${if (pressEnter) " и отправлен (Enter)" else ""}.\n\n" + formatSnapshotForLlm(snap)
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 6. Прокрутка страницы (down / up / top / bottom).
     */
    suspend fun scrollPage(
        context: Context,
        direction: String = "down",
        amount: Int = 700
    ): BrowserActionResult = actionMutex.withLock {
        val dir = direction.trim().lowercase(Locale.US)
        val px = amount.coerceIn(100, 5000)
        setAgentAction("Прокрутка ($dir)...")
        try {
            val wv = ensureWebView(context)
            val js = """
                (function() {
                    var d = ${JSONObject.quote(dir)};
                    var amt = $px;
                    if (d === 'top') window.scrollTo(0, 0);
                    else if (d === 'bottom') window.scrollTo(0, document.documentElement.scrollHeight || document.body.scrollHeight || 0);
                    else if (d === 'up') window.scrollBy(0, -amt);
                    else window.scrollBy(0, amt);
                    return JSON.stringify({
                        y: Math.round(window.scrollY || window.pageYOffset || 0),
                        h: Math.round(document.documentElement.scrollHeight || document.body.scrollHeight || 0),
                        vh: Math.round(window.innerHeight || 0)
                    });
                })();
            """.trimIndent()
            evalJsInternal(wv, js)
            delay(450L)
            val snap = extractPageSnapshot(wv, maxChars = 6500, includeElements = true)
            BrowserActionResult(
                ok = true,
                summary = "Прокрутка $dir (${snap.scrollPercent}%)",
                output = formatSnapshotForLlm(snap)
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 7. Навигация по истории (back / forward / reload).
     */
    suspend fun navigateHistory(
        context: Context,
        action: String = "back"
    ): BrowserActionResult = actionMutex.withLock {
        val act = action.trim().lowercase(Locale.US)
        setAgentAction("Навигация: $act")
        try {
            val wv = ensureWebView(context)
            val deferred = CompletableDeferred<String?>()
            pageLoadDeferred = deferred

            val performed = withContext(Dispatchers.Main) {
                when (act) {
                    "forward" -> if (wv.canGoForward()) { wv.goForward(); true } else false
                    "reload", "refresh" -> { wv.reload(); true }
                    else -> if (wv.canGoBack()) { wv.goBack(); true } else false
                }
            }
            if (!performed) {
                pageLoadDeferred = null
                return BrowserActionResult(
                    ok = false,
                    summary = "Нет истории для '$act'",
                    output = "Невозможно выполнить '$act': история переходов в этом направлении пуста."
                )
            }
            withTimeoutOrNull(12_000L) { deferred.await() }
            pageLoadDeferred = null
            delay(900L)

            val snap = extractPageSnapshot(wv, maxChars = 6500, includeElements = true)
            BrowserActionResult(
                ok = true,
                summary = "Браузер ($act): ${snap.title.take(36).ifBlank { snap.url.take(36) }}",
                output = formatSnapshotForLlm(snap)
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 8. Скриншот текущей страницы WebView (возвращает JPEG data URL для Vision-модели и опционально сохраняет файл).
     */
    suspend fun takeScreenshot(
        context: Context,
        saveToDownloads: Boolean = false
    ): BrowserActionResult = actionMutex.withLock {
        setAgentAction("Скриншот страницы...")
        try {
            val wv = ensureWebView(context)
            val shot = captureBitmapFromWebView(wv)
                ?: return BrowserActionResult(
                    ok = false,
                    summary = "Не удалось снять скриншот",
                    output = "Ошибка: не удалось отрендерить скриншот WebView (возможно, страница ещё не загружена)."
                )

            // Масштабируем до комфортного размера для Vision API (макс. 1080px по длинной стороне)
            val maxSide = 1080
            val scaled = if (shot.width > maxSide || shot.height > maxSide) {
                val ratio = minOf(maxSide.toFloat() / shot.width, maxSide.toFloat() / shot.height)
                val nw = (shot.width * ratio).toInt().coerceAtLeast(1)
                val nh = (shot.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(shot, nw, nh, true).also {
                    if (it !== shot) shot.recycle()
                }
            } else {
                shot
            }

            val jpegBytes = ByteArrayOutputStream().use { baos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                baos.toByteArray()
            }
            val w = scaled.width
            val h = scaled.height
            scaled.recycle()

            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$b64"

            var savedPath: String? = null
            if (saveToDownloads) {
                savedPath = saveScreenshotFile(context, jpegBytes)
            }

            val curUrl = _state.value.currentUrl.ifBlank { "about:blank" }
            val curTitle = _state.value.title
            val sizeKb = jpegBytes.size / 1024
            val outText = buildString {
                append("Скриншот веб-страницы успешно снят (${w}x${h}, ~${sizeKb} КБ).\n")
                append("Заголовок: $curTitle\n")
                append("URL: $curUrl\n")
                if (savedPath != null) {
                    append("Сохранён на диск: $savedPath\n")
                }
                append("Если текущая модель поддерживает Vision, изображение прикреплено к этому результату.")
            }

            BrowserActionResult(
                ok = true,
                summary = "Скриншот ${w}x${h} (~${sizeKb} КБ)",
                output = outText,
                screenshotDataUrl = dataUrl,
                savedScreenshotPath = savedPath
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * 9. Выполнение произвольного JavaScript в контексте текущей страницы.
     */
    suspend fun evalJs(
        context: Context,
        script: String
    ): BrowserActionResult = actionMutex.withLock {
        val code = script.trim()
        if (code.isEmpty()) {
            return BrowserActionResult(false, "Пустой JS-скрипт", "Ошибка: передан пустой параметр script.")
        }
        setAgentAction("Выполнение JS...")
        try {
            val wv = ensureWebView(context)
            val isSimpleExpression = !code.contains("return ") && !code.contains(";") && !code.contains("\n")
            val bodyExpr = if (isSimpleExpression) "return ($code);" else code
            val wrapped = """
                (function() {
                    try {
                        var __fn = function() { $bodyExpr };
                        var __res = __fn();
                        if (__res === undefined) return "undefined";
                        if (typeof __res === 'string') return __res;
                        return JSON.stringify(__res);
                    } catch (e) {
                        return "JS_ERROR: " + (e && e.message ? e.message : String(e));
                    }
                })();
            """.trimIndent()
            val out = evalJsInternal(wv, wrapped)
            val isErr = out.startsWith("JS_ERROR:")
            BrowserActionResult(
                ok = !isErr,
                summary = if (isErr) "Ошибка JS" else "JS выполнен (${out.length} симв.)",
                output = out.take(MAX_OUTPUT_CHARS)
            )
        } finally {
            setAgentAction(null)
        }
    }

    /**
     * Снимок текущей страницы (скриншот Base64 + размеры) для ручной кнопки «📸 В чат».
     */
    suspend fun captureScreenshotForChat(context: Context): Triple<String, Int, Int>? {
        val res = takeScreenshot(context, saveToDownloads = false)
        val dataUrl = res.screenshotDataUrl ?: return null
        val b64 = dataUrl.substringAfter("base64,", "")
        if (b64.isEmpty()) return null
        val match = Regex("""(\d+)x(\d+)""").find(res.summary)
        val w = match?.groupValues?.get(1)?.toIntOrNull() ?: 720
        val h = match?.groupValues?.get(2)?.toIntOrNull() ?: 1280
        return Triple(b64, w, h)
    }

    /**
     * Текст текущей страницы для ручной кнопки «📄 Текст в чат».
     */
    suspend fun extractTextForChat(context: Context): Pair<String, String>? {
        val res = readPage(context, maxChars = 10_000, includeElements = false)
        if (!res.ok) return null
        val title = _state.value.title.ifBlank { _state.value.shortHost.ifBlank { "web_page" } }
        return Pair(title, res.output)
    }

    // =========================================================================
    // Внутренняя реализация WebView / JS / HTML-поиска
    // =========================================================================

    private fun setAgentAction(label: String?) {
        _state.update { it.copy(agentActionLabel = label) }
    }

    private suspend fun ensureWebView(context: Context): WebView {
        attachedWebView?.let { return it }
        fallbackWebView?.let { return it }
        return withContext(Dispatchers.Main) {
            attachedWebView?.let { return@withContext it }
            fallbackWebView?.let { return@withContext it }
            val wv = WebView(context.applicationContext)
            configureWebView(wv)
            val w = 1080
            val h = 1920
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
            fallbackWebView = wv
            wv
        }
    }

    private suspend fun loadUrlAndWait(wv: WebView, url: String, waitAfterMs: Long): String? {
        val deferred = CompletableDeferred<String?>()
        pageLoadDeferred = deferred

        withContext(Dispatchers.Main) {
            _state.update { it.copy(lastError = null) }
            wv.loadUrl(url)
        }

        val completedUrl = withTimeoutOrNull(NAV_TIMEOUT_MS) { deferred.await() }
        pageLoadDeferred = null

        if (waitAfterMs > 0) {
            delay(waitAfterMs)
        }

        if (completedUrl == null) {
            // Даже при таймауте страница могла частично прогрузиться (например, тяжёлые трекеры)
            val hasContent = withContext(Dispatchers.Main) {
                !wv.url.isNullOrBlank() && wv.url != "about:blank"
            }
            if (!hasContent) {
                return "Превышено время ожидания загрузки (${NAV_TIMEOUT_MS / 1000} сек)"
            }
        }
        return null
    }

    private suspend fun waitForPotentialNavigation(wv: WebView, urlBefore: String, waitMs: Long) {
        val deferred = CompletableDeferred<String?>()
        pageLoadDeferred = deferred
        // Даём короткое окно: если клик/Enter вызвал переход — ждём onPageFinished
        delay(350L)
        val isLoadingNow = _state.value.isLoading
        val urlChanged = withContext(Dispatchers.Main) { (wv.url ?: "") != urlBefore }
        if (isLoadingNow || urlChanged) {
            withTimeoutOrNull(14_000L) { deferred.await() }
            delay(waitMs.coerceAtMost(1400L))
        } else {
            delay(waitMs.coerceAtMost(900L))
        }
        pageLoadDeferred = null
    }

    private suspend fun evalJsInternal(wv: WebView, js: String): String {
        val raw = withTimeoutOrNull(JS_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    try {
                        wv.evaluateJavascript(js) { value ->
                            if (cont.isActive) cont.resume(value)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        return decodeEvalJsResult(raw)
    }

    private fun decodeEvalJsResult(raw: String?): String {
        if (raw == null || raw == "null" || raw == "undefined") return ""
        return try {
            val parsed = JSONTokener(raw).nextValue()
            when (parsed) {
                is String -> parsed
                JSONObject.NULL -> ""
                else -> parsed.toString()
            }
        } catch (_: Exception) {
            raw.trim().removeSurrounding("\"")
        }
    }

    private suspend fun captureBitmapFromWebView(wv: WebView): Bitmap? {
        return withContext(Dispatchers.Main) {
            try {
                if (wv.width <= 0 || wv.height <= 0) {
                    val w = 1080
                    val h = 1920
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, w, h)
                }
                val w = wv.width.coerceIn(1, 1440)
                val h = wv.height.coerceIn(1, 2560)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                wv.draw(canvas)
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка снятия скриншота WebView: ${e.message}", e)
                null
            }
        }
    }

    private fun saveScreenshotFile(context: Context, jpegBytes: ByteArray): String? {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "browser_$ts.jpg"
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LLM_Browser"
            )
            val targetDir = if (downloadsDir.exists() || downloadsDir.mkdirs()) downloadsDir else context.cacheDir
            val outFile = File(targetDir, fileName)
            FileOutputStream(outFile).use { it.write(jpegBytes) }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось сохранить скриншот на диск: ${e.message}")
            null
        }
    }

    private data class PageSnapshot(
        val title: String,
        val url: String,
        val scrollPercent: Int,
        val text: String,
        val elements: List<BrowserElementInfo>
    )

    private suspend fun extractPageSnapshot(
        wv: WebView,
        maxChars: Int,
        includeElements: Boolean
    ): PageSnapshot {
        val js = """
            (function() {
                var maxText = $maxChars;
                var wantElems = ${if (includeElements) "true" else "false"};

                // Очищаем старые метки
                var oldTagged = document.querySelectorAll('[data-llm-id]');
                for (var k = 0; k < oldTagged.length; k++) {
                    oldTagged[k].removeAttribute('data-llm-id');
                }

                function isVisible(el) {
                    if (!el) return false;
                    var style = window.getComputedStyle(el);
                    if (!style || style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity || '1') === 0) {
                        return false;
                    }
                    var rect = el.getBoundingClientRect();
                    if (rect.width < 2 || rect.height < 2) return false;
                    return true;
                }

                var elements = [];
                if (wantElems) {
                    var selector = 'a[href], button, input, textarea, select, [role="button"], [role="link"], [role="textbox"], [role="searchbox"], [role="tab"], [contenteditable="true"]';
                    var nodes = Array.prototype.slice.call(document.querySelectorAll(selector));
                    var vh = window.innerHeight || 900;

                    // Приоритет видимым в окне элементам
                    var inView = [];
                    var outView = [];
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        if (!isVisible(el)) continue;
                        var r = el.getBoundingClientRect();
                        if (r.bottom >= -150 && r.top <= vh + 250) {
                            inView.push(el);
                        } else if (outView.length < 40) {
                            outView.push(el);
                        }
                    }

                    var ordered = inView.concat(outView).slice(0, 75);
                    var nextId = 1;
                    for (var j = 0; j < ordered.length; j++) {
                        var n = ordered[j];
                        var tag = (n.tagName || '').toLowerCase();
                        var type = (n.getAttribute('type') || n.getAttribute('role') || '').toLowerCase();
                        if (tag === 'input' && type === 'hidden') continue;

                        var rawText = (n.innerText || n.textContent || '').replace(/\s+/g, ' ').trim();
                        var aria = (n.getAttribute('aria-label') || n.getAttribute('title') || '').trim();
                        var placeholder = (n.getAttribute('placeholder') || '').trim();
                        var label = rawText || aria || placeholder || (n.getAttribute('name') || '');
                        var href = (tag === 'a') ? (n.href || n.getAttribute('href') || '') : '';
                        var val = (tag === 'input' || tag === 'textarea' || tag === 'select') ? String(n.value || '') : '';
                        if (type === 'password' && val.length > 0) val = '••••••';

                        if (!label && !href && tag !== 'input' && tag !== 'textarea' && tag !== 'select') continue;

                        n.setAttribute('data-llm-id', String(nextId));
                        elements.push({
                            id: nextId,
                            tag: tag,
                            type: type,
                            label: label.slice(0, 90),
                            href: href.slice(0, 140),
                            value: val.slice(0, 60)
                        });
                        nextId++;
                    }
                }

                var rawBody = (document.body && document.body.innerText) ? document.body.innerText : '';
                var cleanedLines = rawBody.split('\n').map(function(l) { return l.trim(); }).filter(function(l) { return l.length > 0; });
                var cleanedText = cleanedLines.join('\n');
                if (cleanedText.length > maxText) {
                    cleanedText = cleanedText.slice(0, maxText) + '\n… [текст обрезан по лимиту ' + maxText + ' симв.]';
                }

                var sy = window.scrollY || window.pageYOffset || 0;
                var sh = Math.max((document.documentElement && document.documentElement.scrollHeight) || 0, (document.body && document.body.scrollHeight) || 0, 1);
                var ih = window.innerHeight || 1;
                var maxScroll = Math.max(sh - ih, 1);
                var pct = Math.min(100, Math.max(0, Math.round((sy / maxScroll) * 100)));

                return JSON.stringify({
                    title: document.title || '',
                    url: location.href || '',
                    scrollPercent: pct,
                    text: cleanedText,
                    elements: elements
                });
            })();
        """.trimIndent()

        val raw = evalJsInternal(wv, js)
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("elements") ?: JSONArray()
            val elems = mutableListOf<BrowserElementInfo>()
            val elemMap = LinkedHashMap<Int, BrowserElementInfo>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.optInt("id", -1)
                if (id <= 0) continue
                val label = e.optString("label", "")
                val type = e.optString("type", "")
                val href = e.optString("href", "")
                val low = "$label $type".lowercase(Locale.ROOT)
                val sensitive = type == "submit" || SENSITIVE_ACTION_KEYWORDS.any { low.contains(it) }
                val info = BrowserElementInfo(
                    id = id,
                    tag = e.optString("tag", ""),
                    type = type,
                    label = label,
                    href = href,
                    value = e.optString("value", ""),
                    isSensitive = sensitive
                )
                elems.add(info)
                elemMap[id] = info
            }
            lastIndexedElements = elemMap
            val pageUrl = obj.optString("url", "").ifBlank { _state.value.currentUrl }
            val pageTitle = obj.optString("title", "").ifBlank { _state.value.title }
            _state.update {
                it.copy(
                    currentUrl = pageUrl,
                    title = pageTitle,
                    indexedElementsCount = elems.size,
                    hasLoadedPage = pageUrl.isNotBlank() && pageUrl != "about:blank"
                )
            }
            PageSnapshot(
                title = pageTitle,
                url = pageUrl,
                scrollPercent = obj.optInt("scrollPercent", 0),
                text = obj.optString("text", ""),
                elements = elems
            )
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось разобрать PageSnapshot: ${e.message}")
            PageSnapshot(
                title = _state.value.title,
                url = _state.value.currentUrl,
                scrollPercent = 0,
                text = raw.take(maxChars),
                elements = emptyList()
            )
        }
    }

    private fun formatSnapshotForLlm(snap: PageSnapshot): String {
        val sb = StringBuilder()
        sb.append("Заголовок: ").append(snap.title.ifBlank { "(без заголовка)" }).append("\n")
        sb.append("URL: ").append(snap.url).append("\n")
        sb.append("Прокрутка: ").append(snap.scrollPercent).append("%\n")

        val captcha = detectCaptchaOrConsent(snap.title, snap.url, snap.text)
        if (captcha != null) {
            sb.append("⚠️ ").append(captcha).append("\n")
        }

        sb.append("\n--- Содержимое страницы ---\n")
        sb.append(snap.text.ifBlank { "(страница не содержит видимого текста)" })

        if (snap.elements.isNotEmpty()) {
            sb.append("\n\n--- Интерактивные элементы (используй element_id в browser_click / browser_type) ---\n")
            for (el in snap.elements) {
                val typeStr = if (el.type.isNotBlank()) ":${el.type}" else ""
                val valStr = if (el.value.isNotBlank()) " [значение=\"${el.value}\"]" else ""
                val hrefStr = if (el.href.isNotBlank() && el.tag == "a") " -> ${el.href.take(90)}" else ""
                sb.append("[${el.id}] <${el.tag}$typeStr> ${el.label.ifBlank { "(без подписи)" }}$valStr$hrefStr\n")
            }
        }
        return sb.toString().take(MAX_OUTPUT_CHARS)
    }

    private fun detectCaptchaOrConsent(title: String, url: String, text: String): String? {
        val low = "$title $url ${text.take(800)}".lowercase(Locale.ROOT)
        return when {
            low.contains("captcha") || low.contains("smartcaptcha") || low.contains("вы не робот") ||
                low.contains("i am not a robot") || low.contains("unusual traffic") || low.contains("необычный трафик") ->
                "Обнаружена капча/проверка на робота. Пользователь может открыть встроенный браузер (🌐), пройти проверку вручную, и после этого можно продолжить."
            low.contains("just a moment") && low.contains("cloudflare") ->
                "Обнаружена проверка Cloudflare. Подожди пару секунд и вызови browser_read, либо попроси пользователя открыть встроенный браузер (🌐)."
            url.contains("consent.google.") ->
                "Открыт экран согласия cookies Google. Можно кликнуть кнопку принятия через browser_click или переключиться на DuckDuckGo."
            else -> null
        }
    }

    private fun buildClickJs(elementId: Int?, selector: String?, textQuery: String?): String {
        val idArg = elementId?.toString() ?: "null"
        val selArg = if (!selector.isNullOrBlank()) JSONObject.quote(selector) else "null"
        val txtArg = if (!textQuery.isNullOrBlank()) JSONObject.quote(textQuery) else "null"

        return """
            (function() {
                var targetId = $idArg;
                var sel = $selArg;
                var txt = $txtArg;
                var el = null;

                if (targetId !== null) {
                    el = document.querySelector('[data-llm-id="' + targetId + '"]');
                }
                if (!el && sel) {
                    try { el = document.querySelector(sel); } catch(e) {}
                }
                if (!el && txt) {
                    var needle = txt.toLowerCase();
                    var candidates = document.querySelectorAll('a, button, input[type="submit"], input[type="button"], [role="button"], [role="link"], [role="tab"]');
                    for (var i = 0; i < candidates.length; i++) {
                        var c = candidates[i];
                        var label = ((c.innerText || c.textContent || c.getAttribute('aria-label') || c.value || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                        if (label === needle) { el = c; break; }
                    }
                    if (!el) {
                        for (var j = 0; j < candidates.length; j++) {
                            var c2 = candidates[j];
                            var label2 = ((c2.innerText || c2.textContent || c2.getAttribute('aria-label') || c2.value || '')).replace(/\s+/g, ' ').trim().toLowerCase();
                            if (label2.indexOf(needle) !== -1) { el = c2; break; }
                        }
                    }
                }

                if (!el) {
                    return JSON.stringify({ ok: false, error: 'Элемент не найден (id=' + targetId + ', selector=' + sel + ', text=' + txt + ')' });
                }

                try { el.scrollIntoView({ behavior: 'instant', block: 'center' }); } catch(e) {}

                // Кратковременная визуальная подсветка для пользователя в окне браузера
                var prevOutline = el.style.outline;
                el.style.outline = '3px solid #38BDF8';
                setTimeout(function() { try { el.style.outline = prevOutline; } catch(e) {} }, 900);

                // Если это ссылка с target="_blank" — перехватываем, чтобы открыть в этой же вкладке
                var linkEl = (el.tagName && el.tagName.toLowerCase() === 'a') ? el : (el.closest ? el.closest('a[href]') : null);
                var directHref = '';
                if (linkEl && linkEl.getAttribute('target') === '_blank' && linkEl.href) {
                    linkEl.removeAttribute('target');
                    directHref = linkEl.href;
                }

                try { el.focus(); } catch(e) {}
                ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function(ev) {
                    try { el.dispatchEvent(new MouseEvent(ev, { bubbles: true, cancelable: true, view: window })); } catch(e) {}
                });
                el.click();

                var desc = ((el.innerText || el.textContent || el.getAttribute('aria-label') || el.value || el.tagName) + '').replace(/\s+/g, ' ').trim().slice(0, 80);
                return JSON.stringify({ ok: true, clicked: desc, directHref: directHref });
            })();
        """.trimIndent()
    }

    private fun buildTypeJs(
        elementId: Int?,
        selector: String?,
        text: String,
        pressEnter: Boolean,
        clear: Boolean
    ): String {
        val idArg = elementId?.toString() ?: "null"
        val selArg = if (!selector.isNullOrBlank()) JSONObject.quote(selector) else "null"
        val txtArg = JSONObject.quote(text)
        val enterArg = if (pressEnter) "true" else "false"
        val clearArg = if (clear) "true" else "false"

        return """
            (function() {
                var targetId = $idArg;
                var sel = $selArg;
                var val = $txtArg;
                var doEnter = $enterArg;
                var doClear = $clearArg;
                var el = null;

                if (targetId !== null) {
                    el = document.querySelector('[data-llm-id="' + targetId + '"]');
                }
                if (!el && sel) {
                    try { el = document.querySelector(sel); } catch(e) {}
                }
                if (!el) {
                    var active = document.activeElement;
                    if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) {
                        el = active;
                    }
                }
                if (!el) {
                    el = document.querySelector('input[type="search"], input[type="text"], input:not([type]), textarea');
                }
                if (!el) {
                    return JSON.stringify({ ok: false, error: 'Поле ввода не найдено на странице' });
                }

                try { el.scrollIntoView({ behavior: 'instant', block: 'center' }); } catch(e) {}
                try { el.focus(); } catch(e) {}

                var tag = (el.tagName || '').toLowerCase();
                if (el.isContentEditable) {
                    el.textContent = doClear ? val : ((el.textContent || '') + val);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                } else {
                    var finalVal = doClear ? val : (String(el.value || '') + val);
                    var proto = (tag === 'textarea') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                    if (desc && desc.set) {
                        desc.set.call(el, finalVal);
                    } else {
                        el.value = finalVal;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                if (doEnter) {
                    var kd = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                    var kp = new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                    var ku = new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                    el.dispatchEvent(kd);
                    el.dispatchEvent(kp);
                    el.dispatchEvent(ku);
                    var form = el.form || (el.closest ? el.closest('form') : null);
                    if (form) {
                        if (typeof form.requestSubmit === 'function') {
                            try { form.requestSubmit(); } catch(e) { form.submit(); }
                        } else {
                            try { form.submit(); } catch(e) {}
                        }
                    }
                }

                var label = el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.getAttribute('name') || tag;
                return JSON.stringify({ ok: true, target: String(label).slice(0, 60) });
            })();
        """.trimIndent()
    }

    // =========================================================================
    // Поисковые экстракторы (DuckDuckGo HTTP + Google / Яндекс / DDG WebView)
    // =========================================================================

    private data class SearchResultItem(
        val title: String,
        val url: String,
        val snippet: String
    )

    private suspend fun fetchDuckDuckGoHtmlResults(query: String, limit: Int): List<SearchResultItem> {
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://html.duckduckgo.com/html/?q=$encoded&kl=ru-ru")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", DESKTOP_UA)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                }
                if (conn.responseCode !in 200..299) return@withContext emptyList()
                val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseDuckDuckGoHtml(html, limit)
            } catch (e: Exception) {
                Log.w(TAG, "DDG fast HTTP поиск не удался, переход на WebView: ${e.message}")
                emptyList()
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun parseDuckDuckGoHtml(html: String, limit: Int): List<SearchResultItem> {
        val items = mutableListOf<SearchResultItem>()
        // Парсим блоки результатов DuckDuckGo HTML
        val linkRegex = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRegex = Regex(
            """<(?:a|div|span)[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|span)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()

        for (i in links.indices) {
            if (items.size >= limit) break
            val rawHref = decodeHtmlEntities(links[i].groupValues[1])
            val resolvedUrl = unwrapDuckDuckGoRedirect(rawHref)
            if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) continue
            val title = stripHtmlTags(links[i].groupValues[2]).trim()
            if (title.isBlank()) continue
            val snippet = if (i < snippets.size) stripHtmlTags(snippets[i].groupValues[1]).trim() else ""
            items.add(SearchResultItem(title = title, url = resolvedUrl, snippet = snippet))
        }
        return items
    }

    private fun unwrapDuckDuckGoRedirect(rawHref: String): String {
        val normalized = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
        val uddgMatch = Regex("""[?&]uddg=([^&]+)""").find(normalized)
        if (uddgMatch != null) {
            return try {
                URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
            } catch (_: Exception) {
                normalized
            }
        }
        return normalized
    }

    private suspend fun extractSearchResultsFromWebView(
        wv: WebView,
        engine: BrowserSearchEngine,
        limit: Int
    ): List<SearchResultItem> {
        val js = when (engine) {
            BrowserSearchEngine.GOOGLE -> """
                (function() {
                    var out = [];
                    var seen = {};
                    var h3s = document.querySelectorAll('h3');
                    for (var i = 0; i < h3s.length && out.length < $limit; i++) {
                        var h = h3s[i];
                        var a = h.closest ? h.closest('a[href]') : null;
                        if (!a || !a.href) continue;
                        var u = a.href;
                        if (u.indexOf('http') !== 0 || u.indexOf('google.com/search') !== -1 || seen[u]) continue;
                        seen[u] = true;
                        var container = a.closest('div.g, div.MjjYud, div.tF2Cxc, div[data-hveid]') || a.parentElement;
                        var rawTxt = container ? (container.innerText || '') : '';
                        var title = (h.innerText || '').trim();
                        var snippet = rawTxt.replace(title, '').replace(/\s+/g, ' ').trim().slice(0, 260);
                        out.push({ title: title, url: u, snippet: snippet });
                    }
                    return JSON.stringify(out);
                })();
            """.trimIndent()

            BrowserSearchEngine.YANDEX -> """
                (function() {
                    var out = [];
                    var seen = {};
                    var cards = document.querySelectorAll('li.serp-item, .SerpItem, [data-cid]');
                    for (var i = 0; i < cards.length && out.length < $limit; i++) {
                        var card = cards[i];
                        var a = card.querySelector('h2 a[href], a.OrganicTitle-Link[href], a.Link[href]');
                        if (!a || !a.href) continue;
                        var u = a.href;
                        if (u.indexOf('http') !== 0 || u.indexOf('yabs.yandex') !== -1 || seen[u]) continue;
                        seen[u] = true;
                        var title = (a.innerText || '').replace(/\s+/g, ' ').trim();
                        if (!title) continue;
                        var snipEl = card.querySelector('.OrganicText, .OrganicTextContentSpan, .TextContainer, .text-container');
                        var snippet = snipEl ? (snipEl.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 260) : '';
                        out.push({ title: title, url: u, snippet: snippet });
                    }
                    return JSON.stringify(out);
                })();
            """.trimIndent()

            BrowserSearchEngine.DUCKDUCKGO -> """
                (function() {
                    var out = [];
                    var seen = {};
                    var links = document.querySelectorAll('a[data-testid="result-title-a"], a.result__a, article h2 a[href]');
                    for (var i = 0; i < links.length && out.length < $limit; i++) {
                        var a = links[i];
                        var u = a.href || '';
                        if (u.indexOf('http') !== 0 || seen[u]) continue;
                        seen[u] = true;
                        var title = (a.innerText || '').replace(/\s+/g, ' ').trim();
                        if (!title) continue;
                        var art = a.closest ? a.closest('article, .result') : a.parentElement;
                        var snippet = art ? (art.innerText || '').replace(title, '').replace(/\s+/g, ' ').trim().slice(0, 260) : '';
                        out.push({ title: title, url: u, snippet: snippet });
                    }
                    return JSON.stringify(out);
                })();
            """.trimIndent()
        }

        val raw = evalJsInternal(wv, js)
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SearchResultItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("title", "").trim()
                val u = o.optString("url", "").trim()
                val s = o.optString("snippet", "").trim()
                if (t.isNotBlank() && u.startsWith("http")) {
                    list.add(SearchResultItem(t, u, s))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun formatSearchResults(
        engine: BrowserSearchEngine,
        query: String,
        results: List<SearchResultItem>
    ): String {
        val sb = StringBuilder()
        sb.append("Результаты поиска ${engine.displayName} по запросу «$query» (${results.size}):\n\n")
        results.forEachIndexed { idx, r ->
            sb.append("${idx + 1}. ${r.title}\n")
            sb.append("   URL: ${r.url}\n")
            if (r.snippet.isNotBlank()) {
                sb.append("   ${r.snippet}\n")
            }
            sb.append("\n")
        }
        sb.append("Чтобы открыть нужную страницу и прочитать подробности, вызови browser_open с выбранным URL.")
        return sb.toString().trim()
    }

    private fun stripHtmlTags(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        return decodeHtmlEntities(noTags).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeHtmlEntities(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
    }

    // =========================================================================
    // Нормализация и валидация URL
    // =========================================================================

    fun normalizeStrictWebUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("javascript:") || lower.startsWith("file:") ||
            lower.startsWith("content:") || lower.startsWith("intent:") || lower.startsWith("data:")
        ) {
            return null
        }
        val withScheme = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
        val uri = try { Uri.parse(withScheme) } catch (_: Exception) { return null }
        val host = uri.host
        if (host.isNullOrBlank()) return null
        return withScheme
    }

    fun normalizeUserInputToUrl(input: String, engine: BrowserSearchEngine): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return normalizeStrictWebUrl(trimmed)
        }
        // Если похоже на домен/IP без пробелов — открываем как URL, иначе ищем в выбранном поисковике
        val looksLikeDomain = !trimmed.contains(' ') &&
            (trimmed.contains('.') || trimmed.startsWith("localhost")) &&
            !trimmed.startsWith(".")
        return if (looksLikeDomain) {
            normalizeStrictWebUrl("https://$trimmed")
        } else {
            engine.buildSearchUrl(trimmed)
        }
    }
}
