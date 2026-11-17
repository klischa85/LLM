package com.klischa.llmnotes.api

import android.util.Log
import com.klischa.llmnotes.AgentToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Типы провайдеров LLM:
 * - LOCAL_GGUF: локальный автономный запуск на процессоре смартфона через llama.cpp (100% офлайн).
 * - DEEPSEEK: официальный API DeepSeek (https://api.deepseek.com/v1 — DeepSeek V3 `deepseek-chat`, DeepSeek R1 `deepseek-reasoner`).
 * - OPENROUTER: облачный API OpenRouter с десятками бесплатных моделей (:free).
 * - GROQ: облачный высокоскоростной API Groq с доступом к LLaMA 3.3 70B.
 * - OPENCODE_ZEN: облачный API OpenCode подписки ZEN (https://opencode.ai/zen/v1).
 * - OPENCODE_GO: облачный API OpenCode подписки GO (https://opencode.ai/zen/go/v1).
 */
enum class LLMProviderType(val displayName: String, val baseUrl: String) {
    LOCAL_GGUF("Офлайн GGUF", ""),
    DEEPSEEK("DeepSeek API", "https://api.deepseek.com/v1"),
    OPENROUTER("OpenRouter (Free)", "https://openrouter.ai/api/v1"),
    GROQ("Groq Cloud (Free)", "https://api.groq.com/openai/v1"),
    OPENCODE_ZEN("OpenCode ZEN", "https://opencode.ai/zen/v1"),
    OPENCODE_GO("OpenCode GO", "https://opencode.ai/zen/go/v1")
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
 * Клиент взаимодействия с внешними OpenAI-совместимыми API:
 * DeepSeek API, OpenRouter, Groq Cloud, OpenCode ZEN, OpenCode GO.
 */
object OpenCodeClient {
    private const val TAG = "OpenCodeClient"
    private const val APP_USER_AGENT = "LLM-Android/1.3.8"
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

    /**
     * Генератор идентификаторов OpenCode CLI.
     * Шлюз OpenCode проверяет временную метку и алгоритм инверсии битов:
     * - для сессий (ses_): инвертированное нисходящее время (desc = true)
     * - для сообщений/запросов (msg_): прямое восходящее время (desc = false)
     * Первые 12 hex-символов кодируют миллисекунды, затем следуют 14 символов Base62.
     */
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
            hexBuilder.append(String.format(java.util.Locale.US, "%02x", byteVal))
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

    // Официальные модели DeepSeek API (api.deepseek.com/v1)
    val DEEPSEEK_DEFAULT_MODELS = listOf(
        "deepseek-chat",
        "deepseek-reasoner"
    )

    // Рекомендуемые бесплатные модели OpenRouter (суффикс :free гарантирует бесплатность)
    val OPENROUTER_DEFAULT_MODELS = listOf(
        "google/gemini-2.0-flash-exp:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "deepseek/deepseek-r1:free",
        "deepseek/deepseek-chat:free",
        "qwen/qwen-2.5-coder-32b-instruct:free",
        "meta-llama/llama-3.1-8b-instruct:free",
        "mistralai/mistral-7b-instruct:free",
        "google/gemini-flash-1.5:free"
    )

    // Рекомендуемые бесплатные модели Groq Cloud
    val GROQ_DEFAULT_MODELS = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "deepseek-r1-distill-llama-70b",
        "mixtral-8x7b-32768",
        "gemma2-9b-it"
    )

    // Рекомендуемые модели для подписки OpenCode GO
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

    // Рекомендуемые модели для подписки OpenCode ZEN
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

    fun isFreeModel(provider: LLMProviderType, model: String): Boolean {
        return when (provider) {
            LLMProviderType.OPENROUTER -> model.contains(":free")
            LLMProviderType.GROQ -> true
            LLMProviderType.OPENCODE_ZEN -> model == "big-pickle" || model.endsWith("-free")
            else -> false
        }
    }

    fun getDefaultModels(provider: LLMProviderType): List<String> {
        return when (provider) {
            LLMProviderType.DEEPSEEK -> DEEPSEEK_DEFAULT_MODELS
            LLMProviderType.OPENROUTER -> OPENROUTER_DEFAULT_MODELS
            LLMProviderType.GROQ -> GROQ_DEFAULT_MODELS
            LLMProviderType.OPENCODE_GO -> GO_DEFAULT_MODELS
            LLMProviderType.OPENCODE_ZEN -> ZEN_DEFAULT_MODELS
            LLMProviderType.LOCAL_GGUF -> emptyList()
        }
    }

    /**
     * Запрос актуального каталога моделей с сервера провайдера (GET /v1/models).
     */
    fun fetchModels(provider: LLMProviderType, apiKey: String): List<String> {
        if (apiKey.isBlank() || provider == LLMProviderType.LOCAL_GGUF) {
            return getDefaultModels(provider)
        }

        val endpoint = "${provider.baseUrl}/models"
        var conn: HttpsURLConnection? = null
        try {
            val url = URL(endpoint)
            conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "GET"

            val rawKey = apiKey.removePrefix("Bearer ").removePrefix("bearer ").trim()
            conn.setRequestProperty("Authorization", "Bearer $rawKey")
            conn.setRequestProperty("Accept", "application/json")

            when (provider) {
                LLMProviderType.DEEPSEEK -> {
                    conn.setRequestProperty("User-Agent", APP_USER_AGENT)
                }
                LLMProviderType.OPENROUTER -> {
                    conn.setRequestProperty("User-Agent", APP_USER_AGENT)
                    conn.setRequestProperty("HTTP-Referer", "https://github.com/Klischa/LLM")
                    conn.setRequestProperty("X-Title", "LLM Android Assistant")
                }
                LLMProviderType.GROQ -> {
                    conn.setRequestProperty("User-Agent", APP_USER_AGENT)
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
                }
                LLMProviderType.LOCAL_GGUF -> Unit
            }
            conn.connectTimeout = 10000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.w(TAG, "Ошибка fetchModels ($responseCode): $err")
                return getDefaultModels(provider)
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val list = mutableListOf<String>()
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val id = item?.optString("id") ?: array.optString(i)
                    if (id.isNotBlank()) list.add(id)
                }
            } else if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val array = json.optJSONArray("data") ?: json.optJSONArray("models")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i)
                        val id = item?.optString("id") ?: array.optString(i)
                        if (id.isNotBlank()) list.add(id)
                    }
                }
            }

            if (provider == LLMProviderType.DEEPSEEK && list.isNotEmpty()) {
                return (DEEPSEEK_DEFAULT_MODELS + list).distinct()
            }

            if (provider == LLMProviderType.OPENROUTER && list.isNotEmpty()) {
                val freeModels = list.filter { it.contains(":free") }.sorted()
                val otherModels = list.filter { !it.contains(":free") }.sorted()
                return (freeModels + otherModels).distinct()
            }

            return if (list.isNotEmpty()) (getDefaultModels(provider) + list).distinct().sorted() else getDefaultModels(provider)
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка получения моделей: ${e.message}")
            return getDefaultModels(provider)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Потоковая генерация ответа через Server-Sent Events (SSE).
     * Поддерживает мультимодальные запросы с изображениями (OpenAI Vision `image_url`)
     * с автоматическим откатом на текстовое описание, если модель не поддерживает Vision.
     * Рассуждения (`reasoning_content` / `reasoning`) автоматически оборачиваются в `<think>...</think>`,
     * чтобы UI отображал их в аккуратной сворачиваемой карточке (ThoughtParser).
     */
    @Suppress("UNUSED_PARAMETER")
    fun streamChatCompletions(
        provider: LLMProviderType,
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        conversationId: String = "",
        temperature: Float = 0.7f,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean
    ): StreamCompletionResult {
        val hasImages = messages.any { it.imageDataUrls.isNotEmpty() }
        // Официальный DeepSeek API (/v1/chat/completions) является текстовым — отправляем без image_url
        val allowVision = hasImages && provider != LLMProviderType.DEEPSEEK

        return try {
            executeStreamRequest(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                includeImages = allowVision,
                onToken = onToken,
                isCancelled = isCancelled
            )
        } catch (e: VisionNotSupportedException) {
            Log.w(TAG, "Модель '$model' (${provider.displayName}) не поддерживает Vision (${e.message}). Повторяем запрос в текстовом режиме.")
            // Автоматический безопасный откат на текстовый формат с метаданными изображения
            executeStreamRequest(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = messages,
                temperature = temperature,
                includeImages = false,
                onToken = onToken,
                isCancelled = isCancelled
            )
        }
    }

    private class VisionNotSupportedException(message: String) : Exception(message)

    private fun executeStreamRequest(
        provider: LLMProviderType,
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        temperature: Float,
        includeImages: Boolean,
        onToken: (String) -> Unit,
        isCancelled: () -> Boolean
    ): StreamCompletionResult {
        val endpoint = "${provider.baseUrl}/chat/completions"
        var conn: HttpsURLConnection? = null
        try {
            val isDeepSeekReasoner =
                provider == LLMProviderType.DEEPSEEK &&
                    model.contains("reasoner", ignoreCase = true)

            val url = URL(endpoint)
            conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"

            val rawKey = apiKey.removePrefix("Bearer ").removePrefix("bearer ").trim()

            conn.setRequestProperty("Authorization", "Bearer $rawKey")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "text/event-stream")

            when (provider) {
                LLMProviderType.DEEPSEEK -> {
                    conn.setRequestProperty("User-Agent", APP_USER_AGENT)
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
                LLMProviderType.OPENROUTER -> {
                    conn.setRequestProperty("User-Agent", APP_USER_AGENT)
                    conn.setRequestProperty("HTTP-Referer", "https://github.com/Klischa/LLM")
                    conn.setRequestProperty("X-Title", "LLM Android Assistant")
                }
                LLMProviderType.GROQ -> {
                    conn.setRequestProperty("User-Agent", APP_USER_AGENT)
                }
                LLMProviderType.LOCAL_GGUF -> Unit
            }

            conn.doOutput = true
            conn.connectTimeout = 20000
            // Модели с рассуждениями (DeepSeek R1, Qwen3, QwQ) и агентные шаги могут думать длительно
            conn.readTimeout = 120000

            val rootJson = JSONObject().apply {
                put("model", model)
                put("stream", true)
                // Официальная спецификация DeepSeek: deepseek-reasoner не поддерживает параметр temperature
                if (!isDeepSeekReasoner) {
                    put("temperature", temperature.toDouble())
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
                val cleanErr = try {
                    val errJson = JSONObject(err)
                    val errorObj = errJson.optJSONObject("error")
                    errorObj?.optString("message")
                        ?: errJson.optString("message").takeIf { it.isNotBlank() }
                        ?: err
                } catch (_: Exception) {
                    err
                }

                // Если выбранная облачная модель не поддерживает Vision / image_url — откатываемся на текст
                if (includeImages && responseCode in listOf(400, 404, 415, 422)) {
                    val low = cleanErr.lowercase()
                    if (low.contains("image") || low.contains("vision") || low.contains("multimodal") ||
                        low.contains("content") || low.contains("unsupported") || low.contains("array")
                    ) {
                        throw VisionNotSupportedException(cleanErr)
                    }
                }

                val tip = when (responseCode) {
                    401 -> when (provider) {
                        LLMProviderType.DEEPSEEK ->
                            "Неверный API-ключ DeepSeek. Проверьте ключ на platform.deepseek.com/api_keys."
                        else -> "Неверный API-ключ для ${provider.displayName}."
                    }
                    402 -> if (provider == LLMProviderType.DEEPSEEK) {
                        "Недостаточно средств на балансе DeepSeek API (HTTP 402). Пополните счёт на platform.deepseek.com."
                    } else {
                        "Требуется пополнение баланса ${provider.displayName}: $cleanErr"
                    }
                    403 -> when (provider) {
                        LLMProviderType.DEEPSEEK ->
                            "Доступ отклонён DeepSeek API ($cleanErr). Проверьте статус аккаунта на platform.deepseek.com."
                        LLMProviderType.OPENROUTER ->
                            "Доступ отклонен OpenRouter ($cleanErr). Проверьте ключ на openrouter.ai/keys."
                        LLMProviderType.GROQ ->
                            "Доступ отклонен Groq Cloud ($cleanErr). Проверьте ключ на console.groq.com."
                        else -> if (isFreeModel(provider, model)) {
                            "Бесплатная промо-модель '$model' заблокирована шлюзом OpenCode для вашего платного API-ключа (HTTP 403).\n💡 Выберите стандартную модель из каталога (например, 'qwen3.6-plus', 'claude-3-7-sonnet', 'gpt-4o')."
                        } else {
                            "Доступ отклонен ($cleanErr). Проверьте активность подписки ${provider.displayName}."
                        }
                    }
                    404 -> if (provider == LLMProviderType.OPENCODE_ZEN && !isFreeModel(provider, model)) {
                        "Модель '$model' недоступна или маршрут не найден (HTTP 404).\n💡 Рекомендуем использовать проверенные рабочие модели: 'qwen3.6-plus', 'qwen3.8-flash', 'claude-3-7-sonnet', 'gpt-4o'."
                    } else {
                        "Модель '$model' не найдена или временно недоступна: $cleanErr"
                    }
                    429 -> "Превышен лимит запросов ${provider.displayName}. Попробуйте чуть позже."
                    500, 502, 503, 504 -> "Сервер ${provider.displayName} временно перегружен (HTTP $responseCode). Повторите попытку."
                    else -> cleanErr
                }
                throw Exception("Ошибка ${provider.displayName} ($responseCode): $tip")
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

                        // Проверяем, не прислал ли сервер ошибку прямо внутри SSE-потока (часто у OpenRouter/OpenCode)
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

                            // Сборка нативных OpenAI tool_calls (если шлюз перехватил <tool_call> на стороне сервера)
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
                                // Убираем дублирующие <think>/</think> внутри поля reasoning
                                val cleanReasoning = reasoning
                                    .replace("<think>", "", ignoreCase = true)
                                    .replace("</think>", "", ignoreCase = true)
                                if (cleanReasoning.isNotEmpty()) {
                                    if (!isThinking && !hasStartedContent) {
                                        isThinking = true
                                        onToken("<think>\n")
                                    }
                                    if (isThinking) {
                                        receivedReasoningChars += cleanReasoning.length
                                        onToken(cleanReasoning)
                                    }
                                }
                            }
                            if (content.isNotEmpty()) {
                                // Не закрываем <think> на пустых/пробельных дельтах между чанками рассуждений
                                if (isThinking && content.isBlank()) {
                                    continue
                                }
                                var emitContent = content
                                if (isThinking) {
                                    isThinking = false
                                    hasStartedContent = true
                                    onToken("\n</think>\n")
                                    // Если модель дополнительно сама прислала ведущий </think> — убираем дубликат
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
                // Если поток завершился внутри блока рассуждений, аккуратно закрываем тег <think>
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
}
