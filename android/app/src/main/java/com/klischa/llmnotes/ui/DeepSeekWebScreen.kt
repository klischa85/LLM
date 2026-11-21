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
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeepSeekWebScreen(
    seedPrompt: String,
    onSaveAnswer: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val controller = remember { DeepSeekWebController(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { controller.persistCookies() }
    }

    // Системная кнопка «назад» ведёт по истории внутри сайта
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
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
                            text = if (isLoggedIn) "Веб-чат без API-ключа"
                            else "Email/пароль или телефон",
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
                    if (isLoggedIn) {
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

    // Однократная передача исходного промпта в поле ввода после авторизации
    LaunchedEffect(isLoggedIn, seedPrompt) {
        if (!isLoggedIn || seeded || seedPrompt.isBlank()) return@LaunchedEffect
        val w = webView ?: return@LaunchedEffect
        // ждём отрисовку SPA-интерфейса чата
        delay(1200)
        val ok = controller.seedPrompt(w, seedPrompt)
        if (ok) seeded = true
    }
}
