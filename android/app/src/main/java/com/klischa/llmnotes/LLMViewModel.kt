package com.klischa.llmnotes

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.klischa.llmnotes.api.ApiChatMessage
import com.klischa.llmnotes.api.LLMProviderType
import com.klischa.llmnotes.api.OpenCodeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSec: Float? = null,
    val isStreaming: Boolean = false
)

enum class SystemPromptPreset(val title: String, val prompt: String) {
    UNIVERSAL(
        "Универсальный",
        "Ты — умный, точный и лаконичный русскоязычный персональный ассистент. " +
        "Всегда отвечай исключительно на русском языке понятно и по существу."
    ),
    CONCISE(
        "Кратко",
        "Ты — лаконичный русскоязычный ассистент. Отвечай только на русском языке, предельно кратко и строго по делу."
    ),
    TASKS(
        "Задачи",
        "Ты — менеджер задач. Всегда отвечай на русском языке. Формируй структурированный список конкретных действий с чекбоксами [ ]."
    ),
    DIALOG(
        "Диалог",
        "Ты — дружелюбный и внимательный русскоязычный собеседник. Отвечай на русском языке, развернуто и живым языком."
    )
}

data class AttachedDoc(
    val name: String,
    val sizeBytes: Long,
    val content: String,
    val path: String? = null,
    val mimeType: String = "application/octet-stream",
    val category: AttachmentCategory = AttachmentCategory.TEXT,
    val imageBase64: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val summaryBadge: String = ""
)

data class UiState(
    val isModelLoaded: Boolean = false,
    val isLoadingModel: Boolean = false,
    val modelPath: String = "",
    val isGenerating: Boolean = false,
    val inputText: String = "",
    val attachedDoc: AttachedDoc? = null,
    val messages: List<ChatMessage> = emptyList(),
    val currentChatId: String = "",
    val chatSessions: List<com.klischa.llmnotes.data.ChatSession> = emptyList(),
    val providerType: LLMProviderType = LLMProviderType.LOCAL_GGUF,
    val deepSeekApiKey: String = "",
    val openCodeGoApiKey: String = "",
    val openCodeZenApiKey: String = "",
    val openRouterApiKey: String = "",
    val groqApiKey: String = "",
    val openCodeSelectedModel: String = "qwen3.6-plus",
    val openCodeAvailableModels: List<String> = OpenCodeClient.ZEN_DEFAULT_MODELS,
    val isProviderDialogVisible: Boolean = false,
    val isFetchingModels: Boolean = false,
    val systemPrompt: String = SystemPromptPreset.UNIVERSAL.prompt,
    val isSystemPromptExpanded: Boolean = false,
    val statusMessage: String = "Выберите модель GGUF в памяти смартфона",
    val tokensPerSecond: Float = 0.0f,
    val totalTokensGenerated: Int = 0,
    val generationTimeSeconds: Float = 0.0f,
    val allocatedRamMb: Long = 0,
    val threadCount: Int = 2, // Оптимум 2 ядра Cortex-A76 для Helio G99
    val temperature: Float = 0.6f,
    val topP: Float = 0.9f,
    // Режим агента (работа с файлами устройства и Termux RUN_COMMAND)
    val agentModeEnabled: Boolean = true,
    val agentConfirmationMode: AgentConfirmationMode = AgentConfirmationMode.CONFIRM_DESTRUCTIVE,
    val isAgentDialogVisible: Boolean = false,
    val termuxStatus: TermuxStatus = TermuxStatus(isInstalled = false, hasRunPermission = false),
    val termuxTestOutput: String = "",
    val isTestingTermux: Boolean = false,
    val pendingToolApproval: PendingToolApproval? = null,
    val activeToolStatus: String? = null
) {
    val currentApiKey: String
        get() = when (providerType) {
            LLMProviderType.DEEPSEEK -> deepSeekApiKey
            LLMProviderType.OPENROUTER -> openRouterApiKey
            LLMProviderType.GROQ -> groqApiKey
            LLMProviderType.OPENCODE_GO -> openCodeGoApiKey
            LLMProviderType.OPENCODE_ZEN -> openCodeZenApiKey
            LLMProviderType.LOCAL_GGUF -> ""
        }

    val isReadyToChat: Boolean
        get() = if (providerType == LLMProviderType.LOCAL_GGUF) isModelLoaded else currentApiKey.isNotBlank()
}

class LLMViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val dbHelper = com.klischa.llmnotes.data.ChatDatabaseHelper(application)
    private var openPfd: ParcelFileDescriptor? = null

    @Volatile
    private var isRemoteCancelled: Boolean = false

    @Volatile
    private var isLocalCancelled: Boolean = false

    @Volatile
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

    companion object {
        private const val TAG = "LLMViewModel"
        private const val MAX_AGENT_STEPS = 8
        private const val PREFS_NAME = "llm_app_prefs"
        private const val PREF_LAST_MODEL_PATH = "last_model_path"
        private const val PREF_LAST_MODEL_URI = "last_model_uri"
        private const val PREF_LAST_MODEL_NAME = "last_model_name"
        private const val PREF_AUTO_LOAD_ENABLED = "auto_load_enabled"
        private const val PREF_PROVIDER_TYPE = "provider_type"
        private const val PREF_DEEPSEEK_KEY = "deepseek_key"
        private const val PREF_DEEPSEEK_MODEL = "deepseek_model"
        private const val PREF_OPENCODE_API_KEY = "opencode_api_key"
        private const val PREF_OPENCODE_GO_KEY = "opencode_go_key"
        private const val PREF_OPENCODE_ZEN_KEY = "opencode_zen_key"
        private const val PREF_OPENROUTER_KEY = "openrouter_key"
        private const val PREF_GROQ_KEY = "groq_key"
        private const val PREF_OPENCODE_MODEL_GO = "opencode_model_go"
        private const val PREF_OPENCODE_MODEL_ZEN = "opencode_model_zen"
        private const val PREF_OPENROUTER_MODEL = "openrouter_model"
        private const val PREF_GROQ_MODEL = "groq_model"
        private const val PREF_AGENT_MODE_ENABLED = "agent_mode_enabled"
        private const val PREF_AGENT_CONFIRM_MODE = "agent_confirm_mode"
    }

    fun saveLastModelInfo(directPath: String?, uriStr: String?, displayName: String) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (directPath != null) {
                putString(PREF_LAST_MODEL_PATH, directPath)
            }
            if (uriStr != null) {
                putString(PREF_LAST_MODEL_URI, uriStr)
            }
            putString(PREF_LAST_MODEL_NAME, displayName)
            putBoolean(PREF_AUTO_LOAD_ENABLED, true)
            apply()
        }
    }

    fun tryAutoLoadLastModel() {
        // Не тратим RAM на локальную GGUF-модель, если активен облачный API-провайдер
        if (_uiState.value.providerType != LLMProviderType.LOCAL_GGUF) return
        if (_uiState.value.isModelLoaded || _uiState.value.isLoadingModel) return

        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoLoad = prefs.getBoolean(PREF_AUTO_LOAD_ENABLED, true)
        if (!autoLoad) return

        val lastPath = prefs.getString(PREF_LAST_MODEL_PATH, null)
        val lastUriStr = prefs.getString(PREF_LAST_MODEL_URI, null)
        val lastName = prefs.getString(PREF_LAST_MODEL_NAME, "модели") ?: "модели"

        // Приоритет 1: сохраненный прямой путь к файлу (например /sdcard/Download/...)
        if (!lastPath.isNullOrBlank()) {
            val file = File(lastPath)
            if (file.exists() && file.canRead() && file.length() > 0) {
                Log.i(TAG, "Автозагрузка сохраненной модели по пути: $lastPath")
                _uiState.update {
                    it.copy(
                        isLoadingModel = true,
                        statusMessage = "Автозагрузка модели ($lastName)..."
                    )
                }
                loadModel(lastPath)
                return
            }
        }

        // Приоритет 2: сохраненный URI SAF
        if (!lastUriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(lastUriStr)
                Log.i(TAG, "Автозагрузка сохраненной модели по URI: $lastUriStr")
                _uiState.update {
                    it.copy(
                        isLoadingModel = true,
                        statusMessage = "Автозагрузка модели ($lastName)..."
                    )
                }
                loadModelUri(uri)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось выполнить автозагрузку по URI: ${e.message}")
            }
        }

        // Приоритет 3: поиск во внутренней папке models
        val modelsDir = File(getApplication<Application>().filesDir, "models")
        if (modelsDir.exists()) {
            val cachedFile = modelsDir.listFiles()?.firstOrNull { it.extension.lowercase() == "gguf" && it.length() > 0 }
            if (cachedFile != null) {
                Log.i(TAG, "Автозагрузка модели из внутренней папки models: ${cachedFile.absolutePath}")
                _uiState.update {
                    it.copy(
                        isLoadingModel = true,
                        statusMessage = "Автозагрузка модели (${cachedFile.name})..."
                    )
                }
                loadModel(cachedFile.absolutePath)
            }
        }
    }

    init {
        loadChatSessions()
        initProviderSettings()
    }

    private fun buildIdleStatus(state: UiState): String {
        return when (state.providerType) {
            LLMProviderType.LOCAL_GGUF ->
                if (state.isModelLoaded) "Модель готова: ${state.modelPath}"
                else "Выберите модель GGUF в памяти смартфона"
            else ->
                if (state.currentApiKey.isNotBlank()) {
                    "${state.providerType.displayName} готов (${state.openCodeSelectedModel})"
                } else {
                    "Укажите API-ключ для ${state.providerType.displayName}"
                }
        }
    }

    private fun initProviderSettings() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val providerStr = prefs.getString(PREF_PROVIDER_TYPE, LLMProviderType.LOCAL_GGUF.name)
        val provider = try {
            LLMProviderType.valueOf(providerStr ?: "")
        } catch (_: Exception) {
            LLMProviderType.LOCAL_GGUF
        }
        val keyDeepSeek = prefs.getString(PREF_DEEPSEEK_KEY, "") ?: ""
        val keyGo = prefs.getString(PREF_OPENCODE_GO_KEY, null)
            ?: prefs.getString(PREF_OPENCODE_API_KEY, "") ?: ""
        val keyZen = prefs.getString(PREF_OPENCODE_ZEN_KEY, null)
            ?: prefs.getString(PREF_OPENCODE_API_KEY, "") ?: ""
        val keyOpenRouter = prefs.getString(PREF_OPENROUTER_KEY, "") ?: ""
        val keyGroq = prefs.getString(PREF_GROQ_KEY, "") ?: ""

        val modelDeepSeek = prefs.getString(PREF_DEEPSEEK_MODEL, "deepseek-chat") ?: "deepseek-chat"
        val modelGo = prefs.getString(PREF_OPENCODE_MODEL_GO, "deepseek-v4-pro") ?: "deepseek-v4-pro"
        val modelZen = prefs.getString(PREF_OPENCODE_MODEL_ZEN, "qwen3.6-plus") ?: "qwen3.6-plus"
        val modelOpenRouter = prefs.getString(PREF_OPENROUTER_MODEL, "google/gemini-2.0-flash-exp:free") ?: "google/gemini-2.0-flash-exp:free"
        val modelGroq = prefs.getString(PREF_GROQ_MODEL, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"

        val selectedModel = when (provider) {
            LLMProviderType.DEEPSEEK -> modelDeepSeek
            LLMProviderType.OPENROUTER -> modelOpenRouter
            LLMProviderType.GROQ -> modelGroq
            LLMProviderType.OPENCODE_ZEN -> modelZen
            LLMProviderType.OPENCODE_GO -> modelGo
            LLMProviderType.LOCAL_GGUF -> ""
        }
        val models = OpenCodeClient.getDefaultModels(provider)

        val agentEnabled = prefs.getBoolean(PREF_AGENT_MODE_ENABLED, true)
        val confirmStr = prefs.getString(PREF_AGENT_CONFIRM_MODE, AgentConfirmationMode.CONFIRM_DESTRUCTIVE.name)
        val confirmMode = try {
            AgentConfirmationMode.valueOf(confirmStr ?: "")
        } catch (_: Exception) {
            AgentConfirmationMode.CONFIRM_DESTRUCTIVE
        }
        val termuxStatus = TermuxBridge.checkStatus(getApplication())

        _uiState.update { current ->
            val updated = current.copy(
                providerType = provider,
                deepSeekApiKey = keyDeepSeek,
                openCodeGoApiKey = keyGo,
                openCodeZenApiKey = keyZen,
                openRouterApiKey = keyOpenRouter,
                groqApiKey = keyGroq,
                openCodeSelectedModel = selectedModel,
                openCodeAvailableModels = models,
                agentModeEnabled = agentEnabled,
                agentConfirmationMode = confirmMode,
                termuxStatus = termuxStatus
            )
            updated.copy(statusMessage = buildIdleStatus(updated))
        }
    }

    fun setAgentModeEnabled(enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_AGENT_MODE_ENABLED, enabled).apply()
        _uiState.update {
            it.copy(
                agentModeEnabled = enabled,
                statusMessage = if (enabled) "🤖 Режим агента включён (файлы и Termux)" else "Режим агента выключен (обычный чат)"
            )
        }
    }

    fun setAgentConfirmationMode(mode: AgentConfirmationMode) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_AGENT_CONFIRM_MODE, mode.name).apply()
        _uiState.update {
            it.copy(
                agentConfirmationMode = mode,
                statusMessage = "Подтверждение агента: ${mode.title}"
            )
        }
    }

    fun setAgentDialogVisible(visible: Boolean) {
        if (visible) {
            refreshTermuxStatus()
        }
        _uiState.update { it.copy(isAgentDialogVisible = visible) }
    }

    fun refreshTermuxStatus() {
        val status = TermuxBridge.checkStatus(getApplication())
        _uiState.update { it.copy(termuxStatus = status) }
    }

    fun approveToolCall() {
        val d = pendingApprovalDeferred
        pendingApprovalDeferred = null
        _uiState.update { it.copy(pendingToolApproval = null) }
        d?.complete(true)
    }

    fun denyToolCall() {
        val d = pendingApprovalDeferred
        pendingApprovalDeferred = null
        _uiState.update { it.copy(pendingToolApproval = null) }
        d?.complete(false)
    }

    fun testTermuxConnection() {
        if (_uiState.value.isTestingTermux) return
        refreshTermuxStatus()
        _uiState.update { it.copy(isTestingTermux = true, termuxTestOutput = "Выполняю тестовую команду в Termux...") }
        viewModelScope.launch(Dispatchers.IO) {
            val res = TermuxBridge.runCommand(
                context = getApplication(),
                command = "echo \"Termux OK | $(uname -m) | bash $(bash --version | head -n1 | cut -d' ' -f4)\"",
                workdir = AgentTools.storageRootCanonical(),
                timeoutSeconds = 15
            )
            val msg = if (res.success) {
                "✅ ${res.stdout.trim()}"
            } else {
                "⚠️ ${res.errorMessage ?: res.stderr.ifBlank { "Код выхода: ${res.exitCode}" }}"
            }
            _uiState.update {
                it.copy(
                    isTestingTermux = false,
                    termuxTestOutput = msg,
                    termuxStatus = TermuxBridge.checkStatus(getApplication())
                )
            }
        }
    }

    fun setProviderType(type: LLMProviderType) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_PROVIDER_TYPE, type.name).apply()

        val selectedModel = when (type) {
            LLMProviderType.DEEPSEEK -> prefs.getString(PREF_DEEPSEEK_MODEL, "deepseek-chat") ?: "deepseek-chat"
            LLMProviderType.OPENROUTER -> prefs.getString(PREF_OPENROUTER_MODEL, "google/gemini-2.0-flash-exp:free") ?: "google/gemini-2.0-flash-exp:free"
            LLMProviderType.GROQ -> prefs.getString(PREF_GROQ_MODEL, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
            LLMProviderType.OPENCODE_GO -> prefs.getString(PREF_OPENCODE_MODEL_GO, "deepseek-v4-pro") ?: "deepseek-v4-pro"
            LLMProviderType.OPENCODE_ZEN -> prefs.getString(PREF_OPENCODE_MODEL_ZEN, "qwen3.6-plus") ?: "qwen3.6-plus"
            LLMProviderType.LOCAL_GGUF -> ""
        }
        val defaultModels = OpenCodeClient.getDefaultModels(type)

        _uiState.update { current ->
            val updated = current.copy(
                providerType = type,
                openCodeSelectedModel = selectedModel,
                openCodeAvailableModels = defaultModels
            )
            updated.copy(statusMessage = buildIdleStatus(updated))
        }

        if (type == LLMProviderType.LOCAL_GGUF) {
            tryAutoLoadLastModel()
        } else if (_uiState.value.currentApiKey.isNotBlank()) {
            fetchRemoteModels()
        }
    }

    fun setOpenCodeApiKey(key: String, provider: LLMProviderType = _uiState.value.providerType) {
        val cleanKey = key.trim()
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (provider) {
            LLMProviderType.DEEPSEEK -> {
                prefs.edit().putString(PREF_DEEPSEEK_KEY, cleanKey).apply()
                _uiState.update { it.copy(deepSeekApiKey = cleanKey) }
            }
            LLMProviderType.OPENROUTER -> {
                prefs.edit().putString(PREF_OPENROUTER_KEY, cleanKey).apply()
                _uiState.update { it.copy(openRouterApiKey = cleanKey) }
            }
            LLMProviderType.GROQ -> {
                prefs.edit().putString(PREF_GROQ_KEY, cleanKey).apply()
                _uiState.update { it.copy(groqApiKey = cleanKey) }
            }
            LLMProviderType.OPENCODE_ZEN -> {
                prefs.edit().putString(PREF_OPENCODE_ZEN_KEY, cleanKey).apply()
                _uiState.update { it.copy(openCodeZenApiKey = cleanKey) }
            }
            LLMProviderType.OPENCODE_GO -> {
                prefs.edit().putString(PREF_OPENCODE_GO_KEY, cleanKey).apply()
                _uiState.update { it.copy(openCodeGoApiKey = cleanKey) }
            }
            LLMProviderType.LOCAL_GGUF -> {}
        }
        // Обновляем статус без лишнего сетевого запроса на каждое нажатие клавиши
        _uiState.update { current -> current.copy(statusMessage = buildIdleStatus(current)) }
    }

    fun setOpenCodeSelectedModel(model: String) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = when (_uiState.value.providerType) {
            LLMProviderType.DEEPSEEK -> PREF_DEEPSEEK_MODEL
            LLMProviderType.OPENROUTER -> PREF_OPENROUTER_MODEL
            LLMProviderType.GROQ -> PREF_GROQ_MODEL
            LLMProviderType.OPENCODE_ZEN -> PREF_OPENCODE_MODEL_ZEN
            LLMProviderType.OPENCODE_GO -> PREF_OPENCODE_MODEL_GO
            LLMProviderType.LOCAL_GGUF -> ""
        }
        if (key.isNotEmpty()) {
            prefs.edit().putString(key, model).apply()
        }
        _uiState.update {
            it.copy(
                openCodeSelectedModel = model,
                statusMessage = "${it.providerType.displayName}: выбрана модель $model"
            )
        }
    }

    fun fetchRemoteModels() {
        val provider = _uiState.value.providerType
        val apiKey = _uiState.value.currentApiKey
        if (provider == LLMProviderType.LOCAL_GGUF || apiKey.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isFetchingModels = true) }
            val models = OpenCodeClient.fetchModels(provider, apiKey)
            _uiState.update {
                it.copy(
                    isFetchingModels = false,
                    openCodeAvailableModels = models
                )
            }
        }
    }

    fun setProviderDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(isProviderDialogVisible = visible) }
    }

    private fun loadChatSessions() {
        val sessions = dbHelper.getAllSessions()
        if (sessions.isEmpty()) {
            val newId = UUID.randomUUID().toString()
            val newSession = dbHelper.createSession(newId, "Новый чат")
            _uiState.update {
                it.copy(
                    currentChatId = newId,
                    chatSessions = listOf(newSession),
                    messages = emptyList()
                )
            }
        } else {
            val current = sessions.first()
            val msgs = dbHelper.getMessages(current.id)
            _uiState.update {
                it.copy(
                    currentChatId = current.id,
                    chatSessions = sessions,
                    messages = msgs
                )
            }
        }
    }

    fun createNewChat() {
        if (_uiState.value.isGenerating) {
            stopGeneration()
        }
        val newId = UUID.randomUUID().toString()
        val newSession = dbHelper.createSession(newId, "Новый чат")
        val updatedList = listOf(newSession) + _uiState.value.chatSessions.filter { it.id != newId }
        _uiState.update {
            it.copy(
                currentChatId = newId,
                chatSessions = updatedList,
                messages = emptyList(),
                statusMessage = if (it.isReadyToChat) "Новый диалог создан" else buildIdleStatus(it)
            )
        }
    }

    fun selectChat(chatId: String) {
        if (chatId == _uiState.value.currentChatId) return
        if (_uiState.value.isGenerating) {
            stopGeneration()
        }
        val msgs = dbHelper.getMessages(chatId)
        _uiState.update {
            it.copy(
                currentChatId = chatId,
                messages = msgs,
                statusMessage = if (it.isReadyToChat) "Диалог загружен" else buildIdleStatus(it)
            )
        }
    }

    fun deleteChat(chatId: String) {
        if (_uiState.value.isGenerating && chatId == _uiState.value.currentChatId) {
            stopGeneration()
        }
        dbHelper.deleteSession(chatId)
        val sessions = dbHelper.getAllSessions()
        if (sessions.isEmpty()) {
            createNewChat()
        } else {
            val nextSession = sessions.first()
            val msgs = dbHelper.getMessages(nextSession.id)
            _uiState.update {
                it.copy(
                    currentChatId = nextSession.id,
                    chatSessions = sessions,
                    messages = msgs
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        isRemoteCancelled = true
        isLocalCancelled = true
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        TermuxBridge.cancelAllPending()
        LlamaBridge.nativeStop()
        openPfd?.close()
        openPfd = null
        LlamaBridge.isModelLoaded = false
        LlamaBridge.nativeUnload()
    }

    fun attachFile(name: String, sizeBytes: Long, content: String, path: String? = null) {
        attachParsedDoc(
            AttachedDoc(
                name = name,
                sizeBytes = sizeBytes,
                content = content,
                path = path,
                summaryBadge = UniversalFileParser.formatSize(sizeBytes)
            )
        )
    }

    fun attachParsedDoc(doc: AttachedDoc) {
        _uiState.update {
            it.copy(
                attachedDoc = doc,
                statusMessage = "${doc.category.emoji} Прикреплён файл: ${doc.name}" +
                    if (doc.summaryBadge.isNotBlank()) " (${doc.summaryBadge})" else ""
            )
        }
    }

    /**
     * Универсальное асинхронное прикрепление файла ЛЮБОГО формата по content:// URI
     * (скриншоты, фото, PDF, DOCX/XLSX/PPTX, EPUB/FB2/RTF, ZIP/APK, аудио, видео, текст, бинарники).
     */
    fun attachUri(uri: Uri, directPath: String? = null, onError: ((String) -> Unit)? = null) {
        _uiState.update { it.copy(statusMessage = "Чтение и обработка файла...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parsed = UniversalFileParser.parseUri(getApplication(), uri, directPath)
                attachParsedDoc(parsed)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка прикрепления файла: ${e.message}", e)
                val msg = "Ошибка чтения файла: ${e.message ?: "неизвестная ошибка"}"
                _uiState.update { it.copy(statusMessage = msg) }
                onError?.invoke(msg)
            }
        }
    }

    fun clearAttachment() {
        _uiState.update { it.copy(attachedDoc = null) }
    }

    fun saveCodeBlock(code: String, suggestedName: String? = null, language: String? = null): File {
        return FileStorageManager.saveCodeToDownloads(getApplication(), code, suggestedName, language)
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun updateSystemPrompt(newPrompt: String) {
        _uiState.update { it.copy(systemPrompt = newPrompt) }
    }

    fun applyPreset(preset: SystemPromptPreset) {
        _uiState.update { it.copy(systemPrompt = preset.prompt) }
    }

    fun toggleSystemPromptExpanded() {
        _uiState.update { it.copy(isSystemPromptExpanded = !it.isSystemPromptExpanded) }
    }

    fun setThreadCount(threads: Int) {
        _uiState.update { it.copy(threadCount = threads) }
    }

    fun setTemperature(temp: Float) {
        _uiState.update { it.copy(temperature = temp) }
    }

    /**
     * Загрузка модели из URI Android Storage Access Framework (SAF).
     * Приоритет отдается прямому чтению без копирования.
     */
    fun loadModelUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver
            val (displayName, fileSize) = queryUriMetadata(contentResolver, uri)

            _uiState.update {
                it.copy(
                    isLoadingModel = true,
                    statusMessage = "Подготовка файла модели ($displayName)..."
                )
            }

            openPfd?.close()
            openPfd = null

            // Способ 1: Прямой путь к файлу (если схема uri = file)
            if (uri.scheme == "file") {
                val directPath = uri.path
                if (directPath != null && File(directPath).canRead()) {
                    loadModelInternal(directPath, displayName)
                    return@launch
                }
            }

            // Способ 2: Открытие через File Descriptor (SAF proc/self/fd)
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val fd = pfd.fd
                    val fdPath = "/proc/self/fd/$fd"

                    val realPath = try {
                        android.system.Os.readlink(fdPath)
                    } catch (_: Exception) {
                        null
                    }

                    val pathToUse = if (realPath != null && File(realPath).canRead() && File(realPath).length() > 0) {
                        Log.i(TAG, "Определен прямой путь к файлу: $realPath")
                        pfd.close()
                        realPath
                    } else {
                        Log.i(TAG, "Используем дескриптор SAF: $fdPath")
                        openPfd = pfd
                        fdPath
                    }

                    _uiState.update {
                        it.copy(statusMessage = "Загрузка модели в память ($displayName)...")
                    }

                    val error = LlamaBridge.nativeLoadModel(
                        pathToUse,
                        2048,
                        _uiState.value.threadCount
                    )

                    if (error.isEmpty()) {
                        LlamaBridge.isModelLoaded = true
                        val direct = if (File(pathToUse).exists() && File(pathToUse).length() > 0) pathToUse else null
                        saveLastModelInfo(direct, uri.toString(), displayName)
                        _uiState.update {
                            it.copy(
                                isModelLoaded = true,
                                isLoadingModel = false,
                                modelPath = displayName,
                                statusMessage = "Модель готова: $displayName"
                            )
                        }
                        updateRamUsage()
                        return@launch
                    } else {
                        Log.w(TAG, "Не удалось загрузить напрямую ($pathToUse): $error")
                        openPfd?.close()
                        openPfd = null

                        val isFatalModelError = error.contains("unknown model architecture") ||
                                                error.contains("unsupported") ||
                                                error.contains("нехватка памяти") ||
                                                error.contains("out of memory") ||
                                                error.contains("cannot allocate")

                        if (isFatalModelError) {
                            val friendlyError = formatLoadError(error)
                            _uiState.update {
                                it.copy(
                                    isModelLoaded = false,
                                    isLoadingModel = false,
                                    statusMessage = friendlyError
                                )
                            }
                            updateRamUsage()
                            return@launch
                        }

                        _uiState.update {
                            it.copy(
                                statusMessage = "Прямой доступ не удался. Проверка места для локального кэша..."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка открытия FileDescriptor: ${e.message}")
            }

            // Способ 3: Кэширование во внутреннее хранилище приложения при необходимости
            val appFilesDir = getApplication<Application>().filesDir
            val freeBytes = appFilesDir.freeSpace
            val neededBytes = if (fileSize > 0) fileSize else 1500L * 1024 * 1024

            if (freeBytes < neededBytes + 150L * 1024 * 1024) {
                val freeMb = freeBytes / (1024 * 1024)
                val needMb = neededBytes / (1024 * 1024)
                _uiState.update {
                    it.copy(
                        isModelLoaded = false,
                        isLoadingModel = false,
                        statusMessage = "Прямой доступ отклонен ОС. Для кэша свободно $freeMb МБ, нужно $needMb МБ."
                    )
                }
                return@launch
            }

            val modelsDir = File(appFilesDir, "models").apply { mkdirs() }
            val cacheFile = File(modelsDir, displayName.ifBlank { "model.gguf" })
            try {
                _uiState.update {
                    it.copy(statusMessage = "Копирование модели в папку models...")
                }

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var bytesCopied = 0L
                        var lastReportTime = System.currentTimeMillis()

                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            val now = System.currentTimeMillis()
                            if (now - lastReportTime > 600) {
                                lastReportTime = now
                                val progress = if (fileSize > 0) " (${(bytesCopied * 100 / fileSize)}%)" else ""
                                _uiState.update {
                                    it.copy(statusMessage = "Копирование во внутренний кэш$progress...")
                                }
                            }
                        }
                    }
                }

                loadModelInternal(cacheFile.absolutePath, displayName)

            } catch (e: Exception) {
                if (cacheFile.exists()) cacheFile.delete()
                _uiState.update {
                    it.copy(
                        isModelLoaded = false,
                        isLoadingModel = false,
                        statusMessage = "Ошибка кэширования модели: ${e.message}"
                    )
                }
            }
        }
    }

    private fun loadModelInternal(path: String, displayName: String) {
        _uiState.update {
            it.copy(
                isLoadingModel = true,
                statusMessage = "Инициализация llama.cpp ($displayName)..."
            )
        }
        updateRamUsage()

        val error = LlamaBridge.nativeLoadModel(
            path,
            2048,
            _uiState.value.threadCount
        )

        if (error.isEmpty()) {
            LlamaBridge.isModelLoaded = true
            saveLastModelInfo(path, null, displayName)
            _uiState.update {
                it.copy(
                    isModelLoaded = true,
                    isLoadingModel = false,
                    modelPath = displayName,
                    statusMessage = "Модель готова: $displayName"
                )
            }
        } else {
            val friendlyError = formatLoadError(error)
            _uiState.update {
                it.copy(
                    isModelLoaded = false,
                    isLoadingModel = false,
                    statusMessage = friendlyError
                )
            }
        }
        updateRamUsage()
    }

    private fun formatLoadError(error: String): String {
        return when {
            error.contains("unknown model architecture: 'qwen35'") || error.contains("'qwen35'") ->
                "Архитектура 'qwen35' (Qwen 3.5) содержит гибридные слои DeltaNet и не поддерживается мобильным llama.cpp. Используйте официальные модели линейки Qwen 2.5 (архитектура 'qwen2') или Llama-3.2."
            error.contains("unknown model architecture") ->
                "Неподдерживаемая архитектура ($error). Поддерживаются архитектуры: Qwen 2.5 (qwen2), LLaMA 3/3.2 (llama), Gemma 2 (gemma2), Mistral, Phi-3."
            error.contains("недостаточно памяти") || error.contains("out of memory") ->
                "Недостаточно RAM для модели. Выберите модель 1.5B или закройте тяжелые приложения."
            else -> error
        }
    }

    fun loadModel(filePath: String) {
        val fileName = File(filePath).name
        viewModelScope.launch(Dispatchers.IO) {
            loadModelInternal(filePath, fileName)
        }
    }

    private fun queryUriMetadata(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = "model.gguf"
        var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) cursor.getString(nameIndex)?.let { name = it }
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка чтения метаданных: ${e.message}")
        }
        return Pair(name, size)
    }

    /**
     * Отправка сообщения в чат с сохранением многошаговой истории диалога (Multi-turn chat).
     * Поддерживает как локальный инференс (LlamaBridge / GGUF), так и облачные API
     * (DeepSeek API, OpenRouter, Groq Cloud, OpenCode ZEN/GO).
     */
    fun sendMessage(userText: String = _uiState.value.inputText) {
        val stateSnapshot = _uiState.value
        val attached = stateSnapshot.attachedDoc
        val rawPrompt = userText.trim()
        if ((rawPrompt.isEmpty() && attached == null) || stateSnapshot.isGenerating) return

        val prompt = rawPrompt.ifEmpty {
            when (attached?.category) {
                AttachmentCategory.IMAGE -> "Опиши и проанализируй это изображение (${attached.name})"
                AttachmentCategory.PDF -> "Проанализируй прикреплённый PDF-документ (${attached.name})"
                else -> "Проанализируй прикреплённый файл (${attached?.name ?: "документ"})"
            }
        }

        val isLocal = stateSnapshot.providerType == LLMProviderType.LOCAL_GGUF
        if (isLocal && !stateSnapshot.isModelLoaded) return
        if (!isLocal && stateSnapshot.currentApiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    statusMessage = "Укажите API-ключ для ${stateSnapshot.providerType.displayName}",
                    isProviderDialogVisible = true
                )
            }
            return
        }

        val displayUserText = if (attached != null) {
            val badge = attached.summaryBadge.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "${attached.category.emoji} ${attached.name}$badge\n$prompt"
        } else {
            prompt
        }

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            text = displayUserText
        )
        val assistantMessage = ChatMessage(
            role = MessageRole.ASSISTANT,
            text = "",
            isStreaming = true
        )

        val previousMessages = stateSnapshot.messages
        val updatedMessages = previousMessages + userMessage + assistantMessage
        val assistantMsgId = assistantMessage.id
        val existingChatId = stateSnapshot.currentChatId
        val activeProvider = stateSnapshot.providerType
        val activeApiKey = stateSnapshot.currentApiKey
        val activeRemoteModel = stateSnapshot.openCodeSelectedModel
        val activeTemperature = stateSnapshot.temperature
        val activeTopP = stateSnapshot.topP
        val baseSysPrompt = stateSnapshot.systemPrompt.trim()
        val agentEnabled = stateSnapshot.agentModeEnabled
        val activeConfirmMode = stateSnapshot.agentConfirmationMode

        _uiState.update {
            it.copy(
                inputText = "",
                attachedDoc = null,
                messages = updatedMessages,
                isGenerating = true,
                pendingToolApproval = null,
                activeToolStatus = null,
                statusMessage = "Генерация ответа...",
                tokensPerSecond = 0.0f,
                totalTokensGenerated = 0
            )
        }

        isRemoteCancelled = false
        isLocalCancelled = false

        // Вся работа с БД, файлами, телеметрией, Termux и сетью/инференсом — в фоновом потоке
        viewModelScope.launch(Dispatchers.IO) {
            val currentChatId = existingChatId.ifBlank {
                val newId = UUID.randomUUID().toString()
                dbHelper.createSession(newId, "Новый чат")
                _uiState.update { it.copy(currentChatId = newId) }
                newId
            }

            // Сохраняем сообщение пользователя в БД
            dbHelper.saveMessage(currentChatId, userMessage)

            // Обновляем заголовок чата, если он еще стандартный
            val currentSession = _uiState.value.chatSessions.find { it.id == currentChatId }
            if (currentSession == null || currentSession.title == "Новый чат") {
                val titleSeed = prompt.replace("\n", " ").trim()
                val newTitle = titleSeed.take(30) + if (titleSeed.length > 30) "..." else ""
                dbHelper.updateSessionTitle(currentChatId, newTitle)
                val updatedSessions = dbHelper.getAllSessions()
                _uiState.update { it.copy(chatSessions = updatedSessions) }
            }

            // Собираем историю диалога (последние 10 сообщений)
            val historyForPrompt = previousMessages.takeLast(10)
            val rolesList = mutableListOf<String>()
            val contentsList = mutableListOf<String>()

            // 1. Системный промпт + телеметрия устройства + инструкции режима агента (файлы и Termux)
            val telemetryContext = if (DeviceTools.isDeviceQuery(prompt)) {
                try {
                    DeviceTools.buildTelemetryContext(getApplication())
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка сбора телеметрии: ${e.message}")
                    ""
                }
            } else {
                ""
            }

            val agentPrompt = if (agentEnabled) {
                val tStatus = TermuxBridge.checkStatus(getApplication())
                _uiState.update { it.copy(termuxStatus = tStatus) }
                AgentTools.buildAgentSystemPrompt(tStatus)
            } else {
                ""
            }

            val effectiveSysPrompt = listOf(baseSysPrompt, telemetryContext, agentPrompt)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")

            if (effectiveSysPrompt.isNotBlank()) {
                rolesList.add("system")
                contentsList.add(effectiveSysPrompt)
            }

            // 2. История предыдущих реплик (очищаем <think> и <agent_steps> для экономии контекста и совместимости с DeepSeek R1)
            for (msg in historyForPrompt) {
                if (msg.role == MessageRole.USER) {
                    rolesList.add("user")
                    contentsList.add(msg.text)
                } else if (msg.role == MessageRole.ASSISTANT && msg.text.isNotBlank()) {
                    val parsed = ThoughtParser.parse(msg.text, isStreaming = false)
                    val cleanText = parsed.answerText.ifBlank { ThoughtParser.cleanStopTokens(msg.text) }.trim()
                    if (cleanText.isNotBlank()) {
                        rolesList.add("assistant")
                        contentsList.add(cleanText)
                    }
                }
            }

            // 3. Текущее сообщение пользователя с проверкой вложений и локальных файлов (любых форматов)
            val currentImageDataUrls = mutableListOf<String>()
            val filePrefix = if (attached != null) {
                if (attached.imageBase64 != null) {
                    currentImageDataUrls.add("data:image/jpeg;base64,${attached.imageBase64}")
                }
                val sizeLabel = UniversalFileParser.formatSize(attached.sizeBytes)
                if (attached.category == AttachmentCategory.TEXT) {
                    val ext = attached.name.substringAfterLast('.', "").lowercase()
                    "[Прикреплённый файл: ${attached.name} ($sizeLabel)]:\n```$ext\n${attached.content}\n```\n\n"
                } else {
                    "[Прикреплённый файл: ${attached.name} ($sizeLabel, ${attached.mimeType})]:\n${attached.content}\n\n"
                }
            } else ""

            val (enrichedPrompt, loadedLocalFiles, localImages) =
                FileStorageManager.enrichPromptWithLocalFilesAndImages(getApplication(), prompt)
            currentImageDataUrls.addAll(localImages)
            val finalUserPromptForModel = filePrefix + enrichedPrompt

            rolesList.add("user")
            contentsList.add(finalUserPromptForModel)

            val maxSteps = if (agentEnabled) MAX_AGENT_STEPS else 1
            val agentLogLines = mutableListOf<String>()
            val startTime = System.currentTimeMillis()
            var totalTokens = 0
            var finalStepText = ""

            // Ветка 1: Внешний облачный API (DeepSeek / OpenRouter / Groq / OpenCode ZEN / GO) с многошаговым агентным циклом
            if (!isLocal) {
                val apiMessages = ArrayList<ApiChatMessage>(rolesList.size)
                for (i in 0 until rolesList.size) {
                    val isCurrentUserMsg = (i == rolesList.lastIndex && rolesList[i] == "user")
                    apiMessages.add(
                        ApiChatMessage(
                            role = rolesList[i],
                            content = contentsList[i],
                            imageDataUrls = if (isCurrentUserMsg) currentImageDataUrls else emptyList()
                        )
                    )
                }

                try {
                    for (step in 1..maxSteps) {
                        if (isRemoteCancelled) break
                        val stepBuilder = StringBuilder()
                        val stepsPrefix = buildStepsPrefix(agentLogLines)

                        OpenCodeClient.streamChatCompletions(
                            provider = activeProvider,
                            apiKey = activeApiKey,
                            model = activeRemoteModel,
                            messages = apiMessages,
                            conversationId = currentChatId,
                            temperature = activeTemperature,
                            onToken = { tokenPiece ->
                                totalTokens++
                                stepBuilder.append(tokenPiece)
                                val currentStepClean = ThoughtParser.cleanStopTokens(stepBuilder.toString())
                                val liveDisplay = stepsPrefix + currentStepClean
                                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                                val speed = if (elapsedSec > 0.05f) totalTokens / elapsedSec else 0.0f

                                _uiState.update { current ->
                                    val msgs = current.messages.map { m ->
                                        if (m.id == assistantMsgId) {
                                            m.copy(text = liveDisplay, tokensPerSec = speed, isStreaming = true)
                                        } else {
                                            m
                                        }
                                    }
                                    current.copy(
                                        messages = msgs,
                                        totalTokensGenerated = totalTokens,
                                        tokensPerSecond = speed,
                                        generationTimeSeconds = elapsedSec
                                    )
                                }
                            },
                            isCancelled = { isRemoteCancelled }
                        )

                        val stepRaw = ThoughtParser.cleanStopTokens(stepBuilder.toString())
                        finalStepText = stepRaw

                        val toolCalls = if (agentEnabled && !isRemoteCancelled) {
                            AgentTools.extractToolCalls(stepRaw)
                        } else {
                            emptyList()
                        }

                        if (toolCalls.isEmpty() || step == maxSteps) {
                            break
                        }

                        // Выполняем запрошенные агентом инструменты (с подтверждением опасных действий!)
                        val observation = executeAgentToolCalls(
                            calls = toolCalls,
                            confirmMode = activeConfirmMode,
                            agentLogLines = agentLogLines,
                            assistantMsgId = assistantMsgId,
                            isCancelled = { isRemoteCancelled }
                        )
                        if (isRemoteCancelled) break

                        apiMessages.add(ApiChatMessage("assistant", stepRaw))
                        apiMessages.add(
                            ApiChatMessage(
                                "user",
                                "$observation\n\nПродолжи выполнение задачи или дай итоговый ответ пользователю на русском языке."
                            )
                        )
                        _uiState.update { it.copy(statusMessage = "Агент анализирует результат (шаг ${step + 1})...") }
                    }

                    val totalElapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val finalSpeed = if (totalElapsed > 0.05f) totalTokens / totalElapsed else 0.0f
                    val cleanFinalBody = AgentTools.stripToolCallBlocks(finalStepText)
                    val finalText = buildStepsPrefix(agentLogLines) + cleanFinalBody
                    val wasCancelled = isRemoteCancelled

                    val completedMsg = ChatMessage(
                        id = assistantMsgId,
                        role = MessageRole.ASSISTANT,
                        text = finalText,
                        tokensPerSec = finalSpeed,
                        isStreaming = false
                    )
                    dbHelper.saveMessage(currentChatId, completedMsg)
                    val refreshedSessions = dbHelper.getAllSessions()

                    val fileNote = if (loadedLocalFiles.isNotEmpty()) " (файлы: ${loadedLocalFiles.joinToString()})" else ""
                    val stepsNote = if (agentLogLines.isNotEmpty()) " • действий: ${agentLogLines.size}" else ""
                    val doneStatus = if (wasCancelled) {
                        "Генерация остановлена пользователем"
                    } else {
                        "Готово. Токенов: $totalTokens (~${String.format(Locale.US, "%.1f", finalSpeed)} tok/s)$stepsNote$fileNote"
                    }

                    _uiState.update { current ->
                        val msgs = current.messages.map { m ->
                            if (m.id == assistantMsgId) completedMsg else m
                        }
                        current.copy(
                            messages = msgs,
                            chatSessions = refreshedSessions,
                            isGenerating = false,
                            pendingToolApproval = null,
                            activeToolStatus = null,
                            statusMessage = doneStatus,
                            tokensPerSecond = finalSpeed,
                            generationTimeSeconds = totalElapsed
                        )
                    }

                    // Обновляем виджет на рабочем столе (передаем чистый ответ без <think> и <agent_steps>)
                    val widgetAnswer = ThoughtParser.parse(finalText, isStreaming = false).answerText.ifBlank { finalText }
                    if (widgetAnswer.isNotBlank()) {
                        LLMWidgetProvider.updateAllWidgets(
                            getApplication(),
                            prompt,
                            widgetAnswer,
                            "${activeProvider.displayName} • $activeRemoteModel"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка генерации через ${activeProvider.displayName}: ${e.message}")
                    val errText = buildStepsPrefix(agentLogLines) +
                        "⚠️ Ошибка API (${activeProvider.displayName}): ${e.message ?: "Сбой соединения"}"
                    val errorMsg = ChatMessage(
                        id = assistantMsgId,
                        role = MessageRole.ASSISTANT,
                        text = errText,
                        isStreaming = false
                    )
                    dbHelper.saveMessage(currentChatId, errorMsg)
                    _uiState.update { current ->
                        val msgs = current.messages.map { m ->
                            if (m.id == assistantMsgId) errorMsg else m
                        }
                        current.copy(
                            messages = msgs,
                            isGenerating = false,
                            pendingToolApproval = null,
                            activeToolStatus = null,
                            statusMessage = "Ошибка ${activeProvider.displayName}: ${e.message}"
                        )
                    }
                }
                return@launch
            }

            // Ветка 2: Локальная модель GGUF (также поддерживает агентные инструменты!)
            for (step in 1..maxSteps) {
                if (isLocalCancelled) break
                val formattedPrompt = LlamaBridge.nativeFormatChat(
                    rolesList.toTypedArray(),
                    contentsList.toTypedArray()
                )
                val stepBuilder = StringBuilder()
                val stepsPrefix = buildStepsPrefix(agentLogLines)

                val callback = LlamaBridge.TokenCallback { tokenPiece ->
                    totalTokens++
                    stepBuilder.append(tokenPiece)

                    for (marker in ThoughtParser.STOP_MARKERS) {
                        val idx = stepBuilder.indexOf(marker)
                        if (idx != -1) {
                            LlamaBridge.stopGeneration()
                            stepBuilder.setLength(idx)
                            break
                        }
                    }

                    val currentStepClean = ThoughtParser.cleanStopTokens(stepBuilder.toString())
                    val liveDisplay = stepsPrefix + currentStepClean
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                    val speed = if (elapsedSec > 0.05f) totalTokens / elapsedSec else 0.0f

                    _uiState.update { current ->
                        val msgs = current.messages.map { m ->
                            if (m.id == assistantMsgId) {
                                m.copy(text = liveDisplay, tokensPerSec = speed, isStreaming = true)
                            } else {
                                m
                            }
                        }
                        current.copy(
                            messages = msgs,
                            totalTokensGenerated = totalTokens,
                            tokensPerSecond = speed,
                            generationTimeSeconds = elapsedSec
                        )
                    }
                }

                LlamaBridge.nativeGenerate(
                    formattedPrompt,
                    1024,
                    activeTemperature,
                    activeTopP,
                    callback
                )

                val stepRaw = ThoughtParser.cleanStopTokens(stepBuilder.toString())
                finalStepText = stepRaw

                val toolCalls = if (agentEnabled && !isLocalCancelled) {
                    AgentTools.extractToolCalls(stepRaw)
                } else {
                    emptyList()
                }
                if (toolCalls.isEmpty() || step == maxSteps) {
                    break
                }

                val observation = executeAgentToolCalls(
                    calls = toolCalls,
                    confirmMode = activeConfirmMode,
                    agentLogLines = agentLogLines,
                    assistantMsgId = assistantMsgId,
                    isCancelled = { isLocalCancelled }
                )
                if (isLocalCancelled) break

                rolesList.add("assistant")
                contentsList.add(stepRaw)
                rolesList.add("user")
                contentsList.add("$observation\n\nПродолжи задачу или ответь пользователю на русском языке.")
            }

            val totalElapsed = (System.currentTimeMillis() - startTime) / 1000f
            val finalSpeed = if (totalElapsed > 0.05f) totalTokens / totalElapsed else 0.0f
            val cleanFinalBody = AgentTools.stripToolCallBlocks(finalStepText)
            val finalText = buildStepsPrefix(agentLogLines) + cleanFinalBody
            val wasCancelled = isLocalCancelled

            val completedMsg = ChatMessage(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                text = finalText,
                tokensPerSec = finalSpeed,
                isStreaming = false
            )
            dbHelper.saveMessage(currentChatId, completedMsg)
            val refreshedSessions = dbHelper.getAllSessions()

            val fileNote = if (loadedLocalFiles.isNotEmpty()) " (файлы: ${loadedLocalFiles.joinToString()})" else ""
            val stepsNote = if (agentLogLines.isNotEmpty()) " • действий: ${agentLogLines.size}" else ""
            val doneStatus = if (wasCancelled) {
                "Генерация остановлена пользователем"
            } else {
                "Готово. Токенов: $totalTokens (~${String.format(Locale.US, "%.1f", finalSpeed)} tok/s)$stepsNote$fileNote"
            }

            _uiState.update { current ->
                val msgs = current.messages.map { m ->
                    if (m.id == assistantMsgId) completedMsg else m
                }
                current.copy(
                    messages = msgs,
                    chatSessions = refreshedSessions,
                    isGenerating = false,
                    pendingToolApproval = null,
                    activeToolStatus = null,
                    statusMessage = doneStatus,
                    tokensPerSecond = finalSpeed,
                    generationTimeSeconds = totalElapsed
                )
            }
            updateRamUsage()

            val widgetAnswer = ThoughtParser.parse(finalText, isStreaming = false).answerText.ifBlank { finalText }
            if (widgetAnswer.isNotBlank()) {
                LLMWidgetProvider.updateAllWidgets(
                    getApplication(),
                    prompt,
                    widgetAnswer,
                    "Офлайн GGUF • ${_uiState.value.modelPath}"
                )
            }
        }
    }

    private fun buildStepsPrefix(agentLogLines: List<String>): String {
        if (agentLogLines.isEmpty()) return ""
        return "<agent_steps>\n${agentLogLines.joinToString("\n")}\n</agent_steps>\n\n"
    }

    private suspend fun executeAgentToolCalls(
        calls: List<AgentToolCall>,
        confirmMode: AgentConfirmationMode,
        agentLogLines: MutableList<String>,
        assistantMsgId: String,
        isCancelled: () -> Boolean
    ): String {
        val observations = StringBuilder()

        for (call in calls) {
            if (isCancelled()) break

            if (AgentTools.needsConfirmation(call, confirmMode)) {
                val approval = AgentTools.buildApprovalPrompt(call)
                val deferred = CompletableDeferred<Boolean>()
                pendingApprovalDeferred = deferred
                _uiState.update {
                    it.copy(
                        pendingToolApproval = approval,
                        activeToolStatus = "⏳ Ожидание подтверждения: ${approval.title}",
                        statusMessage = "Подтвердите действие агента в чате"
                    )
                }

                val approved = deferred.await()
                pendingApprovalDeferred = null
                _uiState.update { it.copy(pendingToolApproval = null) }

                if (!approved || isCancelled()) {
                    val shortTarget = approval.targetDisplay.lineSequence().firstOrNull()?.take(56) ?: ""
                    agentLogLines.add("⛔ Отклонено: ${approval.title} ($shortTarget)")
                    updateAssistantStepsPreview(assistantMsgId, agentLogLines)
                    observations.append("<tool_result tool=\"${call.tool}\" status=\"denied\">\n")
                    observations.append("[Отклонено пользователем] Пользователь запретил выполнять операцию '${approval.title}' для '$shortTarget'.")
                    observations.append("\n</tool_result>\n")
                    continue
                }
            }

            _uiState.update {
                it.copy(
                    activeToolStatus = "⚙️ Выполняется: ${call.tool}...",
                    statusMessage = "Агент выполняет ${call.tool}..."
                )
            }

            val res = AgentTools.execute(getApplication(), call)
            val icon = if (res.ok) "✅" else "❌"
            agentLogLines.add("$icon ${res.summary}")
            updateAssistantStepsPreview(assistantMsgId, agentLogLines)

            observations.append("<tool_result tool=\"${call.tool}\" status=\"${if (res.ok) "ok" else "error"}\">\n")
            observations.append(res.output)
            observations.append("\n</tool_result>\n")
        }

        _uiState.update { it.copy(activeToolStatus = null) }
        return observations.toString().trim()
    }

    private fun updateAssistantStepsPreview(assistantMsgId: String, agentLogLines: List<String>) {
        val prefix = buildStepsPrefix(agentLogLines)
        _uiState.update { current ->
            val msgs = current.messages.map { m ->
                if (m.id == assistantMsgId) m.copy(text = prefix, isStreaming = true) else m
            }
            current.copy(messages = msgs)
        }
    }

    fun clearChat() {
        if (_uiState.value.isGenerating) {
            stopGeneration()
        }
        val currentId = _uiState.value.currentChatId
        if (currentId.isNotBlank()) {
            dbHelper.deleteSession(currentId)
            dbHelper.createSession(currentId, "Новый чат")
            val refreshed = dbHelper.getAllSessions()
            _uiState.update {
                it.copy(
                    chatSessions = refreshed,
                    messages = emptyList(),
                    statusMessage = if (it.isReadyToChat) "Диалог очищен" else buildIdleStatus(it)
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    statusMessage = if (it.isReadyToChat) "Диалог очищен" else buildIdleStatus(it)
                )
            }
        }
    }

    fun insertNotePrompt(type: String) {
        val query = when (type) {
            "photos" -> "Сколько фото и видео сохранено на моем телефоне?"
            "storage" -> "Сколько свободно памяти на моем телефоне?"
            "battery" -> "Какой текущий уровень заряда аккумулятора и температура?"
            "device" -> "Какие характеристики, модель и параметры у моего телефона?"
            "summary" -> "Сделай краткое структурированное резюме следующего текста:\n\n"
            "tasks" -> "Выдели четкий список задач (Action Items) с чекбоксами [ ] из следующего текста:\n\n"
            "format" -> "Отредактируй и красиво структурируй текст в формате Markdown:\n\n"
            "explain" -> "Объясни простыми словами следующую тему:\n\n"
            else -> ""
        }

        if (type in listOf("photos", "storage", "battery", "device")) {
            if (_uiState.value.inputText.isBlank() && _uiState.value.isReadyToChat && !_uiState.value.isGenerating) {
                sendMessage(query)
            } else {
                _uiState.update { it.copy(inputText = query) }
            }
        } else {
            _uiState.update {
                it.copy(inputText = query + it.inputText)
            }
        }
    }

    fun stopGeneration() {
        if (_uiState.value.providerType == LLMProviderType.LOCAL_GGUF) {
            isLocalCancelled = true
            LlamaBridge.nativeStop()
        } else {
            isRemoteCancelled = true
        }
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        TermuxBridge.cancelAllPending()

        val currentId = _uiState.value.currentChatId
        _uiState.update { current ->
            val msgs = current.messages.map { m ->
                if (m.isStreaming) {
                    val stopped = m.copy(isStreaming = false)
                    if (currentId.isNotBlank() && stopped.text.isNotBlank()) {
                        dbHelper.saveMessage(currentId, stopped)
                    }
                    stopped
                } else {
                    m
                }
            }
            current.copy(
                messages = msgs,
                isGenerating = false,
                pendingToolApproval = null,
                activeToolStatus = null,
                statusMessage = "Генерация остановлена пользователем"
            )
        }
    }

    fun unloadModel() {
        if (_uiState.value.isGenerating && _uiState.value.providerType == LLMProviderType.LOCAL_GGUF) {
            stopGeneration()
        }
        LlamaBridge.isModelLoaded = false
        LlamaBridge.nativeUnload()
        openPfd?.close()
        openPfd = null

        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_AUTO_LOAD_ENABLED, false).apply()

        _uiState.update {
            it.copy(
                isModelLoaded = false,
                isLoadingModel = false,
                modelPath = "",
                statusMessage = if (it.providerType == LLMProviderType.LOCAL_GGUF) {
                    "Модель выгружена из памяти"
                } else {
                    buildIdleStatus(it)
                },
                tokensPerSecond = 0.0f,
                allocatedRamMb = 0
            )
        }
        updateRamUsage()
    }

    private fun updateRamUsage() {
        val rssMb = try {
            File("/proc/self/status").useLines { lines ->
                val line = lines.firstOrNull { it.startsWith("VmRSS:") }
                line?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull()?.div(1024)
            }
        } catch (_: Exception) {
            null
        }

        val fallbackMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        val finalMb = rssMb ?: fallbackMem
        _uiState.update { it.copy(allocatedRamMb = finalMb) }
    }
}
