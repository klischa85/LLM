#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cstring>
#include <cerrno>
#include <cstdint>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model   * g_model   = nullptr;
static llama_context * g_context = nullptr;
static std::atomic<bool> g_stop_requested(false);
static std::mutex g_llama_mutex;
static std::string g_last_error = "";

static void llama_log_callback_capture(enum ggml_log_level level, const char * text, void * user_data) {
    (void) user_data;
    if (text) {
        if (level == GGML_LOG_LEVEL_ERROR) {
            LOGE("%s", text);
            g_last_error += text;
        } else if (level == GGML_LOG_LEVEL_WARN) {
            LOGE("%s", text);
        } else {
            LOGI("%s", text);
        }
    }
}

/**
 * Безопасное преобразование стандартной UTF-8 строки (включая 4-байтные эмодзи)
 * в JNI jstring через UTF-16 (env->NewString), избегая крашей ART в NewStringUTF.
 */
static jstring utf8_to_jstring(JNIEnv *env, const std::string &utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());

    size_t i = 0;
    const size_t n = utf8.size();
    while (i < n) {
        uint8_t c = static_cast<uint8_t>(utf8[i]);
        uint32_t cp = 0;
        size_t extra = 0;

        if (c < 0x80) {
            cp = c;
            extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F;
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F;
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07;
            extra = 3;
        } else {
            // Невалидный байт — заменяем на U+FFFD
            utf16.push_back(0xFFFD);
            i++;
            continue;
        }

        if (i + extra >= n) {
            utf16.push_back(0xFFFD);
            break;
        }

        bool valid = true;
        for (size_t k = 1; k <= extra; ++k) {
            uint8_t cc = static_cast<uint8_t>(utf8[i + k]);
            if ((cc & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3F);
        }

        if (!valid) {
            utf16.push_back(0xFFFD);
            i++;
            continue;
        }

        i += extra + 1;

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            utf16.push_back(0xFFFD);
        }
    }

    return env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
}

/**
 * Безопасное преобразование JNI jstring (UTF-16) в стандартную UTF-8 строку (включая 4-байтные эмодзи).
 */
static std::string jstring_to_utf8(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    if (!chars) return "";
    jsize len = env->GetStringLength(jstr);

    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);

    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && (i + 1) < len) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                i++;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(jstr, chars);
    return out;
}

/**
 * Вычисляет длину завершённого валидного UTF-8 префикса строки, оставляя неполную
 * многобайтовую последовательность в хвосте для следующего токена.
 */
static size_t valid_utf8_prefix_len(const std::string &s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        uint8_t c = static_cast<uint8_t>(s[i]);
        size_t need = 1;
        if (c < 0x80) {
            need = 1;
        } else if ((c & 0xE0) == 0xC0) {
            need = 2;
        } else if ((c & 0xF0) == 0xE0) {
            need = 3;
        } else if ((c & 0xF8) == 0xF0) {
            need = 4;
        } else {
            // Одиночный невалидный continuation-байт — пропускаем
            i++;
            continue;
        }
        if (i + need > n) {
            break; // Незавершённая последовательность в конце — ждём следующий токен
        }
        i += need;
    }
    return i;
}

static bool ends_with(const std::string &str, const std::string &suffix) {
    return str.size() >= suffix.size() &&
           str.compare(str.size() - suffix.size(), suffix.size(), suffix) == 0;
}

static void batch_add_token(struct llama_batch & batch, llama_token id, llama_pos pos, const std::vector<llama_seq_id> & seq_ids, bool logits) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = static_cast<int32_t>(seq_ids.size());
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits  [batch.n_tokens] = logits;
    batch.n_tokens++;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_klischa_llmnotes_LlamaBridge_nativeInit(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    LOGI("Инициализация llama_backend_init()");
    llama_backend_init();
    llama_log_set(llama_log_callback_capture, nullptr);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_klischa_llmnotes_LlamaBridge_nativeLoadModel(
    JNIEnv *env,
    jobject thiz,
    jstring model_path,
    jint n_ctx,
    jint n_threads
) {
    (void) thiz;
    g_stop_requested.store(true);
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    g_stop_requested.store(false);
    g_last_error.clear();

    std::string path_str = jstring_to_utf8(env, model_path);
    if (path_str.empty()) {
        return utf8_to_jstring(env, "Не удалось прочитать путь к файлу модели");
    }
    const char *path = path_str.c_str();

    LOGI("Загрузка модели из: %s (контекст: %d, потоков: %d)", path, n_ctx, n_threads);

    // 1. Проверка существования и размера файла
    struct stat st;
    bool stat_ok = false;
    if (strncmp(path, "/proc/self/fd/", 14) == 0) {
        int fd = atoi(path + 14);
        stat_ok = (fstat(fd, &st) == 0);
    } else {
        stat_ok = (stat(path, &st) == 0);
    }

    if (!stat_ok) {
        FILE * f = fopen(path, "rb");
        if (!f) {
            std::string err = "Не удалось открыть файл: " + std::string(strerror(errno));
            LOGE("%s (%s)", err.c_str(), path);
            return utf8_to_jstring(env, err);
        }
        fseek(f, 0, SEEK_END);
        st.st_size = ftell(f);
        fclose(f);
    }

    if (st.st_size < 1024 * 1024) {
        std::string err = "Файл поврежден или недокачан (размер: " + std::to_string(st.st_size / 1024) + " КБ)";
        LOGE("%s", err.c_str());
        return utf8_to_jstring(env, err);
    }

    // Освобождение ранее загруженных ресурсов
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    // 2. Загрузка модели (use_mmap = true, при ошибке — use_mmap = false)
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    g_model = llama_load_model_from_file(path, model_params);
    if (!g_model) {
        LOGI("Загрузка с use_mmap=true не удалась, повторная попытка с use_mmap=false...");
        g_last_error.clear();
        model_params.use_mmap = false;
        g_model = llama_load_model_from_file(path, model_params);
    }

    if (!g_model) {
        std::string err = "Ошибка llama_load_model_from_file: " + (g_last_error.empty() ? "неверный формат GGUF или поврежденный файл" : g_last_error);
        LOGE("%s", err.c_str());
        return utf8_to_jstring(env, err);
    }

    // 3. Создание контекста с адаптивным размером (4096 -> 2048 -> 1024 -> 512)
    int target_ctx = n_ctx > 0 ? n_ctx : 4096;
    std::vector<int> ctx_attempts;
    ctx_attempts.push_back(target_ctx);
    for (int fallback_ctx : { 2048, 1024, 512 }) {
        if (fallback_ctx < target_ctx) {
            ctx_attempts.push_back(fallback_ctx);
        }
    }

    for (int ctx_size : ctx_attempts) {
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = ctx_size;
        ctx_params.n_batch = 512;
        ctx_params.n_threads = n_threads > 0 ? n_threads : 2;
        ctx_params.n_threads_batch = n_threads > 0 ? n_threads : 2;

        g_context = llama_new_context_with_model(g_model, ctx_params);
        if (g_context) {
            LOGI("Контекст llama успешно создан (n_ctx = %d)!", ctx_size);
            break;
        }
    }

    if (!g_context) {
        llama_free_model(g_model);
        g_model = nullptr;
        std::string err = "Недостаточно памяти для создания контекста: " + g_last_error;
        LOGE("%s", err.c_str());
        return utf8_to_jstring(env, err);
    }

    LOGI("Модель успешно загружена в память!");
    return utf8_to_jstring(env, "");
}

JNIEXPORT void JNICALL
Java_com_klischa_llmnotes_LlamaBridge_nativeStop(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    LOGI("Запрос на прерывание инференса");
    g_stop_requested.store(true);
}

JNIEXPORT void JNICALL
Java_com_klischa_llmnotes_LlamaBridge_nativeUnload(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    LOGI("Выгрузка модели и освобождение памяти");
    g_stop_requested.store(true);
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_klischa_llmnotes_LlamaBridge_nativeFormatPrompt(
    JNIEnv *env,
    jobject thiz,
    jstring system_prompt_str,
    jstring user_prompt_str
) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_model) return user_prompt_str ? user_prompt_str : utf8_to_jstring(env, "");

    std::string sys_str = jstring_to_utf8(env, system_prompt_str);
    std::string usr_str = jstring_to_utf8(env, user_prompt_str);

    std::vector<llama_chat_message> chat;
    if (!sys_str.empty()) {
        chat.push_back({"system", sys_str.c_str()});
    }
    if (!usr_str.empty()) {
        chat.push_back({"user", usr_str.c_str()});
    }

    if (chat.empty()) {
        return utf8_to_jstring(env, "");
    }

    int32_t req_len = llama_chat_apply_template(g_model, nullptr, chat.data(), chat.size(), true, nullptr, 0);
    if (req_len > 0) {
        std::vector<char> buf(req_len + 1, 0);
        int32_t res_len = llama_chat_apply_template(g_model, nullptr, chat.data(), chat.size(), true, buf.data(), buf.size());
        if (res_len > 0) {
            std::string templated(buf.data(), res_len);

            // Если шаблон опустил system prompt (как в DeepSeek-R1 / Gemma):
            if (!sys_str.empty() && templated.find(sys_str) == std::string::npos) {
                LOGI("Шаблон модели опустил system prompt. Внедряем системную инструкцию.");
                size_t user_chatml = templated.find("<|im_start|>user");
                size_t user_deepseek = templated.find("<｜User｜>");
                if (user_chatml != std::string::npos) {
                    templated.insert(user_chatml, "<|im_start|>system\n" + sys_str + "<|im_end|>\n");
                } else if (user_deepseek != std::string::npos) {
                    size_t insert_pos = user_deepseek + strlen("<｜User｜>");
                    templated.insert(insert_pos, sys_str + "\n\n");
                } else {
                    templated = sys_str + "\n\n" + templated;
                }
            }

            return utf8_to_jstring(env, templated);
        }
    }

    std::string fallback = "";
    if (!sys_str.empty()) {
        fallback += "<|im_start|>system\n" + sys_str + "<|im_end|>\n";
    }
    fallback += "<|im_start|>user\n" + usr_str + "<|im_end|>\n<|im_start|>assistant\n";

    return utf8_to_jstring(env, fallback);
}

JNIEXPORT jstring JNICALL
Java_com_klischa_llmnotes_LlamaBridge_nativeFormatChat(
    JNIEnv *env,
    jobject thiz,
    jobjectArray roles_arr,
    jobjectArray contents_arr
) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!roles_arr || !contents_arr) {
        return utf8_to_jstring(env, "");
    }

    int n_msgs = std::min(env->GetArrayLength(roles_arr), env->GetArrayLength(contents_arr));
    if (n_msgs <= 0) {
        return utf8_to_jstring(env, "");
    }

    std::vector<std::string> roles(n_msgs);
    std::vector<std::string> contents(n_msgs);
    std::vector<llama_chat_message> chat(n_msgs);
    std::string sys_content = "";

    for (int i = 0; i < n_msgs; i++) {
        jstring r_str = (jstring)env->GetObjectArrayElement(roles_arr, i);
        jstring c_str = (jstring)env->GetObjectArrayElement(contents_arr, i);

        roles[i] = jstring_to_utf8(env, r_str);
        contents[i] = jstring_to_utf8(env, c_str);

        if (roles[i] == "system" && sys_content.empty()) {
            sys_content = contents[i];
        }

        if (r_str) env->DeleteLocalRef(r_str);
        if (c_str) env->DeleteLocalRef(c_str);

        chat[i].role = roles[i].c_str();
        chat[i].content = contents[i].c_str();
    }

    if (g_model) {
        int32_t req_len = llama_chat_apply_template(g_model, nullptr, chat.data(), chat.size(), true, nullptr, 0);
        if (req_len > 0) {
            std::vector<char> buf(req_len + 1, 0);
            int32_t res_len = llama_chat_apply_template(g_model, nullptr, chat.data(), chat.size(), true, buf.data(), buf.size());
            if (res_len > 0) {
                std::string templated(buf.data(), res_len);

                // Если шаблон опустил system prompt (как в DeepSeek-R1 / Gemma):
                if (!sys_content.empty() && templated.find(sys_content) == std::string::npos) {
                    LOGI("Шаблон модели опустил system prompt. Внедряем системную инструкцию.");
                    size_t user_chatml = templated.find("<|im_start|>user");
                    size_t user_deepseek = templated.find("<｜User｜>");
                    if (user_chatml != std::string::npos) {
                        templated.insert(user_chatml, "<|im_start|>system\n" + sys_content + "<|im_end|>\n");
                    } else if (user_deepseek != std::string::npos) {
                        size_t insert_pos = user_deepseek + strlen("<｜User｜>");
                        templated.insert(insert_pos, sys_content + "\n\n");
                    } else {
                        templated = sys_content + "\n\n" + templated;
                    }
                }

                return utf8_to_jstring(env, templated);
            }
        }
    }

    // Резервный формат ChatML при отсутствии шаблона в метаданных модели
    std::string fallback = "";
    for (int i = 0; i < n_msgs; i++) {
        fallback += "<|im_start|>" + roles[i] + "\n" + contents[i] + "<|im_end|>\n";
    }
    fallback += "<|im_start|>assistant\n";

    return utf8_to_jstring(env, fallback);
}

JNIEXPORT jstring JNICALL
Java_com_klischa_llmnotes_LlamaBridge_nativeGenerate(
    JNIEnv *env,
    jobject thiz,
    jstring prompt_str,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jobject callback_obj
) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_model || !g_context) {
        LOGE("Модель не инициализирована!");
        return utf8_to_jstring(env, "Ошибка: модель не загружена.");
    }

    std::string prompt = jstring_to_utf8(env, prompt_str);
    if (prompt.empty()) return utf8_to_jstring(env, "");

    g_stop_requested.store(false);

    jclass callback_class = nullptr;
    jmethodID on_token_method = nullptr;
    if (callback_obj != nullptr) {
        callback_class = env->GetObjectClass(callback_obj);
        on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    }

    // Токенизация входного промпта (add_special = false, чтобы не дублировать BOS)
    int n_prompt_tokens = -llama_tokenize(g_model, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
    if (n_prompt_tokens <= 0) {
        if (callback_class) env->DeleteLocalRef(callback_class);
        return utf8_to_jstring(env, "Ошибка токенизации");
    }

    std::vector<llama_token> prompt_tokens(n_prompt_tokens);
    if (llama_tokenize(g_model, prompt.c_str(), static_cast<int32_t>(prompt.size()), prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), false, true) < 0) {
        if (callback_class) env->DeleteLocalRef(callback_class);
        return utf8_to_jstring(env, "Ошибка токенизации");
    }

    // Защита от переполнения контекстного окна n_ctx
    const int n_ctx = static_cast<int>(llama_n_ctx(g_context));
    const int max_prompt_tokens = std::max(64, n_ctx - 32);
    if (static_cast<int>(prompt_tokens.size()) > max_prompt_tokens) {
        LOGI("Промпт (%zu токенов) превышает лимит контекста (%d). Обрезаем старую часть.", prompt_tokens.size(), max_prompt_tokens);
        // Сохраняем BOS/начало (64 токена) и самый свежий хвост
        std::vector<llama_token> trimmed;
        trimmed.reserve(max_prompt_tokens);
        int keep_head = std::min(64, max_prompt_tokens / 4);
        int keep_tail = max_prompt_tokens - keep_head;
        trimmed.insert(trimmed.end(), prompt_tokens.begin(), prompt_tokens.begin() + keep_head);
        trimmed.insert(trimmed.end(), prompt_tokens.end() - keep_tail, prompt_tokens.end());
        prompt_tokens.swap(trimmed);
    }

    // Подготовка KV-кэша и батчевое декодирование промпта по 512 токенов (исправляет SIGSEGV при длинном контексте!)
    llama_kv_cache_clear(g_context);
    const int n_batch = 512;
    struct llama_batch batch = llama_batch_init(n_batch, 0, 1);

    for (size_t start = 0; start < prompt_tokens.size(); start += n_batch) {
        if (g_stop_requested.load()) {
            llama_batch_free(batch);
            if (callback_class) env->DeleteLocalRef(callback_class);
            return utf8_to_jstring(env, "");
        }
        batch.n_tokens = 0;
        size_t end = std::min(prompt_tokens.size(), start + static_cast<size_t>(n_batch));
        for (size_t i = start; i < end; ++i) {
            bool need_logits = (i == prompt_tokens.size() - 1);
            batch_add_token(batch, prompt_tokens[i], static_cast<llama_pos>(i), { 0 }, need_logits);
        }
        if (llama_decode(g_context, batch) != 0) {
            LOGE("Ошибка: сбой llama_decode для чанка промпта [%zu..%zu)", start, end);
            llama_batch_free(batch);
            if (callback_class) env->DeleteLocalRef(callback_class);
            return utf8_to_jstring(env, "Ошибка декодирования промпта");
        }
    }

    std::string full_response = "";
    std::string utf8_pending = "";
    int n_cur = static_cast<int>(prompt_tokens.size());
    int generated_count = 0;
    int32_t n_vocab = llama_n_vocab(g_model);

    std::vector<llama_token> last_tokens;
    last_tokens.reserve(64);

    // Reuse sampling storage between tokens: allocating a full vocabulary vector on every
    // decode step caused severe allocator overhead on Android CPUs.
    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);

    // Цикл декодирования токенов (с защитой n_cur < n_ctx)
    while (generated_count < max_tokens && n_cur < n_ctx && !g_stop_requested.load()) {
        float * logits = llama_get_logits_ith(g_context, batch.n_tokens - 1);
        if (!logits) {
            LOGE("Ошибка: logits == nullptr");
            break;
        }

        candidates.clear();
        for (llama_token token_id = 0; token_id < n_vocab; ++token_id) {
            candidates.emplace_back(llama_token_data{token_id, logits[token_id], 0.0f});
        }
        llama_token_data_array candidates_p = { candidates.data(), candidates.size(), false };

        if (!last_tokens.empty()) {
            llama_sample_repetition_penalties(
                g_context,
                &candidates_p,
                last_tokens.data(),
                last_tokens.size(),
                1.08f,
                0.0f,
                0.0f
            );
        }

        // Prune the vocabulary before nucleus sampling. Without top-k, top-p sorts the
        // entire 100k+ vocabulary for every token, which can make low-end Android devices
        // appear frozen for tens of minutes. Keep 40 candidates for quality/speed balance.
        llama_sample_top_k(g_context, &candidates_p, 40, 1);
        llama_sample_top_p(g_context, &candidates_p, top_p, 1);
        llama_sample_temp(g_context, &candidates_p, temperature);
        llama_token new_token_id = llama_sample_token(g_context, &candidates_p);

        // 1. Проверка на стандартный EOG / EOS / EOT токен
        if (llama_token_is_eog(g_model, new_token_id) ||
            new_token_id == llama_token_eos(g_model) ||
            new_token_id == llama_token_eot(g_model)) {
            LOGI("Генерация завершена: EOG/EOS токен %d", new_token_id);
            break;
        }

        char piece_buf[256];
        int n_chars = llama_token_to_piece(g_model, new_token_id, piece_buf, sizeof(piece_buf), 0, true);
        if (n_chars <= 0) {
            batch.n_tokens = 0;
            batch_add_token(batch, new_token_id, n_cur, { 0 }, true);
            if (llama_decode(g_context, batch) != 0) break;
            n_cur++;
            generated_count++;
            continue;
        }

        std::string piece(piece_buf, n_chars);

        // 2. Строгая проверка на служебные стоп-токены разметки диалога
        if (piece.find("<|") != std::string::npos ||
            piece.find("<｜") != std::string::npos ||
            piece == "</s>" ||
            piece == "<end_of_turn>" ||
            piece == "[|im_end|]") {
            LOGI("Генерация завершена по стоп-токену в piece: '%s'", piece.c_str());
            break;
        }

        // 3. Проверка на случай разбиения служебного тега между токенами в full_response
        full_response += piece;
        size_t cut_pos = std::string::npos;
        size_t p1 = full_response.rfind("<|");
        if (p1 != std::string::npos && full_response.size() >= piece.size() + 4 && p1 >= full_response.size() - piece.size() - 4) {
            cut_pos = p1;
        } else if (p1 != std::string::npos && full_response.size() < piece.size() + 4) {
            cut_pos = p1;
        }
        size_t p2 = full_response.rfind("<｜");
        if (p2 != std::string::npos && (full_response.size() < piece.size() + 8 || p2 >= full_response.size() - piece.size() - 8)) {
            cut_pos = (cut_pos == std::string::npos) ? p2 : std::min(cut_pos, p2);
        }

        if (cut_pos != std::string::npos) {
            full_response = full_response.substr(0, cut_pos);
            LOGI("Отсечен стоп-маркер в хвосте ответа на позиции %zu", cut_pos);
            break;
        }

        // 4. Безопасная потоковая отправка полных UTF-8 символов подписчику (Compose UI)
        utf8_pending += piece;
        size_t emit_len = valid_utf8_prefix_len(utf8_pending);
        if (emit_len > 0) {
            std::string ready_chunk = utf8_pending.substr(0, emit_len);
            utf8_pending.erase(0, emit_len);
            if (callback_obj != nullptr && on_token_method != nullptr) {
                jstring piece_jstr = utf8_to_jstring(env, ready_chunk);
                env->CallVoidMethod(callback_obj, on_token_method, piece_jstr);
                env->DeleteLocalRef(piece_jstr);
            }
        }

        // 5. В буфер штрафов добавляем только смысловые токены
        if (!llama_token_is_control(g_model, new_token_id)) {
            if (last_tokens.size() >= 64) {
                last_tokens.erase(last_tokens.begin());
            }
            last_tokens.push_back(new_token_id);
        }

        batch.n_tokens = 0;
        batch_add_token(batch, new_token_id, n_cur, { 0 }, true);

        if (llama_decode(g_context, batch) != 0) {
            LOGE("Ошибка: сбой декодирования токена");
            break;
        }

        n_cur++;
        generated_count++;
    }

    // Безопасно удаляем ТОЛЬКО реальные висячие символы служебных тегов в хвосте (<, |, ｜),
    // НЕ затрагивая кириллицу и обычные UTF-8 символы!
    const std::string fullwidth_bar = "\xEF\xBD\x9C"; // '｜'
    while (!full_response.empty()) {
        if (full_response.back() == '<') {
            full_response.pop_back();
        } else if (full_response.back() == '|' &&
                   full_response.size() >= 2 && full_response[full_response.size() - 2] == '<') {
            full_response.erase(full_response.size() - 2);
        } else if (ends_with(full_response, fullwidth_bar) &&
                   full_response.size() > fullwidth_bar.size() &&
                   full_response[full_response.size() - fullwidth_bar.size() - 1] == '<') {
            full_response.erase(full_response.size() - fullwidth_bar.size() - 1);
        } else {
            break;
        }
    }

    llama_batch_free(batch);
    if (callback_class) env->DeleteLocalRef(callback_class);
    return utf8_to_jstring(env, full_response);
}

} // extern "C"
