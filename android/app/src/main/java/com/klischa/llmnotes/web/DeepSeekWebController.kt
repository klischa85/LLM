package com.klischa.llmnotes.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.klischa.llmnotes.AgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Обёртка над WebView официального веб-чата DeepSeek (провайдер без API-ключа).
 *
 * Сознательно НЕ переиспользует AgentBrowserEngine: тот является object с единственным
 * shared WebView и собственными WebViewClient, поэтому два экрана конфликтовали бы.
 *
 * С версии v1.4.7 умеет вести tool-loop: ответ модели разбирается на вызовы
 * инструментов тем же парсером, что и в нативном режиме агента (AgentTools),
 * результаты возвращаются в чат следующим сообщением.
 */
class DeepSeekWebController(private val appContext: Context) {

    companion object {
        const val HOME_URL = "https://chat.deepseek.com"
        private const val HOST = "chat.deepseek.com"
        private const val TAG = "DeepSeekWeb"
        private const val POLL_INTERVAL_MS = 400L
        /** Предохранитель на один вызов JS: колбэк evaluateJavascript может не прийти. */
        private const val EVAL_TIMEOUT_MS = 8_000L
        const val STABLE_MS = 2_500L
        const val HARD_STABLE_MS = 6_000L
        const val TIMEOUT_MS = 60_000L
        const val MAX_OBSERVATION_CHARS = 6_000
        /** Инструменты, включённые по умолчанию: только чтение, без записи и Termux. */
        val DEFAULT_SAFE_TOOLS: Set<String> =
            setOf("list_dir", "read_file", "search_files", "file_info")

        /** Полный список канонических инструментов, доступных в tool-loop. */
        val ALL_TOOLS: List<String> = listOf(
            "list_dir", "read_file", "search_files", "file_info", "mkdir",
            "write_file", "edit_file", "move_file", "delete_file", "run_termux",
            "browser_search", "browser_open", "browser_read", "browser_click",
            "browser_type", "browser_scroll", "browser_back", "browser_screenshot",
            "browser_eval_js"
        )

        /** Вставка текста в textarea с обходом React-контроля значения. */
        private val SEED_INPUT_JS = """
        (function(){
          const ta = document.querySelector('textarea');
          if (!ta) return false;
          const val = %s;
          function setNative(el, v) {
            const proto = window.HTMLTextAreaElement.prototype;
            const desc = Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && desc.set) desc.set.call(el, v); else el.value = v;
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
          }
          ta.focus();
          setNative(ta, val);
          // React часто игнорирует нативный сеттер: пробуем вставку через execCommand
          // и событие paste, иначе поле остаётся пустым и отправлять нечего.
          if (!(ta.value || '').length) {
            try { document.execCommand('insertText', false, val); } catch (e) {}
          }
          if (!(ta.value || '').length) {
            try {
              const dt = new DataTransfer();
              dt.setData('text/plain', val);
              ta.dispatchEvent(new ClipboardEvent('paste', {
                clipboardData: dt, bubbles: true, cancelable: true
              }));
            } catch (e) {}
          }
          return (ta.value || '').length > 0;
        })()
    """


        /** Состояние страницы после отправки: число ответов, генерация, черновик. */
        private val SUBMIT_CHECK_JS = """
            (function(){
              const md = document.querySelectorAll('[class*="ds-markdown"]');
              const nodes = Array.from(document.querySelectorAll('div[role="button"], button'));
              let busy = false;
              for (const n of nodes) {
                const l = (n.getAttribute('aria-label') || '').toLowerCase();
                const c = (n.className || '').toString().toLowerCase();
                if (l.indexOf('stop') >= 0 || c.indexOf('stop') >= 0) { busy = true; break; }
              }
              const ta = document.querySelector('textarea');
              const draft = ta ? (ta.value || '') : '';
              return JSON.stringify({answers: md.length, busy: busy, draftLen: draft.length});
            })()
"""

        /** Нажатие кнопки отправки: три стратегии, последняя — синтетический Enter. */
        private val SUBMIT_JS = """
            (function(){
              const ta = document.querySelector('textarea');
              if (!ta) return 'no-input';
              const nodes = Array.from(document.querySelectorAll('div[role="button"], button'));
              const visible = nodes.filter(function(n){ return !!n.querySelector('svg'); });
              const labelled = visible.find(function(n){
                const l = (n.getAttribute('aria-label') || '').toLowerCase();
                return l.indexOf('send') >= 0 || l.indexOf('отправ') >= 0;
              });
              if (labelled) { labelled.click(); return 'click'; }
              const tr = ta.getBoundingClientRect();
              let best = null, bestDist = Infinity;
              visible.forEach(function(n){
                const r = n.getBoundingClientRect();
                const d = Math.abs(r.top - tr.top) + Math.abs(r.left - (tr.right - r.width / 2));
                if (d < bestDist) { bestDist = d; best = n; }
              });
              if (best) { best.click(); return 'click'; }
              const ev = new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true});
              ta.dispatchEvent(ev);
              return 'enter';
            })()
        """

        /** Извлечение текста последнего ответа ассистента: три стратегии по очереди. */
        private val EXTRACT_JS = """
            (function(){
              const tries = [
                () => document.querySelectorAll('[class*="ds-markdown"]'),
                () => document.querySelectorAll('[class*="markdown"]'),
                () => document.querySelectorAll('[class*="message"]')
              ];
              for (const pick of tries) {
                try {
                  const nodes = pick();
                  if (nodes && nodes.length) {
                    const t = nodes[nodes.length - 1].innerText;
                    if (t && t.trim().length > 1) return t.trim();
                  }
                } catch (e) {}
              }
              return '';
            })()
        """
    }

    private val cookies: CookieManager get() = CookieManager.getInstance()

    /** Универсальный запуск JS в WebView с обязательным переключением на main-поток. */
    private suspend fun evalJs(
        web: WebView,
        script: String,
        timeoutMs: Long = EVAL_TIMEOUT_MS
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    web.evaluateJavascript(script) { r -> if (cont.isActive) cont.resume(r) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(
        web: WebView,
        onPageFinished: (WebView, String?) -> Unit = { _, _ -> }
    ) {
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // убираем маркер "wv": снижает шанс антибот-проверок
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Внутренние адреса остаются в WebView (включая узел антибот-проверки awswaf),
        // внешние уходят в системный браузер. Экран обязан пользоваться именно этим
        // configure(): раньше он перезаписывал webViewClient и терял обработчик переходов.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host ?: return false
                val internal = host == HOST || host.endsWith(".deepseek.com") ||
                    host.endsWith("google.com") || host.endsWith("gstatic.com") ||
                    host.endsWith("cloudflare.com") || host.endsWith("awswaf.com")
                if (internal) return false
                openExternally(request.url.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onPageFinished.invoke(view, url)
            }
        }
    }

    /** Открывает адрес в системном браузере. true — если переход состоялся. */
    fun openExternally(url: String): Boolean = runCatching {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /**
     * Проверяет, отрисовался ли настоящий интерфейс чата.
     * Возвращает код причины, если страница пустая или под антибот-заглушкой:
     * "waf", "challenge", "empty", "error"; пустая строка — страница в порядке;
     * null — не удалось опросить страницу.
     */
    suspend fun diagnoseBlankPage(web: WebView): String? {
        val js = """
            (function(){
              try {
                if (document.getElementById('challenge-container')) return 'waf';
                var b = document.body;
                if (!b) return 'empty';
                var t = (b.innerText || '').trim();
                if (!t) return 'empty';
                if (/just a moment|checking your browser|verify you are human/i.test(t)) return 'challenge';
                if (document.querySelector('textarea')) return '';
                if (t.length < 40) return 'empty';
                return '';
              } catch (e) { return 'error'; }
            })()
        """.trimIndent()
        val raw = evalJs(web, js) ?: return null
        val code = raw.removeSurrounding("\"").trim()
        return code.takeIf { it != "null" }
    }

    /** Сессия DeepSeek хранится в HttpOnly-cookie, поэтому читаем её только через CookieManager. */
    fun isLoggedIn(): Boolean {
        val raw = cookies.getCookie("https://$HOST") ?: return false
        return raw.split(";").any { part ->
            val name = part.substringBefore('=').trim()
            name.contains("session", ignoreCase = true) &&
                part.substringAfter('=', "").trim().isNotEmpty()
        }
    }

    fun persistCookies() {
        runCatching { cookies.flush() }
    }

    /**
     * Готово ли поле ввода к приёму текста.
     * Нужно невидимому хосту: проверяем только наличие и доступность textarea,
     * без геометрии — скрытый WebView может быть отрисован за пределами экрана,
     * и getBoundingClientRect() там даёт нули, хотя DOM полностью живой.
     */
    suspend fun isInputReady(web: WebView): Boolean {
        val js = """
            (function(){
              if (document.readyState !== 'complete') return false;
              var ta = document.querySelector('textarea');
              if (!ta) return false;
              return !ta.disabled && !ta.readOnly;
            })()
        """.trimIndent()
        val raw = evalJs(web, js) ?: return false
        return raw.removeSurrounding("\"").trim() == "true"
    }

    fun navigateHome(web: WebView) = web.loadUrl(HOME_URL)

    /** Вставляет исходный промпт в поле ввода веб-чата (если оно уже отрисовано). */
    suspend fun seedPrompt(web: WebView, prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val quoted = JSONObject.quote(prompt)
        val js = SEED_INPUT_JS.trimIndent().replace("%s", quoted)
        val res = evalJs(web, js)
        return res?.trim() == "true"
    }

    /** Нажимает кнопку отправки. Возвращает true, если удалось отправить. */
    suspend fun submitPrompt(web: WebView): Boolean {
        val res = evalJs(web, SUBMIT_JS.trimIndent())
        val clean = res?.removeSurrounding("\"")?.trim()
        return clean == "click" || clean == "enter"
    }

    /** Итог отправки сообщения в веб-чат: причина отказа видна пользователю. */
    data class SubmitReport(val ok: Boolean, val reason: String = "")

    private data class SubmitState(val answers: Int, val busy: Boolean, val draftLen: Int)

    private suspend fun readSubmitState(web: WebView): SubmitState? {
        val raw = evalJs(web, SUBMIT_CHECK_JS.trimIndent()) ?: return null
        val json = raw.removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        return runCatching {
            val o = JSONObject(json)
            SubmitState(o.optInt("answers"), o.optBoolean("busy"), o.optInt("draftLen"))
        }.getOrNull()
    }

    /** Сколько блоков ответа ассистента сейчас на странице. */
    suspend fun countAnswers(web: WebView): Int = readSubmitState(web)?.answers ?: 0

    /**
     * Отправляет текст и убеждается, что запрос действительно ушёл:
     * выросло число блоков ответа, появился индикатор генерации или
     * поле ввода очистилось. Без этой проверки форма могла молча
     * не отправиться, и приложение висело в ожидании до общего таймаута.
     */
    suspend fun sendMessageChecked(web: WebView, text: String, timeoutMs: Long = 12_000L): SubmitReport {
        if (text.isBlank()) return SubmitReport(false, "Пустой запрос")
        val before = countAnswers(web)
        if (!seedPrompt(web, text)) {
            return SubmitReport(false, "Поле ввода DeepSeek не найдено на странице")
        }
        delay(250L)
        // Пустой черновик после вставки = React не принял текст (поле пересоздалось
        // или страница не готова). Раньше это ошибочно считалось успешной отправкой,
        // и приложение висело в ожидании ответа до самого таймаута.
        val draftAfterSeed = readSubmitState(web)?.draftLen ?: 0
        Log.d(TAG, "sendMessageChecked: вставлено=" + text.length + ", черновик=" + draftAfterSeed)
        if (draftAfterSeed <= 0) {
            return SubmitReport(false, "DeepSeek не принял текст в поле ввода — страница не готова")
        }
        val submitted = submitPrompt(web)
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            delay(500L)
            val st = readSubmitState(web) ?: continue
            if (st.answers > before) return SubmitReport(true)
            if (st.busy) return SubmitReport(true)
            // draftLen == 0 сам по себе НЕ доказывает отправку: поле могло быть пустым изначально
        }
        val reason = if (submitted)
            "Сообщение не ушло: DeepSeek не отреагировал на отправку. Проверьте VPN и вход в аккаунт."
        else
            "Кнопка отправки DeepSeek недоступна — страница не готова."
        return SubmitReport(false, reason)
    }

    /** Вставляет текст, отправляет и проверяет, что запрос действительно ушёл. */
    suspend fun sendMessage(web: WebView, text: String): Boolean = sendMessageChecked(web, text).ok

    /** Извлекает текст последнего ответа ассистента: три стратегии по очереди. */
    suspend fun readLastAnswer(web: WebView): String? {
        val raw = evalJs(web, EXTRACT_JS.trimIndent()) ?: return null
        return raw
            .removeSurrounding("\"")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf { it.isNotBlank() && it != "null" }
    }

    /** Признак идущей генерации: кнопка Stop видна в поле ввода. */
    private val GENERATING_JS = """
        (function(){
          const nodes = Array.from(document.querySelectorAll('div[role="button"], button'));
          for (const n of nodes) {
            const r = n.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) continue;
            const l = (n.getAttribute('aria-label') || '').toLowerCase();
            const c = (n.className || '').toString().toLowerCase();
            if (l.indexOf('stop') >= 0 || l.indexOf('останов') >= 0) return true;
            if (c.indexOf('stop') >= 0) return true;
          }
          return false;
        })()
    """

    /**
     * Ждёт завершения ответа веб-чата. Приоритет — точный сигнал (исчезновение Stop);
     * резерв — стабилизация текста ответа в течение [STABLE_MS]. Если и это не сработало,
     * срабатывает жёсткий таймаут, и вызывающая сторона показывает ошибку.
     */
    suspend fun waitForAnswer(
        web: WebView,
        previousText: String?,
        timeoutMs: Long = TIMEOUT_MS,
        onProgress: (String) -> Unit = {}
    ): String? {
        val baseline = previousText.orEmpty()
        val startedAt = System.currentTimeMillis()
        var lastText = ""
        var lastChangeAt = System.currentTimeMillis()
        var sawAnyText = false

        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            delay(POLL_INTERVAL_MS)
            val current = readLastAnswer(web).orEmpty()
            val changed = current != lastText
            if (changed) {
                lastText = current
                lastChangeAt = System.currentTimeMillis()
                if (current.isNotBlank() && current != baseline) {
                    sawAnyText = true
                    onProgress(current)
                }
            }

            val generating = evalJs(web, GENERATING_JS.trimIndent())?.trim() == "true"
            val stableFor = System.currentTimeMillis() - lastChangeAt
            
            // Готово, если текст отличается от исходного и стабилен достаточно долго
            if (sawAnyText && stableFor >= STABLE_MS) {
                // при явном индикаторе генерации ждём ещё, но не дольше остатка таймаута
                if (generating) continue
                return lastText
            }
            // Резервный выход: индикатора нет, текст не менялся дольше жёсткого порога
            if (!generating && sawAnyText && stableFor >= HARD_STABLE_MS) {
                return lastText
            }
        }
        val tail = lastText.takeIf { it.isNotBlank() && it != baseline }
        Log.w(TAG, "waitForAnswer: таймаут " + timeoutMs + " мс, получено " + (tail?.length ?: 0) + " символов, видели текст: " + sawAnyText)
        return tail
    }

    /** Разрешённые пользователем инструменты (имена из AgentTools). */
    class ToolPolicy(
        val enabledTools: Set<String>,
        val maxIterations: Int = 8,
        val maxCalls: Int = 20,
        val enableSkills: Boolean = false
    )

    /** Итог одного прогона tool-loop. */
    data class ToolLoopOutcome(
        val finalText: String,
        val iterations: Int,
        val calls: Int,
        val failed: Boolean = false,
        val failReason: String? = null
    )

    /**
     * Основной цикл: отправляет промпт, ждёт ответ, извлекает вызовы инструментов,
     * выполняет разрешённые и возвращает результат обратно в чат следующим сообщением.
     *
     * @param systemPrefix добавляется к первому сообщению (промпт агента / скиллы).
     */
    suspend fun runToolLoop(
        web: WebView,
        userPrompt: String,
        policy: ToolPolicy,
        systemPrefix: String = "",
        isCancelled: () -> Boolean = { false },
        onStatus: (String) -> Unit = {},
        onPartial: (String) -> Unit = {}
    ): ToolLoopOutcome {
        var callsUsed = 0
        var lastAnswer: String? = runCatching { readLastAnswer(web) }.getOrNull()
        var finalText = ""

        val firstMessage = if (systemPrefix.isBlank()) userPrompt
                           else systemPrefix.trimEnd() + "\n\n" + userPrompt

        val firstReport = sendMessageChecked(web, firstMessage)
        if (!firstReport.ok) {
            return ToolLoopOutcome(
                finalText = "",
                iterations = 0,
                calls = 0,
                failed = true,
                failReason = firstReport.reason
            )
        }

        var iteration = 0
        while (iteration < policy.maxIterations) {
            if (isCancelled()) break
            iteration++
            onStatus("Ожидание ответа DeepSeek (итерация $iteration)...")

            val answer = waitForAnswer(web, lastAnswer, onProgress = onPartial)
            if (answer.isNullOrBlank()) {
                return ToolLoopOutcome(
                    finalText = finalText,
                    iterations = iteration,
                    calls = callsUsed,
                    failed = true,
                    failReason = "Ответ не получен: кнопка Stop не исчезла и текст не стабилизировался за $TIMEOUT_MS мс"
                )
            }
            lastAnswer = answer

            val extraction = AgentTools.extractToolCallsDetailed(answer)
            val allowed = extraction.calls.filter { it.tool in policy.enabledTools }

            if (allowed.isEmpty()) {
                // нет вызовов — это финальный ответ
                finalText = AgentTools.stripToolCallBlocks(answer).trim().ifEmpty { answer.trim() }
                return ToolLoopOutcome(finalText, iteration, callsUsed)
            }

            // бюджет вызовов
            val budgetLeft = policy.maxCalls - callsUsed
            if (budgetLeft <= 0) {
                finalText = AgentTools.stripToolCallBlocks(answer).trim()
                return ToolLoopOutcome(
                    finalText,
                    iteration,
                    callsUsed,
                    failed = true,
                    failReason = "Исчерпан лимит вызовов инструментов (${policy.maxCalls})"
                )
            }

            val batch = allowed.take(budgetLeft)
            val observations = StringBuilder()
            for (call in batch) {
                if (isCancelled()) break
                callsUsed++
                onStatus("⚙️ Выполняется: ${call.tool}...")
                val res = runCatching { AgentTools.execute(appContext, call) }.getOrNull()
                val ok = res?.ok == true
                observations.append("<tool_result tool=\"${call.tool}\" status=\"").append(if (ok) "ok" else "error").append("\">\n")
                observations.append((res?.output ?: "Ошибка выполнения инструмента").take(MAX_OBSERVATION_CHARS))
                observations.append("\n</tool_result>\n")
            }

            if (isCancelled()) break

            val followUp = "Результаты инструментов:\n" + observations.toString() +
                "\nПродолжай: если задача решена — дай итоговый ответ пользователю без вызовов инструментов."

            val followReport = sendMessageChecked(web, followUp)
            if (!followReport.ok) {
                return ToolLoopOutcome(
                    finalText = finalText,
                    iterations = iteration,
                    calls = callsUsed,
                    failed = true,
                    failReason = followReport.reason
                )
            }
        }

        // лимит итераций
        val tail = AgentTools.stripToolCallBlocks(lastAnswer.orEmpty()).trim()
        return ToolLoopOutcome(
            finalText = tail,
            iterations = iteration,
            calls = callsUsed,
            failed = true,
            failReason = "Достигнут лимит итераций (${policy.maxIterations})"
        )
    }
}
