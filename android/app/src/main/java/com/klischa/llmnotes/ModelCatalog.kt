package com.klischa.llmnotes

/**
 * Каталог рекомендованных GGUF-моделей.
 * Ссылки ведут напрямую на файл в huggingface.co (без авторизации).
 * Файлы кладутся в filesDir/models — оттуда LLMViewModel подхватывает их автоматически.
 */
data class ModelInfo(
    val id: String,
    val title: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val params: String,
    val quant: String,
    val license: String,
    val minRamGb: Int,
    val description: String,
    val recommended: Boolean = false
) {
    val sizeText: String get() = ModelCatalog.formatSize(sizeBytes)
}

object ModelCatalog {

    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = "qwen25-1.5b-q4km",
            title = "Qwen2.5 1.5B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            params = "1.5B",
            quant = "Q4_K_M",
            license = "Apache 2.0",
            minRamGb = 3,
            description = "Базовая модель проекта. Хороший русский, ~8-11 tok/s на Helio G99, ~1.2 ГБ ОЗУ.",
            recommended = true
        ),
        ModelInfo(
            id = "qwen25-0.5b-q4km",
            title = "Qwen2.5 0.5B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491_400_032L,
            params = "0.5B",
            quant = "Q4_K_M",
            license = "Apache 2.0",
            minRamGb = 2,
            description = "Самая лёгкая и быстрая. Для слабых устройств и быстрых ответов, качество ниже."
        ),
        ModelInfo(
            id = "qwen25-1.5b-q8",
            title = "Qwen2.5 1.5B Instruct (Q8)",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q8_0.gguf",
            fileName = "qwen2.5-1.5b-instruct-q8_0.gguf",
            sizeBytes = 1_894_532_128L,
            params = "1.5B",
            quant = "Q8_0",
            license = "Apache 2.0",
            minRamGb = 4,
            description = "Точнее Q4 при том же размере модели, но медленнее и требует больше ОЗУ."
        ),
        ModelInfo(
            id = "qwen25-3b-q4km",
            title = "Qwen2.5 3B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768L,
            params = "3B",
            quant = "Q4_K_M",
            license = "Qwen Research",
            minRamGb = 6,
            description = "Заметно умнее 1.5B, но на 8 ГБ ОЗУ работает впритык и медленнее (~4-6 tok/s)."
        ),
        ModelInfo(
            id = "llama32-1b-q4km",
            title = "Llama 3.2 1B Instruct",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
            params = "1B",
            quant = "Q4_K_M",
            license = "Llama 3.2 Community",
            minRamGb = 3,
            description = "Быстрая альтернатива. Английский лучше, русский слабее Qwen."
        ),
        ModelInfo(
            id = "gemma2-2b-q4km",
            title = "Gemma 2 2B Instruct",
            url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            sizeBytes = 1_708_582_752L,
            params = "2B",
            quant = "Q4_K_M",
            license = "Gemma Terms",
            minRamGb = 4,
            description = "Хорошо следует инструкциям, но словарь крупнее — русский дороже по токенам."
        ),
        ModelInfo(
            id = "phi35-mini-q4km",
            title = "Phi-3.5 Mini Instruct",
            url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            sizeBytes = 2_393_232_672L,
            params = "3.8B",
            quant = "Q4_K_M",
            license = "MIT",
            minRamGb = 6,
            description = "Сильна в рассуждениях, но крупная и русский заметно слабее. Только для 8+ ГБ ОЗУ."
        )
    )

    fun byId(id: String): ModelInfo? = models.firstOrNull { it.id == id }

    /** Модели, которые влезают в устройство с указанным объёмом ОЗУ. */
    fun forRam(ramGb: Int): List<ModelInfo> = models.filter { it.minRamGb <= ramGb }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "?"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.2f ГБ", mb / 1024.0)
        else String.format(java.util.Locale.US, "%.0f МБ", mb)
    }
}
