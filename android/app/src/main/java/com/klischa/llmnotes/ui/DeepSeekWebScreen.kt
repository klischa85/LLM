@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.klischa.llmnotes.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Стоп")
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

            if (isRunning) {
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
                            controller.configure(this)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    isLoggedIn = controller.isLoggedIn()
                                }
                            }
                            controller.navigateHome(this)
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        webView = null
                        runCatching { view.destroy() }
                    }
                )

                if (!isLoggedIn) {
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
                                "Вход через Google в WebView недоступен — используйте " +
                                "адрес электронной почты DeepSeek.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
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
