@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.klischa.llmnotes.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klischa.llmnotes.AgentConfirmationMode
import com.klischa.llmnotes.AgentStepLog
import com.klischa.llmnotes.browser.AgentBrowserEngine
import com.klischa.llmnotes.browser.BrowserSearchEngine
import com.klischa.llmnotes.browser.BrowserUiState
import com.klischa.llmnotes.ChatMessage
import com.klischa.llmnotes.FileStorageManager
import com.klischa.llmnotes.LLMViewModel
import com.klischa.llmnotes.MessageRole
import com.klischa.llmnotes.ParsedMessage
import com.klischa.llmnotes.PendingToolApproval
import com.klischa.llmnotes.QueuedFollowUp
import com.klischa.llmnotes.R
import com.klischa.llmnotes.SystemPromptPreset
import com.klischa.llmnotes.TermuxBridge
import com.klischa.llmnotes.ThoughtParser
import com.klischa.llmnotes.UiState
import com.klischa.llmnotes.UniversalFileParser
import com.klischa.llmnotes.api.LLMProviderType
import com.klischa.llmnotes.api.OpenCodeClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: LLMViewModel,
    onSelectModelClick: () -> Unit,
    onAttachFileClick: () -> Unit = {},
    onImportSkillFileClick: () -> Unit = {},
    onRequestTermuxPermissionClick: () -> Unit = {},
    onOpenStoragePermissionClick: () -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsState().value
    val browserState = AgentBrowserEngine.state.collectAsState().value
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var chatSearchQuery by remember { mutableStateOf("") }
    var showModelStore by remember { mutableStateOf(false) }
    var pendingRollbackMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Диалог подтверждения отмены отправленного сообщения и отката чата до этого момента
    val rollbackTarget = pendingRollbackMessage
    if (rollbackTarget != null) {
        val targetIndex = uiState.messages.indexOfFirst { it.id == rollbackTarget.id }
        val affectedCount = if (targetIndex >= 0) uiState.messages.size - targetIndex else 0
        RollbackConfirmDialog(
            message = rollbackTarget,
            affectedCount = affectedCount,
            onConfirm = { restoreToInput ->
                viewModel.rollbackToMessage(rollbackTarget.id, restoreToInput = restoreToInput)
                pendingRollbackMessage = null
                Toast.makeText(context, "Чат откачен", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { pendingRollbackMessage = null }
        )
    }

    // Диалог настройки провайдеров (DeepSeek, OpenRouter, Groq, OpenCode GO/ZEN, локальный GGUF)
    if (uiState.isProviderDialogVisible) {
        ProviderSettingsDialog(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { viewModel.setProviderDialogVisible(false) }
        )
    }

    // Диалог настройки Режима Агента (файлы телефона и Termux RUN_COMMAND)
    if (uiState.isAgentDialogVisible) {
        AgentSettingsDialog(
            uiState = uiState,
            viewModel = viewModel,
            onRequestTermuxPermissionClick = onRequestTermuxPermissionClick,
            onOpenStoragePermissionClick = onOpenStoragePermissionClick,
            onDismiss = { viewModel.setAgentDialogVisible(false) }
        )
    }

    // Диалог управления скиллами (Agent Skills / SKILL.md)
    if (uiState.isSkillsDialogVisible) {
        SkillsDialog(
            uiState = uiState,
            viewModel = viewModel,
            onImportSkillFileClick = onImportSkillFileClick,
            onDismiss = { viewModel.setSkillsDialogVisible(false) }
        )
    }

    // Каталог моделей: скачивание GGUF прямо из приложения
    if (showModelStore) {
        ModelStoreDialog(
            onDismiss = { showModelStore = false },
            onModelReady = { path -> viewModel.loadModel(path) }
        )
    }

    // Автоматическая прокрутка к последнему сообщению при получении новых токенов
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(310.dp)
            ) {
                // Заголовок боковой шторки
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "История диалогов",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(
                        onClick = {
                            viewModel.createNewChat()
                            coroutineScope.launch { drawerState.close() }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Новый диалог")
                    }
                }

                HorizontalDivider()

                // Поиск по названию диалогов
                OutlinedTextField(
                    value = chatSearchQuery,
                    onValueChange = { chatSearchQuery = it },
                    placeholder = { Text("Поиск диалога...", fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                // Список диалогов
                val filteredSessions = if (chatSearchQuery.isBlank()) {
                    uiState.chatSessions
                } else {
                    uiState.chatSessions.filter { it.title.contains(chatSearchQuery, ignoreCase = true) }
                }

                val sessionDateFormat = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        val isSelected = session.id == uiState.currentChatId
                        val timeStr = sessionDateFormat.format(Date(session.updatedAt))

                        NavigationDrawerItem(
                            label = {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = session.title,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                    Text(
                                        text = timeStr,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            selected = isSelected,
                            onClick = {
                                viewModel.selectChat(session.id)
                                coroutineScope.launch { drawerState.close() }
                            },
                            badge = {
                                IconButton(
                                    onClick = { viewModel.deleteChat(session.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Удалить диалог",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            modifier = Modifier.padding(vertical = 2.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            )
                        )
                    }
                }

                // Кнопка создания нового диалога внизу шторки
                Button(
                    onClick = {
                        viewModel.createNewChat()
                        coroutineScope.launch { drawerState.close() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Новый диалог")
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню диалогов")
                        }
                    },
                    title = {
                        Column {
                            val currentTitle = uiState.chatSessions.find { it.id == uiState.currentChatId }?.title ?: "LLM"
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            val modelDisplayName = when (uiState.providerType) {
                                LLMProviderType.LOCAL_GGUF -> {
                                    if (uiState.isModelLoaded) java.io.File(uiState.modelPath).name.ifBlank { uiState.modelPath }
                                    else "Офлайн GGUF • Helio G99"
                                }
                                else -> "${uiState.providerType.displayName} • ${uiState.openCodeSelectedModel}"
                            }
                            Text(
                                text = modelDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        // Настройки режима агента (файлы на телефоне и Termux)
                        IconButton(onClick = { viewModel.setAgentDialogVisible(true) }) {
                            Surface(
                                shape = CircleShape,
                                color = if (uiState.agentModeEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "🤖",
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }

                        // Выбор провайдера (Локально / DeepSeek / OpenRouter / Groq / OpenCode)
                        IconButton(onClick = { viewModel.setProviderDialogVisible(true) }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Провайдер LLM",
                                tint = if (uiState.providerType != LLMProviderType.LOCAL_GGUF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Кнопка настройки системного промпта
                        IconButton(onClick = { viewModel.toggleSystemPromptExpanded() }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Системный промпт",
                                tint = if (uiState.isSystemPromptExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Каталог моделей (скачать GGUF из интернета)
                        if (uiState.providerType == LLMProviderType.LOCAL_GGUF) {
                            IconButton(onClick = { showModelStore = true }) {
                                Text(text = "\u2B07\uFE0F", fontSize = 16.sp)
                            }
                        }

                        // Выбор GGUF модели (только в локальном режиме)
                        if (uiState.providerType == LLMProviderType.LOCAL_GGUF) {
                            IconButton(onClick = onSelectModelClick) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Выбрать GGUF"
                                )
                            }
                        }

                        // Очистить историю диалога
                        if (uiState.messages.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearChat() }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Очистить чат"
                                )
                            }
                        }

                        // Выгрузить локальную модель
                        if (uiState.providerType == LLMProviderType.LOCAL_GGUF && uiState.isModelLoaded) {
                            IconButton(onClick = { viewModel.unloadModel() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Выгрузить модель",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding()
            ) {
                // 1. Панель параметров системного промпта
                AnimatedVisibility(visible = uiState.isSystemPromptExpanded) {
                    SystemPromptCard(uiState, viewModel)
                }

                // 2. Информационная плашка статуса модели и производительности
                DeviceStatusStrip(uiState)

                // 2.5. Панель быстрого управления: Режим размышления (Вкл/Выкл), Скиллы, Браузер, Провайдер, Агент
                QuickControlsBar(
                    uiState = uiState,
                    browserState = browserState,
                    onToggleThinking = { viewModel.toggleThinkingEnabled() },
                    onTogglePlanOnly = { viewModel.togglePlanOnlyMode() },
                    onOpenSkills = { viewModel.setSkillsDialogVisible(true) },
                    onOpenBrowser = { viewModel.setBrowserVisible(true) },
                    onOpenProviders = { viewModel.setProviderDialogVisible(true) },
                    onOpenAgent = { viewModel.setAgentDialogVisible(true) }
                )

                // 3. Область сообщений чата
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (uiState.messages.isEmpty()) {
                        EmptyChatPlaceholder(
                            uiState = uiState,
                            onSelectModelClick = onSelectModelClick,
                            onOpenProviderSettings = { viewModel.setProviderDialogVisible(true) }
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.messages, key = { it.id }) { message ->
                                ChatMessageBubble(
                                    message = message,
                                    showThinking = uiState.thinkingEnabled,
                                    onCopyClick = {
                                        val parsedCopy = ThoughtParser.parse(message.text, false)
                                        val textToCopy = parsedCopy.answerText.ifBlank {
                                            ThoughtParser.cleanStopTokens(message.text)
                                        }
                                        clipboardManager.setText(AnnotatedString(textToCopy))
                                        Toast.makeText(context, "Текст ответа скопирован", Toast.LENGTH_SHORT).show()
                                    },
                                    onRollbackClick = {
                                        pendingRollbackMessage = message
                                    }
                                )
                            }
                        }
                    }
                }

                // 3.5. Интерактивная карточка подтверждения опасных действий агента (файлы, браузер, Termux)
                val approval = uiState.pendingToolApproval
                if (approval != null) {
                    ToolApprovalCard(
                        approval = approval,
                        onApprove = { viewModel.approveToolCall() },
                        onDeny = { viewModel.denyToolCall() },
                        onOpenBrowser = { viewModel.setBrowserVisible(true) }
                    )
                } else if (!uiState.activeToolStatus.isNullOrBlank()) {
                    val isBrowserActive = uiState.activeToolStatus.contains("Браузер") ||
                        uiState.activeToolStatus.contains("browser_")
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.activeToolStatus,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (isBrowserActive) {
                                TextButton(
                                    onClick = { viewModel.setBrowserVisible(true) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Открыть 🌐", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // 4. Панель быстрых подсказок (телеметрия, саммари, задачи)
                QuickPromptChips(
                    isReady = uiState.isReadyToChat,
                    onChipClick = { type -> viewModel.insertNotePrompt(type) }
                )

                // 5. Поле ввода сообщения, очередь дополнений и кнопки отправки/очереди/остановки
                ChatInputBar(
                    uiState = uiState,
                    onInputTextChange = { viewModel.updateInputText(it) },
                    onSendClick = { viewModel.sendMessage() },
                    onEnqueueClick = { viewModel.enqueueFollowUp() },
                    onStopClick = { viewModel.stopGeneration() },
                    onAttachFileClick = onAttachFileClick,
                    onRemoveAttachmentClick = { viewModel.clearAttachment() },
                    onEditQueuedItem = { viewModel.editQueuedFollowUp(it) },
                    onRemoveQueuedItem = { viewModel.removeQueuedFollowUp(it) },
                    onClearQueue = { viewModel.clearQueuedFollowUps() },
                    onSendNextQueuedNow = { viewModel.sendNextQueuedNow() }
                )
            }
        }
        }

        // Полноценный встроенный интерактивный браузер агента (монтируется ТОЛЬКО при открытии!)
        if (uiState.isBrowserVisible) {
            AgentBrowserOverlay(
                browserState = browserState,
                isAgentGenerating = uiState.isGenerating,
                onClose = { viewModel.setBrowserVisible(false) },
                onStopAgent = { viewModel.stopGeneration() },
                onSelectSearchEngine = { viewModel.setBrowserSearchEngine(it) },
                onToggleIncognito = { viewModel.setBrowserIncognitoMode(it) },
                onToggleDesktopMode = { viewModel.setBrowserDesktopMode(it) },
                onClearCookies = { viewModel.clearBrowserData() },
                onAttachScreenshotToChat = { viewModel.attachBrowserScreenshotToChat() },
                onAttachTextToChat = { viewModel.attachBrowserTextToChat() }
            )
        }
    }
}

@Composable
fun DeviceStatusStrip(uiState: UiState) {
    val isLocal = uiState.providerType == LLMProviderType.LOCAL_GGUF
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(
                                when {
                                    isLocal && uiState.isModelLoaded -> Color(0xFF4CAF50)
                                    !isLocal && uiState.isReadyToChat -> Color(0xFF4CAF50)
                                    uiState.isLoadingModel -> Color(0xFF2196F3)
                                    else -> Color(0xFFFF9800)
                                },
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isLocal && uiState.isModelLoaded -> "Модель активна"
                            isLocal && uiState.isLoadingModel -> "Загрузка модели..."
                            isLocal -> "Модель не выбрана"
                            !isLocal && uiState.isReadyToChat -> {
                                val lock = if (uiState.hasCurrentApiKey) " 🔒" else ""
                                "${uiState.providerType.displayName}$lock"
                            }
                            uiState.providerType == LLMProviderType.CUSTOM_OPENAI -> "Требуется Base URL"
                            else -> "Требуется API-ключ"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLocal) {
                        Text(
                            text = "RAM: ~${uiState.allocatedRamMb} МБ",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            text = "Облачный API",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "${String.format("%.1f", uiState.tokensPerSecond)} tok/s",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (uiState.isLoadingModel) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Текст статуса или ошибки
            val isError = (isLocal && !uiState.isModelLoaded && !uiState.isLoadingModel &&
                          (uiState.statusMessage.contains("Ошибка") ||
                           uiState.statusMessage.contains("не найден") ||
                           uiState.statusMessage.contains("не поддерживается") ||
                           uiState.statusMessage.contains("Недостаточно"))) ||
                          (!isLocal && uiState.statusMessage.contains("Ошибка"))

            if (uiState.statusMessage.isNotBlank()) {
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = when {
                        isError -> MaterialTheme.colorScheme.error
                        uiState.isLoadingModel -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun QuickControlsBar(
    uiState: UiState,
    browserState: BrowserUiState,
    onToggleThinking: () -> Unit,
    onTogglePlanOnly: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAgent: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1-клик переключатель режима размышления (Вкл / Выкл)
        FilterChip(
            selected = uiState.thinkingEnabled,
            onClick = onToggleThinking,
            label = {
                Text(
                    text = if (uiState.thinkingEnabled) "💭 Размышления: Вкл" else "⚡ Размышления: Выкл",
                    fontSize = 11.sp
                )
            }
        )

        // Безопасное планирование без инструментов/изменений, либо исполнение в Direct.
        FilterChip(
            selected = uiState.planOnlyMode,
            onClick = onTogglePlanOnly,
            label = {
                Text(
                    text = if (uiState.planOnlyMode) "📝 План: только чтение" else "⚡ Direct: выполнять",
                    fontSize = 11.sp
                )
            }
        )

        // 1-клик открытие каталога и управления скиллами (Skills)
        FilterChip(
            selected = uiState.activeSkillsCount > 0,
            onClick = onOpenSkills,
            label = {
                val badge = if (uiState.activeSkillsCount > 0) " (${uiState.activeSkillsCount})" else ""
                Text(
                    text = "🧩 Скиллы$badge",
                    fontSize = 11.sp
                )
            }
        )

        // Встроенный интерактивный браузер агента
        FilterChip(
            selected = browserState.hasLoadedPage || !browserState.agentActionLabel.isNullOrBlank(),
            onClick = onOpenBrowser,
            label = {
                val icon = if (browserState.incognitoMode) "🕵️" else "🌐"
                val host = browserState.shortHost
                val labelText = when {
                    !browserState.agentActionLabel.isNullOrBlank() -> "$icon Браузер • активен"
                    host.isNotBlank() -> "$icon ${host.take(18)}"
                    else -> "$icon Браузер"
                }
                Text(text = labelText, fontSize = 11.sp)
            }
        )

        // Быстрый доступ к выбору API-провайдера
        FilterChip(
            selected = uiState.providerType != LLMProviderType.LOCAL_GGUF,
            onClick = onOpenProviders,
            label = {
                Text(
                    text = uiState.providerType.chipLabel,
                    fontSize = 11.sp
                )
            }
        )

        // Индикатор режима агента
        FilterChip(
            selected = uiState.agentModeEnabled,
            onClick = onOpenAgent,
            label = {
                Text(
                    text = if (uiState.agentModeEnabled) "🤖 Агент: Вкл" else "🤖 Агент: Выкл",
                    fontSize = 11.sp
                )
            }
        )
    }
}

@Composable
fun SystemPromptCard(uiState: UiState, viewModel: LLMViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Системный промпт диалога:",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Пресеты (горизонтальная прокрутка для узких экранов)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SystemPromptPreset.values().forEach { preset ->
                    FilterChip(
                        selected = uiState.systemPrompt == preset.prompt,
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(preset.title, fontSize = 11.sp) },
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = uiState.systemPrompt,
                onValueChange = { viewModel.updateSystemPrompt(it) },
                label = { Text("Пользовательская инструкция") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp, max = 130.dp),
                shape = RoundedCornerShape(10.dp)
            )

            // Переключатель режима размышления (Thinking)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "💭 Режим размышления (Thinking)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (uiState.thinkingEnabled) {
                            "Модель может размышлять в скрываемом блоке перед ответом"
                        } else {
                            "Отключено: прямые быстрые ответы без блока рассуждений"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.thinkingEnabled,
                    onCheckedChange = { viewModel.setThinkingEnabled(it) }
                )
            }

            // Переключатель потоков (для локальной модели)
            if (uiState.providerType == LLMProviderType.LOCAL_GGUF) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Потоки (Helio G99 оптимум: 2):",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row {
                        listOf(2, 3, 4).forEach { count ->
                            FilterChip(
                                selected = uiState.threadCount == count,
                                onClick = { viewModel.setThreadCount(count) },
                                label = { Text("$count", fontSize = 11.sp) },
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyChatPlaceholder(
    uiState: UiState,
    onSelectModelClick: () -> Unit,
    onOpenProviderSettings: () -> Unit
) {
    val isLocal = uiState.providerType == LLMProviderType.LOCAL_GGUF
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isLocal) "Офлайн-чат с LLM" else "Чат с ${uiState.providerType.displayName}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        isLocal && uiState.isModelLoaded -> "Модель готова к диалогу. Введите сообщение ниже или воспользуйтесь быстрыми кнопками."
                        isLocal -> "Загрузите GGUF-модель (Qwen 2.5, Llama 3.2), чтобы начать общение, либо подключите бесплатный облачный API."
                        !isLocal && uiState.isReadyToChat -> "Модель ${uiState.openCodeSelectedModel} готова к работе через ${uiState.providerType.displayName}."
                        uiState.providerType == LLMProviderType.CUSTOM_OPENAI -> "Укажите Base URL вашего OpenAI-совместимого сервера в настройках провайдера."
                        else -> "Введите API-ключ для доступа к ${uiState.providerType.displayName} (ключ шифруется в Android Keystore)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))
                if (isLocal && !uiState.isModelLoaded) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSelectModelClick,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выбрать файл .gguf")
                        }
                        OutlinedButton(
                            onClick = onOpenProviderSettings,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Подключить бесплатный API / Облако")
                        }
                    }
                } else if (!isLocal && !uiState.isReadyToChat) {
                    Button(
                        onClick = onOpenProviderSettings,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.providerType == LLMProviderType.CUSTOM_OPENAI) "Настроить Base URL" else "Ввести API-ключ")
                    }
                }
            }
        }
    }
}

@Composable
fun ProviderSettingsDialog(
    uiState: UiState,
    viewModel: LLMViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val provider = uiState.providerType
    val allProviders = remember { LLMProviderType.values().toList() }
    val freeProviders = remember { allProviders.filter { it.hasFreeTier } }

    var showOnlyFree by remember { mutableStateOf(provider.hasFreeTier) }
    var apiKeyDraft by remember(provider) { mutableStateOf("") }
    var isEditingKey by remember(provider, uiState.hasCurrentApiKey) {
        mutableStateOf(!uiState.hasCurrentApiKey)
    }

    fun openProviderKeyPage(url: String) {
        if (url.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть браузер: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun saveDraftIfPresent() {
        val trimmed = apiKeyDraft.trim()
        if (trimmed.isNotEmpty()) {
            viewModel.saveProviderApiKey(trimmed, provider)
            apiKeyDraft = ""
            isEditingKey = false
            Toast.makeText(context, "🔒 API-ключ зашифрован и скрыт", Toast.LENGTH_SHORT).show()
        }
    }

    val displayedProviders = if (showOnlyFree) {
        if (provider in freeProviders) freeProviders else freeProviders + provider
    } else {
        allProviders
    }

    AlertDialog(
        onDismissRequest = {
            saveDraftIfPresent()
            onDismiss()
        },
        title = {
            Column {
                Text(
                    text = "Провайдеры LLM и API-ключи",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "🔒 Все ключи шифруются в Android Keystore",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2E7D32)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Фильтр: Бесплатные vs Все провайдеры
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = showOnlyFree,
                        onClick = { showOnlyFree = true },
                        label = { Text("🆓 С бесплатным тарифом (${freeProviders.size})", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = !showOnlyFree,
                        onClick = { showOnlyFree = false },
                        label = { Text("Все (${allProviders.size})", fontSize = 11.sp) }
                    )
                }

                // Горизонтальная карусель чипов провайдеров (с индикатором сохранённого ключа 🔒)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    displayedProviders.forEach { item ->
                        val hasKey = uiState.savedKeyMasks.containsKey(item)
                        val labelSuffix = when {
                            hasKey -> " 🔒"
                            item.hasFreeTier && item != LLMProviderType.LOCAL_GGUF && item != LLMProviderType.CUSTOM_OPENAI -> " • Free"
                            else -> ""
                        }
                        FilterChip(
                            selected = provider == item,
                            onClick = {
                                saveDraftIfPresent()
                                viewModel.setProviderType(item)
                            },
                            label = {
                                Text(
                                    text = item.chipLabel + labelSuffix,
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }

                if (provider != LLMProviderType.LOCAL_GGUF) {
                    // Карточка провайдера + кнопка перехода на страницу получения API-ключа прямо в браузере
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                if (provider.hasFreeTier) {
                                    Surface(
                                        color = Color(0xFF2E7D32).copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "FREE TIER",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF2E7D32),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = provider.description,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                            )

                            if (provider.apiKeyUrl.isNotBlank() && provider != LLMProviderType.CUSTOM_OPENAI) {
                                OutlinedButton(
                                    onClick = { openProviderKeyPage(provider.apiKeyUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "🌐 Получить API-ключ → ${provider.apiKeyUrlLabel}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Произвольный Base URL для CUSTOM_OPENAI (Ollama, LM Studio, Together, свой прокси)
                    if (provider == LLMProviderType.CUSTOM_OPENAI) {
                        OutlinedTextField(
                            value = uiState.customBaseUrl,
                            onValueChange = { viewModel.setCustomBaseUrl(it) },
                            label = { Text("Base URL (OpenAI-совместимый) *", fontSize = 11.sp) },
                            placeholder = { Text("https://api.together.xyz/v1 или http://192.168.1.5:11434/v1", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    // Защищённое хранилище API-ключа: после сохранения ключ скрыт и зашифрован!
                    if (uiState.hasCurrentApiKey && !isEditingKey) {
                        Surface(
                            color = Color(0xFF2E7D32).copy(alpha = 0.10f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🔒 Ключ зашифрован и скрыт",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = uiState.currentKeyMask,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "Хранится в AES-256-GCM (Android Keystore). Просмотр исходного ключа отключён для защиты от утечки.",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            apiKeyDraft = ""
                                            isEditingKey = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("🔄 Заменить ключ", fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = {
                                            viewModel.deleteProviderApiKey(provider)
                                            apiKeyDraft = ""
                                            isEditingKey = true
                                            Toast.makeText(context, "Ключ удалён", Toast.LENGTH_SHORT).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("🗑️ Удалить", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = apiKeyDraft,
                                onValueChange = { apiKeyDraft = it },
                                label = {
                                    Text(
                                        if (provider.requiresApiKey) "Новый API-ключ ${provider.displayName}"
                                        else "API-ключ (опционально)"
                                    )
                                },
                                placeholder = { Text(provider.keyPlaceholder) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { saveDraftIfPresent() },
                                    enabled = apiKeyDraft.isNotBlank(),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("🔒 Зашифровать и сохранить", fontSize = 11.sp)
                                }
                                if (uiState.hasCurrentApiKey) {
                                    TextButton(
                                        onClick = {
                                            apiKeyDraft = ""
                                            isEditingKey = false
                                        }
                                    ) {
                                        Text("Отмена", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Выбор модели из каталога
                    val canFetchModels = !uiState.isFetchingModels && (
                        uiState.hasCurrentApiKey ||
                            (provider == LLMProviderType.CUSTOM_OPENAI && uiState.customBaseUrl.isNotBlank())
                        )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Модель (${uiState.openCodeAvailableModels.size} в списке):",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        TextButton(
                            onClick = { viewModel.fetchRemoteModels() },
                            enabled = canFetchModels
                        ) {
                            Text(if (uiState.isFetchingModels) "Загрузка..." else "🔄 Обновить", fontSize = 11.sp)
                        }
                    }

                    // Список моделей
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        uiState.openCodeAvailableModels.forEach { modelName ->
                            val isSelected = modelName == uiState.openCodeSelectedModel
                            val isFree = OpenCodeClient.isFreeModel(provider, modelName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setOpenCodeSelectedModel(modelName) }
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                        else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.setOpenCodeSelectedModel(modelName) }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = modelName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                val badgeText = when {
                                    isFree -> "Free"
                                    provider == LLMProviderType.DEEPSEEK && modelName == "deepseek-chat" -> "V3"
                                    provider == LLMProviderType.DEEPSEEK && modelName == "deepseek-reasoner" -> "R1"
                                    else -> null
                                }
                                if (badgeText != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = badgeText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Поле ввода произвольного имени модели
                    OutlinedTextField(
                        value = uiState.openCodeSelectedModel,
                        onValueChange = { viewModel.setOpenCodeSelectedModel(it) },
                        label = { Text("Выбранная модель (можно ввести вручную)", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    if (provider.tip.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = provider.tip,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        text = provider.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saveDraftIfPresent()
                    onDismiss()
                }
            ) {
                Text("Готово")
            }
        }
    )
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    showThinking: Boolean = true,
    onCopyClick: () -> Unit,
    onRollbackClick: () -> Unit = {}
) {
    val isUser = message.role == MessageRole.USER
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }
    val context = LocalContext.current

    val parsed = remember(message.text, message.isStreaming) {
        if (!isUser) ThoughtParser.parse(message.text, message.isStreaming)
        else ParsedMessage(thinkingText = null, answerText = message.text, isStillThinking = false)
    }

    val displayAnswer = parsed.answerText
    // Рассуждения остаются видимыми и после завершения ответа; пользователь может свернуть вручную.
    var isThoughtExpanded by remember(message.id) { mutableStateOf(true) }

    val codeBlocks = remember(displayAnswer) {
        if (!isUser) FileStorageManager.extractCodeBlocks(displayAnswer) else emptyList()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = if (isUser) {
                RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            } else {
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
            },
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier.widthIn(min = 80.dp, max = if (!isUser && parsed.agentSteps.isNotEmpty()) 350.dp else 325.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Роль собеседника
                Text(
                    text = if (isUser) "Вы" else "Ассистент",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Блок выполненных действий агента — разворачивается и список, и каждое действие с параметрами и результатом
                if (!isUser && parsed.agentSteps.isNotEmpty()) {
                    AgentStepsSection(
                        messageId = message.id,
                        steps = parsed.agentSteps,
                        isStreaming = message.isStreaming
                    )
                }

                // Индикатор подготовки команды агента во время стриминга
                if (!isUser && parsed.isPreparingToolCall) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "⚙️ Агент готовит действие...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Блок размышлений модели (скрывается при выключенном режиме размышления)
                if (showThinking && !isUser && parsed.thinkingText != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clickable { isThoughtExpanded = !isThoughtExpanded },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (parsed.isStillThinking) "💭 Размышление..." else "💭 Рассуждения",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (parsed.isStillThinking) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(10.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    text = if (isThoughtExpanded) "Свернуть ▲" else "Раскрыть ▼",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (isThoughtExpanded || (parsed.isStillThinking && displayAnswer.isEmpty())) {
                                Spacer(modifier = Modifier.height(4.dp))
                                SelectionContainer {
                                    Text(
                                        text = parsed.thinkingText,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontStyle = FontStyle.Italic,
                                            lineHeight = 16.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Текст сообщения
                if (displayAnswer.isNotEmpty() || (!(showThinking && parsed.isStillThinking) && !parsed.isPreparingToolCall && parsed.agentStepsText == null)) {
                    SelectionContainer {
                        val showCursor = message.isStreaming && displayAnswer.isEmpty() && !(showThinking && parsed.isStillThinking) && !parsed.isPreparingToolCall
                        Text(
                            text = if (showCursor) "▌" else displayAnswer,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Кнопки скачивания и копирования для каждого найденного блока кода
                if (!isUser && codeBlocks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    codeBlocks.forEach { block ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = block.language.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = block.suggestedFilename,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            FileStorageManager.copyToClipboard(context, block.code)
                                            Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_copy),
                                            contentDescription = "Скопировать",
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Скопировать", fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = {
                                            val saved = FileStorageManager.saveCodeToDownloads(
                                                context,
                                                block.code,
                                                block.suggestedFilename,
                                                block.language
                                            )
                                            Toast.makeText(context, "📥 Код сохранен: Download/${saved.name}", Toast.LENGTH_LONG).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_download),
                                            contentDescription = "Скачать код",
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Скачать код", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Нижняя строка: скорость / кнопка отката, кнопка копирования и время
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isUser && message.tokensPerSec != null && message.tokensPerSec > 0) {
                        Text(
                            text = "${String.format("%.1f", message.tokensPerSec)} tok/s",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (isUser) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.clickable { onRollbackClick() }
                        ) {
                            Text(
                                text = "↩ Отменить",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isUser && message.text.isNotBlank()) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = "Копировать",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onCopyClick() },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Text(
                            text = timeStr,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentStepsSection(
    messageId: String,
    steps: List<AgentStepLog>,
    isStreaming: Boolean
) {
    // Начальное развёрнутое состояние можно переключить в любой момент, в том числе во время генерации.
    var isSectionExpanded by remember(messageId) { mutableStateOf(true) }
    val expandedSteps = remember(messageId) { mutableStateMapOf<Int, Boolean>() }
    val showList = isSectionExpanded

    val okCount = remember(steps) { steps.count { it.status == "ok" } }
    val errCount = remember(steps) { steps.count { it.status == "error" } }
    val deniedCount = remember(steps) { steps.count { it.status == "denied" } }
    val hasAnyDetails = remember(steps) { steps.any { it.argsPreview.isNotBlank() || it.output.isNotBlank() } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isSectionExpanded = !isSectionExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "🛠️ Действия агента (${steps.size})",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (errCount > 0 || deniedCount > 0) {
                        val badgeParts = buildList {
                            if (okCount > 0) add("✅$okCount")
                            if (errCount > 0) add("❌$errCount")
                            if (deniedCount > 0) add("⛔$deniedCount")
                        }.joinToString(" ")
                        Text(
                            text = badgeParts,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                Text(
                    text = if (showList) "Свернуть ▲" else "Развернуть ▼",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = showList) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (steps.size > 1 && hasAnyDetails) {
                        val allExpanded = steps.all { expandedSteps[it.index] == true }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = if (allExpanded) "Свернуть все детали" else "Развернуть все детали",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        val target = !allExpanded
                                        steps.forEach { expandedSteps[it.index] = target }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    steps.forEach { step ->
                        val isStepExpanded = expandedSteps[step.index] == true
                        AgentStepItemCard(
                            step = step,
                            expanded = isStepExpanded,
                            onToggle = { expandedSteps[step.index] = !isStepExpanded }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStepItemCard(
    step: AgentStepLog,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val hasDetails = step.argsPreview.isNotBlank() || step.output.isNotBlank()
    val statusIcon = when (step.status) {
        "ok" -> "✅"
        "denied" -> "⛔"
        else -> "❌"
    }
    val durationLabel = when {
        step.durationMs <= 0L -> null
        step.durationMs < 1000L -> "${step.durationMs} мс"
        else -> String.format(Locale.US, "%.1f с", step.durationMs / 1000f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        border = BorderStroke(
            width = 1.dp,
            color = when (step.status) {
                "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                "denied" -> Color(0xFFD97706).copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (hasDetails) Modifier.clickable { onToggle() } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(text = statusIcon, fontSize = 11.sp)

                if (step.tool.isNotBlank() && step.tool != "agent") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = step.tool,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Text(
                    text = step.summary,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (durationLabel != null) {
                    Text(
                        text = durationLabel,
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }

                if (hasDetails) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Свернуть действие" else "Развернуть действие",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasDetails) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (step.argsPreview.isNotBlank()) {
                        Text(
                            text = "Параметры:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(
                                    text = step.argsPreview,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 170.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(6.dp)
                                )
                            }
                        }
                    }

                    if (step.output.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Результат:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "📋 Копировать",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        FileStorageManager.copyToClipboard(context, step.output)
                                        Toast.makeText(context, "Результат действия скопирован", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(
                                    text = step.output,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RollbackConfirmDialog(
    message: ChatMessage,
    affectedCount: Int,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var restoreToInput by remember(message.id) { mutableStateOf(true) }
    val preview = remember(message.text) {
        val clean = message.text.trim()
        if (clean.length > 140) clean.take(140) + "…" else clean
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "↩️ Откатить чат?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Выбранное сообщение и все последующие сообщения после него (всего: $affectedCount) будут удалены из истории диалога.",
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { restoreToInput = !restoreToInput },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = restoreToInput,
                        onCheckedChange = { restoreToInput = it }
                    )
                    Text(
                        text = "Вернуть текст сообщения в поле ввода",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(restoreToInput) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Откатить")
            }
        }
    )
}

@Composable
fun QuickPromptChips(
    isReady: Boolean,
    onChipClick: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            SuggestionChip(
                onClick = { onChipClick("photos") },
                label = { Text("📸 Сколько фото?", fontSize = 11.sp) },
                enabled = isReady
            )
        }
        item {
            SuggestionChip(
                onClick = { onChipClick("storage") },
                label = { Text("💾 Память", fontSize = 11.sp) },
                enabled = isReady
            )
        }
        item {
            SuggestionChip(
                onClick = { onChipClick("battery") },
                label = { Text("🔋 Батарея", fontSize = 11.sp) },
                enabled = isReady
            )
        }
        item {
            SuggestionChip(
                onClick = { onChipClick("device") },
                label = { Text("📱 Телефон", fontSize = 11.sp) },
                enabled = isReady
            )
        }
        item {
            SuggestionChip(
                onClick = { onChipClick("summary") },
                label = { Text("📝 Саммари", fontSize = 11.sp) },
                enabled = isReady
            )
        }
        item {
            SuggestionChip(
                onClick = { onChipClick("tasks") },
                label = { Text("✅ Задачи", fontSize = 11.sp) },
                enabled = isReady
            )
        }
        item {
            SuggestionChip(
                onClick = { onChipClick("format") },
                label = { Text("✨ Markdown", fontSize = 11.sp) },
                enabled = isReady
            )
        }
        item {
            SuggestionChip(
                onClick = { onChipClick("explain") },
                label = { Text("💡 Объясни", fontSize = 11.sp) },
                enabled = isReady
            )
        }
    }
}

@Composable
fun QueuedFollowUpsCard(
    queue: List<QueuedFollowUp>,
    isGenerating: Boolean,
    onEditItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onClearQueue: () -> Unit,
    onSendNextNow: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isGenerating) {
                        "🕒 В очереди (${queue.size}) — отправится после ответа"
                    } else {
                        "🕒 В очереди (${queue.size})"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isGenerating) {
                        Text(
                            text = "▶ Отправить",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onSendNextNow() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "Очистить",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { onClearQueue() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            queue.forEachIndexed { idx, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val attBadge = item.attachedDoc?.let { "${it.category.emoji} " } ?: ""
                    Text(
                        text = "${idx + 1}. $attBadge${item.text.replace("\n", " ")}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "✎",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onEditItem(item.id) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Text(
                        text = "✕",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { onRemoveItem(item.id) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    uiState: UiState,
    onInputTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onEnqueueClick: () -> Unit,
    onStopClick: () -> Unit,
    onAttachFileClick: () -> Unit,
    onRemoveAttachmentClick: () -> Unit,
    onEditQueuedItem: (String) -> Unit,
    onRemoveQueuedItem: (String) -> Unit,
    onClearQueue: () -> Unit,
    onSendNextQueuedNow: () -> Unit
) {
    val isReady = uiState.isReadyToChat
    val attached = uiState.attachedDoc
    val hasDraft = uiState.inputText.isNotBlank() || attached != null

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Очередь дополнений (отправляются строго после завершения текущего ответа)
            if (uiState.queuedFollowUps.isNotEmpty()) {
                QueuedFollowUpsCard(
                    queue = uiState.queuedFollowUps,
                    isGenerating = uiState.isGenerating,
                    onEditItem = onEditQueuedItem,
                    onRemoveItem = onRemoveQueuedItem,
                    onClearQueue = onClearQueue,
                    onSendNextNow = onSendNextQueuedNow
                )
            }

            // Превью прикреплённого файла любого формата (с миниатюрой для скриншотов/фото/PDF/видео)
            if (attached != null) {
                val previewBitmap = remember(attached.imageBase64) {
                    attached.imageBase64?.let { b64 ->
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } catch (_: Exception) { null }
                    }
                }
                val isTextOnlyProvider = !uiState.providerType.supportsVision

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (previewBitmap != null) {
                                    Image(
                                        bitmap = previewBitmap,
                                        contentDescription = attached.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = attached.category.emoji,
                                            fontSize = 18.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = attached.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    val badge = attached.summaryBadge.ifBlank {
                                        UniversalFileParser.formatSize(attached.sizeBytes)
                                    }
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = onRemoveAttachmentClick, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Удалить вложение", modifier = Modifier.size(16.dp))
                            }
                        }

                        if (attached.imageBase64 != null && isTextOnlyProvider) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ℹ️ ${uiState.providerType.displayName} работает в текстовом режиме (будут переданы метаданные). Для визуального анализа выберите Vision-провайдер (Gemini / OpenRouter / Groq / OpenAI / Claude).",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB45309),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Кнопка скрепки остаётся активной даже во время ответа (можно прикрепить файл к дополнению в очередь!)
                IconButton(
                    onClick = onAttachFileClick,
                    enabled = isReady,
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.CenterVertically)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_attach),
                        contentDescription = "Прикрепить любой файл",
                        tint = if (attached != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Поле ввода НЕ блокируется при генерации — можно спокойно дописывать дополнение в очередь!
                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = onInputTextChange,
                    placeholder = {
                        Text(
                            when {
                                !isReady && uiState.providerType == LLMProviderType.LOCAL_GGUF -> "Сначала выберите модель GGUF..."
                                !isReady && uiState.providerType == LLMProviderType.CUSTOM_OPENAI -> "Укажите Base URL в настройках..."
                                !isReady -> "Укажите API-ключ ${uiState.providerType.displayName}..."
                                uiState.isGenerating -> "Дополнить вопрос (встанет в очередь)..."
                                else -> "Сообщение, /скилл или файл..."
                            }
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp, max = 130.dp),
                    shape = RoundedCornerShape(22.dp),
                    enabled = isReady
                )

                if (uiState.isGenerating) {
                    // Кнопка постановки дополнения в очередь (не перебивает текущий ответ!)
                    IconButton(
                        onClick = onEnqueueClick,
                        enabled = isReady && hasDraft,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (isReady && hasDraft) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                    ) {
                        Text(
                            text = "🕒",
                            fontSize = 17.sp,
                            color = if (isReady && hasDraft) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // Кнопка остановки текущей генерации
                    IconButton(
                        onClick = onStopClick,
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Остановить",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSendClick,
                        enabled = isReady && hasDraft,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isReady && hasDraft)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = if (isReady && hasDraft)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolApprovalCard(
    approval: PendingToolApproval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onOpenBrowser: () -> Unit = {}
) {
    val borderColor = if (approval.isHighRisk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val containerColor = if (approval.isHighRisk) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    }
    var isDetailsExpanded by remember(approval) { mutableStateOf(false) }
    val targetScrollState = rememberScrollState()
    val detailScrollState = rememberScrollState()
    val canExpand = approval.targetDisplay.length > 100 ||
        approval.targetDisplay.count { it == '\n' } >= 3 ||
        approval.detailPreview.length > 140 ||
        approval.detailPreview.count { it == '\n' } >= 4

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, borderColor.copy(alpha = 0.7f)),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = approval.riskLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (approval.isHighRisk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (canExpand) {
                    Text(
                        text = if (isDetailsExpanded) "Свернуть ▲" else "Развернуть ▼",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { isDetailsExpanded = !isDetailsExpanded }
                            .padding(start = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Подтвердите: ${approval.title}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Путь к файлу или команда Termux в моноширинном блоке
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = approval.targetDisplay,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isDetailsExpanded) Modifier.heightIn(max = 180.dp).verticalScroll(targetScrollState)
                                else Modifier
                            )
                            .padding(8.dp),
                        maxLines = if (isDetailsExpanded) Int.MAX_VALUE else 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            if (approval.detailPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = approval.detailPreview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isDetailsExpanded) Modifier.heightIn(max = 220.dp).verticalScroll(detailScrollState)
                                else Modifier
                            ),
                        maxLines = if (isDetailsExpanded) Int.MAX_VALUE else 5,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            if (approval.call.tool.startsWith("browser_")) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenBrowser,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("🌐 Открыть браузер и проверить страницу", fontSize = 11.5.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("❌ Отклонить", fontSize = 12.sp)
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = if (approval.isHighRisk) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text("✅ Разрешить", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun AgentSettingsDialog(
    uiState: UiState,
    viewModel: LLMViewModel,
    onRequestTermuxPermissionClick: () -> Unit,
    onOpenStoragePermissionClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hasAllFilesAccess = remember(uiState.isAgentDialogVisible) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }
    val termux = uiState.termuxStatus
    var showMcpSettings by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖 Агент: файлы, браузер и Termux", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Включение / выключение режима агента
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Режим агента",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Файлы в /sdcard, встроенный интерактивный веб-браузер и команды в Termux",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.agentModeEnabled,
                        onCheckedChange = { viewModel.setAgentModeEnabled(it) }
                    )
                }

                OutlinedButton(
                    onClick = { showMcpSettings = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("🔌 Подключения MCP…")
                }

                HorizontalDivider()

                // Настройки встроенного браузера агента
                Text(
                    text = "🌐 Встроенный браузер агента:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Поисковик по умолчанию (можно также менять в самом браузере):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BrowserSearchEngine.values().forEach { engine ->
                        FilterChip(
                            selected = uiState.browserSearchEngine == engine,
                            onClick = { viewModel.setBrowserSearchEngine(engine) },
                            label = { Text(engine.chipLabel, fontSize = 11.sp) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🕵️ Режим Инкогнито",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Если выкл. — входы на сайты и куки сохраняются; если вкл. — сессия чистится автоматически",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.browserIncognitoMode,
                        onCheckedChange = { viewModel.setBrowserIncognitoMode(it) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            onDismiss()
                            viewModel.setBrowserVisible(true)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("🌐 Открыть браузер", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.clearBrowserData()
                            Toast.makeText(context, "🧹 Куки и кэш браузера очищены", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("🧹 Очистить куки", fontSize = 11.sp)
                    }
                }

                HorizontalDivider()

                // Политика подтверждения действий
                Text(
                    text = "Подтверждение операций:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                AgentConfirmationMode.values().forEach { mode ->
                    val selected = uiState.agentConfirmationMode == mode
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setAgentConfirmationMode(mode) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.setAgentConfirmationMode(mode) }
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Статус доступа к файлам /sdcard
                Text(
                    text = "Память телефона (/sdcard):",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (hasAllFilesAccess) {
                        "✅ Доступ ко всем файлам выдан (системные папки Android/data защищены)"
                    } else {
                        "⚠️ Нет доступа ко всем файлам — агент не сможет создавать/удалять файлы в /sdcard"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasAllFilesAccess) Color(0xFF15803D) else MaterialTheme.colorScheme.error
                )
                if (!hasAllFilesAccess) {
                    OutlinedButton(
                        onClick = onOpenStoragePermissionClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Открыть доступ ко всем файлам", fontSize = 12.sp)
                    }
                }

                HorizontalDivider()

                // Диагностика и настройка Termux
                Text(
                    text = "Интеграция с Termux (RUN_COMMAND):",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (termux.isInstalled) {
                        "✅ Termux установлен${termux.versionName?.let { " (v$it)" } ?: ""}"
                    } else {
                        "❌ Termux не найден (установите с F-Droid или GitHub)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (termux.isInstalled) Color(0xFF15803D) else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (termux.hasRunPermission) {
                        "✅ Разрешение RUN_COMMAND выдано"
                    } else {
                        "⚠️ Разрешение RUN_COMMAND ещё не выдано"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (termux.hasRunPermission) Color(0xFF15803D) else Color(0xFFB45309)
                )

                if (termux.isInstalled && !termux.hasRunPermission) {
                    Button(
                        onClick = onRequestTermuxPermissionClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Выдать разрешение Termux", fontSize = 12.sp)
                    }
                }

                // Одноразовая настройка allow-external-apps в Termux
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Один раз выполните в Termux (для приёма команд от других приложений):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "allow-external-apps=true → ~/.termux/termux.properties",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    FileStorageManager.copyToClipboard(context, TermuxBridge.SETUP_PROPERTIES_CMD)
                                    Toast.makeText(context, "Команда скопирована — вставьте её в Termux", Toast.LENGTH_LONG).show()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Скопировать", fontSize = 11.sp)
                            }
                            Button(
                                onClick = { viewModel.testTermuxConnection() },
                                enabled = termux.isInstalled && !uiState.isTestingTermux,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (uiState.isTestingTermux) "Проверка..." else "Тест Termux",
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (uiState.termuxTestOutput.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.termuxTestOutput,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово")
            }
        }
    )

    if (showMcpSettings) {
        McpSettingsDialog(onDismiss = { showMcpSettings = false })
    }
}
