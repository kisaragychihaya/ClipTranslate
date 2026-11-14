// JNI bridge: dev.cliptranslate.local.LlamaBridge -> llama.cpp C API.
//
// Built by AGP/CMake with the Android NDK; llama.cpp itself is pulled in by
// CMakeLists.txt (FetchContent, pinned release tag b12375). Everything below is
// written against the API of that exact tag, where the old names are gone:
//   llama_model_load_from_file  (was llama_load_model_from_file)
//   llama_init_from_model       (was llama_new_context_with_model)
//   llama_memory_clear          (was llama_kv_self_clear)
//   llama_model_get_vocab       (vocab is no longer a context member)
// llama_model_params/llama_context_params are also field-unstable (no
// use_mmap/use_mlock anymore, `load_mode` instead), so both structs are taken
// from llama_model_default_params()/llama_context_default_params() and only the
// fields we care about are overwritten.

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <exception>
#include <mutex>
#include <new>
#include <string>
#include <unordered_set>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * kLogTag = "CliptranslateJNI";
constexpr const char * kErrTooLong = "ERR_TOO_LONG";
constexpr const char * kEndOfTurn = "<end_of_turn>";
constexpr int32_t kNBatch = 512;

/** 一次 nativeLoad 的全部 native 状态，整体作为 jlong 句柄交给 Kotlin。 */
struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    /** `<end_of_turn>` 的单 token id；模型里不是单 token 时为 LLAMA_TOKEN_NULL。 */
    llama_token stop_token = LLAMA_TOKEN_NULL;
    int32_t n_ctx = 0;
    int32_t n_batch = kNBatch;
};

// 句柄注册表：handle 是从 Kotlin 绕回来的裸 jlong，用它反查一下能避免
// 陈旧/伪造的 handle 变成野指针崩溃（重复 unload、加载失败后误用等）。
// Kotlin 侧已保证推理调用串行，所以这里只保护表本身，不保护 ctx。
std::mutex g_mutex;
std::unordered_set<Session *> g_sessions;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)

void throw_runtime(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls == nullptr) {
        // FindClass 自己已经挂了异常（OOM 之类），保持原样即可。
        return;
    }
    env->ThrowNew(cls, message.c_str());
    env->DeleteLocalRef(cls);
}

/** 把 std::string 的 UTF-8 字节交给 Java，用 String(byte[], "UTF-8") 构造。 */
jstring to_jstring(JNIEnv * env, const std::string & utf8) {
    // 不用 NewStringUTF：JNI 的“UTF-8”是 modified UTF-8，模型输出里的 4 字节
    // 序列（emoji、部分生僻字）在 modified UTF-8 里不合法，ART 的 CheckJNI
    // 会直接报 “input is not valid Modified UTF-8”；而 String(byte[], "UTF-8")
    // 是标准 UTF-8 解码，任何字节序列都安全。
    jclass cls = env->FindClass("java/lang/String");
    if (cls == nullptr) {
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    if (ctor == nullptr) {
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    const jsize length = static_cast<jsize>(utf8.size());
    jbyteArray bytes = env->NewByteArray(length);
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = nullptr;
    if (bytes != nullptr && charset != nullptr) {
        if (length > 0) {
            env->SetByteArrayRegion(
                    bytes, 0, length, reinterpret_cast<const jbyte *>(utf8.data()));
        }
        result = static_cast<jstring>(env->NewObject(cls, ctor, bytes, charset));
    }
    if (charset != nullptr) env->DeleteLocalRef(charset);
    if (bytes != nullptr) env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(cls);
    return result;
}

/**
 * jstring -> 真正的 UTF-8。
 *
 * GetStringUTFChars 给的是 modified UTF-8（CESU-8）：emoji 会变成 6 字节代理对、
 * 内嵌 NUL 会变成 2 字节，llama 的 tokenizer 只认标准 UTF-8，遇上一律当坏字节
 * 处理。所以自己走一遍 UTF-16 -> UTF-8。
 */
std::string from_jstring(JNIEnv * env, jstring text) {
    const jsize length = env->GetStringLength(text);
    if (length <= 0) {
        return std::string();
    }
    const jchar * chars = env->GetStringChars(text, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000u + ((cp - 0xD800u) << 10) + (low - 0xDC00u);
                ++i;
            }
        }
        if (cp < 0x80u) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800u) {
            out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else if (cp < 0x10000u) {
            out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else {
            out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        }
    }
    env->ReleaseStringChars(text, chars);
    return out;
}

/** llama_tokenize 的两段式调用；失败时写 err 返回 false。 */
bool tokenize(
        const llama_vocab * vocab,
        const std::string & text,
        bool add_special,
        bool parse_special,
        std::vector<llama_token> & out,
        std::string & err) {
    out.clear();
    const int32_t text_len = static_cast<int32_t>(text.size());
    const int32_t needed = llama_tokenize(
            vocab, text.c_str(), text_len, nullptr, 0, add_special, parse_special);
    if (needed == INT32_MIN) {
        err = "ERR_TOKENIZE: prompt is too large to tokenize";
        return false;
    }
    // 负数表示“需要这么多 token”；这里顺手多留一点余量。
    const int32_t capacity = (needed < 0 ? -needed : needed) + 8;
    out.resize(static_cast<size_t>(capacity));
    const int32_t produced = llama_tokenize(
            vocab, text.c_str(), text_len, out.data(), capacity, add_special, parse_special);
    if (produced < 0) {
        err = "ERR_TOKENIZE: llama_tokenize failed (ret=" + std::to_string(produced) + ")";
        return false;
    }
    out.resize(static_cast<size_t>(produced));
    return true;
}

/** 把 token 解码成文本片段追加到 out；special=false 让控制 token 不落进译文。 */
bool append_piece(const llama_vocab * vocab, llama_token token, std::string & out, std::string & err) {
    char buf[256];
    const int32_t written =
            llama_token_to_piece(vocab, token, buf, static_cast<int32_t>(sizeof(buf)), 0, false);
    if (written >= 0) {
        out.append(buf, static_cast<size_t>(written));
        return true;
    }
    // 负数是需要的字节数；256 字节一般足够，真的要更多就再要一次。
    const int32_t needed = -written;
    if (needed <= static_cast<int32_t>(sizeof(buf)) || needed > 64 * 1024) {
        err = "ERR_PIECE: llama_token_to_piece failed (ret=" + std::to_string(written) + ")";
        return false;
    }
    std::vector<char> big(static_cast<size_t>(needed));
    const int32_t retry = llama_token_to_piece(vocab, token, big.data(), needed, 0, false);
    if (retry < 0) {
        err = "ERR_PIECE: llama_token_to_piece failed (ret=" + std::to_string(retry) + ")";
        return false;
    }
    out.append(big.data(), static_cast<size_t>(retry));
    return true;
}

void release_fields(Session * session) {
    // 顺序有讲究：sampler 引用 ctx，ctx 引用 model。
    if (session->sampler != nullptr) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }
    if (session->ctx != nullptr) {
        llama_free(session->ctx);
        session->ctx = nullptr;
    }
    if (session->model != nullptr) {
        llama_model_free(session->model);
        session->model = nullptr;
    }
    session->vocab = nullptr;
}

/** 反注册 + 释放；最后一个 session 走掉时顺带 llama_backend_free。 */
void release_session(Session * session) {
    bool last = false;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        last = g_sessions.erase(session) > 0 && g_sessions.empty();
    }
    release_fields(session);
    delete session;
    if (last) {
        llama_backend_free();
    }
}

Session * lookup_session(jlong handle) {
    if (handle == 0) {
        return nullptr;
    }
    auto * session = reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_sessions.count(session) > 0 ? session : nullptr;
}

/** llama.cpp 的日志默认写 stderr，Android 上会丢；接到 logcat 才能在真机上看问题。 */
void llama_log_to_logcat(enum ggml_log_level level, const char * text, void * /* user_data */) {
    if (text == nullptr) {
        return;
    }
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  priority = ANDROID_LOG_INFO;  break;
        default:                   priority = ANDROID_LOG_DEBUG; break;
    }
    __android_log_write(priority, kLogTag, text);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_cliptranslate_local_LlamaBridge_nativeLoad(
        JNIEnv * env, jobject /* thiz */, jstring model_path, jint n_threads, jint n_ctx) {
    // 契约：失败返回 0，绝不抛（Kotlin 侧只判断 == 0，抛出去会击穿协程）。
    if (model_path == nullptr) {
        LOGE("nativeLoad: model_path is null");
        return 0;
    }

    Session * session = new (std::nothrow) Session();
    if (session == nullptr) {
        LOGE("nativeLoad: out of memory allocating session");
        return 0;
    }

    try {
        const std::string path = from_jstring(env, model_path);
        LOGI("nativeLoad: path=%s n_threads=%d n_ctx=%d", path.c_str(), (int) n_threads, (int) n_ctx);

        // 必须先 init：静态构建里 CPU backend 由 ggml_backend_load_all() 注册，
        // 没有 backend 时 llama_model_load_from_file 直接返回 nullptr。
        llama_backend_init();
        llama_log_set(llama_log_to_logcat, nullptr);

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;  // 这个 APK 只带 CPU backend

        session->model = llama_model_load_from_file(path.c_str(), model_params);
        if (session->model == nullptr) {
            LOGE("nativeLoad: llama_model_load_from_file failed");
            release_fields(session);
            delete session;
            return 0;
        }

        session->vocab = llama_model_get_vocab(session->model);
        if (session->vocab == nullptr) {
            LOGE("nativeLoad: model has no vocab");
            release_fields(session);
            delete session;
            return 0;
        }

        llama_context_params ctx_params = llama_context_default_params();
        if (n_ctx > 0) {
            ctx_params.n_ctx = static_cast<uint32_t>(n_ctx);
        }
        ctx_params.n_batch = static_cast<uint32_t>(kNBatch);
        ctx_params.n_ubatch = static_cast<uint32_t>(kNBatch);
        if (n_threads > 0) {
            ctx_params.n_threads = n_threads;
            ctx_params.n_threads_batch = n_threads;
        }

        session->ctx = llama_init_from_model(session->model, ctx_params);
        if (session->ctx == nullptr) {
            LOGE("nativeLoad: llama_init_from_model failed (n_ctx=%u)", ctx_params.n_ctx);
            release_fields(session);
            delete session;
            return 0;
        }

        // 实际生效的上下文长度/批大小（llama 会把 n_batch 收到 min(n_ctx, n_batch)）。
        session->n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
        session->n_batch = std::max<int32_t>(1, std::min<int32_t>(kNBatch, session->n_ctx));

        session->sampler = llama_sampler_init_greedy();
        if (session->sampler == nullptr) {
            LOGE("nativeLoad: llama_sampler_init_greedy failed");
            release_fields(session);
            delete session;
            return 0;
        }

        // `<end_of_turn>` 若在词表里是单个 special token 就记下 id：Gemma 系模型
        // 一般在 eos 列表里（llama_vocab_is_eog 已覆盖），这里再兜一层，防止某些
        // GGUF 的 eos 元数据没列全导致一直生成到 maxTokens。
        {
            std::vector<llama_token> marker;
            std::string err;
            if (tokenize(session->vocab, kEndOfTurn, /*add_special=*/false, /*parse_special=*/true,
                         marker, err) &&
                    marker.size() == 1) {
                session->stop_token = marker[0];
            }
        }

        {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_sessions.insert(session);
        }

        LOGI("nativeLoad: ok n_ctx=%d n_batch=%d stop_token=%d", (int) session->n_ctx,
             (int) session->n_batch, (int) session->stop_token);
        return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
    } catch (const std::exception & e) {
        LOGE("nativeLoad: exception: %s", e.what());
        release_fields(session);
        delete session;
        return 0;
    } catch (...) {
        LOGE("nativeLoad: unknown exception");
        release_fields(session);
        delete session;
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_dev_cliptranslate_local_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject /* thiz */, jlong handle, jstring prompt, jint max_tokens) {
    Session * session = lookup_session(handle);
    if (session == nullptr) {
        throw_runtime(env, "ERR_BAD_HANDLE: model is not loaded (handle=0 or already unloaded)");
        return nullptr;
    }
    if (prompt == nullptr) {
        throw_runtime(env, "ERR_BAD_ARGS: prompt is null");
        return nullptr;
    }
    if (max_tokens <= 0) {
        throw_runtime(env, "ERR_BAD_ARGS:max_tokens=" + std::to_string(max_tokens));
        return nullptr;
    }

    try {
        const std::string prompt_text = from_jstring(env, prompt);

        // 每次生成都是独立请求：清掉上一次的 KV cache（位置也会从 0 重新走）。
        llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);
        llama_sampler_reset(session->sampler);

        std::string err;
        std::vector<llama_token> tokens;
        if (!tokenize(session->vocab, prompt_text, /*add_special=*/true, /*parse_special=*/true,
                      tokens, err)) {
            throw_runtime(env, err);
            return nullptr;
        }
        if (tokens.empty()) {
            throw_runtime(env, "ERR_EMPTY_PROMPT: prompt produced no tokens");
            return nullptr;
        }

        const int32_t n_ctx = session->n_ctx;
        if (static_cast<int64_t>(tokens.size()) + static_cast<int64_t>(max_tokens) >
                static_cast<int64_t>(n_ctx)) {
            // Kotlin 侧按前缀匹配这条错误码，措辞别改。
            throw_runtime(env,
                    std::string(kErrTooLong) + ":n_prompt=" + std::to_string(tokens.size()) +
                            ",n_max=" + std::to_string(max_tokens) +
                            ",n_ctx=" + std::to_string(n_ctx));
            return nullptr;
        }

        // 一次 llama_decode 喂进去的 token 数不能超过 n_batch（llama 内部是
        // GGML_ASSERT），所以 prompt 要切片投喂；位置由 llama 从 memory 里续着走。
        for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(session->n_batch)) {
            const int32_t chunk = static_cast<int32_t>(std::min<size_t>(
                    tokens.size() - offset, static_cast<size_t>(session->n_batch)));
            llama_batch batch = llama_batch_get_one(tokens.data() + offset, chunk);
            const int32_t ret = llama_decode(session->ctx, batch);
            if (ret != 0) {
                throw_runtime(env, "ERR_DECODE: llama_decode failed (ret=" + std::to_string(ret) + ")");
                return nullptr;
            }
        }

        std::string output;
        output.reserve(static_cast<size_t>(max_tokens) * 4);
        llama_token sampled = 0;
        bool stopped = false;
        for (int32_t i = 0; i < max_tokens; ++i) {
            // llama_sampler_sample 内部已经对采样结果调过 llama_sampler_accept，
            // 这里不能再 accept 一次（会把 token 记两遍，惩罚类 sampler 会算错）。
            sampled = llama_sampler_sample(session->sampler, session->ctx, -1);

            if (llama_vocab_is_eog(session->vocab, sampled)) {
                stopped = true;
                break;
            }
            if (session->stop_token != LLAMA_TOKEN_NULL && sampled == session->stop_token) {
                stopped = true;
                break;
            }

            if (!append_piece(session->vocab, sampled, output, err)) {
                throw_runtime(env, err);
                return nullptr;
            }

            // 模型把结束标记当普通文本吐出来时（不是 special token），按它截断。
            const size_t marker_at = output.find(kEndOfTurn);
            if (marker_at != std::string::npos) {
                output.resize(marker_at);
                stopped = true;
                break;
            }

            if (i + 1 >= max_tokens) {
                break;  // 到上限了，最后一个 token 不用再 decode
            }

            llama_batch batch = llama_batch_get_one(&sampled, 1);
            const int32_t ret = llama_decode(session->ctx, batch);
            if (ret != 0) {
                throw_runtime(env, "ERR_DECODE: llama_decode failed (ret=" + std::to_string(ret) + ")");
                return nullptr;
            }
        }

        LOGI("nativeGenerate: n_prompt=%d out_bytes=%d stopped=%d",
             (int) tokens.size(), (int) output.size(), stopped ? 1 : 0);
        return to_jstring(env, output);
    } catch (const std::exception & e) {
        throw_runtime(env, std::string("ERR_NATIVE: ") + e.what());
        return nullptr;
    } catch (...) {
        throw_runtime(env, "ERR_NATIVE: unknown native exception");
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_dev_cliptranslate_local_LlamaBridge_nativeUnload(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    if (handle == 0) {
        return;  // 未加载/已卸载，什么也不用做
    }
    Session * session = lookup_session(handle);
    if (session == nullptr) {
        LOGW("nativeUnload: unknown handle, ignored");
        return;
    }
    try {
        release_session(session);
    } catch (...) {
        // 析构路径上不该再抛；真抛了也只是漏一点内存，不能让 JVM 看到 std::terminate。
        LOGE("nativeUnload: exception while freeing session");
    }
}

}  // extern "C"
