package com.klischa.llmnotes

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Скилл (Agent Skill / SKILL.md) — подключаемый набор специализированных правил и инструкций
 * для чата и файлового/Termux-агента.
 *
 * Совместим с форматом SKILL.md (YAML frontmatter `name` + `description` + Markdown тело),
 * поддерживает встроенную библиотеку, установку по URL / GitHub, импорт .md файлов и ручное создание.
 */
data class AgentSkill(
    val id: String,
    val name: String,
    val slug: String,
    val description: String,
    val instructions: String,
    val source: String = "builtin", // "builtin" | "url" | "file" | "custom"
    val sourceUrl: String? = null,
    val enabled: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class RemoteSkillEntry(
    val slug: String,
    val title: String,
    val description: String,
    val rawUrl: String,
    val repoLabel: String
)

data class ResolvedSkills(
    val systemBlock: String,
    val activeSkills: List<AgentSkill>,
    val cleanedPrompt: String
)

object SkillManager {
    private const val TAG = "SkillManager"
    private const val SKILLS_DIR = "skills"
    private const val SKILLS_FILE = "skills_registry_v1.json"
    private const val MAX_REMOTE_SKILL_BYTES = 256 * 1024

    val BUILTIN_SKILLS: List<AgentSkill> = listOf(
        AgentSkill(
            id = "builtin_termux_devops",
            name = "Termux & Linux DevOps",
            slug = "termux-devops",
            description = "Автоматизация через Termux: скрипты bash/python, пакеты pkg, диагностика, git.",
            instructions = """
Ты действуешь как опытный Linux/Android DevOps-инженер в среде Termux.
- Перед выполнением рискованных команд проверяй окружение (ls, pwd, which, uname).
- Пиши идемпотентные и безопасные bash/python-скрипты с обработкой ошибок (`set -euo pipefail`).
- При установке пакетов в Termux используй `pkg install -y ...`, не предлагай `sudo`, если устройство без root.
- Всегда кратко объясняй, что сделает команда и какой результат получен.
            """.trimIndent(),
            source = "builtin",
            enabled = false
        ),
        AgentSkill(
            id = "builtin_code_architect",
            name = "Код-ревью и разработка",
            slug = "code-architect",
            description = "Поиск багов, рефакторинг, чистая архитектура и готовые рабочие патчи.",
            instructions = """
Ты действуешь как Senior Software Architect и ревьюер кода.
- Сначала определи корневую причину проблемы, затем предложи минимально инвазивное и надёжное исправление.
- Проверяй граничные случаи: null-safety, потокобезопасность, утечки ресурсов, кодировки, обработку исключений.
- Давай готовый к запуску код без «заглушек» и `// TODO`, указывая точные имена файлов.
            """.trimIndent(),
            source = "builtin",
            enabled = false
        ),
        AgentSkill(
            id = "builtin_file_organizer",
            name = "Менеджер файлов телефона",
            slug = "file-organizer",
            description = "Умный поиск, разбор загрузок, сортировка и бэкапы файлов на /sdcard.",
            instructions = """
Ты помогаешь наводить порядок в файлах смартфона (/storage/emulated/0).
- Сначала исследуй содержимое папок с помощью `list_files` / `search_files` / `file_info`, прежде чем что-то менять.
- Никогда не удаляй файлы без явного списка и объяснения, что именно будет удалено.
- При сортировке предлагай понятную структуру папок (Документы, Фото, Архивы, Проекты) и проверяй, чтобы не перезаписать существующие файлы.
            """.trimIndent(),
            source = "builtin",
            enabled = false
        ),
        AgentSkill(
            id = "builtin_doc_analyst",
            name = "Аналитик документов",
            slug = "doc-analyst",
            description = "Структурированные выжимки, ключевые факты, риски и чек-листы по файлам.",
            instructions = """
Ты действуешь как внимательный аналитик документов, таблиц и отчётов.
- Структурируй ответ блоками: 1) Главный вывод, 2) Ключевые цифры и факты, 3) Риски/противоречия, 4) Рекомендуемые действия (чек-лист).
- Не выдумывай данные, которых нет в документе — прямо указывай, если информации недостаточно.
            """.trimIndent(),
            source = "builtin",
            enabled = false
        ),
        AgentSkill(
            id = "builtin_prompt_engineer",
            name = "Инженер промптов",
            slug = "prompt-coach",
            description = "Декомпозиция сложных задач и составление точных системных промптов.",
            instructions = """
Ты специалист по промпт-инженерии и декомпозиции сложных задач.
- Помогай превращать расплывчатые идеи в чёткие спецификации: роль, контекст, ограничения, формат вывода, критерии проверки.
- Если задачу лучше решить в несколько шагов — разбивай её на последовательный план с понятными результатами каждого этапа.
            """.trimIndent(),
            source = "builtin",
            enabled = false
        ),
        AgentSkill(
            id = "builtin_translator",
            name = "Переводчик и редактор",
            slug = "translator-pro",
            description = "Естественный перевод RU↔EN и профессиональная редактура текста.",
            instructions = """
Ты профессиональный переводчик и литературный редактор.
- Переводи естественно и идиоматично, сохраняя смысл, терминологию, форматирование Markdown и тон оригинала.
- При редактуре убирай канцеляризмы, повторы и опечатки, не искажая авторский замысел.
            """.trimIndent(),
            source = "builtin",
            enabled = false
        ),
        AgentSkill(
            id = "builtin_study_tutor",
            name = "Репетитор и наставник",
            slug = "study-tutor",
            description = "Понятные объяснения сложных тем от простого к сложному с примерами.",
            instructions = """
Ты терпеливый и ясный преподаватель.
- Объясняй сложные концепции шаг за шагом: сначала простая аналогия из жизни, затем точное определение, затем 1–2 наглядных примера.
- В конце предлагай короткий проверочный вопрос или мини-практику, чтобы закрепить материал.
            """.trimIndent(),
            source = "builtin",
            enabled = false
        )
    )

    // Проверенные онлайн-скиллы (формат SKILL.md с GitHub anthropics/skills) — всегда доступны в 1 клик
    val CURATED_REMOTE_SKILLS: List<RemoteSkillEntry> = listOf(
        RemoteSkillEntry(
            slug = "skill-creator",
            title = "Skill Creator (Anthropic)",
            description = "Руководство по созданию и улучшению качественных SKILL.md навыков.",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/skill-creator/SKILL.md",
            repoLabel = "anthropics/skills"
        ),
        RemoteSkillEntry(
            slug = "pdf",
            title = "PDF Toolkit (Anthropic)",
            description = "Продвинутая работа с PDF: разбор, извлечение таблиц, сборка и конвертация.",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/pdf/SKILL.md",
            repoLabel = "anthropics/skills"
        ),
        RemoteSkillEntry(
            slug = "docx",
            title = "DOCX Documents (Anthropic)",
            description = "Создание, правка и анализ Word-документов (.docx).",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/docx/SKILL.md",
            repoLabel = "anthropics/skills"
        ),
        RemoteSkillEntry(
            slug = "xlsx",
            title = "Excel / XLSX (Anthropic)",
            description = "Работа с электронными таблицами, формулами и отчётами.",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/xlsx/SKILL.md",
            repoLabel = "anthropics/skills"
        ),
        RemoteSkillEntry(
            slug = "pptx",
            title = "Presentations / PPTX (Anthropic)",
            description = "Проектирование и генерация презентаций PowerPoint.",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/pptx/SKILL.md",
            repoLabel = "anthropics/skills"
        ),
        RemoteSkillEntry(
            slug = "frontend-design",
            title = "Frontend Design (Anthropic)",
            description = "Создание выразительных современных веб-интерфейсов и UI-компонентов.",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/frontend-design/SKILL.md",
            repoLabel = "anthropics/skills"
        ),
        RemoteSkillEntry(
            slug = "mcp-builder",
            title = "MCP Server Builder (Anthropic)",
            description = "Проектирование и написание серверов Model Context Protocol (MCP).",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/mcp-builder/SKILL.md",
            repoLabel = "anthropics/skills"
        ),
        RemoteSkillEntry(
            slug = "webapp-testing",
            title = "Webapp Testing (Anthropic)",
            description = "Автоматическое тестирование и отладка веб-приложений.",
            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/webapp-testing/SKILL.md",
            repoLabel = "anthropics/skills"
        )
    )

    private fun registryFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, SKILLS_DIR).apply { mkdirs() }
        return File(dir, SKILLS_FILE)
    }

    @Synchronized
    fun loadSkills(context: Context): List<AgentSkill> {
        val file = registryFile(context)
        val savedList = mutableListOf<AgentSkill>()
        if (file.exists() && file.length() > 0L) {
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    val instructions = obj.optString("instructions").trim()
                    if (id.isEmpty() || name.isEmpty() || instructions.isEmpty()) continue
                    savedList.add(
                        AgentSkill(
                            id = id,
                            name = name,
                            slug = obj.optString("slug").ifBlank { toSlug(name) },
                            description = obj.optString("description"),
                            instructions = instructions,
                            source = obj.optString("source", "custom"),
                            sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() },
                            enabled = obj.optBoolean("enabled", false),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка чтения реестра скиллов: ${e.message}")
            }
        }

        // Объединяем встроенные скиллы (сохраняя состояние enabled пользователя) с установленными/пользовательскими
        val savedById = savedList.associateBy { it.id }
        val merged = mutableListOf<AgentSkill>()
        for (builtin in BUILTIN_SKILLS) {
            val existing = savedById[builtin.id]
            merged.add(
                if (existing != null) builtin.copy(enabled = existing.enabled)
                else builtin
            )
        }
        val builtinIds = BUILTIN_SKILLS.map { it.id }.toSet()
        for (skill in savedList) {
            if (skill.id !in builtinIds) {
                merged.add(skill)
            }
        }
        return merged
    }

    @Synchronized
    fun saveSkills(context: Context, skills: List<AgentSkill>) {
        try {
            val arr = JSONArray()
            for (s in skills) {
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("slug", s.slug)
                    put("description", s.description)
                    put("instructions", s.instructions)
                    put("source", s.source)
                    if (!s.sourceUrl.isNullOrBlank()) put("sourceUrl", s.sourceUrl)
                    put("enabled", s.enabled)
                    put("updatedAt", s.updatedAt)
                })
            }
            registryFile(context).writeText(arr.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения реестра скиллов: ${e.message}")
        }
    }

    fun toSlug(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9а-яё_-]+"), "-")
            .trim('-')
        return cleaned.ifBlank { "skill-${System.currentTimeMillis() % 10000}" }.take(36)
    }

    /**
     * Разбирает содержимое SKILL.md (включая YAML frontmatter `---\nname: ...\ndescription: ...\n---`)
     * в готовый AgentSkill.
     */
    fun parseSkillMarkdown(
        rawMarkdown: String,
        fallbackName: String = "Импортированный скилл",
        source: String = "url",
        sourceUrl: String? = null
    ): AgentSkill {
        var text = rawMarkdown.replace("\r\n", "\n").trim()
        var fmName: String? = null
        var fmDesc: String? = null

        if (text.startsWith("---\n")) {
            val endIdx = text.indexOf("\n---", startIndex = 4)
            if (endIdx != -1) {
                val frontmatter = text.substring(4, endIdx)
                text = text.substring(endIdx + 4).trim()
                for (line in frontmatter.lines()) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("name:", ignoreCase = true) -> {
                            fmName = trimmed.substringAfter(":").trim().trim('"', '\'')
                        }
                        trimmed.startsWith("description:", ignoreCase = true) -> {
                            fmDesc = trimmed.substringAfter(":").trim().trim('"', '\'')
                        }
                    }
                }
            }
        }

        // Если имени нет во frontmatter — ищем первый Markdown-заголовок `# ...`
        val headingName = text.lineSequence()
            .firstOrNull { it.trim().startsWith("# ") }
            ?.trim()
            ?.removePrefix("# ")
            ?.trim()

        val finalName = fmName?.takeIf { it.isNotBlank() }
            ?: headingName?.takeIf { it.isNotBlank() }
            ?: fallbackName

        val finalDesc = fmDesc?.takeIf { it.isNotBlank() }
            ?: text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") }
                ?.take(140)
            ?: "Загруженный навык (SKILL.md)"

        val instructions = text.ifBlank { rawMarkdown.trim() }.take(12_000)

        return AgentSkill(
            id = "skill_${UUID.randomUUID().toString().take(8)}",
            name = finalName.take(64),
            slug = toSlug(fmName ?: finalName),
            description = finalDesc.take(220),
            instructions = instructions,
            source = source,
            sourceUrl = sourceUrl,
            enabled = true,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Загружает SKILL.md по URL или GitHub-ссылке.
     * Поддерживает:
     * - прямые ссылки raw.githubusercontent.com / *.md / *.txt
     * - github.com/owner/repo/blob/branch/path/SKILL.md
     * - github.com/owner/repo/tree/branch/path/to/skill (автоматически ищет SKILL.md)
     * - краткий формат `owner/repo/path/to/skill`
     */
    fun fetchSkillFromUrl(inputUrl: String): AgentSkill {
        val trimmed = inputUrl.trim()
        require(trimmed.isNotEmpty()) { "Укажите ссылку на SKILL.md или GitHub-репозиторий" }

        val candidates = buildCandidateRawUrls(trimmed)
        var lastError = "Не удалось загрузить файл"

        for (candidate in candidates) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(candidate).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "LLMNotes-Android/1.4.4")
                    setRequestProperty("Accept", "text/plain, text/markdown, */*")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    lastError = "HTTP $code ($candidate)"
                    continue
                }
                val bytes = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_REMOTE_SKILL_BYTES) {
                            out.write(buf, 0, n - (total - MAX_REMOTE_SKILL_BYTES))
                            break
                        }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val content = String(bytes, Charsets.UTF_8).trim()
                if (content.isBlank()) {
                    lastError = "Пустой ответ от $candidate"
                    continue
                }
                // Защита от случайного HTML 404/страницы GitHub вместо сырого Markdown
                val lowerHead = content.take(300).lowercase(Locale.US)
                if (lowerHead.contains("<!doctype html") || lowerHead.contains("<html")) {
                    lastError = "Получена HTML-страница вместо Markdown ($candidate). Укажите raw-ссылку на SKILL.md."
                    continue
                }

                val fallbackName = candidate
                    .substringBefore("?")
                    .trimEnd('/')
                    .split("/")
                    .filter { it.isNotBlank() && !it.equals("SKILL.md", ignoreCase = true) && !it.equals("README.md", ignoreCase = true) }
                    .lastOrNull()
                    ?.removeSuffix(".md")
                    ?.removeSuffix(".txt")
                    ?: "Скилл"

                return parseSkillMarkdown(
                    rawMarkdown = content,
                    fallbackName = fallbackName,
                    source = "url",
                    sourceUrl = candidate
                )
            } catch (e: Exception) {
                lastError = e.message ?: "Ошибка сети"
            } finally {
                conn?.disconnect()
            }
        }
        throw IllegalStateException(lastError)
    }

    /**
     * Загружает актуальный список скиллов из официального репозитория GitHub `anthropics/skills`,
     * объединяя его с встроенным curated-списком.
     */
    fun fetchOnlineCatalog(): List<RemoteSkillEntry> {
        val fetched = mutableListOf<RemoteSkillEntry>()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://api.github.com/repos/anthropics/skills/contents/skills").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "LLMNotes-Android/1.4.4")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (item.optString("type") != "dir") continue
                    val dirName = item.optString("name").trim()
                    if (dirName.isBlank()) continue
                    val curatedMatch = CURATED_REMOTE_SKILLS.find { it.slug == dirName }
                    fetched.add(
                        curatedMatch ?: RemoteSkillEntry(
                            slug = dirName,
                            title = dirName.replace('-', ' ').replaceFirstChar { it.uppercaseChar() },
                            description = "Официальный SKILL.md из репозитория anthropics/skills",
                            rawUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/$dirName/SKILL.md",
                            repoLabel = "anthropics/skills"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось обновить каталог GitHub skills: ${e.message}")
        } finally {
            conn?.disconnect()
        }

        if (fetched.isEmpty()) return CURATED_REMOTE_SKILLS
        val existingSlugs = fetched.map { it.slug }.toSet()
        return fetched + CURATED_REMOTE_SKILLS.filter { it.slug !in existingSlugs }
    }

    /**
     * Импортирует скилл из локального .md / .txt файла по Android SAF URI.
     */
    fun importSkillFromUri(context: Context, uri: Uri, displayName: String? = null): AgentSkill {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            String(bytes.take(MAX_REMOTE_SKILL_BYTES).toByteArray(), Charsets.UTF_8)
        } ?: throw IllegalStateException("Не удалось прочитать файл")

        require(text.isNotBlank()) { "Выбранный файл пуст" }
        val fallbackName = displayName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Локальный скилл"

        return parseSkillMarkdown(
            rawMarkdown = text,
            fallbackName = fallbackName,
            source = "file",
            sourceUrl = displayName
        )
    }

    /**
     * Формирует блок активных скиллов для включения в системный промпт.
     * Также поддерживает одноразовый вызов `/slug запрос` прямо в тексте сообщения.
     */
    fun resolveSkillsForPrompt(
        allSkills: List<AgentSkill>,
        userPrompt: String,
        isLocalCompact: Boolean
    ): ResolvedSkills {
        val active = linkedMapOf<String, AgentSkill>()
        for (skill in allSkills) {
            if (skill.enabled) {
                active[skill.id] = skill
            }
        }

        var cleanedPrompt = userPrompt
        val trimmed = userPrompt.trimStart()
        if (trimmed.startsWith("/")) {
            val firstToken = trimmed.substring(1).takeWhile { !it.isWhitespace() }.lowercase(Locale.US)
            val matched = allSkills.firstOrNull { it.slug.equals(firstToken, ignoreCase = true) }
            if (matched != null) {
                active[matched.id] = matched
                cleanedPrompt = trimmed.substring(1 + firstToken.length).trimStart().ifBlank { userPrompt }
            }
        }

        val resolvedList = active.values.toList()
        if (resolvedList.isEmpty()) return ResolvedSkills("", emptyList(), cleanedPrompt)

        val maxTotalChars = if (isLocalCompact) 2400 else 10_000
        val sb = StringBuilder()
        sb.append("[ПОДКЛЮЧЁННЫЕ СКИЛЛЫ (SKILLS)]\n")
        sb.append("Следуй инструкциям активных скиллов при выполнении задачи пользователя:\n")
        for (skill in resolvedList) {
            val block = "\n### Skill: ${skill.name} (/${skill.slug})\n${skill.instructions.trim()}\n"
            if (sb.length + block.length > maxTotalChars) {
                val remaining = (maxTotalChars - sb.length).coerceAtLeast(0)
                if (remaining > 120) {
                    sb.append(block.take(remaining)).append("\n...[сокращено]\n")
                }
                break
            }
            sb.append(block)
        }
        return ResolvedSkills(sb.toString().trim(), resolvedList, cleanedPrompt)
    }

    private fun buildCandidateRawUrls(rawInput: String): List<String> {
        val input = rawInput.trim()

        // 1. https://github.com/owner/repo/blob/branch/path/file.md
        val blobRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$")
        blobRegex.matchEntire(input)?.let { m ->
            val (owner, repo, branch, path) = m.destructured
            return listOf("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
        }

        // 2. https://github.com/owner/repo/tree/branch/path
        val treeRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+)$")
        treeRegex.matchEntire(input)?.let { m ->
            val (owner, repo, branch, rawPath) = m.destructured
            val path = rawPath.trimEnd('/')
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$path/SKILL.md",
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$path/skill.md",
                "https://raw.githubusercontent.com/$owner/$repo/$branch/$path/README.md"
            )
        }

        // 3. https://github.com/owner/repo (корень репозитория)
        val repoRegex = Regex("^https?://github\\.com/([^/]+)/([^/]+)/?$")
        repoRegex.matchEntire(input)?.let { m ->
            val (owner, repo) = m.destructured
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/main/SKILL.md",
                "https://raw.githubusercontent.com/$owner/$repo/master/SKILL.md",
                "https://raw.githubusercontent.com/$owner/$repo/main/README.md"
            )
        }

        // 4. https://skills.sh/owner/repo/skill-name
        val skillsShRegex = Regex("^https?://(?:www\\.)?skills\\.sh/([^/]+)/([^/]+)/([^/?#]+).*$")
        skillsShRegex.matchEntire(input)?.let { m ->
            val (owner, repo, skillSlug) = m.destructured
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/main/skills/$skillSlug/SKILL.md",
                "https://raw.githubusercontent.com/$owner/$repo/main/$skillSlug/SKILL.md",
                "https://raw.githubusercontent.com/$owner/$repo/master/skills/$skillSlug/SKILL.md",
                "https://raw.githubusercontent.com/$owner/$repo/main/skills/$skillSlug/skill.md"
            )
        }

        // 5. Полный HTTP/HTTPS URL
        if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
            return if (input.endsWith(".md", ignoreCase = true) || input.endsWith(".txt", ignoreCase = true)) {
                listOf(input)
            } else {
                val base = input.trimEnd('/')
                listOf(input, "$base/SKILL.md", "$base/skill.md")
            }
        }

        // 6. Сокращённая запись owner/repo/path или owner/repo
        val parts = input.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val owner = parts[0]
            val repo = parts[1]
            val rest = parts.drop(2).joinToString("/")
            return if (rest.isBlank()) {
                listOf(
                    "https://raw.githubusercontent.com/$owner/$repo/main/SKILL.md",
                    "https://raw.githubusercontent.com/$owner/$repo/master/SKILL.md"
                )
            } else if (rest.endsWith(".md", ignoreCase = true)) {
                listOf(
                    "https://raw.githubusercontent.com/$owner/$repo/main/$rest",
                    "https://raw.githubusercontent.com/$owner/$repo/master/$rest"
                )
            } else {
                listOf(
                    "https://raw.githubusercontent.com/$owner/$repo/main/$rest/SKILL.md",
                    "https://raw.githubusercontent.com/$owner/$repo/main/skills/$rest/SKILL.md",
                    "https://raw.githubusercontent.com/$owner/$repo/master/$rest/SKILL.md"
                )
            }
        }

        return listOf("https://$input")
    }
}
