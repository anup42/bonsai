#define LOG_TAG "bonsai-runtime"

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
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

constexpr const char * ROLE_SYSTEM = "system";
constexpr const char * ROLE_USER = "user";
constexpr const char * ROLE_ASSISTANT = "assistant";
constexpr const char * DISABLED_THINKING_SUFFIX = "<think>\n\n</think>\n\n";
constexpr const char * ENABLED_THINKING_SUFFIX = "<think>\n";
constexpr const char * THINKING_OPEN_TAG = "<think>";
constexpr const char * THINKING_CLOSE_TAG = "</think>";

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

std::string chat_add_and_format(const std::string & role, const std::string & content) {
    common_chat_msg message;
    message.role = role;
    message.content = content;

    const bool add_assistant_prompt = role == ROLE_USER;
    std::string formatted = common_chat_format_single(
            g_chat_templates.get(), g_chat_messages, message, add_assistant_prompt, true);
    g_chat_messages.push_back(message);

    LOGD("formatted %s message: %s", role.c_str(), formatted.c_str());
    return formatted;
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

bool enable_thinking_in_generation_prompt(std::string & prompt) {
    const std::string disabled_suffix(DISABLED_THINKING_SUFFIX);
    if (prompt.size() < disabled_suffix.size() ||
        prompt.compare(prompt.size() - disabled_suffix.size(), disabled_suffix.size(), disabled_suffix) != 0) {
        return false;
    }
    prompt.replace(
            prompt.size() - disabled_suffix.size(),
            disabled_suffix.size(),
            ENABLED_THINKING_SUFFIX);
    return true;
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

        const int decode_result = llama_decode(g_context, g_batch);
        if (decode_result != 0) {
            LOGE("llama_decode failed: %d", decode_result);
            return 1;
        }
    }

    return 0;
}

void throw_illegal_state(JNIEnv * env, const char * message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
    }
}

int process_system_prompt(const std::string & prompt) {
    reset_chat_state(true);

    std::string formatted_prompt = prompt;
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_prompt = chat_add_and_format(ROLE_SYSTEM, prompt);
    }

    const llama_tokens tokens = common_tokenize(
            g_context, formatted_prompt, has_chat_template, has_chat_template);
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

bool is_valid_utf8(const char * string) {
    if (string == nullptr) {
        return true;
    }

    const auto * bytes = (const unsigned char *) string;
    int num = 0;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
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

    llama_model_params model_params = llama_model_default_params();
    const bool request_gpu = g_gpu_device != nullptr && llama_supports_gpu_offload();
    // Limit Adreno to the Q1_0 output projection. Full repeating-layer offload
    // currently returns invalid activations on this driver, while this matrix
    // path is stable and retains the verified ARM CPU transformer path.
    model_params.n_gpu_layers = request_gpu ? 1 : 0;
    const char * model_path_chars = env->GetStringUTFChars(model_path, nullptr);
    LOGI(
            "loading model: %s (GPU offload: %s)",
            model_path_chars,
            request_gpu ? "Q1_0 output projection" : "disabled");
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
    g_compute_backend = g_gpu_enabled ? "Hybrid CPU + " + g_accelerator_description : "CPU";
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
    sampling_params.temp = 0.5f;
    sampling_params.top_k = 20;
    sampling_params.top_p = 0.9f;
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
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    const std::string prompt_string(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
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

    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    const std::string prompt_string(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    std::string formatted_prompt = prompt_string;
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_prompt = chat_add_and_format(ROLE_USER, prompt_string);
    }
    if (enable_thinking == JNI_TRUE) {
        if (!has_chat_template || !enable_thinking_in_generation_prompt(formatted_prompt)) {
            LOGE("thinking mode is incompatible with this model's chat template");
            return 5;
        }
        g_turn_thinking_enabled = true;
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

    const llama_token new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, g_current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGE("llama_decode failed for generated token");
        finish_assistant_message();
        throw_illegal_state(env, "Native token decoding failed");
        return nullptr;
    }
    g_current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        finish_assistant_message();
        return nullptr;
    }

    const std::string token_chars = common_token_to_piece(g_context, new_token_id);
    g_cached_token_chars += token_chars;

    if (!is_valid_utf8(g_cached_token_chars.c_str())) {
        return env->NewStringUTF("");
    }

    jstring result = env->NewStringUTF(g_cached_token_chars.c_str());
    g_assistant << g_cached_token_chars;
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
