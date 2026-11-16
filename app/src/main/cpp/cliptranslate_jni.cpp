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
//
// Backends: CPU, OpenCL and Hexagon(NPU/HTP) are all statically registered by
// ggml (see CMakeLists.txt), so llama_backend_init() already exposes every
// device. Which one a load uses is decided here from the `gpuBackend` argument,
// via llama_model_params.devices (the same field llama-server's --device uses)
// plus n_gpu_layers. There is no CPU fallback for an explicit backend request: a
// missing device makes nativeLoad() return 0 so Kotlin can show a readable
// message instead of silently running 10x slower.
//
// Hexagon needs its DSP-side skel (libggml-htp-vXX.so) on a real filesystem path:
// AGP-packed jniLibs are mmap'ed straight out of the APK, which fastrpc cannot
// load. Kotlin extracts the skels from assets/htp-skel into filesDir and passes
// that directory as `skelDir`; we hand it to ggml-hexagon through the
// GGML_HEXAGON_LIBHTP_DIR env var (read by htp_lib_dir() in htp-drv.cpp) before
// llama_backend_init(), because backend registration runs htpdrv_init().
//
// Vulkan used to be a third backend. It is gone: the Adreno 830 driver fails
// createComputePipeline() on ggml's basic mul_mat_vec_q4_k shaders with
// ErrorUnknown and kills the process (a driver bug with no workaround).

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <functional>
#include <mutex>
#include <new>
#include <string>
#include <unordered_set>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
// 多模态（图片翻译）走 llama.cpp 的 libmtmd。它是独立静态库（CI 上由
// LLAMA_BUILD_MTMD=ON 编出来，见 CMakeLists.txt），只依赖 ggml/llama，不依赖
// common，所以能直接静态链进本 .so。
// 约定：Kotlin 在 prompt 里用 __IMAGE__ 占位，native 在 tokenize 之前把它换成
// mtmd 库自己给出的 marker（mtmd_get_marker()，当前默认 "<__media__>"）。
// marker 由 mtmd_tokenize() 负责替换成「<start_of_image> + 图像 embedding +
// <end_of_image>」三段 chunk，所以我们只负责把文本里的占位符换掉，不自己拼特殊 token。
#include "mtmd-helper.h"
#include "mtmd.h"

/**
 * 调试日志总开关。默认 true 是 fail-open：构建类型的默认值（debug 开 / release 关）
 * 由 Kotlin 在 Application 启动时经 nativeSetLogEnabled 注入，这里只保证在 Kotlin
 * 调用之前不会把日志吞掉。关闭时只保留 ERROR，见 llama_log_to_logcat 与 LOGI/LOGW。
 */
static std::atomic<bool> g_log_enabled{true};

namespace {

constexpr const char * kLogTag = "CliptranslateJNI";
constexpr const char * kErrTooLong = "ERR_TOO_LONG";
constexpr const char * kEndOfTurn = "<end_of_turn>";
/** Index-Translate（Qwen3 风格模板）的结束符：两个模型各用各的，都要认。 */
constexpr const char * kImEnd = "<|im_end|>";
/** Kotlin 侧在 prompt 里放的图片占位符，native 会把它换成 mtmd 的 marker。 */
constexpr const char * kImagePlaceholder = "__IMAGE__";
constexpr int32_t kNBatch = 512;

/**
 * 模型把结束标记当普通文本吐出来时（不是 special token 的那种情况）按它截断。
 * `<end_of_turn>` 是 Gemma，`<|im_end|>` 是 Qwen/Index。
 */
constexpr const char * kStopMarkers[] = {kEndOfTurn, kImEnd};

/** 与 Kotlin 的 GpuBackend 枚举一一对应；改这里必须同步改 LlamaBridge.nativeLoad。 */
constexpr int32_t kGpuAuto = 0;      // GPU 优先（OpenCL），GPU 装载失败时自动回落 CPU
constexpr int32_t kGpuCpuOnly = 1;   // 只用 CPU
constexpr int32_t kGpuOpenCl = 2;    // 强制 OpenCL（Adreno）
constexpr int32_t kGpuHexagon = 4;   // 强制 Hexagon（高通 NPU / HTP）

/** 曾经是「强制 Vulkan」（3）；Vulkan 后端已从构建里移除，这里只留个记号做防御。 */
constexpr int32_t kGpuVulkanRemoved = 3;

/** 全部 offload：llama 会把 99 当成“参数里的上限”，实际层数由模型决定。 */
constexpr int32_t kAllGpuLayers = 99;

/** 一次 nativeLoad 的全部 native 状态，整体作为 jlong 句柄交给 Kotlin。 */
struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    /**
     * 单 token 形式的结束符，按模型填：`<end_of_turn>`（Gemma 系）和/或
     * `<|im_end|>`（Qwen/Index 系）。不是单 token 的位置留 LLAMA_TOKEN_NULL。
     * 一般 eos 列表里已经覆盖了（llama_vocab_is_eog），这层兜底是防止某些 GGUF 的
     * eos 元数据没列全，导致一直生成到 maxTokens。
     */
    llama_token stop_tokens[2] = {LLAMA_TOKEN_NULL, LLAMA_TOKEN_NULL};
    /** mmproj 的 mtmd 上下文；mmprojPath 为空（纯文本加载）时是 nullptr。 */
    mtmd_context * mtmd = nullptr;
    int32_t n_ctx = 0;
    int32_t n_batch = kNBatch;
};

// 句柄注册表：handle 是从 Kotlin 绕回来的裸 jlong，用它反查一下能避免
// 陈旧/伪造的 handle 变成野指针崩溃（重复 unload、加载失败后误用等）。
// Kotlin 侧已保证推理调用串行，所以这里只保护表本身，不保护 ctx。
std::mutex g_mutex;
std::unordered_set<Session *> g_sessions;

// INFO/WARN 受调试日志开关控制；ERROR 无条件输出，保证关闭日志后仍能看到真故障。
#define LOGI(...) \
    do { \
        if (g_log_enabled) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__); \
    } while (0)
#define LOGW(...) \
    do { \
        if (g_log_enabled) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__); \
    } while (0)
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

/** 按词表查单 token 形式的结束符；不在词表里、或不是单 token 时返回 LLAMA_TOKEN_NULL。 */
llama_token find_stop_token(const llama_vocab * vocab, const char * text) {
    std::vector<llama_token> marker;
    std::string err;
    if (tokenize(vocab, text, /*add_special=*/false, /*parse_special=*/true, marker, err) &&
            marker.size() == 1) {
        return marker[0];
    }
    return LLAMA_TOKEN_NULL;
}

bool is_stop_token(const Session & session, llama_token token) {
    for (const llama_token stop : session.stop_tokens) {
        if (stop != LLAMA_TOKEN_NULL && token == stop) {
            return true;
        }
    }
    return false;
}

/**
 * 贪心解码循环，nativeGenerate / nativeGenerateImage 共用（两条路径的停止条件必须
 * 完全一致，否则同一个模型给文字和给图片会吐出不同长度的结果）。
 *
 * 进入前前提：prompt 已经喂完，ctx 里最后一步的 logits 有效（logits_last）。
 * 返回 false 表示已经 throw_runtime 抛过异常，调用方直接 return nullptr 即可。
 */
bool sample_loop(JNIEnv * env, Session * session, int32_t max_tokens,
                 std::string & output, int32_t & gen_tokens, bool & stopped) {
    std::string err;
    output.clear();
    output.reserve(static_cast<size_t>(max_tokens) * 4);
    gen_tokens = 0;
    stopped = false;
    for (int32_t i = 0; i < max_tokens; ++i) {
        // llama_sampler_sample 内部已经对采样结果调过 llama_sampler_accept，
        // 这里不能再 accept 一次（会把 token 记两遍，惩罚类 sampler 会算错）。
        llama_token sampled = llama_sampler_sample(session->sampler, session->ctx, -1);

        if (llama_vocab_is_eog(session->vocab, sampled) || is_stop_token(*session, sampled)) {
            stopped = true;
            break;
        }

        if (!append_piece(session->vocab, sampled, output, err)) {
            throw_runtime(env, err);
            return false;
        }
        ++gen_tokens;

        // 结束标记被当普通文本吐出来时（不是 special token），按它截断。
        size_t cut_at = std::string::npos;
        for (const char * marker : kStopMarkers) {
            const size_t at = output.find(marker);
            if (at != std::string::npos && (cut_at == std::string::npos || at < cut_at)) {
                cut_at = at;
            }
        }
        if (cut_at != std::string::npos) {
            output.resize(cut_at);
            stopped = true;
            break;
        }

        if (i + 1 >= max_tokens) {
            break;  // 到上限了，最后一个 token 不用再 decode
        }

        // pos 留空：llama 会按 KV 里该序列的 seq_pos_max + 1 续位（图片 chunk 用
        // M-RoPE 占掉的位置也已经记在 KV 里，续位是准的）。
        llama_batch batch = llama_batch_get_one(&sampled, 1);
        const int32_t ret = llama_decode(session->ctx, batch);
        if (ret != 0) {
            throw_runtime(env, "ERR_DECODE: llama_decode failed (ret=" + std::to_string(ret) + ")");
            return false;
        }
    }
    return true;
}

void release_fields(Session * session) {
    // 顺序有讲究：mtmd 引用 model（不持有），sampler 引用 ctx，ctx 引用 model。
    // 所以 mtmd 必须先于 ctx/model 释放（它内部的 subcontext 还拿着 model 指针）。
    if (session->mtmd != nullptr) {
        mtmd_free(session->mtmd);
        session->mtmd = nullptr;
    }
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
    // 关闭时只放行 ERROR；GGML_LOG_LEVEL_CONT 是 ERROR 多行日志的续行，一并保留，
    // 否则 ERROR 会被拆成半行。b12375 的枚举是 DEBUG=1<INFO=2<WARN=3<ERROR=4<CONT=5，
    // 「大于 ERROR」只会滤掉 CONT，不能用来当严重度判断。
    if (!g_log_enabled && level != GGML_LOG_LEVEL_ERROR && level != GGML_LOG_LEVEL_CONT) {
        return;
    }
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

const char * dev_type_name(enum ggml_backend_dev_type type) {
    switch (type) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:   return "CPU";
        case GGML_BACKEND_DEVICE_TYPE_GPU:   return "GPU";
        case GGML_BACKEND_DEVICE_TYPE_IGPU:  return "IGPU";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "ACCEL";
        case GGML_BACKEND_DEVICE_TYPE_META:  return "META";
        default:                             return "UNKNOWN";
    }
}

/** ASCII 大小写不敏感的 contains（want 传小写字面量）。 */
bool contains_ci(const char * haystack, const char * want) {
    if (haystack == nullptr || want == nullptr || *want == '\0') {
        return false;
    }
    const size_t want_len = std::strlen(want);
    for (const char * p = haystack; *p != '\0'; ++p) {
        size_t i = 0;
        while (i < want_len && p[i] != '\0' &&
               std::tolower(static_cast<unsigned char>(p[i])) == want[i]) {
            ++i;
        }
        if (i == want_len) {
            return true;
        }
    }
    return false;
}

bool is_gpu_device(ggml_backend_dev_t dev) {
    const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

/**
 * 后端注册名（"OpenCL" / "HTP"）、设备名（"HTP0"）、设备描述（"Hexagon"）
 * 任一处含 want 就算匹配。
 */
bool device_matches(ggml_backend_dev_t dev, const char * want) {
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    if (reg != nullptr && contains_ci(ggml_backend_reg_name(reg), want)) {
        return true;
    }
    if (contains_ci(ggml_backend_dev_name(dev), want)) {
        return true;
    }
    return contains_ci(ggml_backend_dev_description(dev), want);
}

/** 把 ggml 注册进来的 backend 设备全打一遍：真机上定位“为什么走了 CPU”全靠这行。 */
void log_devices() {
    const size_t count = ggml_backend_dev_count();
    LOGI("devices: %zu registered", count);
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) {
            continue;
        }
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
        const char * reg_name = reg != nullptr ? ggml_backend_reg_name(reg) : "?";
        size_t free_bytes = 0;
        size_t total_bytes = 0;
        ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);
        LOGI("devices: [%zu] backend=%s type=%s name=%s mem_free=%zu MiB mem_total=%zu MiB", i,
             reg_name, dev_type_name(ggml_backend_dev_type(dev)), ggml_backend_dev_name(dev),
             free_bytes / (1024 * 1024), total_bytes / (1024 * 1024));
    }
}

/**
 * 按 gpuBackend 选一个 GPU 设备。
 *
 * 构建里有 OpenCL（Adreno GPU）和 Hexagon（高通 NPU，注册名 "HTP"、设备描述
 * "Hexagon"）两个 GPU 类后端。显式指定后端时按后端注册名/设备名/描述匹配；
 * Hexagon 同时匹配 "htp" 和 "hexagon"（上游注册名是 "HTP"、设备描述才是
 * "Hexagon"，两个词都不算错）。
 *
 * auto 的优先级：Hexagon NPU > OpenCL GPU > CPU（NPU 实测 decode 是 CPU 的 11 倍，
 * prefill 是 30 倍，且已完成真机验证）。NPU 装载失败会回落 OpenCL，再失败回落 CPU。
 *
 * 不把 devices 留空交给 llama 自己挑，是因为 llama 只按 props.device_id 去重，
 * 而 OpenCL backend 根本不填 device_id，留空万一将来多挂一个后端就会把同一块
 * GPU 当成两台设备、把层切开来跑（两边各自建一份 buffer，更慢也更吃内存）。
 */
ggml_backend_dev_t pick_device(int32_t gpu_backend, bool exclude_hexagon = false) {
    const size_t count = ggml_backend_dev_count();
    auto is_hexagon = [](ggml_backend_dev_t dev) {
        return device_matches(dev, "htp") || device_matches(dev, "hexagon");
    };
    auto find = [&](const std::function<bool(ggml_backend_dev_t)> & match) {
        for (size_t i = 0; i < count; ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (dev != nullptr && is_gpu_device(dev) && match(dev)) {
                return dev;
            }
        }
        return static_cast<ggml_backend_dev_t>(nullptr);
    };
    if (gpu_backend == kGpuOpenCl) {
        return find([&](ggml_backend_dev_t dev) { return device_matches(dev, "opencl"); });
    }
    if (gpu_backend == kGpuHexagon) {
        return find(is_hexagon);
    }
    // auto
    if (!exclude_hexagon) {
        if (ggml_backend_dev_t dev = find(is_hexagon)) {
            return dev;
        }
    }
    return find([&](ggml_backend_dev_t dev) { return !is_hexagon(dev); });
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_cliptranslate_local_LlamaBridge_nativeLoad(
        JNIEnv * env, jobject /* thiz */, jstring model_path, jint n_threads, jint n_ctx,
        jint gpu_backend, jstring skel_dir, jstring mmproj_path) {
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
        const std::string skel_path = skel_dir != nullptr ? from_jstring(env, skel_dir) : std::string();
        // 空串 = 这次加载不带视觉能力（纯文本翻译），nativeGenerateImage 会直接拒绝。
        const std::string mmproj = mmproj_path != nullptr ? from_jstring(env, mmproj_path) : std::string();
        LOGI("nativeLoad: path=%s n_threads=%d n_ctx=%d gpu_backend=%d skel_dir=%s mmproj=%s",
             path.c_str(), (int) n_threads, (int) n_ctx, (int) gpu_backend,
             skel_path.empty() ? "<none>" : skel_path.c_str(),
             mmproj.empty() ? "<none>" : mmproj.c_str());

        // 防御：Kotlin 侧已删掉 Vulkan 枚举（3），但历史设置里可能还留着这个值；
        // 其他未知值同理。一律按 auto 处理并打一行警告，不要因为脏数据让翻译不可用。
        // 这一步必须在下面的 setenv 之前做完：脏数据不该影响环境变量。
        if (gpu_backend == kGpuVulkanRemoved) {
            LOGW("nativeLoad: gpu_backend=3 (Vulkan) no longer exists, treating as auto");
            gpu_backend = kGpuAuto;
        } else if (gpu_backend != kGpuAuto && gpu_backend != kGpuCpuOnly &&
                   gpu_backend != kGpuOpenCl && gpu_backend != kGpuHexagon) {
            LOGW("nativeLoad: unknown gpu_backend=%d, treating as auto", (int) gpu_backend);
            gpu_backend = kGpuAuto;
        }

        // Hexagon 的 skel 目录必须在 llama_backend_init() 之前交出去：
        // llama_backend_init() -> ggml_backend_load_all() 会跑 ggml_backend_hexagon_reg()，
        // 里面 htpdrv_init() 探测 DSP（不存在就整个后端不注册）。目录不对就没法加载。
        // auto 现在也可能选中 NPU，所以只要给了目录就 setenv（不论 gpu_backend 取值）；
        // 显式要 NPU 却连目录都没给属于调用方错误，直接失败比悄悄退 CPU 好排查。
        if (!skel_path.empty()) {
            if (setenv("GGML_HEXAGON_LIBHTP_DIR", skel_path.c_str(), 1) != 0) {
                LOGE("nativeLoad: setenv(GGML_HEXAGON_LIBHTP_DIR) failed");
                release_fields(session);
                delete session;
                return 0;
            }
            LOGI("nativeLoad: GGML_HEXAGON_LIBHTP_DIR=%s", skel_path.c_str());
        } else if (gpu_backend == kGpuHexagon) {
            LOGE("nativeLoad: gpu_backend=4 (Hexagon) but skel_dir is empty");
            release_fields(session);
            delete session;
            return 0;
        }

        // 必须先 init：静态构建里三个 backend（CPU/OpenCL/Hexagon）由
        // ggml_backend_load_all() 一次性注册进来，探测失败的 backend 不会注册设备。
        llama_backend_init();
        llama_log_set(llama_log_to_logcat, nullptr);
        // mtmd 有自己的日志开关（默认写 stderr，Android 上直接丢），同样接到 logcat，
        // 否则真机上看不到 "image slice encoded in X ms" 这种关键信息。
        mtmd_log_set(llama_log_to_logcat, nullptr);
        log_devices();

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;  // 默认 CPU，下面按 gpu_backend 覆盖

        // devices 要 NULL 结尾，且必须在 llama_model_load_from_file 返回前一直有效
        // （llama 只把指针抄进 model->devices，不拷贝数组）。
        std::vector<ggml_backend_dev_t> selected;
        bool gpu_requested = false;

        if (gpu_backend == kGpuCpuOnly) {
            LOGI("nativeLoad: backend=CPU (forced by caller)");
        } else {
            const ggml_backend_dev_t dev = pick_device(gpu_backend);
            if (dev == nullptr) {
                if (gpu_backend != kGpuAuto) {
                    // 用户显式指定了后端却没有对应设备（驱动不支持 / 后端没编进来 /
                    // skel 目录不对）：老实失败，让 Kotlin 给出可读提示，别悄悄退到
                    // CPU 装作成功。
                    LOGE("nativeLoad: no device for requested backend (gpu_backend=%d)",
                         (int) gpu_backend);
                    release_fields(session);
                    delete session;
                    return 0;
                }
                // auto：一个 GPU 都没有，就正常用 CPU 跑。
                LOGW("nativeLoad: no GPU device available, running on CPU");
            } else {
                selected.push_back(dev);
                selected.push_back(nullptr);
                model_params.devices = selected.data();
                model_params.n_gpu_layers = kAllGpuLayers;
                gpu_requested = true;
                LOGI("nativeLoad: backend=%s device=%s n_gpu_layers=%d",
                     ggml_backend_dev_name(dev), ggml_backend_dev_description(dev),
                     (int) model_params.n_gpu_layers);
            }
        }

        // 模型装载 + context 创建绑在一起：auto 模式下 GPU 任何一步失败（显存不够、
        // 驱动拒绝）都要能用 CPU 再试一次，两边失败点不同但兜底逻辑一样。
        auto load_and_init = [&](const llama_model_params & params) -> bool {
            session->model = llama_model_load_from_file(path.c_str(), params);
            if (session->model == nullptr) {
                return false;
            }
            session->vocab = llama_model_get_vocab(session->model);
            if (session->vocab == nullptr) {
                return false;
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
                return false;
            }
            return true;
        };

        bool loaded = load_and_init(model_params);
        if (!loaded && gpu_requested && gpu_backend == kGpuAuto) {
            // auto 回落链：NPU 失败 → OpenCL（或别的非 NPU GPU）→ CPU。
            // 显式指定后端的不做这个兜底：用户要的就是那个后端，悄悄换掉更难排查。
            auto is_hexagon = [](ggml_backend_dev_t dev) {
                return device_matches(dev, "htp") || device_matches(dev, "hexagon");
            };
            const bool tried_hexagon =
                !selected.empty() && selected[0] != nullptr && is_hexagon(selected[0]);
            if (tried_hexagon) {
                LOGW("nativeLoad: NPU load failed, retrying on GPU (OpenCL)");
                release_fields(session);
                selected.clear();
                const ggml_backend_dev_t dev = pick_device(kGpuAuto, /*exclude_hexagon=*/true);
                if (dev != nullptr) {
                    selected.push_back(dev);
                    selected.push_back(nullptr);
                    model_params.devices = selected.data();
                    model_params.n_gpu_layers = kAllGpuLayers;
                    LOGI("nativeLoad: fallback backend=%s device=%s",
                         ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
                    loaded = load_and_init(model_params);
                }
            }
            if (!loaded) {
                LOGW("nativeLoad: GPU load failed, retrying on CPU");
                release_fields(session);  // 清掉半成品（model/vocab/ctx）
                model_params.devices = nullptr;
                model_params.n_gpu_layers = 0;
                loaded = load_and_init(model_params);
            }
        }
        if (!loaded) {
            LOGE("nativeLoad: model load failed (device=CPU fallback=%d)",
                 gpu_backend == kGpuAuto ? 1 : 0);
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

        // 两个候选都查一遍：Gemma 用 `<end_of_turn>`，Index-Translate（Qwen3 模板）用
        // `<|im_end|>`。谁在词表里是单个 special token 就认谁，两套提示词都能正常停。
        session->stop_tokens[0] = find_stop_token(session->vocab, kEndOfTurn);
        session->stop_tokens[1] = find_stop_token(session->vocab, kImEnd);

        // -------------------------------------------------------------------
        // 多模态（图片翻译）：mmproj 给了才建 mtmd 上下文
        // -------------------------------------------------------------------
        if (!mmproj.empty()) {
            mtmd_context_params mparams = mtmd_context_params_default();
            // 视觉编码器固定跑 CPU。默认值 use_gpu=true + device=nullptr 时 mtmd 会自己
            // 挑第一个 GPU/IGPU 设备，而在我们的构建里那就是 Hexagon NPU 或 Adreno
            // OpenCL：ViT 的算子覆盖度没验证过，Q8_0 的 mmproj 又占约 345MB 显存，
            // 失败形态是加载即崩或者整个进程被杀（Vulkan 就是这么没了）。
            // 编码一张图在 CPU 上是秒级，稳定压倒速度；文本 decode 用的仍是上面选好的
            // 后端，两者互不影响 —— mtmd 只把图算成 embedding 再交给 llama_decode。
            mparams.use_gpu = false;
            mparams.device = nullptr;
            mparams.print_timings = false;  // 每次请求自己打耗时，别刷屏
            mparams.warmup = true;          // 装载时先空跑一次 ViT，坏 mmproj 早失败
            if (n_threads > 0) {
                mparams.n_threads = n_threads;
            }
            session->mtmd = mtmd_init_from_file(mmproj.c_str(), session->model, mparams);
            if (session->mtmd == nullptr) {
                LOGE("nativeLoad: mtmd_init_from_file failed (mmproj=%s)", mmproj.c_str());
                release_fields(session);
                delete session;
                return 0;
            }
            LOGI("nativeLoad: mtmd ok mmproj=%s vision=%d audio=%d marker=%s",
                 mmproj.c_str(), mtmd_support_vision(session->mtmd) ? 1 : 0,
                 mtmd_support_audio(session->mtmd) ? 1 : 0, mtmd_get_marker(session->mtmd));
        }

        {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_sessions.insert(session);
        }

        LOGI("nativeLoad: ok n_ctx=%d n_batch=%d stop_tokens=%d,%d n_gpu_layers=%d gpu=%d vision=%d",
             (int) session->n_ctx, (int) session->n_batch, (int) session->stop_tokens[0],
             (int) session->stop_tokens[1], (int) model_params.n_gpu_layers, gpu_requested ? 1 : 0,
             session->mtmd != nullptr ? 1 : 0);
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
        const auto prompt_begin = std::chrono::steady_clock::now();
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
        const auto prompt_end = std::chrono::steady_clock::now();

        std::string output;
        // 生成阶段的 token 数（不含结束符），tok_s 就按它算。
        int32_t gen_tokens = 0;
        bool stopped = false;
        if (!sample_loop(env, session, max_tokens, output, gen_tokens, stopped)) {
            return nullptr;  // sample_loop 已经抛过异常
        }

        const auto gen_end = std::chrono::steady_clock::now();
        const long long prompt_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(prompt_end - prompt_begin).count();
        const long long gen_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(gen_end - prompt_end).count();
        // tok_s 只算生成阶段（prompt 处理单独看 prompt_ms）。真机上按 gpuBackend 各跑
        // 一次、logcat 里 grep 这一行就能横向对比 CPU / OpenCL。
        const double tok_s =
                gen_ms > 0 ? static_cast<double>(gen_tokens) * 1000.0 / static_cast<double>(gen_ms) : 0.0;
        LOGI("nativeGenerate: n_prompt=%d prompt_ms=%lld gen_tokens=%d gen_ms=%lld tok_s=%.2f "
             "out_bytes=%d stopped=%d",
             (int) tokens.size(), prompt_ms, (int) gen_tokens, gen_ms, tok_s, (int) output.size(),
             stopped ? 1 : 0);
        return to_jstring(env, output);
    } catch (const std::exception & e) {
        throw_runtime(env, std::string("ERR_NATIVE: ") + e.what());
        return nullptr;
    } catch (...) {
        throw_runtime(env, "ERR_NATIVE: unknown native exception");
        return nullptr;
    }
}

/**
 * 图片翻译：prompt 里的 __IMAGE__ 会被换成本次加载的 mtmd marker，图片按 RGB
 * 3 字节/像素、行紧凑传给 mtmd，图像 embedding 由 mtmd 的 helper 喂进 llama 上下文，
 * 之后的解码与 nativeGenerate 完全一样（同一个贪心循环、同一套停止条件）。
 *
 * @param rgb 长度必须恰好是 width*height*3。
 */
JNIEXPORT jstring JNICALL
Java_dev_cliptranslate_local_LlamaBridge_nativeGenerateImage(
        JNIEnv * env, jobject /* thiz */, jlong handle, jstring prompt, jbyteArray rgb,
        jint width, jint height, jint max_tokens) {
    Session * session = lookup_session(handle);
    if (session == nullptr) {
        throw_runtime(env, "ERR_BAD_HANDLE: model is not loaded (handle=0 or already unloaded)");
        return nullptr;
    }
    // 加载时 mmprojPath 为空 = 这个 handle 没有视觉能力。用独立错误码，Kotlin 才能
    // 提示「去下载 mmproj」，而不是一句含糊的推理失败。
    if (session->mtmd == nullptr) {
        throw_runtime(env, "ERR_NO_VISION: model was loaded without mmproj (mmprojPath is empty)");
        return nullptr;
    }
    if (!mtmd_support_vision(session->mtmd)) {
        throw_runtime(env, "ERR_NO_VISION: the loaded mmproj has no vision encoder");
        return nullptr;
    }
    if (prompt == nullptr) {
        throw_runtime(env, "ERR_BAD_ARGS: prompt is null");
        return nullptr;
    }
    if (rgb == nullptr) {
        throw_runtime(env, "ERR_BAD_ARGS: bitmap is null");
        return nullptr;
    }
    if (max_tokens <= 0) {
        throw_runtime(env, "ERR_BAD_ARGS:max_tokens=" + std::to_string(max_tokens));
        return nullptr;
    }
    if (width <= 0 || height <= 0) {
        throw_runtime(env, "ERR_BAD_ARGS: bitmap size=" + std::to_string(width) + "x" +
                                   std::to_string(height));
        return nullptr;
    }
    // 用 int64 算：width/height 是 Kotlin 传来的，脏数据（比如几万像素）乘 3 之后
    // 会在 int32 上溢出成负数，进而绕过下面的大小校验。
    const int64_t expected_bytes =
            static_cast<int64_t>(width) * static_cast<int64_t>(height) * 3;
    const jsize actual_bytes = env->GetArrayLength(rgb);
    if (static_cast<int64_t>(actual_bytes) != expected_bytes) {
        throw_runtime(env, "ERR_BAD_ARGS: bitmap bytes=" + std::to_string(actual_bytes) + ", need " +
                                   std::to_string(expected_bytes) + " (" + std::to_string(width) + "x" +
                                   std::to_string(height) + " RGB)");
        return nullptr;
    }

    try {
        const std::string prompt_text = from_jstring(env, prompt);
        std::vector<unsigned char> pixels(static_cast<size_t>(expected_bytes));
        env->GetByteArrayRegion(rgb, 0, actual_bytes, reinterpret_cast<jbyte *>(pixels.data()));
        if (env->ExceptionCheck()) {
            // JNI 自己挂了异常（理论上到不了这里，长度已经核对过），原样交给 Kotlin。
            return nullptr;
        }

        // 占位符 -> mtmd marker。marker 从上下文里取，不硬编码字符串：上游改了默认
        // marker 我们跟着走，不会因为 "<__media__>" 变了就静默失败。
        const std::string marker = mtmd_get_marker(session->mtmd);
        const std::string placeholder = kImagePlaceholder;
        const size_t at = prompt_text.find(placeholder);
        if (at == std::string::npos) {
            throw_runtime(env, "ERR_BAD_ARGS: prompt has no " + placeholder + " placeholder");
            return nullptr;
        }
        if (prompt_text.find(placeholder, at + placeholder.size()) != std::string::npos) {
            // 一次调用只带一张图。多出来的占位符会让 mtmd_tokenize 返回 1
            //（marker 数 != 图片数），这里提前给出更明确的错误。
            throw_runtime(env, "ERR_BAD_ARGS: prompt has more than one " + placeholder);
            return nullptr;
        }
        std::string text_with_marker = prompt_text;
        text_with_marker.replace(at, placeholder.size(), marker);

        // 每次生成都是独立请求：清掉上一次的 KV cache（位置也会从 0 重新走）。
        llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);
        llama_sampler_reset(session->sampler);

        // mtmd_bitmap_init 会把像素 memcpy 一份到自己的 buffer 里，所以我们的
        // pixels 只需活到这里；大截图（1080x2400 ≈ 7.7MB）早点还回去，给后面的
        // 图像编码和解码腾地方。
        mtmd::bitmap bitmap(static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                            pixels.data());
        pixels = std::vector<unsigned char>();
        if (bitmap.ptr == nullptr) {
            throw_runtime(env, "ERR_NATIVE: mtmd_bitmap_init failed (out of memory?)");
            return nullptr;
        }

        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        if (chunks.ptr == nullptr) {
            throw_runtime(env, "ERR_NATIVE: mtmd_input_chunks_init failed (out of memory?)");
            return nullptr;
        }

        // add_special/parse_special 与 nativeGenerate 保持一致：prompt 是 Kotlin 展开
        // 好的 chat template，里面的 <|im_start|> 之类必须按 special token 解析。
        const mtmd_input_text text{text_with_marker.data(), text_with_marker.size(),
                                   /*add_special=*/true, /*parse_special=*/true};
        const mtmd_bitmap * bitmaps[] = {bitmap.ptr.get()};

        // 计时从 tokenize 开始：图像预处理也算在 prompt 阶段里，跟纯文本路径的
        // prompt_ms 语义一致（都是「第一个 token 出来之前花的时间」）。
        const auto prompt_begin = std::chrono::steady_clock::now();
        const int32_t tokenize_ret =
                mtmd_tokenize(session->mtmd, chunks.ptr.get(), &text, bitmaps, 1);
        if (tokenize_ret != 0) {
            // 1 = 占位符与图片数不匹配；2 = 图像预处理失败（模型不认这个尺寸/格式）。
            throw_runtime(env, "ERR_TOKENIZE: mtmd_tokenize failed (ret=" +
                                       std::to_string(tokenize_ret) + ")");
            return nullptr;
        }

        const int32_t n_tokens = static_cast<int32_t>(mtmd_helper_get_n_tokens(chunks.ptr.get()));
        // M-RoPE 模型（Qwen-VL 系）里图片占的「位置」比 token 数多，KV 按位置算，
        // 所以 n_ctx 的检查必须用位置数。
        const int64_t n_pos = static_cast<int64_t>(mtmd_helper_get_n_pos(chunks.ptr.get()));
        if (n_tokens <= 0) {
            throw_runtime(env, "ERR_EMPTY_PROMPT: prompt produced no tokens");
            return nullptr;
        }
        if (n_pos + static_cast<int64_t>(max_tokens) > static_cast<int64_t>(session->n_ctx)) {
            // Kotlin 侧按前缀匹配这条错误码，措辞别改（n_prompt 这里是位置数）。
            throw_runtime(env,
                    std::string(kErrTooLong) + ":n_prompt=" + std::to_string(n_pos) +
                            ",n_max=" + std::to_string(max_tokens) +
                            ",n_ctx=" + std::to_string(session->n_ctx));
            return nullptr;
        }
        // 非因果视觉模型（gemma3 系）要求整张图的 embedding 在同一次 ubatch 里过，
        // 拆开算出来的结果不对。Index（Qwen-VL 系）是因果的，不会走到这条警告。
        if (mtmd_decode_use_non_causal(session->mtmd, nullptr) && n_tokens > session->n_batch) {
            LOGW("nativeGenerateImage: n_tokens=%d > n_batch=%d on a non-causal vision model",
                 (int) n_tokens, (int) session->n_batch);
        }

        // 文本 chunk 与图片 chunk 一起喂：helper 内部负责 mtmd_encode_chunk() 然后把
        // 图像 embedding 交给 llama_decode，并且处理 M-RoPE 的位置和非因果注意力。
        llama_pos new_n_past = 0;
        const int32_t eval_ret = mtmd_helper_eval_chunks(
                session->mtmd, session->ctx, chunks.ptr.get(), /*n_past=*/0, /*seq_id=*/0,
                session->n_batch, /*logits_last=*/true, &new_n_past);
        if (eval_ret != 0) {
            throw_runtime(env, "ERR_DECODE: mtmd_helper_eval_chunks failed (ret=" +
                                       std::to_string(eval_ret) + ")");
            return nullptr;
        }
        const auto prompt_end = std::chrono::steady_clock::now();

        std::string output;
        int32_t gen_tokens = 0;
        bool stopped = false;
        if (!sample_loop(env, session, max_tokens, output, gen_tokens, stopped)) {
            return nullptr;  // sample_loop 已经抛过异常
        }

        const auto gen_end = std::chrono::steady_clock::now();
        const long long prompt_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(prompt_end - prompt_begin).count();
        const long long gen_ms =
                std::chrono::duration_cast<std::chrono::milliseconds>(gen_end - prompt_end).count();
        const double tok_s =
                gen_ms > 0 ? static_cast<double>(gen_tokens) * 1000.0 / static_cast<double>(gen_ms) : 0.0;
        LOGI("nativeGenerateImage: %dx%d n_prompt=%d n_pos=%d prompt_ms=%lld gen_tokens=%d "
             "gen_ms=%lld tok_s=%.2f out_bytes=%d stopped=%d",
             (int) width, (int) height, (int) n_tokens, (int) n_pos, prompt_ms, (int) gen_tokens,
             gen_ms, tok_s, (int) output.size(), stopped ? 1 : 0);
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

// 调试日志开关：只写一个 atomic 标志，随时可调（模块加载前 Kotlin 不会调到这里）。
JNIEXPORT void JNICALL
Java_dev_cliptranslate_local_LlamaBridge_nativeSetLogEnabled(
        JNIEnv * /* env */, jobject /* thiz */, jboolean enabled) {
    g_log_enabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

}  // extern "C"
