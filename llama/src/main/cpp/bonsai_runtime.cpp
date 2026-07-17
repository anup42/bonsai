#define LOG_TAG "bonsai-runtime"

#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <exception>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "sampling.h"

#ifndef LOG_MIN_LEVEL
#if defined(NDEBUG)
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#else
#define LOG_MIN_LEVEL ANDROID_LOG_VERBOSE
#endif
#endif

#define SHOULD_LOG(priority) ((priority) >= LOG_MIN_LEVEL)
#define LOGV(...) do { if (SHOULD_LOG(ANDROID_LOG_VERBOSE)) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGD(...) do { if (SHOULD_LOG(ANDROID_LOG_DEBUG)) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGI(...) do { if (SHOULD_LOG(ANDROID_LOG_INFO)) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGW(...) do { if (SHOULD_LOG(ANDROID_LOG_WARN)) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGE(...) do { if (SHOULD_LOG(ANDROID_LOG_ERROR)) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while (0)

namespace {

constexpr int N_THREADS_MIN = 2;
constexpr int N_THREADS_MAX = 6;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int CPU_BATCH_SIZE = 256;
constexpr int HYBRID_GPU_BATCH_SIZE = 1;
constexpr int OVERFLOW_HEADROOM = 8;
constexpr int THINKING_TOKEN_BUDGET = 256;
// Largest model artifact validated with output-projection-only Vulkan offload
// on the connected Adreno 830. Keep this tied to an exercised GGUF rather than
// a theoretical memory estimate: driver stability depends on the compute graph.
constexpr int64_t ADRENO_GPU_MODEL_MAX_BYTES =
        1074969344LL; // Ternary Bonsai 4B Q2_0
constexpr const char * UNSTABLE_VULKAN_DEVICE = "Adreno (TM) 830";

constexpr const char * ROLE_SYSTEM = "system";
constexpr const char * ROLE_USER = "user";
constexpr const char * ROLE_ASSISTANT = "assistant";
constexpr const char * THINKING_OPEN_TAG = "<think>";
constexpr const char * THINKING_CLOSE_TAG = "</think>";
constexpr const char * FORCED_THINKING_CLOSE = "\n</think>\n\n";

llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_batch g_batch = {};
bool g_batch_initialized = false;
common_chat_templates_ptr g_chat_templates;
common_sampler * g_sampler = nullptr;

std::vector<common_chat_msg> g_chat_messages;
llama_pos g_system_position = 0;
llama_pos g_current_position = 0;
llama_pos g_stop_position = 0;
int g_context_size = 4096;
int g_batch_size = CPU_BATCH_SIZE;

std::string g_cached_token_chars;
std::ostringstream g_assistant;
std::string g_accelerator_description;
std::string g_compute_backend = "CPU";
bool g_gpu_enabled = false;
bool g_turn_thinking_enabled = false;
bool g_turn_thinking_closed = false;
int g_turn_generated_tokens = 0;
ggml_backend_dev_t g_gpu_device = nullptr;

std::string find_gpu_device() {
    g_gpu_device = nullptr;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(device);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) {
            continue;
        }

        g_gpu_device = device;
        const char * backend_name = ggml_backend_reg_name(ggml_backend_dev_backend_reg(device));
        const char * description = ggml_backend_dev_description(device);
        std::ostringstream label;
        label << (backend_name != nullptr ? backend_name : "GPU");
        if (description != nullptr && description[0] != '\0') {
            label << " / " << description;
        }
        return label.str();
    }
    return "";
}

bool is_gpu_offload_blocked() {
    return g_accelerator_description.find(UNSTABLE_VULKAN_DEVICE) != std::string::npos;
}

int64_t model_file_size(const char * path) {
    struct stat file_stat {};
    if (path == nullptr || stat(path, &file_stat) != 0 || file_stat.st_size < 0) {
        return -1;
    }
    return static_cast<int64_t>(file_stat.st_size);
}

int android_log_prio_from_ggml(ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN: return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_INFO: return ANDROID_LOG_INFO;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default: return ANDROID_LOG_DEFAULT;
    }
}

void android_log_callback(ggml_log_level level, const char * text, void *) {
    const int priority = android_log_prio_from_ggml(level);
    if (!SHOULD_LOG(priority)) {
        return;
    }
    __android_log_write(priority, LOG_TAG, text);
}

void reset_short_term_state() {
    g_stop_position = 0;
    g_turn_thinking_enabled = false;
    g_turn_thinking_closed = false;
    g_turn_generated_tokens = 0;
    g_cached_token_chars.clear();
    g_assistant.str("");
    g_assistant.clear();
}

void reset_chat_state(bool clear_kv = true) {
    g_chat_messages.clear();
    g_system_position = 0;
    g_current_position = 0;
    reset_short_term_state();

    if (clear_kv && g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

std::string trim_whitespace(const std::string & value) {
    auto begin = value.begin();
    while (begin != value.end() && std::isspace(static_cast<unsigned char>(*begin))) {
        ++begin;
    }
    auto end = value.end();
    while (end != begin && std::isspace(static_cast<unsigned char>(*(end - 1)))) {
        --end;
    }
    return std::string(begin, end);
}

bool ends_with_partial_thinking_close(const std::string & text) {
    const std::string close_tag(THINKING_CLOSE_TAG);
    const size_t maximum_prefix = std::min(text.size(), close_tag.size() - 1);
    for (size_t length = maximum_prefix; length > 0; --length) {
        if (text.compare(text.size() - length, length, close_tag, 0, length) == 0) {
            return true;
        }
    }
    return false;
}

void finish_assistant_message() {
    const std::string text = g_assistant.str();
    if (!text.empty()) {
        common_chat_msg message;
        message.role = ROLE_ASSISTANT;
        if (g_turn_thinking_enabled) {
            const size_t close_position = text.find(THINKING_CLOSE_TAG);
            if (close_position != std::string::npos) {
                std::string reasoning = text.substr(0, close_position);
                if (reasoning.compare(0, std::string(THINKING_OPEN_TAG).size(), THINKING_OPEN_TAG) == 0) {
                    reasoning.erase(0, std::string(THINKING_OPEN_TAG).size());
                }
                message.reasoning_content = trim_whitespace(reasoning);
                message.content = trim_whitespace(
                        text.substr(close_position + std::string(THINKING_CLOSE_TAG).size()));
            } else {
                // Keep a truncated thought structurally separate so the next
                // template pass does not reinterpret it as the final answer.
                message.reasoning_content = trim_whitespace(text);
                LOGW("thinking response ended before </think>");
            }
        } else {
            message.content = text;
        }
        g_chat_messages.push_back(message);
    }
    g_assistant.str("");
    g_assistant.clear();
}

int shift_context() {
    const int available_history = (int) (g_current_position - g_system_position);
    if (available_history <= 0) {
        LOGE("context is full but there is no conversation history to discard");
        return 0;
    }

    const int n_discard = std::max(1, available_history / 2);
    LOGW("context full; discarding %d tokens", n_discard);
    llama_memory_seq_rm(
            llama_get_memory(g_context),
            0,
            g_system_position,
            g_system_position + n_discard);
    llama_memory_seq_add(
            llama_get_memory(g_context),
            0,
            g_system_position + n_discard,
            g_current_position,
            -n_discard);
    g_current_position -= n_discard;
    if (g_stop_position > 0) {
        g_stop_position = std::max(g_current_position, g_stop_position - n_discard);
    }
    return n_discard;
}

bool ensure_context_capacity(int incoming_tokens) {
    const int limit = g_context_size - OVERFLOW_HEADROOM;
    while (g_current_position + incoming_tokens >= limit) {
        if (shift_context() == 0) {
            return false;
        }
    }
    return true;
}

int decode_batch(const char * operation) {
    try {
        const int decode_result = llama_decode(g_context, g_batch);
        if (decode_result != 0) {
            LOGE("llama_decode failed during %s: %d", operation, decode_result);
            return 1;
        }
    } catch (const std::exception & error) {
        // Vulkan-Hpp reports device loss as vk::DeviceLostError. Never let a
        // backend C++ exception cross JNI: Android otherwise terminates the
        // process with SIGABRT before Kotlin can show or recover from the error.
        LOGE("llama_decode threw during %s: %s", operation, error.what());
        return 2;
    } catch (...) {
        LOGE("llama_decode threw an unknown exception during %s", operation);
        return 2;
    }
    return 0;
}

int decode_tokens(const llama_tokens & tokens, llama_pos start_pos, bool compute_last_logit) {
    if (tokens.empty()) {
        return 0;
    }

    for (int i = 0; i < (int) tokens.size(); i += g_batch_size) {
        const int current_batch = std::min((int) tokens.size() - i, g_batch_size);

        common_batch_clear(g_batch);
        for (int j = 0; j < current_batch; ++j) {
            const bool want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            common_batch_add(g_batch, tokens[i + j], start_pos + i + j, {0}, want_logit);
        }

        const int decode_result = decode_batch("token batch");
        if (decode_result != 0) {
            return decode_result;
        }
    }

    return 0;
}

bool close_thinking_section(const char * reason) {
    const std::string closing_text(FORCED_THINKING_CLOSE);
    const llama_tokens closing_tokens = common_tokenize(g_context, closing_text, false, false);
    if (closing_tokens.empty() || !ensure_context_capacity((int) closing_tokens.size())) {
        return false;
    }
    for (const llama_token token : closing_tokens) {
        common_sampler_accept(g_sampler, token, true);
    }
    if (decode_tokens(closing_tokens, g_current_position, true) != 0) {
        return false;
    }
    g_current_position += (llama_pos) closing_tokens.size();
    g_assistant << closing_text;
    g_turn_thinking_closed = true;
    LOGI("closed reasoning %s after %d generated tokens",
         reason,
         g_turn_generated_tokens);
    return true;
}

void throw_illegal_state(JNIEnv * env, const char * message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
    }
}

int process_system_prompt(const std::string & prompt) {
    reset_chat_state(true);

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        // Some templates (including Bonsai 27B's Qwen3.5 template) reject a
        // system-only conversation with "No user query found". Keep the system
        // message in structured history and render it together with the first
        // user turn instead of invoking the template during model loading.
        common_chat_msg system_message;
        system_message.role = ROLE_SYSTEM;
        system_message.content = prompt;
        g_chat_messages.push_back(std::move(system_message));
        return 0;
    }

    const llama_tokens tokens = common_tokenize(
            g_context, prompt, false, false);
    if ((int) tokens.size() > g_context_size - OVERFLOW_HEADROOM) {
        LOGE("system prompt too long: %d tokens", (int) tokens.size());
        return 1;
    }

    if (decode_tokens(tokens, g_current_position, false) != 0) {
        return 2;
    }

    g_system_position = g_current_position = (llama_pos) tokens.size();
    return 0;
}

struct Utf8Conversion {
    bool complete = true;
    bool replaced_invalid_byte = false;
    std::vector<jchar> utf16;
    std::string normalized_utf8;
};

Utf8Conversion convert_utf8(const std::string & input) {
    Utf8Conversion result;
    result.utf16.reserve(input.size());
    result.normalized_utf8.reserve(input.size());

    const auto * bytes = reinterpret_cast<const uint8_t *>(input.data());
    size_t index = 0;
    while (index < input.size()) {
        const uint8_t lead = bytes[index];
        uint32_t code_point = 0;
        size_t length = 0;

        if (lead <= 0x7f) {
            code_point = lead;
            length = 1;
        } else if (lead >= 0xc2 && lead <= 0xdf) {
            code_point = lead & 0x1f;
            length = 2;
        } else if (lead >= 0xe0 && lead <= 0xef) {
            code_point = lead & 0x0f;
            length = 3;
        } else if (lead >= 0xf0 && lead <= 0xf4) {
            code_point = lead & 0x07;
            length = 4;
        }

        if (length > 1 && input.size() - index < length) {
            result.complete = false;
            return result;
        }

        bool valid = length != 0;
        for (size_t offset = 1; valid && offset < length; ++offset) {
            const uint8_t continuation = bytes[index + offset];
            valid = (continuation & 0xc0) == 0x80;
            code_point = (code_point << 6) | (continuation & 0x3f);
        }
        if (valid && length == 3) {
            valid = code_point >= 0x800 && !(code_point >= 0xd800 && code_point <= 0xdfff);
        } else if (valid && length == 4) {
            valid = code_point >= 0x10000 && code_point <= 0x10ffff;
        }

        if (!valid) {
            result.replaced_invalid_byte = true;
            result.utf16.push_back(static_cast<jchar>(0xfffd));
            result.normalized_utf8.append("\xef\xbf\xbd");
            ++index;
            continue;
        }

        result.normalized_utf8.append(input, index, length);
        if (code_point <= 0xffff) {
            result.utf16.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000;
            result.utf16.push_back(static_cast<jchar>(0xd800 + (code_point >> 10)));
            result.utf16.push_back(static_cast<jchar>(0xdc00 + (code_point & 0x3ff)));
        }
        index += length;
    }

    return result;
}

jstring new_java_string(JNIEnv * env, const std::vector<jchar> & utf16) {
    static constexpr jchar EMPTY = 0;
    const jchar * chars = utf16.empty() ? &EMPTY : utf16.data();
    return env->NewString(chars, static_cast<jsize>(utf16.size()));
}

void append_utf8(std::string & output, uint32_t code_point) {
    if (code_point <= 0x7f) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    }
}

std::string java_string_to_utf8(JNIEnv * env, jstring input) {
    if (input == nullptr) {
        return {};
    }

    const jsize length = env->GetStringLength(input);
    const jchar * chars = env->GetStringChars(input, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result;
    result.reserve(static_cast<size_t>(length) * 2);
    for (jsize index = 0; index < length; ++index) {
        uint32_t code_point = chars[index];
        if (code_point >= 0xd800 && code_point <= 0xdbff) {
            if (index + 1 < length) {
                const uint32_t low = chars[index + 1];
                if (low >= 0xdc00 && low <= 0xdfff) {
                    code_point = 0x10000 + ((code_point - 0xd800) << 10) + (low - 0xdc00);
                    ++index;
                } else {
                    code_point = 0xfffd;
                }
            } else {
                code_point = 0xfffd;
            }
        } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
            code_point = 0xfffd;
        }
        append_utf8(result, code_point);
    }
    env->ReleaseStringChars(input, chars);
    return result;
}

void unload_runtime() {
    reset_chat_state(false);

    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_chat_templates.reset();
    if (g_batch_initialized) {
        llama_batch_free(g_batch);
        g_batch = {};
        g_batch_initialized = false;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_gpu_enabled = false;
    g_compute_backend = "CPU";
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeInitialize(
        JNIEnv * env,
        jobject,
        jstring native_library_directory) {
    llama_log_set(android_log_callback, nullptr);

    const char * path = env->GetStringUTFChars(native_library_directory, nullptr);
    LOGI("loading ggml backends from %s", path);
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(native_library_directory, path);

    llama_backend_init();
    g_accelerator_description = find_gpu_device();
    if (g_accelerator_description.empty()) {
        LOGW("no GPU backend detected; CPU fallback will be used");
    } else {
        LOGI("GPU backend detected: %s", g_accelerator_description.c_str());
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeLoad(
        JNIEnv * env,
        jobject,
        jstring model_path,
        jint context_size) {
    unload_runtime();

    g_context_size = std::max(512, (int) context_size);

    const char * model_path_chars = env->GetStringUTFChars(model_path, nullptr);
    const int64_t model_bytes = model_file_size(model_path_chars);
    llama_model_params model_params = llama_model_default_params();
    const bool gpu_available = g_gpu_device != nullptr && llama_supports_gpu_offload();
    const bool small_gpu_candidate = gpu_available && model_bytes > 0 &&
            model_bytes <= ADRENO_GPU_MODEL_MAX_BYTES;
    const bool gpu_blocked = gpu_available && is_gpu_offload_blocked() && !small_gpu_candidate;
    const bool request_gpu = gpu_available && !gpu_blocked;
    // Q1_0 full-model Vulkan offload produces invalid activations on the
    // connected Adreno driver. Small models can still use the conservative
    // output-projection path; larger models retain the established device
    // safety block.
    model_params.n_gpu_layers = request_gpu ? 1 : 0;
    LOGI(
            "loading model: %s (%lld bytes, GPU offload: %s)",
            model_path_chars,
            static_cast<long long>(model_bytes),
            request_gpu ? "output projection" : "disabled");
    if (gpu_blocked) {
        LOGW("Vulkan offload disabled for %s after VK_ERROR_DEVICE_LOST crashes",
             g_accelerator_description.c_str());
    }
    g_model = llama_model_load_from_file(model_path_chars, model_params);
    if (g_model == nullptr && request_gpu) {
        LOGW("GPU model load failed; retrying with CPU fallback");
        model_params.n_gpu_layers = 0;
        g_model = llama_model_load_from_file(model_path_chars, model_params);
    }
    env->ReleaseStringUTFChars(model_path, model_path_chars);

    if (g_model == nullptr) {
        LOGE("model load failed");
        return 1;
    }

    g_gpu_enabled = request_gpu && model_params.n_gpu_layers != 0;
    g_compute_backend = g_gpu_enabled
            ? "Hybrid CPU + " + g_accelerator_description
            : (gpu_blocked ? "CPU (Adreno Vulkan safety fallback)" : "CPU");
    g_batch_size = g_gpu_enabled ? HYBRID_GPU_BATCH_SIZE : CPU_BATCH_SIZE;
    LOGI("active compute backend: %s", g_compute_backend.c_str());

    const int cpu_count = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int n_threads = std::max(
            N_THREADS_MIN,
            std::min(N_THREADS_MAX, cpu_count - N_THREADS_HEADROOM));

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = g_context_size;
    context_params.n_batch = g_batch_size;
    context_params.n_ubatch = g_batch_size;
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;
    context_params.offload_kqv = false;
    context_params.op_offload = false;
    context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    LOGI(
            "initializing context: n_ctx=%d n_batch=%d threads=%d GPU_KQV=off flash_attn=off",
            g_context_size,
            g_batch_size,
            n_threads);
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        LOGE("context init failed");
        unload_runtime();
        return 2;
    }

    g_batch = llama_batch_init(g_batch_size, 0, 1);
    g_batch_initialized = true;
    g_chat_templates = common_chat_templates_init(g_model, "");

    common_params_sampling sampling_params;
    sampling_params.seed = 42;
    sampling_params.temp = 0.2f;
    sampling_params.top_k = 20;
    sampling_params.top_p = 0.85f;
    sampling_params.min_p = 0.0f;
    g_sampler = common_sampler_init(g_model, sampling_params);
    if (g_sampler == nullptr) {
        LOGE("sampler init failed");
        unload_runtime();
        return 3;
    }

    reset_chat_state(true);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeSetSystemPrompt(
        JNIEnv * env,
        jobject,
        jstring prompt) {
    const std::string prompt_string = java_string_to_utf8(env, prompt);
    return process_system_prompt(prompt_string);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativePreparePrompt(
        JNIEnv * env,
        jobject,
        jstring prompt,
        jint max_tokens,
        jboolean enable_thinking) {
    if (g_context == nullptr || g_model == nullptr || g_sampler == nullptr) {
        LOGE("cannot prepare prompt: runtime is not ready");
        return 3;
    }

    reset_short_term_state();
    common_sampler_reset(g_sampler);
    llama_perf_context_reset(g_context);

    const std::string prompt_string = java_string_to_utf8(env, prompt);

    std::string formatted_prompt = prompt_string;
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    std::vector<common_chat_msg> turn_messages;
    if (has_chat_template) {
        common_chat_msg user_message;
        user_message.role = ROLE_USER;
        user_message.content = prompt_string;
        turn_messages = g_chat_messages;
        turn_messages.push_back(std::move(user_message));

        common_chat_templates_inputs template_inputs;
        template_inputs.messages = turn_messages;
        template_inputs.add_generation_prompt = true;
        template_inputs.use_jinja = true;
        template_inputs.enable_thinking = enable_thinking == JNI_TRUE;
        // The app parses text/reasoning tags itself and does not use tool-call
        // grammars. Avoid automatic output-parser generation, which can invoke
        // template branches unrelated to prompt rendering and throw.
        template_inputs.force_pure_content = true;
        try {
            formatted_prompt = common_chat_templates_apply(
                    g_chat_templates.get(), template_inputs).prompt;
        } catch (const std::exception & error) {
            LOGE("chat template failed for user turn: %s", error.what());
            return 6;
        } catch (...) {
            LOGE("chat template failed for user turn with an unknown exception");
            return 6;
        }

        // The whole structured conversation is rendered for each turn. This
        // avoids diff-formatting the system-only prefix, which is invalid for
        // Qwen3.5 templates that require a user query.
        llama_memory_clear(llama_get_memory(g_context), false);
        g_system_position = 0;
        g_current_position = 0;
    } else if (enable_thinking == JNI_TRUE) {
        LOGE("thinking mode requires an embedded chat template");
        return 5;
    }
    if (enable_thinking == JNI_TRUE) {
        g_turn_thinking_enabled = true;
        LOGI("thinking enabled through chat-template parameters");
    }
    if (formatted_prompt.empty()) {
        LOGE("chat template produced an empty prompt");
        return 6;
    }
    if (has_chat_template) {
        LOGD("formatted conversation with %d messages", (int) turn_messages.size());
    }
    LOGI("preparing prompt: thinking=%s max_tokens=%d",
         g_turn_thinking_enabled ? "enabled" : "disabled",
         (int) max_tokens);

    llama_tokens tokens = common_tokenize(
            g_context, formatted_prompt, has_chat_template, has_chat_template);
    const int maximum_prompt_tokens = g_context_size - g_system_position - OVERFLOW_HEADROOM - 1;
    if ((int) tokens.size() > maximum_prompt_tokens) {
        LOGE("user prompt too long: %d tokens (maximum %d)", (int) tokens.size(), maximum_prompt_tokens);
        return 2;
    }

    if (!ensure_context_capacity((int) tokens.size() + 1)) {
        LOGE("not enough context capacity for %d prompt tokens", (int) tokens.size());
        return 4;
    }

    if (decode_tokens(tokens, g_current_position, true) != 0) {
        return 1;
    }

    g_current_position += (llama_pos) tokens.size();
    if (has_chat_template) {
        g_chat_messages = std::move(turn_messages);
    }
    g_stop_position = g_current_position + std::max(1, (int) max_tokens);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeNextToken(JNIEnv * env, jobject) {
    if (g_context == nullptr || g_model == nullptr || g_sampler == nullptr) {
        throw_illegal_state(env, "The native inference runtime is not ready");
        return nullptr;
    }

    if (g_current_position >= g_context_size - OVERFLOW_HEADROOM) {
        if (shift_context() == 0) {
            throw_illegal_state(env, "The model context is full");
            return nullptr;
        }
    }

    if (g_current_position >= g_stop_position) {
        finish_assistant_message();
        return nullptr;
    }

    if (g_turn_thinking_enabled &&
        !g_turn_thinking_closed &&
        g_turn_generated_tokens >= THINKING_TOKEN_BUDGET &&
        g_cached_token_chars.empty() &&
        !ends_with_partial_thinking_close(g_assistant.str())) {
        if (!close_thinking_section("at the reasoning budget")) {
            finish_assistant_message();
            throw_illegal_state(env, "Could not reserve space for the final answer");
            return nullptr;
        }
        const Utf8Conversion conversion = convert_utf8(FORCED_THINKING_CLOSE);
        return new_java_string(env, conversion.utf16);
    }

    const llama_token new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id) &&
        g_turn_thinking_enabled &&
        !g_turn_thinking_closed) {
        LOGW("model emitted end-of-generation before closing reasoning; continuing to final answer");
        if (!close_thinking_section("after an early end-of-generation token")) {
            finish_assistant_message();
            throw_illegal_state(env, "Could not close the reasoning section");
            return nullptr;
        }
        const Utf8Conversion conversion = convert_utf8(FORCED_THINKING_CLOSE);
        return new_java_string(env, conversion.utf16);
    }
    common_sampler_accept(g_sampler, new_token_id, true);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, g_current_position, {0}, true);
    if (decode_batch("generated token") != 0) {
        finish_assistant_message();
        throw_illegal_state(env, "Native token decoding failed");
        return nullptr;
    }
    g_current_position++;
    g_turn_generated_tokens++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        finish_assistant_message();
        return nullptr;
    }

    const std::string token_chars = common_token_to_piece(g_context, new_token_id);
    g_cached_token_chars += token_chars;

    const Utf8Conversion conversion = convert_utf8(g_cached_token_chars);
    if (!conversion.complete) {
        return env->NewStringUTF("");
    }
    if (conversion.replaced_invalid_byte) {
        LOGW("replaced invalid UTF-8 byte in generated output");
    }

    jstring result = new_java_string(env, conversion.utf16);
    g_assistant << conversion.normalized_utf8;
    if (g_turn_thinking_enabled && !g_turn_thinking_closed) {
        g_turn_thinking_closed =
                g_assistant.str().find(THINKING_CLOSE_TAG) != std::string::npos;
    }
    g_cached_token_chars.clear();
    return result;
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativePerformance(JNIEnv * env, jobject) {
    double values[4] = {0.0, 0.0, 0.0, 0.0};
    if (g_context != nullptr) {
        const llama_perf_context_data perf = llama_perf_context(g_context);
        values[0] = perf.n_p_eval;
        values[1] = perf.t_p_eval_ms;
        values[2] = perf.n_eval;
        values[3] = perf.t_eval_ms;
    }

    jdoubleArray result = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(result, 0, 4, values);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeBackendDescription(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_compute_backend.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeResetConversation(
        JNIEnv * env,
        jobject,
        jstring system_prompt) {
    const char * prompt_chars = env->GetStringUTFChars(system_prompt, nullptr);
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(system_prompt, prompt_chars);
    return process_system_prompt(prompt);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_prismml_bonsai_runtime_BonsaiEngine_nativeUnload(JNIEnv *, jobject) {
    unload_runtime();
}
