package com.klischa.llmnotes.ui

import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.klischa.llmnotes.browser.AgentBrowserEngine
import com.klischa.llmnotes.browser.BrowserSearchEngine
import com.klischa.llmnotes.browser.BrowserUiState

/**
 * Полноценный встроенный интерактивный браузер агента (Chromium WebView).
 *
 * Монтируется в Compose-дерево ТОЛЬКО когда пользователь открывает браузер (isBrowserVisible == true),
 * поэтому в закрытом состоянии никак не перекрывает чат и не перехватывает касания.
 * Сам WebView живёт в AgentBrowserEngine: при сворачивании окна страница, JS-состояние и скролл
 * полностью сохраняются, а агент продолжает спокойно работать с сайтом в фоне.
 */
@Composable
fun AgentBrowserOverlay(
    browserState: BrowserUiState,
    isAgentGenerating: Boolean,
    onClose: () -> Unit,
    onStopAgent: () -> Unit,
    onSelectSearchEngine: (BrowserSearchEngine) -> Unit,
    onToggleIncognito: (Boolean) -> Unit,
    onToggleDesktopMode: (Boolean) -> Unit,
    onClearCookies: () -> Unit,
    onAttachScreenshotToChat: () -> Unit,
    onAttachTextToChat: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var addressInput by remember {
        mutableStateOf(if (browserState.currentUrl == "about:blank") "" else browserState.currentUrl)
    }
    var isAddressFocused by remember { mutableStateOf(false) }
    var hostContainerRef by remember { mutableStateOf<FrameLayout?>(null) }

    // Синхронизируем адресную строку с URL браузера, пока пользователь не редактирует её
    LaunchedEffect(browserState.currentUrl) {
        if (!isAddressFocused) {
            addressInput = if (browserState.currentUrl == "about:blank") "" else browserState.currentUrl
        }
    }

    // При нажатии системной кнопки «Назад»: сначала идём назад в браузере, иначе сворачиваем окно
    BackHandler {
        if (browserState.canGoBack) {
            AgentBrowserEngine.goBackUser()
        } else {
            onClose()
        }
    }

    // При закрытии шторки браузера просто отцепляем WebView от контейнера (НЕ уничтожаем страницу!)
    DisposableEffect(Unit) {
        onDispose {
            hostContainerRef?.let { AgentBrowserEngine.detachFromHost(it) }
            hostContainerRef = null
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Surface(
                color = if (browserState.incognitoMode) Color(0xFF1F1633) else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Строка 1: Навигация + Адресная строка + Закрыть
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { AgentBrowserEngine.goBackUser() },
                            enabled = browserState.canGoBack,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Назад",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = { AgentBrowserEngine.goForwardUser() },
                            enabled = browserState.canGoForward,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "Вперёд",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        OutlinedTextField(
                            value = addressInput,
                            onValueChange = {
                                isAddressFocused = true
                                addressInput = it
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            placeholder = {
                                Text(
                                    text = "URL или поиск (${browserState.searchEngine.displayName})...",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    isAddressFocused = false
                                    focusManager.clearFocus()
                                    AgentBrowserEngine.navigateUser(context, addressInput)
                                }
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (isAddressFocused && addressInput.isNotBlank()) {
                                            isAddressFocused = false
                                            focusManager.clearFocus()
                                            AgentBrowserEngine.navigateUser(context, addressInput)
                                        } else {
                                            AgentBrowserEngine.reloadOrStopUser()
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (browserState.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                        contentDescription = if (browserState.isLoading) "Стоп" else "Обновить",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )

                        IconButton(
                            onClick = {
                                isAddressFocused = false
                                focusManager.clearFocus()
                                onClose()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Свернуть в чат",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Строка 2: Панель управления браузером (поисковики, Инкогнито, ПК/Моб, куки, в чат)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BrowserSearchEngine.values().forEach { eng ->
                            FilterChip(
                                selected = browserState.searchEngine == eng,
                                onClick = {
                                    onSelectSearchEngine(eng)
                                    Toast.makeText(context, "Поисковик агента: ${eng.displayName}", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text(eng.chipLabel, fontSize = 11.sp) }
                            )
                        }

                        FilterChip(
                            selected = browserState.incognitoMode,
                            onClick = {
                                val next = !browserState.incognitoMode
                                onToggleIncognito(next)
                                Toast.makeText(
                                    context,
                                    if (next) "🕵️ Режим Инкогнито включён (куки не сохраняются)"
                                    else "🔓 Обычный режим (сессии и входы сохраняются)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            label = {
                                Text(
                                    if (browserState.incognitoMode) "🕵️ Инкогнито: Вкл" else "🕵️ Инкогнито",
                                    fontSize = 11.sp
                                )
                            }
                        )

                        FilterChip(
                            selected = browserState.desktopMode,
                            onClick = { onToggleDesktopMode(!browserState.desktopMode) },
                            label = {
                                Text(
                                    if (browserState.desktopMode) "🖥️ Версия ПК" else "📱 Мобильная",
                                    fontSize = 11.sp
                                )
                            }
                        )

                        AssistChip(
                            onClick = {
                                onClearCookies()
                                Toast.makeText(context, "🧹 Куки, кэш и история браузера очищены", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("🧹 Очистить", fontSize = 11.sp) }
                        )

                        AssistChip(
                            onClick = { onAttachScreenshotToChat() },
                            enabled = browserState.hasLoadedPage,
                            label = { Text("📸 В чат", fontSize = 11.sp) }
                        )

                        AssistChip(
                            onClick = { onAttachTextToChat() },
                            enabled = browserState.hasLoadedPage,
                            label = { Text("📄 Текст в чат", fontSize = 11.sp) }
                        )
                    }

                    // Индикатор прогресса загрузки
                    if (browserState.isLoading && browserState.progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { browserState.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                        )
                    } else {
                        HorizontalDivider()
                    }

                    // Живой статус работы агента в браузере (с кнопкой Стоп)
                    val agentNote = browserState.agentActionLabel
                    if (!agentNote.isNullOrBlank() || isAgentGenerating) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "🤖 ${agentNote ?: "Агент работает..."}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = onStopAgent,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Стоп", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    // Ошибка загрузки (если есть)
                    if (!browserState.lastError.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ ${browserState.lastError}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            // Контейнер с живым WebView из AgentBrowserEngine (либо чистая стартовая страница)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val showHome = !browserState.hasLoadedPage && !browserState.isLoading
                if (showHome) {
                    BrowserEmptyHome(
                        searchEngine = browserState.searchEngine,
                        incognitoMode = browserState.incognitoMode,
                        onOpenUrl = { url ->
                            addressInput = url
                            AgentBrowserEngine.navigateUser(context, url)
                        },
                        onClose = onClose
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val container = FrameLayout(ctx)
                            AgentBrowserEngine.attachToHost(container, ctx)
                            hostContainerRef = container
                            container
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserEmptyHome(
    searchEngine: BrowserSearchEngine,
    incognitoMode: Boolean,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (incognitoMode) "🕵️ Браузер агента (Инкогнито)" else "🌐 Встроенный браузер агента",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Агент может сам искать в ${searchEngine.displayName}, открывать сайты, кликать, заполнять формы, листать страницы и снимать скриншоты.\n\n" +
                    "Вы можете в любой момент открыть это окно, чтобы наблюдать за агентом, войти в аккаунт или пройти капчу.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            val quickLinks = listOf(
                "🦆 DuckDuckGo" to "https://duckduckgo.com",
                "🔍 Google" to "https://www.google.com",
                "🇷🇺 Яндекс" to "https://ya.ru",
                "📚 Википедия" to "https://ru.wikipedia.org",
                "💻 GitHub" to "https://github.com",
                "📰 Хабр" to "https://habr.com"
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickLinks.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { (label, url) ->
                            OutlinedButton(
                                onClick = { onOpenUrl(url) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(label, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            TextButton(onClick = onClose) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Вернуться в чат к агенту")
            }
        }
    }
}
