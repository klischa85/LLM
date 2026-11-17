@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.klischa.llmnotes.ui

import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klischa.llmnotes.AgentConfirmationMode
import com.klischa.llmnotes.ChatMessage
import com.klischa.llmnotes.FileStorageManager
import com.klischa.llmnotes.LLMViewModel
import com.klischa.llmnotes.MessageRole
import com.klischa.llmnotes.ParsedMessage
import com.klischa.llmnotes.PendingToolApproval
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
    onRequestTermuxPermissionClick: () -> Unit = {},
    onOpenStoragePermissionClick: () -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsState().value
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var chatSearchQuery by remember { mutableStateOf("") }
    var showModelStore by remember { mutableStateOf(false) }

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
                                    onCopyClick = {
                                        val cleanToCopy = ThoughtParser.cleanStopTokens(message.text)
                                        val answerOnly = ThoughtParser.parse(cleanToCopy, false).answerText
                                        val textToCopy = if (answerOnly.isNotBlank()) answerOnly else cleanToCopy
                                        clipboardManager.setText(AnnotatedString(textToCopy))
                                        Toast.makeText(context, "Текст ответа скопирован", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }

                // 3.5. Интерактивная карточка подтверждения опасных действий агента (удаление, правка, перезапись, Termux)
                val approval = uiState.pendingToolApproval
                if (approval != null) {
                    ToolApprovalCard(
                        approval = approval,
                        onApprove = { viewModel.approveToolCall() },
                        onDeny = { viewModel.denyToolCall() }
                    )
                } else if (!uiState.activeToolStatus.isNullOrBlank()) {
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
                                maxLines = 1
                            )
                        }
                    }
                }

                // 4. Панель быстрых подсказок (телеметрия, саммари, задачи)
                QuickPromptChips(
                    isReady = uiState.isReadyToChat,
                    onChipClick = { type -> viewModel.insertNotePrompt(type) }
                )

                // 5. Поле ввода сообщения и кнопка отправки/остановки со скрепкой
                ChatInputBar(
                    uiState = uiState,
                    onInputTextChange = { viewModel.updateInputText(it) },
                    onSendClick = { viewModel.sendMessage() },
                    onStopClick = { viewModel.stopGeneration() },
                    onAttachFileClick = onAttachFileClick,
                    onRemoveAttachmentClick = { viewModel.clearAttachment() }
                )
            }
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
                                    !isLocal && uiState.currentApiKey.isNotBlank() -> Color(0xFF4CAF50)
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
                            !isLocal && uiState.currentApiKey.isNotBlank() -> "${uiState.providerType.displayName} активен"
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
                        isLocal -> "Загрузите GGUF-модель (Qwen 2.5, Llama 3.2), чтобы начать общение, либо подключите облачный API."
                        !isLocal && uiState.currentApiKey.isNotBlank() -> "Модель ${uiState.openCodeSelectedModel} готова к работе через ${uiState.providerType.displayName}."
                        else -> "Введите API-ключ для доступа к ${uiState.providerType.displayName}."
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
                            Text("Подключить DeepSeek / Облако")
                        }
                    }
                } else if (!isLocal && uiState.currentApiKey.isBlank()) {
                    Button(
                        onClick = onOpenProviderSettings,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ввести API-ключ")
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
    val currentKey = uiState.currentApiKey
    var apiKeyText by remember(uiState.providerType, currentKey) { mutableStateOf(currentKey) }
    var showApiKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Провайдер LLM и модели",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Выберите источник инференса:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )

                // Чипы выбора провайдера
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = uiState.providerType == LLMProviderType.LOCAL_GGUF,
                        onClick = { viewModel.setProviderType(LLMProviderType.LOCAL_GGUF) },
                        label = { Text("📱 Офлайн", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = uiState.providerType == LLMProviderType.DEEPSEEK,
                        onClick = { viewModel.setProviderType(LLMProviderType.DEEPSEEK) },
                        label = { Text("🐳 DeepSeek", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = uiState.providerType == LLMProviderType.OPENROUTER,
                        onClick = { viewModel.setProviderType(LLMProviderType.OPENROUTER) },
                        label = { Text("🌐 OpenRouter Free", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = uiState.providerType == LLMProviderType.GROQ,
                        onClick = { viewModel.setProviderType(LLMProviderType.GROQ) },
                        label = { Text("⚡ Groq Free", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = uiState.providerType == LLMProviderType.OPENCODE_ZEN,
                        onClick = { viewModel.setProviderType(LLMProviderType.OPENCODE_ZEN) },
                        label = { Text("🧘 Zen", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = uiState.providerType == LLMProviderType.OPENCODE_GO,
                        onClick = { viewModel.setProviderType(LLMProviderType.OPENCODE_GO) },
                        label = { Text("🚀 Go", fontSize = 11.sp) }
                    )
                }

                if (uiState.providerType != LLMProviderType.LOCAL_GGUF) {
                    // Описание провайдера
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when (uiState.providerType) {
                                LLMProviderType.DEEPSEEK ->
                                    "🐳 DeepSeek API: официальный API DeepSeek (deepseek-chat — DeepSeek V3, deepseek-reasoner — DeepSeek R1 с рассуждениями). Получите ключ на platform.deepseek.com/api_keys."
                                LLMProviderType.OPENROUTER ->
                                    "🌐 OpenRouter: десятки бесплатных моделей с суффиксом :free (Gemini 2.0 Flash, LLaMA 3.3 70B, Qwen 2.5 Coder, DeepSeek R1). Получите бесплатный ключ на openrouter.ai/keys."
                                LLMProviderType.GROQ ->
                                    "⚡ Groq Cloud: сверхбыстрый бесплатный доступ (до 200+ tok/s) к LLaMA 3.3 70B, LLaMA 3.1 8B, DeepSeek R1 Distill. Получите бесплатный ключ на console.groq.com/keys."
                                LLMProviderType.OPENCODE_ZEN ->
                                    "🧘 OpenCode ZEN: доступ к платным моделям (Qwen 3.6 Plus, Claude 3.7 Sonnet, GPT-4o, DeepSeek R1). Получите ключ на opencode.ai/auth."
                                LLMProviderType.OPENCODE_GO ->
                                    "🚀 OpenCode GO: открытые и кодинг-модели по подписке GO (DeepSeek V4 Pro, Kimi K2.6, Qwen 3.6). Получите ключ на opencode.ai/auth."
                                LLMProviderType.LOCAL_GGUF -> ""
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    // Поле ввода API ключа
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = {
                            apiKeyText = it
                            viewModel.setOpenCodeApiKey(it, uiState.providerType)
                        },
                        label = {
                            Text("API-ключ ${uiState.providerType.displayName}")
                        },
                        placeholder = {
                            Text(
                                when (uiState.providerType) {
                                    LLMProviderType.DEEPSEEK -> "sk-..."
                                    LLMProviderType.OPENROUTER -> "sk-or-v1-..."
                                    LLMProviderType.GROQ -> "gsk_..."
                                    LLMProviderType.OPENCODE_ZEN -> "sk-..."
                                    LLMProviderType.OPENCODE_GO -> "sk-..."
                                    else -> "sk-..."
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showApiKey = !showApiKey }) {
                                Text(if (showApiKey) "Скрыть" else "Показать", fontSize = 10.sp)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Выбор модели из каталога
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
                            enabled = !uiState.isFetchingModels && uiState.currentApiKey.isNotBlank()
                        ) {
                            Text(if (uiState.isFetchingModels) "Загрузка..." else "🔄 Обновить", fontSize = 11.sp)
                        }
                    }

                    // Список моделей
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        uiState.openCodeAvailableModels.forEach { modelName ->
                            val isSelected = modelName == uiState.openCodeSelectedModel
                            val isFree = OpenCodeClient.isFreeModel(uiState.providerType, modelName)
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
                                    uiState.providerType == LLMProviderType.DEEPSEEK && modelName == "deepseek-chat" -> "V3"
                                    uiState.providerType == LLMProviderType.DEEPSEEK && modelName == "deepseek-reasoner" -> "R1"
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

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (uiState.providerType) {
                            LLMProviderType.DEEPSEEK -> "💡 deepseek-chat — быстрая DeepSeek V3; deepseek-reasoner — DeepSeek R1 с автоматическим сворачиванием блока рассуждений 💭."
                            LLMProviderType.OPENROUTER -> "💡 Модели с плашкой Free (суффикс :free) полностью бесплатны на OpenRouter без пополнения счета."
                            LLMProviderType.GROQ -> "💡 В Groq Cloud все модели предоставляются с высоким бесплатным лимитом (до 30 запросов в минуту)."
                            LLMProviderType.OPENCODE_ZEN -> "💡 Для платного аккаунта Zen рекомендуются: qwen3.6-plus, qwen3.8-flash, claude-3-7-sonnet, gpt-4o. Промо-модели Free предназначены для анонимного CLI."
                            LLMProviderType.OPENCODE_GO -> "💡 Модели доступны при наличии активной подписки OpenCode GO."
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "В офлайн-режиме модель выполняется локально на процессоре Helio G99 смартфона без подключения к интернету.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Готово")
            }
        }
    )
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onCopyClick: () -> Unit
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
    var isThoughtExpanded by remember(message.id) { mutableStateOf(false) }
    var isAgentStepsExpanded by remember(message.id) { mutableStateOf(false) }

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
            modifier = Modifier.widthIn(min = 80.dp, max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Роль собеседника
                Text(
                    text = if (isUser) "Вы" else "Ассистент",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Блок выполненных шагов агента (файлы и Termux)
                if (!isUser && parsed.agentStepsText != null) {
                    val stepLines = remember(parsed.agentStepsText) {
                        parsed.agentStepsText.lines().filter { it.isNotBlank() }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clickable { isAgentStepsExpanded = !isAgentStepsExpanded },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🛠️ Действия агента (${stepLines.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (isAgentStepsExpanded || message.isStreaming) "Свернуть ▲" else "Показать ▼",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (isAgentStepsExpanded || message.isStreaming) {
                                Spacer(modifier = Modifier.height(4.dp))
                                stepLines.forEach { stepLine ->
                                    Text(
                                        text = stepLine,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
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

                // Блок размышлений модели (DeepSeek-R1 / QwQ)
                if (!isUser && parsed.thinkingText != null) {
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
                if (displayAnswer.isNotEmpty() || (!parsed.isStillThinking && !parsed.isPreparingToolCall && parsed.agentStepsText == null)) {
                    SelectionContainer {
                        val showCursor = message.isStreaming && displayAnswer.isEmpty() && !parsed.isStillThinking && !parsed.isPreparingToolCall
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

                // Нижняя строка: скорость, кнопка копирования и время
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
fun ChatInputBar(
    uiState: UiState,
    onInputTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    onAttachFileClick: () -> Unit,
    onRemoveAttachmentClick: () -> Unit
) {
    val isReady = uiState.isReadyToChat
    val attached = uiState.attachedDoc

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                val isTextOnlyProvider = uiState.providerType == LLMProviderType.LOCAL_GGUF ||
                    uiState.providerType == LLMProviderType.DEEPSEEK

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
                                text = "ℹ️ ${uiState.providerType.displayName} работает в текстовом режиме (будут переданы метаданные). Для визуального анализа выберите Vision-модель (OpenRouter / Zen / Go).",
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
                // Кнопка скрепки (прикрепление файлов любых форматов)
                IconButton(
                    onClick = onAttachFileClick,
                    enabled = isReady && !uiState.isGenerating,
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

                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = onInputTextChange,
                    placeholder = {
                        Text(
                            if (isReady) "Сообщение или прикрепите любой файл..."
                            else if (uiState.providerType == LLMProviderType.LOCAL_GGUF) "Сначала выберите модель GGUF..."
                            else "Укажите API-ключ ${uiState.providerType.displayName}..."
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp, max = 130.dp),
                    shape = RoundedCornerShape(22.dp),
                    enabled = isReady && !uiState.isGenerating
                )

                if (uiState.isGenerating) {
                    IconButton(
                        onClick = onStopClick,
                        modifier = Modifier
                            .size(48.dp)
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
                        enabled = isReady && (uiState.inputText.isNotBlank() || uiState.attachedDoc != null),
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isReady && (uiState.inputText.isNotBlank() || uiState.attachedDoc != null))
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = if (isReady && (uiState.inputText.isNotBlank() || uiState.attachedDoc != null))
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
    onDeny: () -> Unit
) {
    val borderColor = if (approval.isHighRisk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val containerColor = if (approval.isHighRisk) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, borderColor.copy(alpha = 0.7f)),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = approval.riskLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (approval.isHighRisk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
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
                Text(
                    text = approval.targetDisplay,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp
                    ),
                    modifier = Modifier.padding(8.dp),
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            if (approval.detailPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = approval.detailPreview,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖 Агент: файлы и Termux", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
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
                            text = "Создание, правка, поиск, удаление файлов в /sdcard и команды в Termux",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.agentModeEnabled,
                        onCheckedChange = { viewModel.setAgentModeEnabled(it) }
                    )
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
}
