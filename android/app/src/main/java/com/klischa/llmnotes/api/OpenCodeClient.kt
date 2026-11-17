package com.klischa.llmnotes.api

import android.util.Log
import com.klischa.llmnotes.AgentToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Каталог поддерживаемых провайдеров LLM (офлайн GGUF, бесплатные облачные тарифы,
 * ведущие коммерческие API и произвольный OpenAI-совместимый сервер).
 *
 * Имена прежних значений enum (LOCAL_GGUF, DEEPSEEK, OPENROUTER, GROQ, OPENCODE_ZEN, OPENCODE_GO)
 * сохранены без изменений для 100% обратной совместимости настроек пользователей.
 */
enum class LLMProviderType(
    val displayName: String,
    val chipLabel: String,
    val baseUrl: String,
    val apiKeyUrl: String,
    val apiKeyUrlLabel: String,
    val hasFreeTier: Boolean,
    val keyPlaceholder: String,
    val description: String,
    val tip: String,
    val supportsVision: Boolean = true,
    val requiresApiKey: Boolean = true,
    val isAnthropicNative: Boolean = false
) {
    LOCAL_GGUF(
        displayName = "Офлайн GGUF",
        chipLabel = "📱 Офлайн",
        baseUrl = "",
        apiKeyUrl = "",
        apiKeyUrlLabel = "",
        hasFreeTier = true,
        keyPlaceholder = "",
        description = "Локальный автономный запуск GGUF-моделей на процессоре смартфона через llama.cpp — 100% бесплатно и без интернета.",
        tip = "Скачивайте GGUF-модели кнопкой ⬇️ в верхней панели.",
        supportsVision = false,
        requiresApiKey = false
    ),
    GEMINI(
        displayName = "Google Gemini (Free)",
        chipLabel = "💎 Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        apiKeyUrl = "https://aistudio.google.com/apikey",
        apiKeyUrlLabel = "aistudio.google.com/apikey",
        hasFreeTier = true,
        keyPlaceholder = "AIza...",
        description = "💎 Google AI Studio: щедрый бесплатный тариф на Gemini 2.5 Flash / 2.5 Pro / 2.0 Flash с поддержкой изображений и длинного контекста.",
        tip = "💡 Бесплатный API-ключ создаётся за 10 секунд в Google AI Studio без привязки банковской карты.",
        supportsVision = true
    ),
    OPENROUTER(
        displayName = "OpenRouter (Free)",
        chipLabel = "🌐 OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKeyUrl = "https://openrouter.ai/keys",
        apiKeyUrlLabel = "openrouter.ai/keys",
        hasFreeTier = true,
        keyPlaceholder = "sk-or-v1-...",
        description = "🌐 OpenRouter: единый шлюз к сотням моделей, включая десятки бесплатных с суффиксом :free (DeepSeek R1, Llama 3.3, Qwen, Gemini, Mistral).",
        tip = "💡 Модели с пометкой Free (:free) полностью бесплатны на OpenRouter без пополнения баланса.",
        supportsVision = true
    ),
    GROQ(
        displayName = "Groq Cloud (Free)",
        chipLabel = "⚡ Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        apiKeyUrl = "https://console.groq.com/keys",
        apiKeyUrlLabel = "console.groq.com/keys",
        hasFreeTier = true,
        keyPlaceholder = "gsk_...",
        description = "⚡ Groq Cloud: сверхбыстрый бесплатный инференс (200–500 tok/s) на Llama 3.3 70B, Llama 4 Scout (Vision), Qwen3 и DeepSeek R1 Distill.",
        tip = "💡 Бесплатный тариф даёт высокие суточные лимиты и мгновенный отклик для агентных задач.",
        supportsVision = true
    ),
    CEREBRAS(
        displayName = "Cerebras (Free)",
        chipLabel = "🧠 Cerebras",
        baseUrl = "https://api.cerebras.ai/v1",
        apiKeyUrl = "https://cloud.cerebras.ai/",
        apiKeyUrlLabel = "cloud.cerebras.ai",
        hasFreeTier = true,
        keyPlaceholder = "csk-...",
        description = "🧠 Cerebras Inference: бесплатный тариф (~1M токенов/день) с рекордной скоростью генерации на Llama 3.3 70B и Qwen3 32B.",
        tip = "💡 Отличный выбор для быстрого многошагового агента и написания кода.",
        supportsVision = false
    ),
    SAMBANOVA(
        displayName = "SambaNova (Free)",
        chipLabel = "🚀 SambaNova",
        baseUrl = "https://api.sambanova.ai/v1",
        apiKeyUrl = "https://cloud.sambanova.ai/apis",
        apiKeyUrlLabel = "cloud.sambanova.ai/apis",
        hasFreeTier = true,
        keyPlaceholder = "...",
        description = "🚀 SambaNova Cloud: быстрый бесплатный доступ к DeepSeek-R1, DeepSeek-V3, Llama 3.3 70B и Qwen2.5-Coder 32B.",
        tip = "💡 Бесплатный API-ключ доступен сразу после регистрации на cloud.sambanova.ai.",
        supportsVision = true
    ),
    GITHUB_MODELS(
        displayName = "GitHub Models (Free)",
        chipLabel = "🐙 GitHub",
        baseUrl = "https://models.inference.ai.azure.com",
        apiKeyUrl = "https://github.com/settings/tokens",
        apiKeyUrlLabel = "github.com/settings/tokens",
        hasFreeTier = true,
        keyPlaceholder = "github_pat_... / ghp_...",
        description = "🐙 GitHub Models: бесплатный доступ по вашему GitHub Token к GPT-4o, GPT-4o-mini, DeepSeek-R1, Llama 3.3 70B и Phi-4.",
        tip = "💡 Создайте Fine-grained PAT с правом models:read (или классический токен) на github.com/settings/tokens.",
        supportsVision = true
    ),
    HUGGINGFACE(
        displayName = "HuggingFace (Free)",
        chipLabel = "🤗 HuggingFace",
        baseUrl = "https://router.huggingface.co/v1",
        apiKeyUrl = "https://huggingface.co/settings/tokens",
        apiKeyUrlLabel = "huggingface.co/settings/tokens",
        hasFreeTier = true,
        keyPlaceholder = "hf_...",
        description = "🤗 HuggingFace Inference Router: бесплатные ежемесячные кредиты на открытые модели Qwen, DeepSeek-R1, Llama 3.3 и Mistral.",
        tip = "💡 Создайте токен с правом «Make calls to Inference Providers» на huggingface.co/settings/tokens.",
        supportsVision = true
    ),
    MISTRAL(
        displayName = "Mistral AI (Free)",
        chipLabel = "🌪️ Mistral",
        baseUrl = "https://api.mistral.ai/v1",
        apiKeyUrl = "https://console.mistral.ai/api-keys",
        apiKeyUrlLabel = "console.mistral.ai/api-keys",
        hasFreeTier = true,
        keyPlaceholder = "...",
        description = "🌪️ Mistral AI (La Plateforme): бесплатный тариф Experiment на Mistral Small, Codestral (код), Pixtral (Vision) и Mistral Large.",
        tip = "💡 Включите бесплатный план Experiment в консоли Mistral для работы без оплаты.",
        supportsVision = true
    ),
    NVIDIA_NIM(
        displayName = "NVIDIA NIM (Free)",
        chipLabel = "🟢 NVIDIA",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        apiKeyUrl = "https://build.nvidia.com/explore/discover",
        apiKeyUrlLabel = "build.nvidia.com",
        hasFreeTier = true,
        keyPlaceholder = "nvapi-...",
        description = "🟢 NVIDIA Build / NIM: бесплатные API-кредиты на DeepSeek-R1, Qwen 2.5 Coder, Llama 3.3 70B и Nemotron.",
        tip = "💡 Откройте любую модель на build.nvidia.com и нажмите «Get API Key» — один ключ nvapi-... подходит ко всем моделям.",
        supportsVision = true
    ),
    COHERE(
        displayName = "Cohere (Free Trial)",
        chipLabel = "🤝 Cohere",
        baseUrl = "https://api.cohere.ai/compatibility/v1",
        apiKeyUrl = "https://dashboard.cohere.com/api-keys",
        apiKeyUrlLabel = "dashboard.cohere.com/api-keys",
        hasFreeTier = true,
        keyPlaceholder = "...",
        description = "🤝 Cohere: бесплатный Trial API-ключ для моделей семейства Command A / Command R+ с отличной поддержкой русского языка и анализа текстов.",
        tip = "💡 Trial-ключ выдаётся бесплатно сразу после регистрации (до 1000 запросов в месяц).",
        supportsVision = false
    ),
    DEEPSEEK(
        displayName = "DeepSeek API",
        chipLabel = "🐳 DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKeyUrl = "https://platform.deepseek.com/api_keys",
        apiKeyUrlLabel = "platform.deepseek.com/api_keys",
        hasFreeTier = false,
        keyPlaceholder = "sk-...",
        description = "🐳 DeepSeek API: официальный сверхдешёвый API — deepseek-chat (DeepSeek V3) и deepseek-reasoner (DeepSeek R1 с рассуждениями).",
        tip = "💡 deepseek-chat — быстрая V3; deepseek-reasoner — R1 с пошаговыми рассуждениями 💭.",
        supportsVision = false
    ),
    OPENAI(
        displayName = "OpenAI",
        chipLabel = "🤖 OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKeyUrl = "https://platform.openai.com/api-keys",
        apiKeyUrlLabel = "platform.openai.com/api-keys",
        hasFreeTier = false,
        keyPlaceholder = "sk-proj-...",
        description = "🤖 OpenAI API: официальный доступ к GPT-4.1, GPT-4.1-mini, GPT-4o, GPT-4o-mini и reasoning-моделям o3-mini / o4-mini.",
        tip = "💡 Поддерживает анализ изображений (Vision) и быстрый стриминг.",
        supportsVision = true
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        chipLabel = "🧬 Claude",
        baseUrl = "https://api.anthropic.com/v1",
        apiKeyUrl = "https://console.anthropic.com/settings/keys",
        apiKeyUrlLabel = "console.anthropic.com/settings/keys",
        hasFreeTier = false,
        keyPlaceholder = "sk-ant-...",
        description = "🧬 Anthropic Claude (нативный Messages API): Claude Sonnet 4, Claude 3.7 Sonnet (Extended Thinking), Claude 3.5 Haiku + Vision.",
        tip = "💡 Переключатель размышлений 💭 управляет режимом Extended Thinking для Claude 3.7 / 4.",
        supportsVision = true,
        isAnthropicNative = true
    ),
    XAI(
        displayName = "xAI Grok",
        chipLabel = "✖️ xAI Grok",
        baseUrl = "https://api.x.ai/v1",
        apiKeyUrl = "https://console.x.ai/",
        apiKeyUrlLabel = "console.x.ai",
        hasFreeTier = false,
        keyPlaceholder = "xai-...",
        description = "✖️ xAI API: доступ к Grok 3, Grok 3 Mini (с рассуждениями) и Grok 2 Vision.",
        tip = "💡 Получите API-ключ в xAI Console на console.x.ai.",
        supportsVision = true
    ),
    OPENCODE_ZEN(
        displayName = "OpenCode ZEN",
        chipLabel = "🧘 Zen",
        baseUrl = "https://opencode.ai/zen/v1",
        apiKeyUrl = "https://opencode.ai/auth",
        apiKeyUrlLabel = "opencode.ai/auth",
        hasFreeTier = false,
        keyPlaceholder = "sk-...",
        description = "🧘 OpenCode ZEN: доступ к моделям Qwen 3.6 Plus, Claude 3.7 Sonnet, GPT-4o, DeepSeek R1 по ключу OpenCode.",
        tip = "💡 Для платного аккаунта Zen рекомендуются: qwen3.6-plus, qwen3.8-flash, claude-3-7-sonnet, gpt-4o.",
        supportsVision = true
    ),
    OPENCODE_GO(
        displayName = "OpenCode GO",
        chipLabel = "🚀 Go",
        baseUrl = "https://opencode.ai/zen/go/v1",
        apiKeyUrl = "https://opencode.ai/auth",
        apiKeyUrlLabel = "opencode.ai/auth",
        hasFreeTier = false,
        keyPlaceholder = "sk-...",
        description = "🚀 OpenCode GO: открытые и кодинг-модели по подписке GO (DeepSeek V4 Pro, Kimi K2.6, Qwen 3.6).",
        tip = "💡 Модели доступны при наличии активной подписки OpenCode GO.",
        supportsVision = true
    ),
    CUSTOM_OPENAI(
        displayName = "Свой OpenAI API",
        chipLabel = "⚙️ Свой URL",
        baseUrl = "",
        apiKeyUrl = "https://openrouter.ai/keys",
        apiKeyUrlLabel = "Любой OpenAI-совместимый URL",
        hasFreeTier = true,
        keyPlaceholder = "API-ключ (опционально для Ollama/LM Studio)",
        description = "⚙️ Произвольный OpenAI-совместимый сервер: Together AI, Fireworks, SiliconFlow, Chutes, локальный Ollama / LM Studio в Wi-Fi сети или собственный прокси.",
        tip = "💡 Укажите Base URL до /v1 (например http://192.168.1.10:11434/v1 для Ollama или https://api.together.xyz/v1).",
        supportsVision = true,
        requiresApiKey = false
    )
}

data class ApiChatMessage(
    val role: String,
    val content: String,
    val imageDataUrls: List<String> = emptyList()
)

data class StreamCompletionResult(
    val finishReason: String? = null,
    val nativeToolCalls: List<AgentToolCall> = emptyList(),
    val receivedContentChars: Int = 0,
    val receivedReasoningChars: Int = 0
)

/**
 * Универсальный клиент взаимодействия с облачными и локальными HTTP/HTTPS LLM API
 * (OpenAI-совместимые шлюзы + нативный Anthropic Messages API).
 */
object OpenCodeClient {
    private const val TAG = "OpenCodeClient"
    private const val APP_USER_AGENT = "LLM-Android/1.4.1"
    const val OPENCODE_USER_AGENT = "opencode/1.18.31 ai-sdk/provider-utils/4.0.40 runtime/bun/1.3.14"

    init {
        try {
            System.setProperty("http.agent", OPENCODE_USER_AGENT)
        } catch (_: Exception) {}
    }

    private const val BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val secureRandom = java.security.SecureRandom()
    private var lastTimestamp = 0L
    private var counter = 0L

    @Synchronized
    fun generateOpenCodeId(prefix: String, desc: Boolean): String {
        val now = System.currentTimeMillis()
        if (now != lastTimestamp) {
            counter = 1L
            lastTimestamp = now
        } else {
            counter++
        }
        var v = now * 4096L + counter
        if (desc) {
            v = v.inv()
        }
        val hexBuilder = StringBuilder(12)
        for (i in 0 until 6) {
            val shift = 40 - 8 * i
            val byteVal = ((v ushr shift) and 0xFFL).toInt()
            hexBuilder.append(String.format(Locale.US, "%02x", byteVal))
        }
        val rndBuilder = StringBuilder(14)
        for (i in 0 until 14) {
            val idx = secureRandom.nextInt(BASE62_CHARS.length)
            rndBuilder.append(BASE62_CHARS[idx])
        }
        return prefix + hexBuilder.toString() + rndBuilder.toString()
    }

    fun generateSessionId(): String = generateOpenCodeId("ses_", desc = true)
    fun generateRequestId(): String = generateOpenCodeId("msg_", desc = false)

    val GEMINI_DEFAULT_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite"
    )

    val OPENROUTER_DEFAULT_MODELS = listOf(
        "deepseek/deepseek-r1:free",
        "deepseek/deepseek-chat-v3-0324:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "qwen/qwen3-235b-a22b:free",
        "qwen/qwen-2.5-coder-32b-instruct:free",
        "google/gemini-2.0-flash-exp:free",
        "mistralai/mistral-small-3.1-24b-instruct:free",
        "meta-llama/llama-3.1-8b-instruct:free"
    )

    val GROQ_DEFAULT_MODELS = listOf(
        "llama-3.3-70b-versatile",
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "qwen/qwen3-32b",
        "deepseek-r1-distill-llama-70b",
        "llama-3.1-8b-instant",
        "gemma2-9b-it"
    )

    val CEREBRAS_DEFAULT_MODELS = listOf(
        "llama-3.3-70b",
        "qwen-3-32b",
        "llama3.1-8b"
    )

    val SAMBANOVA_DEFAULT_MODELS = listOf(
        "DeepSeek-V3-0324",
        "DeepSeek-R1",
        "Meta-Llama-3.3-70B-Instruct",
        "Qwen2.5-Coder-32B-Instruct",
        "Llama-3.2-11B-Vision-Instruct"
    )

    val GITHUB_DEFAULT_MODELS = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "DeepSeek-R1",
        "Meta-Llama-3.3-70B-Instruct",
        "Mistral-Large-2411",
        "Phi-4"
    )

    val HUGGINGFACE_DEFAULT_MODELS = listOf(
        "Qwen/Qwen2.5-72B-Instruct",
        "deepseek-ai/DeepSeek-R1",
        "meta-llama/Llama-3.3-70B-Instruct",
        "Qwen/Qwen2.5-Coder-32B-Instruct",
        "mistralai/Mistral-Small-24B-Instruct-2501"
    )

    val MISTRAL_DEFAULT_MODELS = listOf(
        "mistral-small-latest",
        "codestral-latest",
        "mistral-large-latest",
        "pixtral-12b-2409",
        "open-mistral-nemo"
    )

    val NVIDIA_DEFAULT_MODELS = listOf(
        "deepseek-ai/deepseek-r1",
        "meta/llama-3.3-70b-instruct",
        "qwen/qwen2.5-coder-32b-instruct",
        "nvidia/llama-3.1-nemotron-70b-instruct",
        "mistralai/mistral-large-2-instruct"
    )

    val COHERE_DEFAULT_MODELS = listOf(
        "command-a-03-2025",
        "command-r-plus-08-2024",
        "command-r-08-2024",
        "command-r7b-12-2024"
    )

    val DEEPSEEK_DEFAULT_MODELS = listOf(
        "deepseek-chat",
        "deepseek-reasoner"
    )

    val OPENAI_DEFAULT_MODELS = listOf(
        "gpt-4.1-mini",
        "gpt-4.1",
        "gpt-4o-mini",
        "gpt-4o",
        "o4-mini",
        "o3-mini"
    )

    val ANTHROPIC_DEFAULT_MODELS = listOf(
        "claude-sonnet-4-20250514",
        "claude-3-7-sonnet-20250219",
        "claude-3-5-haiku-20241022",
        "claude-3-5-sonnet-20241022"
    )

    val XAI_DEFAULT_MODELS = listOf(
        "grok-3-mini",
        "grok-3",
        "grok-2-vision-1212"
    )

    val GO_DEFAULT_MODELS = listOf(
        "deepseek-v4-pro",
        "kimi-k2.6",
        "qwen3.6-plus",
        "glm-5.1",
        "minimax-m3",
        "gpt-5.6-luna",
        "grok-4.6",
        "qwen2.5-coder-32b",
        "deepseek-v3"
    )

    val ZEN_DEFAULT_MODELS = listOf(
        "qwen3.6-plus",
        "qwen3.8-flash",
        "qwen3.8-max",
        "claude-3-7-sonnet",
        "claude-3-5-sonnet",
        "claude-haiku-4-5",
        "claude-fable-5-1",
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-5.6-luna",
        "deepseek-r1",
        "deepseek-v3",
        "minimax-m3",
        "glm-5.1",
        "big-pickle",
        "space-bunny-free",
        "nemotron-3.5-lightning-free"
    )

    val CUSTOM_DEFAULT_MODELS = listOf(
        "llama3.2",
        "qwen2.5-coder",
        "deepseek-r1"
    )

    fun isFreeModel(provider: LLMProviderType, model: String): Boolean {
        return when (provider) {
            LLMProviderType.OPENROUTER -> model.contains(":free")
            LLMProviderType.OPENCODE_ZEN -> model == "big-pickle" || model.endsWith("-free")
            LLMProviderType.GEMINI,
            LLMProviderType.GROQ,
            LLMProviderType.CEREBRAS,
            LLMProviderType.SAMBANOVA,
            LLMProviderType.GITHUB_MODELS,
            LLMProviderType.HUGGINGFACE,
            LLMProviderType.MISTRAL,
            LLMProviderType.NVIDIA_NIM,
            LLMProviderType.COHERE -> true
            else -> false
        }
    }

    fun getDefaultModels(provider: LLMProviderType): List<String> {
        return when (provider) {
            LLMProviderType.LOCAL_GGUF -> emptyList()
            LLMProviderType.GEMINI -> GEMINI_DEFAULT_MODELS
            LLMProviderType.OPENROUTER -> OPENROUTER_DEFAULT_MODELS
            LLMProviderType.GROQ -> GROQ_DEFAULT_MODELS
            LLMProviderType.CEREBRAS -> CEREBRAS_DEFAULT_MODELS
            LLMProviderType.SAMBANOVA -> SAMBANOVA_DEFAULT_MODELS
            LLMProviderType.GITHUB_MODELS -> GITHUB_DEFAULT_MODELS
            LLMProviderType.HUGGINGFACE -> HUGGINGFACE_DEFAULT_MODELS
            LLMProviderType.MISTRAL -> MISTRAL_DEFAULT_MODELS
            LLMProviderType.NVIDIA_NIM -> NVIDIA_DEFAULT_MODELS
            LLMProviderType.COHERE -> COHERE_DEFAULT_MODELS
            LLMProviderType.DEEPSEEK -> DEEPSEEK_DEFAULT_MODELS
            LLMProviderType.OPENAI -> OPENAI_DEFAULT_MODELS
            LLMProviderType.ANTHROPIC -> ANTHROPIC_DEFAULT_MODELS
            LLMProviderType.XAI -> XAI_DEFAULT_MODELS
            LLMProviderType.OPENCODE_GO -> GO_DEFAULT_MODELS
            LLMProviderType.OPENCODE_ZEN -> ZEN_DEFAULT_MODELS
            LLMProviderType.CUSTOM_OPENAI -> CUSTOM_DEFAULT_MODELS
        }
    }

    fun getDefaultModel(provider: LLMProviderType): String {
        return getDefaultModels(provider).firstOrNull() ?: ""
    }

    fun resolveBaseUrl(provider: LLMProviderType, customBaseUrl: String = ""): String {
        val raw = if (provider == LLMProviderType.CUSTOM_OPENAI) customBaseUrl.trim() else provider.baseUrl
        return raw.trimEnd('/')
    }

    /**
     * Запрос актуального каталога моделей с сервера провайдера (GET /models).
     */
    fun fetchModels(
        provider: LLMProviderType,
        apiKey: String,
        customBaseUrl: String = ""
    ): List<String> {
        if (provider == LLMProviderType.LOCAL_GGUF) return emptyList()
        if (provider.requiresApiKey && apiKey.isBlank()) return getDefaultModels(provider)

        val base = resolveBaseUrl(provider, customBaseUrl)
        if (base.isBlank()) return getDefaultModels(provider)

        val endpoint = "$base/models"
        var conn: HttpURLConnection? = null
        try {
            val url = URL(endpoint)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"

            val rawKey = apiKey.removePrefix("Bearer ").removePrefix("bearer ").trim()
            conn.setRequestProperty("Accept", "application/json")

            if (provider.isAnthropicNative) {
                conn.setRequestProperty("x-api-key", rawKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.setRequestProperty("User-Agent", APP_USER_AGENT)
            } else {
                if (rawKey.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $rawKey")
                }
                applyProviderHeaders(conn, provider, rawKey)
            }

            conn.connectTimeout = 10000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.w(TAG, "Ошибка fetchModels ${provider.name} ($responseCode): ${err.take(200)}")
                return getDefaultModels(provider)
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val list = mutableListOf<String>()
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val id = item?.optString("id")?.ifBlank { item.optString("name") }
                        ?: array.optString(i)
                    if (id.isNotBlank()) list.add(cleanModelId(provider, id))
                }
            } else if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val array = json.optJSONArray("data") ?: json.optJSONArray("models")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i)
                        val id = item?.optString("id")?.ifBlank { item.optString("name") }
                            ?: array.optString(i)
                        if (id.isNotBlank()) list.add(cleanModelId(provider, id))
                    }
                }
            }

            if (list.isEmpty()) return getDefaultModels(provider)

            return when (provider) {
                LLMProviderType.OPENROUTER -> {
                    val freeModels = list.filter { it.contains(":free") }.sorted()
                    val otherModels = list.filter { !it.contains(":free") }.sorted()
                    (freeModels + otherModels).distinct()
                }
                LLMProviderType.DEEPSEEK -> (DEEPSEEK_DEFAULT_MODELS + list).distinct()
                LLMProviderType.GEMINI -> {
                    val chatModels = list.filter {
                        it.contains("gemini", ignoreCase = true) &&
                            !it.contains("embedding", ignoreCase = true)
                    }
                    (GEMINI_DEFAULT_MODELS + chatModels).distinct()
                }
                else -> (getDefaultModels(provider) + list.sorted()).distinct()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка получения моделей ${provider.name}: ${e.message}")
            return getDefaultModels(provider)
        } finally {
            conn?.disconnect()
        }
    }

    private fun cleanModelId(provider: LLMProviderType, id: String): String {
        return if (provider == LLMProviderType.GEMINI) {
            id.removePrefix("models/").trim()
        } else {
            id.trim()
        }
    }

    private fun applyProviderHeaders(conn: HttpURLConnection, provider: LLMProviderType, rawKey: String) {
        when (provider) {
            LLMProviderType.OPENROUTER -> {
                conn.setRequestProperty("User-Agent", APP_USER_AGENT)
                conn.setRequestProperty("HTTP-Referer", "https://github.com/klischa85/LLM")
                conn.setRequestProperty("X-Title", "LLM Android Assistant")
            }
            LLMProviderType.OPENCODE_GO, LLMProviderType.OPENCODE_ZEN -> {
                val sessionId = generateSessionId()
                val requestId = generateRequestId()
                conn.setRequestProperty("x-api-key", rawKey)
                conn.setRequestProperty("User-Agent", OPENCODE_USER_AGENT)
                conn.setRequestProperty("x-opencode-client", "cli")
                conn.setRequestProperty("x-opencode-project", "global")
                conn.setRequestProperty("x-opencode-session", sessionId)
                conn.setRequestProperty("x-opencode-request", requestId)
                conn.setRequestProperty("x-session-id", sessionId)
                conn.setRequestProperty("x-session-affinity", sessionId)
            }
            else -> {
                conn.setRequestProperty("User-Agent", APP_USER_AGENT)
            }
        }
    }

    /**
     * Потоковая генерация ответа через Server-Sent Events (SSE).
     * Поддерживает Vision, отключение/включение режима размышления (`thinkingEnabled`),
     * произвольный Base URL (`CUSTOM_OPENAI`) и нативный Anthropic Messages API.
     */
    @Suppress("UNUSED_PARAMETER")
    fun streamChatCompletions(
        provider: LLMProviderType,
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        conversationId: String = "",
        temperature: Float = 0.7f,
        customBaseUrl: String = "",
        thinkingEnabled: Boolean = true,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean
    ): StreamCompletionResult {
        val hasImages = messages.any { it.imageDataUrls.isNotEmpty() }
        val allowVision = hasImages && provider.supportsVision

        if (provider.isAnthropicNative) {
            return executeAnthropicWithFallbacks(
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                includeImages = allowVision,
                thinkingEnabled = thinkingEnabled,
                onToken = onToken,
                isCancelled = isCancelled
            )
        }

        return executeOpenAiWithFallbacks(
            provider = provider,
            apiKey = apiKey,
            model = model,
            messages = messages,
            temperature = temperature,
            customBaseUrl = customBaseUrl,
            includeImages = allowVision,
            thinkingEnabled = thinkingEnabled,
            includeThinkingControlParam = true,
            onToken = onToken,
            isCancelled = isCancelled
        )
    }

    private class VisionNotSupportedException(message: String) : Exception(message)
    private class ThinkingParamNotSupportedException(message: String) : Exception(message)

    private fun executeOpenAiWithFallbacks(
        provider: LLMProviderType,
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        temperature: Float,
        customBaseUrl: String,
        includeImages: Boolean,
        thinkingEnabled: Boolean,
        includeThinkingControlParam: Boolean,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean
    ): StreamCompletionResult {
        return try {
            executeOpenAiStreamRequest(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                customBaseUrl = customBaseUrl,
                includeImages = includeImages,
                thinkingEnabled = thinkingEnabled,
                includeThinkingControlParam = includeThinkingControlParam,
                onToken = onToken,
                isCancelled = isCancelled
            )
        } catch (e: VisionNotSupportedException) {
            Log.w(TAG, "Модель '$model' (${provider.displayName}) не поддерживает Vision (${e.message}). Откат на текст.")
            executeOpenAiWithFallbacks(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                customBaseUrl = customBaseUrl,
                includeImages = false,
                thinkingEnabled = thinkingEnabled,
                includeThinkingControlParam = includeThinkingControlParam,
                onToken = onToken,
                isCancelled = isCancelled
            )
        } catch (e: ThinkingParamNotSupportedException) {
            Log.w(TAG, "Провайдер ${provider.displayName} не принял параметр thinking (${e.message}). Повтор без него.")
            executeOpenAiStreamRequest(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                customBaseUrl = customBaseUrl,
                includeImages = includeImages,
                thinkingEnabled = thinkingEnabled,
                includeThinkingControlParam = false,
                onToken = onToken,
                isCancelled = isCancelled
            )
        }
    }

    private fun executeOpenAiStreamRequest(
        provider: LLMProviderType,
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        temperature: Float,
        customBaseUrl: String,
        includeImages: Boolean,
        thinkingEnabled: Boolean,
        includeThinkingControlParam: Boolean,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean
    ): StreamCompletionResult {
        val base = resolveBaseUrl(provider, customBaseUrl)
        if (base.isBlank()) {
            throw Exception("Не указан Base URL для ${provider.displayName}.")
        }
        val endpoint = "$base/chat/completions"
        var conn: HttpURLConnection? = null
        try {
            val lowerModel = model.lowercase(Locale.US)
            val skipTemperature =
                (provider == LLMProviderType.DEEPSEEK && lowerModel.contains("reasoner")) ||
                    (provider == LLMProviderType.OPENAI && (
                        lowerModel.startsWith("o1") ||
                            lowerModel.startsWith("o3") ||
                            lowerModel.startsWith("o4")
                        ))

            val url = URL(endpoint)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"

            val rawKey = apiKey.removePrefix("Bearer ").removePrefix("bearer ").trim()
            if (rawKey.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $rawKey")
            }
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "text/event-stream")
            applyProviderHeaders(conn, provider, rawKey)

            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000

            var attachedThinkingParam = false
            val rootJson = JSONObject().apply {
                put("model", model)
                put("stream", true)
                if (!skipTemperature) {
                    put("temperature", temperature.toDouble())
                }
                // Аккуратное управление режимом размышления на поддерживаемых провайдерах
                if (includeThinkingControlParam && !thinkingEnabled) {
                    when (provider) {
                        LLMProviderType.OPENROUTER -> {
                            put("reasoning", JSONObject().apply { put("exclude", true) })
                            attachedThinkingParam = true
                        }
                        LLMProviderType.GEMINI -> {
                            if (lowerModel.contains("2.5")) {
                                put("reasoning_effort", "none")
                                attachedThinkingParam = true
                            }
                        }
                        else -> Unit
                    }
                }

                val msgsArr = JSONArray()
                for (m in messages) {
                    msgsArr.put(JSONObject().apply {
                        put("role", m.role)
                        if (includeImages && m.imageDataUrls.isNotEmpty() && m.role == "user") {
                            val parts = JSONArray()
                            parts.put(JSONObject().apply {
                                put("type", "text")
                                put("text", m.content)
                            })
                            for (imgUrl in m.imageDataUrls) {
                                parts.put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", imgUrl)
                                    })
                                })
                            }
                            put("content", parts)
                        } else {
                            put("content", m.content)
                        }
                    })
                }
                put("messages", msgsArr)
            }

            conn.outputStream.use { os ->
                os.write(rootJson.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                val cleanErr = extractCleanApiError(err)

                if (attachedThinkingParam && responseCode in listOf(400, 422)) {
                    val low = cleanErr.lowercase(Locale.US)
                    if (low.contains("reasoning") || low.contains("thinking") ||
                        low.contains("unknown") || low.contains("unrecognized") ||
                        low.contains("extra") || low.contains("not supported") ||
                        low.contains("invalid")
                    ) {
                        throw ThinkingParamNotSupportedException(cleanErr)
                    }
                }

                if (includeImages && responseCode in listOf(400, 404, 415, 422)) {
                    val low = cleanErr.lowercase(Locale.US)
                    if (low.contains("image") || low.contains("vision") || low.contains("multimodal") ||
                        low.contains("content") || low.contains("unsupported") || low.contains("array")
                    ) {
                        throw VisionNotSupportedException(cleanErr)
                    }
                }

                throw Exception("Ошибка ${provider.displayName} ($responseCode): ${buildErrorTip(provider, model, responseCode, cleanErr)}")
            }

            var finishReason: String? = null
            var receivedContentChars = 0
            var receivedReasoningChars = 0
            val nativeToolNames = linkedMapOf<Int, StringBuilder>()
            val nativeToolArgs = linkedMapOf<Int, StringBuilder>()
            var streamErrorMessage: String? = null

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                var isThinking = false
                var hasStartedContent = false
                while (reader.readLine().also { line = it } != null) {
                    if (isCancelled()) break
                    val l = line?.trim() ?: continue
                    if (l.isEmpty() || l.startsWith(":")) continue

                    if (l.startsWith("data:")) {
                        val payload = l.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val chunkJson = try {
                            JSONObject(payload)
                        } catch (_: Exception) {
                            continue
                        }

                        if (chunkJson.has("error") && !chunkJson.isNull("error")) {
                            val errObj = chunkJson.optJSONObject("error")
                            streamErrorMessage = errObj?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?: chunkJson.optString("error").takeIf { it.isNotBlank() }
                                ?: "Неизвестная ошибка потока"
                            break
                        }

                        val choices = chunkJson.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val choice = choices.getJSONObject(0)
                            val fr = choice.optString("finish_reason", "")
                            if (fr.isNotBlank() && fr != "null") {
                                finishReason = fr
                            }

                            val delta = choice.optJSONObject("delta")
                            val content = if (delta != null && !delta.isNull("content")) {
                                val s = delta.optString("content", "")
                                if (s == "null") "" else s
                            } else ""

                            val reasoning = when {
                                delta != null && !delta.isNull("reasoning_content") -> {
                                    val s = delta.optString("reasoning_content", "")
                                    if (s == "null") "" else s
                                }
                                delta != null && !delta.isNull("reasoning") -> {
                                    val s = delta.optString("reasoning", "")
                                    if (s == "null") "" else s
                                }
                                else -> ""
                            }

                            val toolCallsArr = delta?.optJSONArray("tool_calls")
                            if (toolCallsArr != null) {
                                for (ti in 0 until toolCallsArr.length()) {
                                    val tcObj = toolCallsArr.optJSONObject(ti) ?: continue
                                    val tcIdx = tcObj.optInt("index", ti)
                                    val fn = tcObj.optJSONObject("function")
                                    val fnName = fn?.optString("name", "").orEmpty()
                                    val fnArgs = fn?.optString("arguments", "").orEmpty()
                                    if (fnName.isNotEmpty()) {
                                        nativeToolNames.getOrPut(tcIdx) { StringBuilder() }.append(fnName)
                                    }
                                    if (fnArgs.isNotEmpty()) {
                                        nativeToolArgs.getOrPut(tcIdx) { StringBuilder() }.append(fnArgs)
                                    }
                                }
                            }

                            if (reasoning.isNotEmpty()) {
                                val cleanReasoning = reasoning
                                    .replace("<think>", "", ignoreCase = true)
                                    .replace("</think>", "", ignoreCase = true)
                                if (cleanReasoning.isNotEmpty()) {
                                    receivedReasoningChars += cleanReasoning.length
                                    if (thinkingEnabled) {
                                        if (!isThinking && !hasStartedContent) {
                                            isThinking = true
                                            onToken("<think>\n")
                                        }
                                        if (isThinking) {
                                            onToken(cleanReasoning)
                                        }
                                    }
                                }
                            }
                            if (content.isNotEmpty()) {
                                if (isThinking && content.isBlank()) {
                                    continue
                                }
                                var emitContent = content
                                if (isThinking) {
                                    isThinking = false
                                    hasStartedContent = true
                                    onToken("\n</think>\n")
                                    val trimmedLead = emitContent.trimStart()
                                    if (trimmedLead.startsWith("</think>", ignoreCase = true)) {
                                        emitContent = trimmedLead.substring("</think>".length)
                                    }
                                } else {
                                    hasStartedContent = true
                                }
                                if (emitContent.isNotEmpty()) {
                                    receivedContentChars += emitContent.length
                                    onToken(emitContent)
                                }
                            }
                        }
                    }
                }
                if (isThinking) {
                    onToken("\n</think>\n")
                }
            }

            if (streamErrorMessage != null && receivedContentChars == 0 && nativeToolNames.isEmpty()) {
                throw Exception("Ошибка ${provider.displayName}: $streamErrorMessage")
            }

            val assembledNativeCalls = mutableListOf<AgentToolCall>()
            for ((idx, nameSb) in nativeToolNames) {
                val rawName = nameSb.toString().trim().lowercase(Locale.US)
                if (rawName.isBlank()) continue
                val toolName = when (rawName) {
                    "bash", "shell", "termux", "terminal", "exec" -> "run_termux"
                    else -> rawName
                }
                val argsStr = nativeToolArgs[idx]?.toString()?.trim().orEmpty()
                val argsObj = try {
                    if (argsStr.isNotEmpty()) JSONObject(argsStr) else JSONObject()
                } catch (_: Exception) {
                    JSONObject()
                }
                assembledNativeCalls.add(
                    AgentToolCall(
                        tool = toolName,
                        args = argsObj,
                        rawJson = argsStr.ifBlank { "{}" }
                    )
                )
            }

            return StreamCompletionResult(
                finishReason = finishReason,
                nativeToolCalls = assembledNativeCalls,
                receivedContentChars = receivedContentChars,
                receivedReasoningChars = receivedReasoningChars
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Нативная поддержка Anthropic Messages API (`/v1/messages`) с SSE, Vision и Extended Thinking.
     */
    private fun executeAnthropicWithFallbacks(
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        temperature: Float,
        includeImages: Boolean,
        thinkingEnabled: Boolean,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean
    ): StreamCompletionResult {
        val lower = model.lowercase(Locale.US)
        val supportsExtendedThinking = thinkingEnabled && (
            lower.contains("3-7") || lower.contains("claude-sonnet-4") ||
                lower.contains("claude-opus-4") || lower.contains("claude-4")
            )
        return try {
            executeAnthropicStreamRequest(
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                includeImages = includeImages,
                enableExtendedThinking = supportsExtendedThinking,
                thinkingVisible = thinkingEnabled,
                onToken = onToken,
                isCancelled = isCancelled
            )
        } catch (e: ThinkingParamNotSupportedException) {
            Log.w(TAG, "Anthropic Extended Thinking отклонён (${e.message}), повтор без thinking.")
            executeAnthropicStreamRequest(
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                includeImages = includeImages,
                enableExtendedThinking = false,
                thinkingVisible = thinkingEnabled,
                onToken = onToken,
                isCancelled = isCancelled
            )
        } catch (e: VisionNotSupportedException) {
            Log.w(TAG, "Anthropic Vision отклонён (${e.message}), повтор в текстовом режиме.")
            executeAnthropicStreamRequest(
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                includeImages = false,
                enableExtendedThinking = false,
                thinkingVisible = thinkingEnabled,
                onToken = onToken,
                isCancelled = isCancelled
            )
        }
    }

    private fun executeAnthropicStreamRequest(
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        temperature: Float,
        includeImages: Boolean,
        enableExtendedThinking: Boolean,
        thinkingVisible: Boolean,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean
    ): StreamCompletionResult {
        val endpoint = "${LLMProviderType.ANTHROPIC.baseUrl}/messages"
        var conn: HttpURLConnection? = null
        try {
            val rawKey = apiKey.removePrefix("Bearer ").removePrefix("bearer ").trim()
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-api-key", rawKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("User-Agent", APP_USER_AGENT)
                doOutput = true
                connectTimeout = 20000
                readTimeout = 120000
            }

            val systemParts = mutableListOf<String>()
            val nonSystem = mutableListOf<ApiChatMessage>()
            for (m in messages) {
                if (m.role == "system") {
                    if (m.content.isNotBlank()) systemParts.add(m.content)
                } else {
                    nonSystem.add(m)
                }
            }

            val rootJson = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("max_tokens", if (enableExtendedThinking) 8192 else 4096)
                if (enableExtendedThinking) {
                    put("thinking", JSONObject().apply {
                        put("type", "enabled")
                        put("budget_tokens", 3072)
                    })
                    put("temperature", 1.0) // Требование Anthropic API при включённом thinking
                } else {
                    put("temperature", temperature.toDouble().coerceIn(0.0, 1.0))
                }
                if (systemParts.isNotEmpty()) {
                    put("system", systemParts.joinToString("\n\n"))
                }

                val msgsArr = JSONArray()
                for (m in nonSystem) {
                    val role = if (m.role == "assistant") "assistant" else "user"
                    msgsArr.put(JSONObject().apply {
                        put("role", role)
                        if (includeImages && m.imageDataUrls.isNotEmpty() && role == "user") {
                            val parts = JSONArray()
                            for (imgUrl in m.imageDataUrls) {
                                val commaIdx = imgUrl.indexOf(',')
                                if (imgUrl.startsWith("data:") && commaIdx != -1) {
                                    val header = imgUrl.substring(5, commaIdx)
                                    val mediaType = header.substringBefore(";").ifBlank { "image/jpeg" }
                                    val b64 = imgUrl.substring(commaIdx + 1)
                                    parts.put(JSONObject().apply {
                                        put("type", "image")
                                        put("source", JSONObject().apply {
                                            put("type", "base64")
                                            put("media_type", mediaType)
                                            put("data", b64)
                                        })
                                    })
                                }
                            }
                            parts.put(JSONObject().apply {
                                put("type", "text")
                                put("text", m.content)
                            })
                            put("content", parts)
                        } else {
                            put("content", m.content)
                        }
                    })
                }
                put("messages", msgsArr)
            }

            conn.outputStream.use { os ->
                os.write(rootJson.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                val cleanErr = extractCleanApiError(err)
                val low = cleanErr.lowercase(Locale.US)
                if (enableExtendedThinking && responseCode == 400 && (low.contains("thinking") || low.contains("budget"))) {
                    throw ThinkingParamNotSupportedException(cleanErr)
                }
                if (includeImages && responseCode in listOf(400, 415, 422) && (low.contains("image") || low.contains("vision"))) {
                    throw VisionNotSupportedException(cleanErr)
                }
                throw Exception("Ошибка Anthropic Claude ($responseCode): ${buildErrorTip(LLMProviderType.ANTHROPIC, model, responseCode, cleanErr)}")
            }

            var finishReason: String? = null
            var receivedContentChars = 0
            var receivedReasoningChars = 0
            var isThinking = false

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (isCancelled()) break
                    val l = line?.trim() ?: continue
                    if (!l.startsWith("data:")) continue
                    val payload = l.removePrefix("data:").trim()
                    if (payload == "[DONE]" || payload.isEmpty()) continue
                    val ev = try {
                        JSONObject(payload)
                    } catch (_: Exception) {
                        continue
                    }

                    when (ev.optString("type")) {
                        "error" -> {
                            val errMsg = ev.optJSONObject("error")?.optString("message") ?: "Ошибка потока Anthropic"
                            throw Exception("Ошибка Anthropic Claude: $errMsg")
                        }
                        "content_block_delta" -> {
                            val delta = ev.optJSONObject("delta") ?: continue
                            when (delta.optString("type")) {
                                "thinking_delta" -> {
                                    val t = delta.optString("thinking", "")
                                    if (t.isNotEmpty()) {
                                        receivedReasoningChars += t.length
                                        if (thinkingVisible) {
                                            if (!isThinking) {
                                                isThinking = true
                                                onToken("<think>\n")
                                            }
                                            onToken(t)
                                        }
                                    }
                                }
                                "text_delta" -> {
                                    val txt = delta.optString("text", "")
                                    if (txt.isNotEmpty()) {
                                        if (isThinking) {
                                            isThinking = false
                                            onToken("\n</think>\n")
                                        }
                                        receivedContentChars += txt.length
                                        onToken(txt)
                                    }
                                }
                            }
                        }
                        "message_delta" -> {
                            val sr = ev.optJSONObject("delta")?.optString("stop_reason", "").orEmpty()
                            if (sr == "max_tokens") finishReason = "length"
                            else if (sr.isNotBlank() && sr != "null") finishReason = sr
                        }
                    }
                }
                if (isThinking) {
                    onToken("\n</think>\n")
                }
            }

            return StreamCompletionResult(
                finishReason = finishReason,
                nativeToolCalls = emptyList(),
                receivedContentChars = receivedContentChars,
                receivedReasoningChars = receivedReasoningChars
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractCleanApiError(err: String): String {
        return try {
            val errJson = JSONObject(err)
            val errorObj = errJson.optJSONObject("error")
            errorObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: errJson.optString("message").takeIf { it.isNotBlank() }
                ?: errJson.optString("detail").takeIf { it.isNotBlank() }
                ?: err
        } catch (_: Exception) {
            err.take(400)
        }
    }

    private fun buildErrorTip(
        provider: LLMProviderType,
        model: String,
        responseCode: Int,
        cleanErr: String
    ): String {
        return when (responseCode) {
            401 -> "Неверный API-ключ для ${provider.displayName}. Проверьте ключ на ${provider.apiKeyUrlLabel}."
            402 -> "Недостаточно средств на балансе ${provider.displayName} (HTTP 402): $cleanErr"
            403 -> if ((provider == LLMProviderType.OPENCODE_ZEN || provider == LLMProviderType.OPENCODE_GO) && isFreeModel(provider, model)) {
                "Промо-модель '$model' заблокирована для вашего платного API-ключа OpenCode (HTTP 403). Выберите 'qwen3.6-plus', 'claude-3-7-sonnet' или 'gpt-4o'."
            } else {
                "Доступ отклонён ${provider.displayName} ($cleanErr). Проверьте ключ на ${provider.apiKeyUrlLabel}."
            }
            404 -> "Модель '$model' не найдена у ${provider.displayName}: $cleanErr"
            429 -> "Превышен лимит запросов ${provider.displayName}. Подождите немного или переключитесь на другого бесплатного провайдера."
            500, 502, 503, 504 -> "Сервер ${provider.displayName} временно перегружен (HTTP $responseCode). Повторите попытку."
            else -> cleanErr
        }
    }
}
