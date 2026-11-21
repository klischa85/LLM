@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.klischa.llmnotes.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.klischa.llmnotes.web.DeepSeekWebController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Сколько ждать отрисовку чата, прежде чем показать баннер о проблеме. */
private const val PAGE_DIAGNOSE_TIMEOUT_MS = 25_000L

/**
 * Полноценный веб-чат DeepSeek внутри приложения: провайдер без API-ключа.
 *
 * WebView монтируется только когда экран виден, поэтому в закрытом состоянии
 * не расходует память и не перехватывает касания.
 *
 * v1.4.7: добавлен режим tool-loop — кнопка «С инструментами» запускает цикл
 * «ответ → вызов инструментов → результат обратно в чат». Цикл живёт в этом
 * Composable, потому что WebView создаётся здесь и недоступен ViewModel.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeepSeekWebScreen(
    seedPrompt: String,
    onSaveAnswer: (String) -> Unit,
    onClose: () -> Unit,
    toolLoopEnabled: Boolean = false,
    enabledTools: Set<String> = DeepSeekWebController.DEFAULT_SAFE_TOOLS,
    systemPrefix: String = "",
    onToolLoopFailure: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val controller = remember { DeepSeekWebController(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    // Состояние tool-loop
    var isRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var partialText by remember { mutableStateOf("") }
    var errorBanner by remember { mutableStateOf<String?>(null) }
    var cancelRequested by remember { mutableStateOf(false) }

    // Состояние загрузки страницы и диагностики пустого экрана
    var pageLoading by remember { mutableStateOf(true) }
    var pageProblem by remember { mutableStateOf<String?>(null) }
    var blankCheck by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { controller.persistCookies() }
    }

    // Системная кнопка «назад» ведёт по истории внутри сайта
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    /** Единая точка запуска цикла с инструментами. */
    fun startToolLoop() {
        val w = webView
        if (w == null) {
            savedHint = "Веб-чат ещё не готов — подождите загрузку"
            return
        }
        if (seedPrompt.isBlank()) {
            savedHint = "Нет запроса: введите текст в чате приложения"
            return
        }
        errorBanner = null
        cancelRequested = false
        isRunning = true
        statusText = "Отправка запроса..."
        partialText = ""
        savedHint = null

        scope.launch {
            val policy = DeepSeekWebController.ToolPolicy(enabledTools = enabledTools)
            val outcome = try {
                controller.runToolLoop(
                    web = w,
                    userPrompt = seedPrompt,
                    policy = policy,
                    systemPrefix = systemPrefix,
                    isCancelled = { cancelRequested },
                    onStatus = { statusText = it },
                    onPartial = { partialText = it }
                )
            } catch (t: Throwable) {
                DeepSeekWebController.ToolLoopOutcome(
                    finalText = "",
                    iterations = 0,
                    calls = 0,
                    failed = true,
                    failReason = "Сбой цикла: ${t.javaClass.simpleName}: ${t.message ?: "без описания"}"
                )
            }

            isRunning = false
            statusText = ""
            partialText = ""

            if (outcome.finalText.isNotBlank()) {
                onSaveAnswer(outcome.finalText)
                savedHint = "Готово: сохранено в чат (${outcome.iterations} итер., ${outcome.calls} вызовов)"
            }

            if (outcome.failed) {
                val reason = outcome.failReason ?: "Неизвестная ошибка"
                errorBanner = reason
                onToolLoopFailure(reason)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isLoggedIn) "DeepSeek" else "Вход в DeepSeek",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                isRunning -> "Цикл инструментов выполняется"
                                isLoggedIn && toolLoopEnabled -> "Веб-чат + инструменты"
                                isLoggedIn -> "Веб-чат без API-ключа"
                                else -> "Email/пароль или телефон"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val w = webView
                        if (w != null && w.canGoBack()) w.goBack() else onClose()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (isLoggedIn && toolLoopEnabled) {
                        if (isRunning) {
                            TextButton(onClick = { cancelRequested = true }) {
                                Text("⏹ Стоп")
                            }
                        } else {
                            TextButton(onClick = { startToolLoop() }) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("С инструментами")
                            }
                        }
                    }
                    if (isLoggedIn && !isRunning) {
                        TextButton(onClick = {
                            val w = webView ?: return@TextButton
                            scope.launch {
                                val answer = controller.readLastAnswer(w)
                                if (answer.isNullOrBlank()) {
                                    savedHint = "Ответ не найден — прокрутите чат"
                                } else {
                                    onSaveAnswer(answer)
                                    savedHint = "Сохранено в чат"
                                }
                            }
                        }) {
                            Text("В заметку")
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            if (isRunning || pageLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            statusText.takeIf { it.isNotBlank() }?.let { st ->
                Text(
                    text = st,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            partialText.takeIf { it.isNotBlank() }?.let { pt ->
                Text(
                    text = pt.takeLast(220),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            savedHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // Баннер сбоя: предлагаем вернуться в обычный чат
            errorBanner?.let { err ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Цикл инструментов прерван",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { errorBanner = null }) {
                                Text("Скрыть")
                            }
                            TextButton(onClick = {
                                errorBanner = null
                                onClose()
                            }) {
                                Text("В обычный чат")
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            // configure() сам держит shouldOverrideUrlLoading; экран лишь
                            // добавляет к нему свой колбэк окончания загрузки.
                            controller.configure(this) { _, _ ->
                                isLoggedIn = controller.isLoggedIn()
                                pageLoading = false
                                blankCheck++
                            }
                            webChromeClient = WebChromeClient()
                            controller.navigateHome(this)
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        webView = null
                        runCatching { view.destroy() }
                    }
                )

                val problem = pageProblem
                if (problem != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Страница DeepSeek не загрузилась",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = pageProblemText(problem),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    val w = webView
                                    pageProblem = null
                                    if (w != null) {
                                        pageLoading = true
                                        controller.navigateHome(w)
                                    }
                                }) { Text("Повторить") }
                                TextButton(onClick = {
                                    controller.openExternally(DeepSeekWebController.HOME_URL)
                                }) { Text("В Chrome") }
                                TextButton(onClick = { pageProblem = null }) { Text("Скрыть") }
                            }
                        }
                    }
                } else if (!isLoggedIn) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp
                    ) {
                        Text(
                            text = "Войдите по email/паролю или номеру телефона. " +
                                "Если экран остаётся пустым, включите VPN — так проходит " +
                                "антибот-проверка сайта.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    // Страховка: если навигация вообще не завершилась, снимаем индикатор сами.
    LaunchedEffect(pageLoading) {
        if (!pageLoading) return@LaunchedEffect
        delay(PAGE_DIAGNOSE_TIMEOUT_MS)
        if (pageLoading) {
            pageLoading = false
            if (pageProblem == null && !isLoggedIn) pageProblem = "timeout"
        }
    }

    // Диагностика пустого экрана: AWS WAF-заглушка, неотрисованный чат, таймаут.
    LaunchedEffect(blankCheck) {
        if (blankCheck == 0) return@LaunchedEffect
        val w = webView ?: return@LaunchedEffect
        pageProblem = null
        if (isLoggedIn) return@LaunchedEffect

        var waited = 0L
        var code = controller.diagnoseBlankPage(w)
        // null = страница ещё не ответила; "" = чат отрисован; иначе — код проблемы.
        while (waited < PAGE_DIAGNOSE_TIMEOUT_MS && code == null && !isLoggedIn) {
            delay(2_000)
            waited += 2_000
            if (isLoggedIn) return@LaunchedEffect
            code = controller.diagnoseBlankPage(w)
        }
        if (isLoggedIn) return@LaunchedEffect

        val finalCode = code
        pageLoading = false
        pageProblem = when {
            finalCode == null -> "timeout"
            finalCode.isEmpty() -> null
            else -> finalCode
        }
    }

    // Однократная передача исходного промпта в поле ввода после авторизации.
    // В режиме инструментов текст отправляет сам цикл — чтобы не дублировать.
    LaunchedEffect(isLoggedIn, seedPrompt, toolLoopEnabled) {
        if (!isLoggedIn || seeded || seedPrompt.isBlank()) return@LaunchedEffect
        if (toolLoopEnabled) return@LaunchedEffect
        val w = webView ?: return@LaunchedEffect
        // ждём отрисовку SPA-интерфейса чата
        delay(1200)
        val ok = controller.seedPrompt(w, seedPrompt)
        if (ok) seeded = true
    }
}

/** Понятный текст подсказки для конкретной причины пустой страницы. */
private fun pageProblemText(code: String): String = when (code) {
    "waf", "challenge" -> "Сайт показывает проверку AWS WAF, но её скрипт не загружается — " +
        "обычно это блокировка у провайдера. Включите VPN или переключитесь на мобильный " +
        "интернет и нажмите «Повторить»."
    "empty" -> "Сервер вернул пустую страницу. Проверьте соединение и нажмите «Повторить»."
    "timeout" -> "Страница не отрисовалась за 25 секунд: похоже, загрузка скриптов " +
        "проверки блокируется. Включите VPN и нажмите «Повторить»."
    else -> "Не удалось открыть веб-чат. Нажмите «Повторить» или откройте сайт в Chrome."
}
