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
import com.klischa.llmnotes.browser.AgentBrowserEngine
import com.klischa.llmnotes.browser.BrowserSearchEngine
import com.klischa.llmnotes.security.SecureKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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

data class QueuedFollowUp(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val attachedDoc: AttachedDoc? = null,
    val createdAt: Long = System.currentTimeMillis()
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
    // Только замаскированные отпечатки сохранённых ключей (открытые ключи НИКОГДА не хранятся в UiState)
    val savedKeyMasks: Map<LLMProviderType, String> = emptyMap(),
    val customBaseUrl: String = "",
    val openCodeSelectedModel: String = OpenCodeClient.getDefaultModel(LLMProviderType.OPENROUTER),
    val openCodeAvailableModels: List<String> = OpenCodeClient.OPENROUTER_DEFAULT_MODELS,
    val isProviderDialogVisible: Boolean = false,
    val isFetchingModels: Boolean = false,
    // Переключатель режима размышления (Thinking / <think>)
    val thinkingEnabled: Boolean = true,
    // Скиллы (Agent Skills / SKILL.md)
    val skills: List<AgentSkill> = emptyList(),
    val isSkillsDialogVisible: Boolean = false,
    val remoteSkillsCatalog: List<RemoteSkillEntry> = SkillManager.CURATED_REMOTE_SKILLS,
    val isFetchingSkill: Boolean = false,
    val isFetchingSkillCatalog: Boolean = false,
    val skillStatusMessage: String = "",
    // Очередь дополнений во время генерации ответа (не перебивает текущий ответ)
    val queuedFollowUps: List<QueuedFollowUp> = emptyList(),
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
    // Режим агента: PLAN формирует только план и никогда не запускает инструменты; DIRECT выполняет действия.
    val planOnlyMode: Boolean = false,
    // Режим агента (работа с файлами устройства, встроенным браузером и Termux RUN_COMMAND)
    val agentModeEnabled: Boolean = true,
    val agentConfirmationMode: AgentConfirmationMode = AgentConfirmationMode.CONFIRM_DESTRUCTIVE,
    val isAgentDialogVisible: Boolean = false,
    val isBrowserVisible: Boolean = false,
    val browserSearchEngine: BrowserSearchEngine = BrowserSearchEngine.DUCKDUCKGO,
    val browserIncognitoMode: Boolean = false,
    val termuxStatus: TermuxStatus = TermuxStatus(isInstalled = false, hasRunPermission = false),
    val termuxTestOutput: String = "",
    val isTestingTermux: Boolean = false,
    val pendingToolApproval: PendingToolApproval? = null,
    val activeToolStatus: String? = null
) {
    val currentKeyMask: String
        get() = savedKeyMasks[providerType].orEmpty()

    val hasCurrentApiKey: Boolean
        get() = currentKeyMask.isNotEmpty()

    val activeSkillsCount: Int
        get() = skills.count { it.enabled }

    val isReadyToChat: Boolean
        get() = when (providerType) {
            LLMProviderType.LOCAL_GGUF -> isModelLoaded
            LLMProviderType.CUSTOM_OPENAI -> customBaseUrl.isNotBlank()
            else -> hasCurrentApiKey
        }
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

    private val generationEpoch = AtomicLong(0L)
    private var generationJob: Job? = null

    companion object {
        private const val TAG = "LLMViewModel"
        private const val MAX_AGENT_STEPS = 20
        private const val MAX_CONSECUTIVE_RECOVERIES = 2
        private const val PREFS_NAME = "llm_app_prefs"
        private const val PREF_LAST_MODEL_PATH = "last_model_path"
        private const val PREF_LAST_MODEL_URI = "last_model_uri"
        private const val PREF_LAST_MODEL_NAME = "last_model_name"
        private const val PREF_AUTO_LOAD_ENABLED = "auto_load_enabled"
        private const val PREF_PROVIDER_TYPE = "provider_type"
        private const val PREF_DEEPSEEK_MODEL = "deepseek_model"
        private const val PREF_OPENCODE_MODEL_GO = "opencode_model_go"
        private const val PREF_OPENCODE_MODEL_ZEN = "opencode_model_zen"
        private const val PREF_OPENROUTER_MODEL = "openrouter_model"
        private const val PREF_GROQ_MODEL = "groq_model"
        private const val PREF_CUSTOM_BASE_URL = "custom_openai_base_url"
        private const val PREF_THINKING_ENABLED = "thinking_enabled"
        private const val PREF_PLAN_ONLY_MODE = "plan_only_mode"
        private const val PREF_AGENT_MODE_ENABLED = "agent_mode_enabled"
        private const val PREF_AGENT_CONFIRM_MODE = "agent_confirm_mode"
        private const val PREF_BROWSER_SEARCH_ENGINE = "browser_search_engine"
        private const val PREF_BROWSER_INCOGNITO = "browser_incognito"
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
            LLMProviderType.CUSTOM_OPENAI ->
                if (state.customBaseUrl.isNotBlank()) {
                    "Свой API готов (${state.openCodeSelectedModel.ifBlank { "укажите модель" }})"
                } else {
                    "Укажите Base URL для своего OpenAI API"
                }
            else ->
                if (state.hasCurrentApiKey) {
                    "${state.providerType.displayName} готов (${state.openCodeSelectedModel})"
                } else {
                    "Укажите API-ключ для ${state.providerType.displayName}"
                }
        }
    }

    private fun modelPrefKeyFor(provider: LLMProviderType): String {
        return when (provider) {
            LLMProviderType.DEEPSEEK -> PREF_DEEPSEEK_MODEL
            LLMProviderType.OPENROUTER -> PREF_OPENROUTER_MODEL
            LLMProviderType.GROQ -> PREF_GROQ_MODEL
            LLMProviderType.OPENCODE_ZEN -> PREF_OPENCODE_MODEL_ZEN
            LLMProviderType.OPENCODE_GO -> PREF_OPENCODE_MODEL_GO
            LLMProviderType.LOCAL_GGUF -> ""
            else -> "model_${provider.name}"
        }
    }

    private fun getSavedModelForProvider(prefs: android.content.SharedPreferences, provider: LLMProviderType): String {
        if (provider == LLMProviderType.LOCAL_GGUF) return ""
        val key = modelPrefKeyFor(provider)
        val fallback = OpenCodeClient.getDefaultModel(provider)
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun initProviderSettings() {
        val app = getApplication<Application>()
        // Переносим прежние открытые API-ключи в зашифрованный Android Keystore и удаляем plaintext
        SecureKeyStore.migrateLegacyPlaintextKeys(app)
        val keyMasks = SecureKeyStore.getAllKeyMasks(app)

        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val providerStr = prefs.getString(PREF_PROVIDER_TYPE, LLMProviderType.LOCAL_GGUF.name)
        val provider = try {
            LLMProviderType.valueOf(providerStr ?: "")
        } catch (_: Exception) {
            LLMProviderType.LOCAL_GGUF
        }
        val customBaseUrl = prefs.getString(PREF_CUSTOM_BASE_URL, "") ?: ""
        val thinkingEnabled = prefs.getBoolean(PREF_THINKING_ENABLED, true)
        val planOnlyMode = prefs.getBoolean(PREF_PLAN_ONLY_MODE, false)
        val selectedModel = getSavedModelForProvider(prefs, provider)
        val models = OpenCodeClient.getDefaultModels(provider)
        val loadedSkills = SkillManager.loadSkills(app)

        val agentEnabled = prefs.getBoolean(PREF_AGENT_MODE_ENABLED, true)
        val confirmStr = prefs.getString(PREF_AGENT_CONFIRM_MODE, AgentConfirmationMode.CONFIRM_DESTRUCTIVE.name)
        val confirmMode = try {
            AgentConfirmationMode.valueOf(confirmStr ?: "")
        } catch (_: Exception) {
            AgentConfirmationMode.CONFIRM_DESTRUCTIVE
        }
        val savedEngineStr = prefs.getString(PREF_BROWSER_SEARCH_ENGINE, BrowserSearchEngine.DUCKDUCKGO.id)
        val browserEngine = BrowserSearchEngine.fromString(savedEngineStr) ?: BrowserSearchEngine.DUCKDUCKGO
        val browserIncognito = prefs.getBoolean(PREF_BROWSER_INCOGNITO, false)
        AgentBrowserEngine.initSettings(browserEngine, browserIncognito)

        val termuxStatus = TermuxBridge.checkStatus(app)

        _uiState.update { current ->
            val updated = current.copy(
                providerType = provider,
                savedKeyMasks = keyMasks,
                customBaseUrl = customBaseUrl,
                openCodeSelectedModel = selectedModel,
                openCodeAvailableModels = models,
                thinkingEnabled = thinkingEnabled,
                planOnlyMode = planOnlyMode,
                skills = loadedSkills,
                agentModeEnabled = agentEnabled,
                agentConfirmationMode = confirmMode,
                browserSearchEngine = browserEngine,
                browserIncognitoMode = browserIncognito,
                termuxStatus = termuxStatus
            )
            updated.copy(statusMessage = buildIdleStatus(updated))
        }
    }

    // ==================== Встроенный браузер агента ====================

    fun setBrowserVisible(visible: Boolean) {
        _uiState.update { it.copy(isBrowserVisible = visible) }
    }

    fun setBrowserSearchEngine(engine: BrowserSearchEngine) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_BROWSER_SEARCH_ENGINE, engine.id).apply()
        AgentBrowserEngine.setSearchEngine(engine)
        _uiState.update {
            it.copy(
                browserSearchEngine = engine,
                statusMessage = "🌐 Поисковик браузера: ${engine.displayName}"
            )
        }
    }

    fun setBrowserIncognitoMode(enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_BROWSER_INCOGNITO, enabled).apply()
        AgentBrowserEngine.setIncognitoMode(enabled)
        _uiState.update {
            it.copy(
                browserIncognitoMode = enabled,
                statusMessage = if (enabled) "🕵️ Браузер: режим Инкогнито включён" else "🌐 Браузер: сохранение сессий включено"
            )
        }
    }

    fun setBrowserDesktopMode(enabled: Boolean) {
        AgentBrowserEngine.setDesktopMode(enabled)
    }

    fun clearBrowserData() {
        AgentBrowserEngine.clearBrowsingData()
        _uiState.update { it.copy(statusMessage = "🧹 Куки, кэш и история браузера очищены") }
    }

    fun attachBrowserScreenshotToChat() {
        viewModelScope.launch(Dispatchers.IO) {
            val captured = AgentBrowserEngine.captureScreenshotForChat(getApplication())
            if (captured == null) {
                _uiState.update { it.copy(statusMessage = "⚠️ Не удалось снять скриншот страницы") }
                return@launch
            }
            val (b64, w, h) = captured
            val host = AgentBrowserEngine.state.value.shortHost.ifBlank { "browser" }
            val pageUrl = AgentBrowserEngine.state.value.currentUrl
            val doc = AttachedDoc(
                name = "screenshot_$host.jpg",
                sizeBytes = (b64.length * 3L) / 4L,
                content = "Скриншот страницы из встроенного браузера ($pageUrl, ${w}x${h}).",
                path = null,
                mimeType = "image/jpeg",
                category = AttachmentCategory.IMAGE,
                imageBase64 = b64,
                imageWidth = w,
                imageHeight = h,
                summaryBadge = "🌐 ${w}x${h}"
            )
            _uiState.update {
                it.copy(
                    attachedDoc = doc,
                    isBrowserVisible = false,
                    statusMessage = "📸 Скриншот $host (${w}x${h}) прикреплён к сообщению"
                )
            }
        }
    }

    fun attachBrowserTextToChat() {
        viewModelScope.launch(Dispatchers.IO) {
            val extracted = AgentBrowserEngine.extractTextForChat(getApplication())
            if (extracted == null) {
                _uiState.update { it.copy(statusMessage = "⚠️ Нет открытой страницы для извлечения текста") }
                return@launch
            }
            val (title, text) = extracted
            val safeName = title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(32).ifBlank { "page" }
            val doc = AttachedDoc(
                name = "$safeName.txt",
                sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                content = text,
                path = null,
                mimeType = "text/plain",
                category = AttachmentCategory.TEXT,
                summaryBadge = "🌐 Веб-страница"
            )
            _uiState.update {
                it.copy(
                    attachedDoc = doc,
                    isBrowserVisible = false,
                    statusMessage = "📄 Текст страницы «${title.take(32)}» прикреплён к сообщению"
                )
            }
        }
    }

    fun setThinkingEnabled(enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_THINKING_ENABLED, enabled).apply()
        _uiState.update {
            it.copy(
                thinkingEnabled = enabled,
                statusMessage = if (enabled) {
                    "💭 Режим размышления включён"
                } else {
                    "⚡ Режим размышления отключён (быстрые прямые ответы)"
                }
            )
        }
    }

    fun toggleThinkingEnabled() {
        setThinkingEnabled(!_uiState.value.thinkingEnabled)
    }

    fun setPlanOnlyMode(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PLAN_ONLY_MODE, enabled)
            .apply()
        _uiState.update { it.copy(planOnlyMode = enabled) }
    }

    fun togglePlanOnlyMode() {
        setPlanOnlyMode(!_uiState.value.planOnlyMode)
    }

    // ==================== Скиллы (Skills / SKILL.md) ====================

    fun setSkillsDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(isSkillsDialogVisible = visible, skillStatusMessage = "") }
        if (visible && _uiState.value.remoteSkillsCatalog.size <= SkillManager.CURATED_REMOTE_SKILLS.size) {
            refreshOnlineSkillsCatalog()
        }
    }

    fun toggleSkillEnabled(skillId: String, enabled: Boolean) {
        val updated = _uiState.value.skills.map { s ->
            if (s.id == skillId) s.copy(enabled = enabled) else s
        }
        SkillManager.saveSkills(getApplication(), updated)
        val count = updated.count { it.enabled }
        _uiState.update {
            it.copy(
                skills = updated,
                statusMessage = if (count > 0) "🧩 Активных скиллов: $count" else "Скиллы отключены"
            )
        }
    }

    fun deleteSkill(skillId: String) {
        val target = _uiState.value.skills.find { it.id == skillId } ?: return
        if (target.source == "builtin") return
        val updated = _uiState.value.skills.filterNot { it.id == skillId }
        SkillManager.saveSkills(getApplication(), updated)
        _uiState.update {
            it.copy(
                skills = updated,
                skillStatusMessage = "Скилл «${target.name}» удалён"
            )
        }
    }

    fun saveCustomSkill(
        existingId: String?,
        name: String,
        slug: String,
        description: String,
        instructions: String
    ) {
        val cleanName = name.trim()
        val cleanInstr = instructions.trim()
        if (cleanName.isBlank() || cleanInstr.isBlank()) return
        val cleanSlug = SkillManager.toSlug(slug.ifBlank { cleanName })
        val cleanDesc = description.trim().ifBlank { cleanInstr.lineSequence().firstOrNull()?.take(120).orEmpty() }

        val current = _uiState.value.skills
        val updated = if (existingId != null && current.any { it.id == existingId }) {
            current.map { s ->
                if (s.id == existingId) {
                    s.copy(
                        name = cleanName,
                        slug = cleanSlug,
                        description = cleanDesc,
                        instructions = cleanInstr,
                        updatedAt = System.currentTimeMillis()
                    )
                } else s
            }
        } else {
            val created = AgentSkill(
                id = "custom_${UUID.randomUUID().toString().take(8)}",
                name = cleanName,
                slug = cleanSlug,
                description = cleanDesc,
                instructions = cleanInstr,
                source = "custom",
                enabled = true,
                updatedAt = System.currentTimeMillis()
            )
            current + created
        }

        SkillManager.saveSkills(getApplication(), updated)
        _uiState.update {
            it.copy(
                skills = updated,
                skillStatusMessage = "✅ Скилл «$cleanName» сохранён и включён"
            )
        }
    }

    fun installSkillFromUrl(url: String, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_uiState.value.isFetchingSkill) return
        _uiState.update { it.copy(isFetchingSkill = true, skillStatusMessage = "Загрузка SKILL.md...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val installed = SkillManager.fetchSkillFromUrl(url)
                val current = _uiState.value.skills
                // Если скилл с таким же sourceUrl или slug (не встроенный) уже есть — обновляем его
                val existingIdx = current.indexOfFirst {
                    it.source != "builtin" && (
                        (!installed.sourceUrl.isNullOrBlank() && it.sourceUrl == installed.sourceUrl) ||
                            it.slug.equals(installed.slug, ignoreCase = true)
                        )
                }
                val updated = if (existingIdx >= 0) {
                    current.toMutableList().apply {
                        this[existingIdx] = installed.copy(id = current[existingIdx].id)
                    }
                } else {
                    current + installed
                }
                SkillManager.saveSkills(getApplication(), updated)
                val msg = "✅ Скилл «${installed.name}» (/${installed.slug}) установлен и включён"
                _uiState.update {
                    it.copy(
                        skills = updated,
                        isFetchingSkill = false,
                        skillStatusMessage = msg,
                        statusMessage = msg
                    )
                }
                withContext(Dispatchers.Main) { onDone(true, msg) }
            } catch (e: Exception) {
                val err = "⚠️ Ошибка загрузки скилла: ${e.message ?: "проверьте ссылку"}"
                _uiState.update {
                    it.copy(
                        isFetchingSkill = false,
                        skillStatusMessage = err
                    )
                }
                withContext(Dispatchers.Main) { onDone(false, err) }
            }
        }
    }

    fun importSkillFromUri(uri: Uri, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (displayName, _) = queryUriMetadata(getApplication<Application>().contentResolver, uri)
                val imported = SkillManager.importSkillFromUri(getApplication(), uri, displayName)
                val updated = _uiState.value.skills + imported
                SkillManager.saveSkills(getApplication(), updated)
                val msg = "✅ Импортирован скилл «${imported.name}» (/${imported.slug})"
                _uiState.update {
                    it.copy(
                        skills = updated,
                        skillStatusMessage = msg,
                        statusMessage = msg
                    )
                }
                withContext(Dispatchers.Main) { onDone(true, msg) }
            } catch (e: Exception) {
                val err = "⚠️ Не удалось импортировать файл: ${e.message}"
                _uiState.update { it.copy(skillStatusMessage = err) }
                withContext(Dispatchers.Main) { onDone(false, err) }
            }
        }
    }

    fun refreshOnlineSkillsCatalog() {
        if (_uiState.value.isFetchingSkillCatalog) return
        _uiState.update { it.copy(isFetchingSkillCatalog = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = SkillManager.fetchOnlineCatalog()
            _uiState.update {
                it.copy(
                    remoteSkillsCatalog = catalog,
                    isFetchingSkillCatalog = false
                )
            }
        }
    }

    // ==================== Очередь дополнений во время генерации ====================

    fun enqueueFollowUp(userText: String = _uiState.value.inputText) {
        val state = _uiState.value
        val attached = state.attachedDoc
        val cleanText = userText.trim()
        if (cleanText.isEmpty() && attached == null) return

        val finalText = cleanText.ifEmpty {
            when (attached?.category) {
                AttachmentCategory.IMAGE -> "Дополнение: опиши и проанализируй изображение (${attached.name})"
                AttachmentCategory.PDF -> "Дополнение: проанализируй PDF (${attached.name})"
                else -> "Дополнение: проанализируй прикреплённый файл (${attached?.name ?: "файл"})"
            }
        }
        val item = QueuedFollowUp(text = finalText, attachedDoc = attached)
        _uiState.update { current ->
            val newQueue = current.queuedFollowUps + item
            current.copy(
                inputText = "",
                attachedDoc = null,
                queuedFollowUps = newQueue,
                statusMessage = "🕒 Дополнение в очереди (${newQueue.size}) — отправится после текущего ответа"
            )
        }
    }

    fun removeQueuedFollowUp(id: String) {
        _uiState.update { current ->
            val newQueue = current.queuedFollowUps.filterNot { it.id == id }
            current.copy(
                queuedFollowUps = newQueue,
                statusMessage = if (newQueue.isEmpty() && !current.isGenerating) buildIdleStatus(current) else current.statusMessage
            )
        }
    }

    fun editQueuedFollowUp(id: String) {
        val target = _uiState.value.queuedFollowUps.find { it.id == id } ?: return
        _uiState.update { current ->
            val mergedInput = if (current.inputText.isBlank()) target.text else "${current.inputText.trim()}\n${target.text}"
            current.copy(
                inputText = mergedInput,
                attachedDoc = target.attachedDoc ?: current.attachedDoc,
                queuedFollowUps = current.queuedFollowUps.filterNot { it.id == id }
            )
        }
    }

    fun clearQueuedFollowUps() {
        _uiState.update { current ->
            current.copy(
                queuedFollowUps = emptyList(),
                statusMessage = if (!current.isGenerating) buildIdleStatus(current) else current.statusMessage
            )
        }
    }

    fun sendNextQueuedNow() {
        val state = _uiState.value
        if (state.isGenerating || state.queuedFollowUps.isEmpty()) return
        val next = state.queuedFollowUps.first()
        _uiState.update { it.copy(queuedFollowUps = it.queuedFollowUps.drop(1)) }
        sendMessage(
            userText = next.text,
            explicitAttachment = next.attachedDoc,
            useCurrentUiAttachment = false
        )
    }

    private fun dispatchNextQueuedFollowUp(expectedChatId: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val state = _uiState.value
            if (state.isGenerating) return@launch
            if (state.currentChatId != expectedChatId) return@launch
            if (state.queuedFollowUps.isEmpty()) return@launch
            val next = state.queuedFollowUps.first()
            _uiState.update { it.copy(queuedFollowUps = it.queuedFollowUps.drop(1)) }
            sendMessage(
                userText = next.text,
                explicitAttachment = next.attachedDoc,
                useCurrentUiAttachment = false
            )
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

        val selectedModel = getSavedModelForProvider(prefs, type)
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
        } else if (_uiState.value.hasCurrentApiKey || (type == LLMProviderType.CUSTOM_OPENAI && _uiState.value.customBaseUrl.isNotBlank())) {
            fetchRemoteModels()
        }
    }

    /**
     * Шифрует API-ключ в Android Keystore (SecureKeyStore) и сохраняет в UiState ТОЛЬКО маску.
     */
    fun saveProviderApiKey(rawKey: String, provider: LLMProviderType = _uiState.value.providerType) {
        if (provider == LLMProviderType.LOCAL_GGUF) return
        val cleanKey = rawKey.trim()
        val app = getApplication<Application>()
        if (cleanKey.isEmpty()) {
            SecureKeyStore.deleteKey(app, provider)
        } else {
            SecureKeyStore.saveKey(app, provider, cleanKey)
        }
        val mask = SecureKeyStore.getKeyMask(app, provider)
        _uiState.update { current ->
            val updatedMasks = if (mask.isEmpty()) {
                current.savedKeyMasks - provider
            } else {
                current.savedKeyMasks + (provider to mask)
            }
            val updated = current.copy(savedKeyMasks = updatedMasks)
            updated.copy(
                statusMessage = if (mask.isNotEmpty()) {
                    "🔒 API-ключ ${provider.displayName} зашифрован и сохранён"
                } else {
                    buildIdleStatus(updated)
                }
            )
        }
        if (cleanKey.isNotEmpty() && provider == _uiState.value.providerType) {
            fetchRemoteModels()
        }
    }

    fun deleteProviderApiKey(provider: LLMProviderType = _uiState.value.providerType) {
        if (provider == LLMProviderType.LOCAL_GGUF) return
        SecureKeyStore.deleteKey(getApplication(), provider)
        _uiState.update { current ->
            val updated = current.copy(savedKeyMasks = current.savedKeyMasks - provider)
            updated.copy(statusMessage = "API-ключ ${provider.displayName} удалён")
        }
    }

    fun setCustomBaseUrl(url: String) {
        val clean = url.trim()
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_CUSTOM_BASE_URL, clean).apply()
        _uiState.update { current ->
            val updated = current.copy(customBaseUrl = clean)
            updated.copy(statusMessage = buildIdleStatus(updated))
        }
    }

    fun setOpenCodeSelectedModel(model: String) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = modelPrefKeyFor(_uiState.value.providerType)
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
        val state = _uiState.value
        val provider = state.providerType
        if (provider == LLMProviderType.LOCAL_GGUF) return
        if (provider.requiresApiKey && !state.hasCurrentApiKey) return
        if (provider == LLMProviderType.CUSTOM_OPENAI && state.customBaseUrl.isBlank()) return

        val customUrl = state.customBaseUrl
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isFetchingModels = true) }
            val decryptedKey = SecureKeyStore.getKey(getApplication(), provider)
            val models = OpenCodeClient.fetchModels(provider, decryptedKey, customUrl)
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
                queuedFollowUps = emptyList(),
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
                queuedFollowUps = emptyList(),
                statusMessage = if (it.isReadyToChat) "Диалог загружен" else buildIdleStatus(it)
            )
        }
    }

    fun deleteChat(chatId: String) {
        val wasActive = chatId == _uiState.value.currentChatId
        if (_uiState.value.isGenerating && wasActive) {
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
                    messages = msgs,
                    queuedFollowUps = if (wasActive) emptyList() else it.queuedFollowUps
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationEpoch.incrementAndGet()
        generationJob?.cancel()
        generationJob = null
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
                        4096,
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
            4096,
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
     * Если модель уже генерирует ответ — безопасно ставит дополнение в очередь (`enqueueFollowUp`),
     * не перебивая текущее сообщение.
     */
    fun sendMessage(
        userText: String = _uiState.value.inputText,
        explicitAttachment: AttachedDoc? = null,
        useCurrentUiAttachment: Boolean = true
    ) {
        val stateSnapshot = _uiState.value
        if (stateSnapshot.isGenerating) {
            if (useCurrentUiAttachment) {
                enqueueFollowUp(userText)
            }
            return
        }

        val attached = explicitAttachment ?: if (useCurrentUiAttachment) stateSnapshot.attachedDoc else null
        val rawPrompt = userText.trim()
        if (rawPrompt.isEmpty() && attached == null) return

        val prompt = rawPrompt.ifEmpty {
            when (attached?.category) {
                AttachmentCategory.IMAGE -> "Опиши и проанализируй это изображение (${attached.name})"
                AttachmentCategory.PDF -> "Проанализируй прикреплённый PDF-документ (${attached.name})"
                else -> "Проанализируй прикреплённый файл (${attached?.name ?: "документ"})"
            }
        }

        val isLocal = stateSnapshot.providerType == LLMProviderType.LOCAL_GGUF
        if (isLocal && !stateSnapshot.isModelLoaded) return
        if (stateSnapshot.providerType == LLMProviderType.CUSTOM_OPENAI && stateSnapshot.customBaseUrl.isBlank()) {
            _uiState.update {
                it.copy(
                    statusMessage = "Укажите Base URL для своего OpenAI API",
                    isProviderDialogVisible = true
                )
            }
            return
        }
        if (!isLocal && stateSnapshot.providerType.requiresApiKey && !stateSnapshot.hasCurrentApiKey) {
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
        val activeCustomBaseUrl = stateSnapshot.customBaseUrl
        val activeRemoteModel = stateSnapshot.openCodeSelectedModel
        val activeTemperature = stateSnapshot.temperature
        val activeTopP = stateSnapshot.topP
        val baseSysPrompt = stateSnapshot.systemPrompt.trim()
        val agentEnabled = stateSnapshot.agentModeEnabled
        val activePlanOnlyMode = stateSnapshot.planOnlyMode
        val activeConfirmMode = stateSnapshot.agentConfirmationMode
        val activeThinkingEnabled = stateSnapshot.thinkingEnabled
        val activeSkillsSnapshot = stateSnapshot.skills

        _uiState.update {
            it.copy(
                inputText = if (useCurrentUiAttachment) "" else it.inputText,
                attachedDoc = if (useCurrentUiAttachment) null else it.attachedDoc,
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
        val myEpoch = generationEpoch.incrementAndGet()
        fun isActiveEpoch(): Boolean = generationEpoch.get() == myEpoch

        // Вся работа с БД, расшифровкой ключа, файлами, телеметрией, Termux и сетью/инференсом — в фоновом потоке
        generationJob = viewModelScope.launch(Dispatchers.IO) {
            if (!isActiveEpoch()) return@launch

            val activeApiKey = if (!isLocal) SecureKeyStore.getKey(getApplication(), activeProvider) else ""

            val currentChatId = existingChatId.ifBlank {
                val newId = UUID.randomUUID().toString()
                dbHelper.createSession(newId, "Новый чат")
                if (isActiveEpoch()) {
                    _uiState.update { it.copy(currentChatId = newId) }
                }
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
                if (isActiveEpoch()) {
                    _uiState.update { it.copy(chatSessions = updatedSessions) }
                }
            }

            // Собираем историю диалога (последние 10 сообщений; для локального GGUF — 6 для экономии контекста)
            val historyForPrompt = previousMessages.takeLast(if (isLocal) 6 else 10)
            val rolesList = mutableListOf<String>()
            val contentsList = mutableListOf<String>()

            // 1. Системный промпт + активные скиллы + телеметрия устройства + инструкции агента + директива размышлений
            val resolvedSkills = SkillManager.resolveSkillsForPrompt(
                allSkills = activeSkillsSnapshot,
                userPrompt = prompt,
                isLocalCompact = isLocal
            )
            val effectiveUserPromptText = resolvedSkills.cleanedPrompt

            val telemetryContext = if (DeviceTools.isDeviceQuery(effectiveUserPromptText)) {
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
                if (isActiveEpoch()) {
                    _uiState.update { it.copy(termuxStatus = tStatus) }
                }
                AgentTools.buildAgentSystemPrompt(tStatus, stateSnapshot.browserSearchEngine)
            } else {
                ""
            }

            // Use saved discovery results only: don't block every chat turn on network/Termux MCP handshakes.
            val availableMcpTools = if (agentEnabled && !activePlanOnlyMode) {
                McpManager.getCachedTools(getApplication())
            } else emptyList()

            val mcpPrompt = McpManager.promptForTools(availableMcpTools)

            val thinkingDirective = if (!activeThinkingEnabled) {
                "Режим размышления отключён пользователем. Отвечай сразу по существу без промежуточных рассуждений и без блоков <think>. /no_think"
            } else {
                ""
            }

            val effectiveSysPrompt = listOf(
                baseSysPrompt,
                resolvedSkills.systemBlock,
                telemetryContext,
                agentPrompt,
                mcpPrompt,
                if (activePlanOnlyMode) "РЕЖИМ ПЛАНА (READ-ONLY): ничего не изменяй и не выполняй действия. Не вызывай инструменты и не открывай/редактируй файлы или сайты. Только проанализируй задачу и выдай пошаговый план, риски и ожидаемые результаты. Выполнение возможно только после явного переключения пользователя в режим Direct." else "РЕЖИМ DIRECT: пользователь разрешил выполнять действия согласно настройкам агента и запрашивать предусмотренные подтверждения.",
                thinkingDirective
            )
                .filter { it.isNotBlank() }
                .joinToString("\n\n")

            if (effectiveSysPrompt.isNotBlank()) {
                rolesList.add("system")
                contentsList.add(effectiveSysPrompt)
            }

            // 2. История предыдущих реплик (очищаем <think> и <agent_steps*> для экономии контекста и совместимости с DeepSeek R1 / Qwen3)
            for (msg in historyForPrompt) {
                if (msg.role == MessageRole.USER) {
                    rolesList.add("user")
                    contentsList.add(msg.text)
                } else if (msg.role == MessageRole.ASSISTANT && msg.text.isNotBlank()) {
                    val parsed = ThoughtParser.parse(msg.text, isStreaming = false)
                    val cleanText = parsed.answerText.trim()
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
                FileStorageManager.enrichPromptWithLocalFilesAndImages(getApplication(), effectiveUserPromptText)
            currentImageDataUrls.addAll(localImages)
            val finalUserPromptForModel = filePrefix + enrichedPrompt

            rolesList.add("user")
            contentsList.add(finalUserPromptForModel)

            val maxSteps = if (agentEnabled) MAX_AGENT_STEPS else 1
            val agentSteps = mutableListOf<AgentStepLog>()
            val startTime = System.currentTimeMillis()
            var totalTokens = 0
            var finalStepText = ""
            var accumulatedAnswerParts = StringBuilder()

            // Ветка 1: Внешний облачный API (DeepSeek / OpenRouter / Groq / OpenCode ZEN / GO) с многошаговым самовосстанавливающимся агентным циклом
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

                var consecutiveRecoveries = 0
                var lengthContinuationUsed = false

                try {
                    for (step in 1..maxSteps) {
                        if (isRemoteCancelled || !isActiveEpoch()) break
                        val stepBuilder = StringBuilder()
                        val stepsPrefix = buildStepsPrefix(agentSteps)
                        val priorContinuationPrefix = if (accumulatedAnswerParts.isNotEmpty()) {
                            accumulatedAnswerParts.toString() + "\n"
                        } else ""

                        val streamRes = OpenCodeClient.streamChatCompletions(
                            provider = activeProvider,
                            apiKey = activeApiKey,
                            model = activeRemoteModel,
                            messages = apiMessages,
                            conversationId = currentChatId,
                            temperature = activeTemperature,
                            customBaseUrl = activeCustomBaseUrl,
                            thinkingEnabled = activeThinkingEnabled,
                            onToken = { tokenPiece ->
                                if (!isRemoteCancelled && isActiveEpoch()) {
                                    totalTokens++
                                    stepBuilder.append(tokenPiece)
                                    val currentStepClean = ThoughtParser.cleanStopTokens(stepBuilder.toString())
                                    val liveDisplay = stepsPrefix + priorContinuationPrefix + currentStepClean
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
                            },
                            isCancelled = { isRemoteCancelled || !isActiveEpoch() }
                        )

                        if (!isActiveEpoch()) return@launch
                        val stepRaw = ThoughtParser.cleanStopTokens(stepBuilder.toString())
                        finalStepText = if (priorContinuationPrefix.isNotEmpty()) {
                            priorContinuationPrefix + stepRaw
                        } else {
                            stepRaw
                        }

                        val extraction = if (agentEnabled && !activePlanOnlyMode && !isRemoteCancelled) {
                            AgentTools.extractToolCallsDetailed(stepRaw)
                        } else {
                            ToolCallExtraction(emptyList())
                        }
                        val toolCalls = when {
                            extraction.calls.isNotEmpty() -> extraction.calls
                            agentEnabled && !activePlanOnlyMode && !isRemoteCancelled && streamRes.nativeToolCalls.isNotEmpty() -> streamRes.nativeToolCalls
                            else -> emptyList()
                        }

                        if (toolCalls.isNotEmpty()) {
                            consecutiveRecoveries = 0
                            accumulatedAnswerParts = StringBuilder()

                            if (step < maxSteps) {
                                // Выполняем запрошенные агентом инструменты (с подтверждением опасных действий!)
                                val batch = executeAgentToolCalls(
                                    calls = toolCalls,
                                    confirmMode = activeConfirmMode,
                                    agentSteps = agentSteps,
                                    assistantMsgId = assistantMsgId,
                                    isCancelled = { isRemoteCancelled || !isActiveEpoch() }
                                )
                                if (isRemoteCancelled || !isActiveEpoch()) break

                                // ВАЖНО: Передаём в историю ЧИСТЫЙ assistant-ход БЕЗ <think>...</think>,
                                // иначе DeepSeek-R1 / Qwen3 обрывают рассуждение с </think> или </tool_call>!
                                apiMessages.add(
                                    ApiChatMessage(
                                        "assistant",
                                        AgentTools.buildAssistantTurnForHistory(stepRaw, toolCalls)
                                    )
                                )
                                val visionImages = if (activeProvider.supportsVision) batch.imageDataUrls else emptyList()
                                apiMessages.add(
                                    ApiChatMessage(
                                        role = "user",
                                        content = "${batch.observation}\n\nПроанализируй результат и продолжи выполнение задачи (вызови следующий инструмент через <tool_call>, если нужно) или дай итоговый ответ пользователю на русском языке.",
                                        imageDataUrls = visionImages
                                    )
                                )
                                _uiState.update { it.copy(statusMessage = "Агент анализирует результат (шаг ${step + 1})...") }
                                continue
                            } else {
                                // Достигнут лимит шагов — просим модель подвести аккуратный итог вместо резкого обрыва
                                apiMessages.add(
                                    ApiChatMessage(
                                        "assistant",
                                        AgentTools.buildAssistantTurnForHistory(stepRaw, toolCalls)
                                    )
                                )
                                apiMessages.add(
                                    ApiChatMessage(
                                        "user",
                                        "Достигнут лимит шагов агента ($maxSteps). Не вызывай больше инструменты — кратко подведи итог выполненных действий и укажи, что осталось сделать, на русском языке."
                                    )
                                )
                                _uiState.update { it.copy(statusMessage = "Агент формирует итог...") }
                                val summaryBuilder = StringBuilder()
                                val finalStepsPrefix = buildStepsPrefix(agentSteps)
                                OpenCodeClient.streamChatCompletions(
                                    provider = activeProvider,
                                    apiKey = activeApiKey,
                                    model = activeRemoteModel,
                                    messages = apiMessages,
                                    conversationId = currentChatId,
                                    temperature = activeTemperature,
                                    customBaseUrl = activeCustomBaseUrl,
                                    thinkingEnabled = activeThinkingEnabled,
                                    onToken = { piece ->
                                        if (!isRemoteCancelled && isActiveEpoch()) {
                                            totalTokens++
                                            summaryBuilder.append(piece)
                                            val liveDisplay = finalStepsPrefix + ThoughtParser.cleanStopTokens(summaryBuilder.toString())
                                            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                                            val speed = if (elapsedSec > 0.05f) totalTokens / elapsedSec else 0.0f
                                            _uiState.update { current ->
                                                val msgs = current.messages.map { m ->
                                                    if (m.id == assistantMsgId) m.copy(text = liveDisplay, tokensPerSec = speed, isStreaming = true) else m
                                                }
                                                current.copy(messages = msgs, totalTokensGenerated = totalTokens, tokensPerSecond = speed, generationTimeSeconds = elapsedSec)
                                            }
                                        }
                                    },
                                    isCancelled = { isRemoteCancelled || !isActiveEpoch() }
                                )
                                finalStepText = ThoughtParser.cleanStopTokens(summaryBuilder.toString())
                                break
                            }
                        }

                        // Инструменты не извлечены — проверяем, не произошла ли ложная остановка
                        // (битый <tool_call>, висячий </tool_call>, пустой ответ после </think> или обрыв по length)
                        if (agentEnabled && !isRemoteCancelled && step < maxSteps) {
                            val parsedStep = ThoughtParser.parse(stepRaw, isStreaming = false)

                            if (extraction.hadMalformedAttempt && consecutiveRecoveries < MAX_CONSECUTIVE_RECOVERIES) {
                                consecutiveRecoveries++
                                val cleanNote = parsedStep.answerText.take(400).ifBlank { "(некорректный вызов инструмента)" }
                                apiMessages.add(ApiChatMessage("assistant", cleanNote))
                                apiMessages.add(
                                    ApiChatMessage(
                                        "user",
                                        "Ошибка разбора <tool_call>: ${extraction.malformedReason ?: "некорректный JSON"}. " +
                                        "Повтори вызов инструмента в строго валидном JSON внутри <tool_call>{\"tool\": \"имя\", \"args\": {...}}</tool_call> " +
                                        "(экранируй переносы строк как \\n и кавычки как \\\"). Не пиши лишних тегов."
                                    )
                                )
                                _uiState.update { it.copy(statusMessage = "Агент исправляет формат команды (попытка $consecutiveRecoveries)...") }
                                continue
                            }

                            if (parsedStep.answerText.isBlank() && consecutiveRecoveries < MAX_CONSECUTIVE_RECOVERIES) {
                                consecutiveRecoveries++
                                val nudge = if (streamRes.finishReason == "length") {
                                    "Твои рассуждения оборвались по лимиту длины ответа. Сразу вызови нужный инструмент через <tool_call> или кратко ответь пользователю на русском языке."
                                } else {
                                    "Ты завершил рассуждение, но не вызвал инструмент и не написал ответ пользователю. " +
                                    "Если нужно выполнить действие — выведи валидный блок <tool_call>{\"tool\": \"...\", \"args\": {...}}</tool_call>, " +
                                    "иначе дай понятный итоговый ответ пользователю на русском языке."
                                }
                                // Соблюдаем строгое чередование user -> assistant -> user (важно для DeepSeek R1 / Anthropic / Qwen)
                                apiMessages.add(ApiChatMessage("assistant", "(рассуждение завершено)"))
                                apiMessages.add(ApiChatMessage("user", nudge))
                                _uiState.update { it.copy(statusMessage = "Агент продолжает работу после рассуждения...") }
                                continue
                            }

                            if (streamRes.finishReason == "length" && parsedStep.answerText.isNotBlank() && !lengthContinuationUsed) {
                                lengthContinuationUsed = true
                                accumulatedAnswerParts.append(parsedStep.answerText)
                                apiMessages.add(ApiChatMessage("assistant", parsedStep.answerText))
                                apiMessages.add(
                                    ApiChatMessage(
                                        "user",
                                        "Твой ответ оборвался из-за лимита длины. Продолжи строго с того места, где остановился, не повторяя уже написанное."
                                    )
                                )
                                _uiState.update { it.copy(statusMessage = "Агент продолжает обрезанный ответ...") }
                                continue
                            }
                        }

                        break
                    }

                    if (!isActiveEpoch()) return@launch

                    val totalElapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val finalSpeed = if (totalElapsed > 0.05f) totalTokens / totalElapsed else 0.0f
                    val cleanFinalBody = AgentTools.stripToolCallBlocks(finalStepText)
                    val finalText = buildStepsPrefix(agentSteps) + cleanFinalBody
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
                    val stepsNote = if (agentSteps.isNotEmpty()) " • действий: ${agentSteps.size}" else ""
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

                    // Обновляем виджет на рабочем столе (передаем чистый ответ без <think> и <agent_steps_json>)
                    val widgetAnswer = ThoughtParser.parse(finalText, isStreaming = false).answerText.ifBlank { finalText }
                    if (widgetAnswer.isNotBlank()) {
                        LLMWidgetProvider.updateAllWidgets(
                            getApplication(),
                            prompt,
                            widgetAnswer,
                            "${activeProvider.displayName} • $activeRemoteModel"
                        )
                    }

                    // Если во время ответа пользователь поставил дополнение в очередь — отправляем его строго ПОСЛЕ завершения текущего ответа
                    if (!wasCancelled && isActiveEpoch()) {
                        dispatchNextQueuedFollowUp(currentChatId)
                    }
                } catch (e: Exception) {
                    if (!isActiveEpoch()) return@launch
                    Log.e(TAG, "Ошибка генерации через ${activeProvider.displayName}: ${e.message}")
                    val errText = buildStepsPrefix(agentSteps) +
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

            // Ветка 2: Локальная модель GGUF (также с самовосстановлением агентного цикла!)
            var localConsecutiveRecoveries = 0
            for (step in 1..maxSteps) {
                if (isLocalCancelled || !isActiveEpoch()) break
                val formattedPrompt = LlamaBridge.nativeFormatChat(
                    rolesList.toTypedArray(),
                    contentsList.toTypedArray()
                )
                val stepBuilder = StringBuilder()
                val stepsPrefix = buildStepsPrefix(agentSteps)

                val callback = LlamaBridge.TokenCallback { tokenPiece ->
                    if (!isLocalCancelled && isActiveEpoch()) {
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
                }

                LlamaBridge.nativeGenerate(
                    formattedPrompt,
                    2048,
                    activeTemperature,
                    activeTopP,
                    callback
                )

                if (!isActiveEpoch()) return@launch
                val stepRaw = ThoughtParser.cleanStopTokens(stepBuilder.toString())
                finalStepText = stepRaw

                val extraction = if (agentEnabled && !activePlanOnlyMode && !isLocalCancelled) {
                    AgentTools.extractToolCallsDetailed(stepRaw)
                } else {
                    ToolCallExtraction(emptyList())
                }
                val toolCalls = extraction.calls

                if (toolCalls.isNotEmpty()) {
                    localConsecutiveRecoveries = 0
                    if (step == maxSteps) break

                    val batch = executeAgentToolCalls(
                        calls = toolCalls,
                        confirmMode = activeConfirmMode,
                        agentSteps = agentSteps,
                        assistantMsgId = assistantMsgId,
                        isCancelled = { isLocalCancelled || !isActiveEpoch() }
                    )
                    if (isLocalCancelled || !isActiveEpoch()) break

                    rolesList.add("assistant")
                    contentsList.add(AgentTools.buildAssistantTurnForHistory(stepRaw, toolCalls))
                    rolesList.add("user")
                    contentsList.add("${batch.observation.take(4000)}\n\nПродолжи задачу или ответь пользователю на русском языке.")
                    continue
                }

                if (agentEnabled && !isLocalCancelled && step < maxSteps) {
                    val parsedStep = ThoughtParser.parse(stepRaw, isStreaming = false)
                    if (extraction.hadMalformedAttempt && localConsecutiveRecoveries < MAX_CONSECUTIVE_RECOVERIES) {
                        localConsecutiveRecoveries++
                        rolesList.add("assistant")
                        contentsList.add(parsedStep.answerText.take(200).ifBlank { "(некорректный вызов инструмента)" })
                        rolesList.add("user")
                        contentsList.add(
                            "Ошибка формата <tool_call> (${extraction.malformedReason ?: "некорректный JSON"}). " +
                            "Повтори вызов инструмента в валидном JSON: <tool_call>{\"tool\": \"...\", \"args\": {...}}</tool_call>."
                        )
                        _uiState.update { it.copy(statusMessage = "Агент исправляет формат команды...") }
                        continue
                    }
                    if (parsedStep.answerText.isBlank() && localConsecutiveRecoveries < MAX_CONSECUTIVE_RECOVERIES) {
                        localConsecutiveRecoveries++
                        rolesList.add("assistant")
                        contentsList.add("(рассуждение завершено)")
                        rolesList.add("user")
                        contentsList.add("Дай итоговый ответ пользователю на русском языке или вызови инструмент через <tool_call>.")
                        _uiState.update { it.copy(statusMessage = "Агент продолжает после рассуждения...") }
                        continue
                    }
                }

                break
            }

            if (!isActiveEpoch()) return@launch

            val totalElapsed = (System.currentTimeMillis() - startTime) / 1000f
            val finalSpeed = if (totalElapsed > 0.05f) totalTokens / totalElapsed else 0.0f
            val cleanFinalBody = AgentTools.stripToolCallBlocks(finalStepText)
            val finalText = buildStepsPrefix(agentSteps) + cleanFinalBody
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
            val stepsNote = if (agentSteps.isNotEmpty()) " • действий: ${agentSteps.size}" else ""
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

            if (!wasCancelled && isActiveEpoch()) {
                dispatchNextQueuedFollowUp(currentChatId)
            }
        }
    }

    private fun buildStepsPrefix(agentSteps: List<AgentStepLog>): String {
        return ThoughtParser.encodeAgentSteps(agentSteps)
    }

    private data class ToolBatchResult(
        val observation: String,
        val imageDataUrls: List<String> = emptyList()
    )

    private suspend fun executeAgentToolCalls(
        calls: List<AgentToolCall>,
        confirmMode: AgentConfirmationMode,
        agentSteps: MutableList<AgentStepLog>,
        assistantMsgId: String,
        isCancelled: () -> Boolean
    ): ToolBatchResult {
        val observations = StringBuilder()
        val capturedImages = mutableListOf<String>()

        for (call in calls) {
            if (isCancelled()) break

            val argsDisplay = AgentTools.formatArgsForDisplay(call).take(2000)

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
                    agentSteps.add(
                        AgentStepLog(
                            index = agentSteps.size + 1,
                            tool = call.tool,
                            status = "denied",
                            summary = "Отклонено: ${approval.title} ($shortTarget)",
                            argsPreview = argsDisplay,
                            output = "Операция отклонена пользователем.",
                            durationMs = 0L
                        )
                    )
                    updateAssistantStepsPreview(assistantMsgId, agentSteps)
                    observations.append("<tool_result tool=\"${call.tool}\" status=\"denied\">\n")
                    observations.append("[Отклонено пользователем] Пользователь запретил выполнять операцию '${approval.title}' для '$shortTarget'.")
                    observations.append("\n</tool_result>\n")
                    continue
                }
            }

            val isBrowserTool = call.tool.startsWith("browser_")
            _uiState.update {
                it.copy(
                    activeToolStatus = if (isBrowserTool) "🌐 Браузер: ${call.tool}..." else "⚙️ Выполняется: ${call.tool}...",
                    statusMessage = if (isBrowserTool) "Агент работает в браузере (${call.tool})..." else "Агент выполняет ${call.tool}..."
                )
            }

            val t0 = System.currentTimeMillis()
            val res = AgentTools.execute(getApplication(), call)
            val elapsedMs = System.currentTimeMillis() - t0

            if (!res.imageDataUrl.isNullOrBlank()) {
                capturedImages.add(res.imageDataUrl)
            }

            agentSteps.add(
                AgentStepLog(
                    index = agentSteps.size + 1,
                    tool = call.tool,
                    status = if (res.ok) "ok" else "error",
                    summary = res.summary,
                    argsPreview = argsDisplay,
                    output = res.output.take(4000),
                    durationMs = elapsedMs
                )
            )
            updateAssistantStepsPreview(assistantMsgId, agentSteps)

            observations.append("<tool_result tool=\"${call.tool}\" status=\"${if (res.ok) "ok" else "error"}\">\n")
            observations.append(res.output)
            observations.append("\n</tool_result>\n")
        }

        _uiState.update { it.copy(activeToolStatus = null) }
        return ToolBatchResult(observations.toString().trim(), capturedImages)
    }

    private fun updateAssistantStepsPreview(assistantMsgId: String, agentSteps: List<AgentStepLog>) {
        val prefix = buildStepsPrefix(agentSteps)
        _uiState.update { current ->
            val msgs = current.messages.map { m ->
                if (m.id == assistantMsgId) m.copy(text = prefix, isStreaming = true) else m
            }
            current.copy(messages = msgs)
        }
    }

    /**
     * Отменяет ранее отправленное сообщение пользователя и откатывает историю чата
     * (как в UI, так и в SQLite) к моменту строго ДО этого сообщения.
     */
    fun rollbackToMessage(messageId: String, restoreToInput: Boolean = true) {
        val state = _uiState.value
        val targetIndex = state.messages.indexOfFirst { it.id == messageId && it.role == MessageRole.USER }
        if (targetIndex == -1) return

        // Мгновенно прерываем любую текущую генерацию, ожидание подтверждения и фоновые команды Termux
        generationEpoch.incrementAndGet()
        generationJob?.cancel()
        generationJob = null
        isRemoteCancelled = true
        isLocalCancelled = true
        LlamaBridge.nativeStop()
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        TermuxBridge.cancelAllPending()

        val targetMsg = state.messages[targetIndex]
        val removedCount = state.messages.size - targetIndex
        val remaining = state.messages.subList(0, targetIndex)
        val chatId = state.currentChatId
        val restoredPrompt = if (restoreToInput) extractCleanUserPrompt(targetMsg.text) else state.inputText

        if (chatId.isNotBlank()) {
            dbHelper.replaceChatMessages(chatId, remaining)
            if (remaining.isEmpty()) {
                dbHelper.updateSessionTitle(chatId, "Новый чат")
            }
        }
        val refreshedSessions = dbHelper.getAllSessions()

        _uiState.update { current ->
            current.copy(
                messages = remaining,
                chatSessions = refreshedSessions,
                inputText = restoredPrompt,
                queuedFollowUps = emptyList(),
                isGenerating = false,
                pendingToolApproval = null,
                activeToolStatus = null,
                statusMessage = "↩️ Чат откачен (удалено сообщений: $removedCount)"
            )
        }
    }

    private fun extractCleanUserPrompt(displayText: String): String {
        val lines = displayText.lines()
        if (lines.size >= 2) {
            val firstLine = lines.first()
            val startsWithAttachmentEmoji = AttachmentCategory.values().any { firstLine.startsWith("${it.emoji} ") }
            if (startsWithAttachmentEmoji) {
                return lines.drop(1).joinToString("\n").trim()
            }
        }
        return displayText.trim()
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
                    queuedFollowUps = emptyList(),
                    statusMessage = if (it.isReadyToChat) "Диалог очищен" else buildIdleStatus(it)
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    queuedFollowUps = emptyList(),
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
        generationEpoch.incrementAndGet()
        generationJob?.cancel()
        generationJob = null
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
