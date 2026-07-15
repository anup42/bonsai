# Bonsai-8B on-device inference

This app runs PrismML's `Bonsai-8B-Q1_0.gguf` locally on a 64-bit Android device through the PrismML llama.cpp fork. The current accelerator configuration is a **hybrid CPU + Vulkan path**: the model's output projection is placed on the GPU, while the 36 repeating transformer layers and the KV cache remain on the ARM CPU. It is real GPU participation, but it is intentionally not described as full-model GPU inference.

## Runtime architecture

```text
Android UI (MainActivity)
        |
        | Kotlin Flow: streamed text pieces; separate native timing snapshot
        v
BonsaiEngine (single serialized inference dispatcher)
        |
        | JNI
        v
libbonsai-runtime.so
        |
        +-- PrismML llama.cpp / GGML
        |     +-- optimized ARM64 CPU variants (transformer layers and KV cache)
        |     `-- Vulkan backend (output projection when a supported GPU is found)
        |
        `-- GGUF chat template, tokenizer, sampler, and context management
```

`BonsaiEngine` loads `libbonsai-runtime.so`, asks the native layer to load GGML backends from the APK's native-library directory, and serializes model loading and generation on one coroutine dispatcher. The JNI layer then:

1. Initializes llama.cpp and discovers a GPU or integrated-GPU backend.
2. Loads the GGUF model and its embedded chat template.
3. Creates a 4,096-token context and processes the system prompt.
4. Formats each user turn using the model's chat template, tokenizes it, and runs prompt decoding.
5. Samples and decodes one token at a time, returning valid UTF-8 text pieces through JNI.
6. Reports llama.cpp prompt and generation timing back to the UI.

The chat KV state remains in memory between turns. When the 4,096-token context approaches its limit, the runtime discards part of the oldest conversation history while retaining the system-prompt prefix. **New chat** clears the conversation and rebuilds that prefix.

## Model format and storage

The app expects exactly this artifact:

- File: `Bonsai-8B-Q1_0.gguf`
- Size: `1,158,654,496` bytes (about 1.08 GiB as displayed by the app)
- Source revision: `prism-ml/Bonsai-8B-gguf@48516770dd04643643e9f9019a2a349cf26c5dbd`

`Q1_0` describes the low-bit weight representation. It does not mean that every activation, cache entry, or Android runtime operation is one bit.

The model is not bundled in the APK. The app offers two ways to provide it:

- **Download 1.08 GiB** shows a size/source confirmation and downloads the official file directly. Downloads are resumable through a temporary `.part` file.
- **Import model** uses Android's document picker and copies a user-selected file into app-private storage.

Before replacing the active model, the app checks the exact byte count, the `GGUF` file signature, and PrismML's published SHA-256 (`284a335aa3fb2ced3b1b01fcb40b08aa783e3b70832767f0dd2e3fdfa134bd54`). The hash is checked on the background I/O dispatcher and the UI reports a separate verification phase. The final internal location is:

```text
<application files directory>/models/Bonsai-8B-Q1_0.gguf
```

The loader can also discover a valid copy under the app-specific external-files directory at `models/Bonsai-8B-Q1_0.gguf`. Keep additional free space available during download or import: the app requires the remaining model bytes plus 64 MiB of headroom.

Resume responses are accepted only when `Content-Range` begins at the saved byte offset and reports the expected total size. If the server ignores a range request or returns HTTP 416, the app safely restarts from the full response; the resume dialog also offers an explicit **Restart** action for other persistent server failures. A process-wide mutex prevents recreated activities from writing the same temporary file concurrently. A complete `.part` file left by an interrupted finalization is verified and promoted without requesting an invalid end-of-file range; invalid complete partials are discarded. Download and import loops are bounded to the published model size, and final replacement uses a same-directory atomic move when the filesystem supports it.

## CPU and Vulkan execution

Vulkan support is built into the `:llama` Android library with `GGML_VULKAN=ON`. At startup, GGML dynamically loads `libggml-vulkan.so` from the installed application's native-library directory. If no GPU backend is found, the runtime uses its ARM CPU path. If a GPU model load fails, it retries that load with GPU layers disabled.

When Vulkan offload is available, the current native configuration uses:

```text
n_gpu_layers   = 1       # output projection only
n_batch        = 1       # conservative hybrid decode/prefill batch
offload_kqv    = false   # KV/attention work stays on CPU
op_offload     = false
flash_attn     = disabled
CPU threads    = 2..6, leaving two detected cores as headroom
```

On the verified Snapdragon/Adreno device, llama.cpp reported:

```text
offloaded 1/37 layers to GPU
CPU_Mapped model buffer size = 1015.97 MiB
Vulkan0 model buffer size    =   83.33 MiB
CPU KV buffer size           =  576.00 MiB (4,096-token context)
active compute backend       = Hybrid CPU + Vulkan / Adreno (TM) 830
```

In this model, that `1/37` entry is the output layer; it does not mean one of the 36 repeating transformer layers is on the GPU.

### Why full GPU offload is not enabled

Full `37/37` offload was tested on the connected Adreno 830 device. The model could be loaded, but prompt prefill caused a Vulkan `DeviceLost` failure. Reducing the GPU batch to one avoided that driver failure but produced invalid, all-zero output; an attempted floating-point norm override also produced invalid activations. Those results are not acceptable inference.

The stable policy therefore offloads only the Q1 output projection and retains the already verified ARM implementation for the repeating layers. This is a correctness and driver-compatibility decision, not a claim that every Adreno device has the same limitation. A future driver or backend revision should be validated for coherent output before increasing `n_gpu_layers`.

## Performance expectations

One verified thinking-mode run on a Galaxy `SM-F966B` running Android 16 with an Adreno 830 generated 137 tokens at approximately **4.09 tokens/second**, displayed 380 characters of model-generated reasoning, and returned the correct final answer `42` for `17 + 25`. Treat this as evidence that this exact APK/model/device combination worked, not as a general benchmark.

Hybrid GPU use does **not** guarantee a speedup. Moving only the output projection introduces CPU/GPU synchronization and transfer overhead, and the optimized ARM-only route can be faster for this workload. Performance also varies with prompt length, context occupancy, thermal state, power mode, driver version, and sampling behavior.

The sampler currently uses temperature `0.5`, top-k `20`, and top-p `0.9`. Direct UI turns and the automatic demo allow 160 generated tokens. Thinking turns allow 320 because reasoning consumes part of the budget. The engine clamps all callers to a maximum of 512.

## Thinking and visible reasoning

Thinking is an optional, persistent UI setting and is off by default. Each turn reads the current switch state, so it can be changed without starting a new chat:

- **Off:** the embedded Qwen3 chat template supplies its normal empty `<think></think>` prefix and the model answers directly. The UI shows only the final answer.
- **On:** the native prompt builder verifies and replaces that exact terminal disabled-thinking suffix with an open `<think>` marker. The model can then generate a reasoning section, close it with `</think>`, and continue with its final answer.

The streaming parser handles a prefilled opening marker, an explicitly emitted marker, and tag boundaries split across tokens. It sends reasoning to a separate tinted **On-device reasoning** panel and the answer to the normal assistant body. Conversation history also stores the two fields separately so subsequent chat-template formatting remains valid.

Thinking mode uses the same model, sampler, context, and hybrid CPU/Vulkan backend. It changes prompt formatting and raises the per-turn generation allowance from 160 to 320 tokens; it does not enable a second inference engine or add GPU layers. The displayed text is model-generated reasoning, may be incomplete at the token limit, and should not be treated as a guarantee that the answer is correct. If the pinned model's template no longer matches the expected suffix, the native layer fails explicitly instead of silently using the wrong response mode.

## Build and install

The current native build is configured for Windows and requires:

- JDK 17
- Android SDK 36
- Android NDK `28.2.13676358`
- An ARM64 Android target (`arm64-v8a`)
- Git and PowerShell to initialize the pinned PrismML llama.cpp submodule and native host tools

The NDK supplies the Android Vulkan loader, SPIR-V headers, and Windows `glslc`. LLVM-MinGW builds llama.cpp's Windows host-side Vulkan shader generator; the Android runtime libraries themselves are cross-compiled by the NDK.

Prepare the pinned native dependencies, then build the debug APK from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-native.ps1
.\gradlew.bat :app:assembleDebug
```

The bootstrap script is idempotent. It initializes `.vendor/llama.cpp`, applies the tracked `patches/llama-vulkan-host-ninja.patch`, and downloads checksum-verified LLVM-MinGW 20260616 and Vulkan-Hpp 1.3.275 archives into the ignored `.tools` directory. These host-side dependencies are not packaged into the Android app.

Confirm that adb can see an authorized device, then install and launch:

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.prismml.bonsai/.MainActivity
```

The APK supports only `arm64-v8a`, and the app's minimum Android version is API 28. Vulkan is optional at runtime because the CPU backend remains available, but GPU behavior depends on the device's Vulkan driver. A modern high-memory phone is recommended: the GGUF is about 1.08 GiB, and the model, 4,096-token KV cache, native buffers, Android process, and temporary import/download storage all require additional memory and disk space.

## Using the app

1. Launch the app. If no valid model exists, choose **Download 1.08 GiB**, review the size/source confirmation, or import the exact official GGUF.
2. Keep the app open while it downloads and verifies. If interrupted, relaunch and choose **Resume download**; saved progress is retained.
3. Wait for model loading to finish. The status area reports the selected backend, and the app runs a short direct-mode inference check after successful acquisition.
4. Leave **Thinking** off for a direct response, or enable it to stream model-generated reasoning into a separate panel before the final answer.
5. Enter a prompt or use one of the example prompt chips. Text streams into the assistant message.
6. Read the generated-token count and generation tokens/second shown under the response.
7. Use **New chat** to clear conversation history and reset the native KV state.

## Verification

Do not infer GPU use merely from Vulkan being packaged in the APK. Verify both backend selection and coherent inference:

```powershell
adb logcat -c
adb shell am force-stop com.prismml.bonsai
adb shell am start -n com.prismml.bonsai/.MainActivity
adb logcat -d | Select-String "GPU backend detected|offloaded|active compute backend|preparing prompt|BONSAI_DEMO_INFERENCE|BONSAI_THINKING_INFERENCE"
```

A successful hybrid run should contain lines equivalent to:

```text
GPU backend detected: Vulkan / Adreno (TM) 830
offloaded 1/37 layers to GPU
active compute backend: Hybrid CPU + Vulkan / Adreno (TM) 830
preparing prompt: thinking=enabled max_tokens=320
BONSAI_DEMO_INFERENCE_OK ... thinking_enabled=true reasoning_chars=380 generated_tokens=137 tg_tps=4.09 ...
BONSAI_THINKING_INFERENCE_OK reasoning_chars=380 answer_chars=21
```

Direct turns log `thinking=disabled max_tokens=160` and normally report `reasoning_chars=0`. The completion line is important: backend initialization alone does not prove that inference produced valid text. Also inspect the response on screen and confirm that the app process remains alive after generation.

## Troubleshooting

### The app says `CPU`

- Confirm the device is ARM64 and that the installed APK contains `lib/arm64-v8a/libggml-vulkan.so`.
- Check logcat for `load_backend` and `GPU backend detected` messages.
- The Vulkan driver may not expose a GGML-compatible GPU device; CPU fallback is expected in that case.

### Model download or import fails

- Confirm that the file is exactly `1,158,654,496` bytes and has SHA-256 `284a335aa3fb2ced3b1b01fcb40b08aa783e3b70832767f0dd2e3fdfa134bd54`.
- Free enough storage for the model, the temporary file, and the app's 64 MiB safety margin.
- A download can resume from its `.part` file. Ignored ranges and HTTP 416 restart safely; use **Restart** in the resume dialog if another server error persists.

### Model load fails or the app exits

- Capture `bonsai-runtime`, `AndroidRuntime`, `AdrenoVK`, and Vulkan messages from logcat.
- Check for memory pressure; the 4,096-token CPU KV cache alone was 576 MiB on the verified model.
- Do not increase `n_gpu_layers` on Adreno without verifying prompt prefill and coherent generated output. `VK_ERROR_DEVICE_LOST`, a surviving process with blank text, or zeroed/garbled output all count as failures.

### Generation returns no visible answer

With thinking enabled, the model may reach the 320-token limit before closing `</think>` or producing a final answer. The reasoning panel preserves the text received so far and the UI reports that the final answer was not completed. Retry with a narrower prompt or turn Thinking off for a direct answer.

## Privacy and network behavior

Model inference, tokenization, chat history, and generated text run inside the Android application process. Prompts are not sent to a remote inference service. Network access is used to download the model only when the user selects the download action.

The Android manifest disables backup, and the data-extraction rules exclude app files, databases, preferences, and app-specific external files from cloud backup and device transfer. This keeps the large model and local chat state out of Android's backup pipeline. As with any application, device compromise, debugging access, screenshots, or user-installed monitoring software are outside this app-level privacy boundary.

## Verified configuration

Local verification artifacts were captured with filtered logcat output, UI hierarchy, and screenshots. They are deliberately excluded from Git because they contain device-specific metadata. The observed configuration was:

- Device: Samsung Galaxy `SM-F966B`
- OS: Android 16
- GPU: Adreno 830 via Vulkan
- Backend label: `Hybrid CPU + Vulkan / Adreno (TM) 830`
- GPU model buffer: 83.33 MiB
- Offload: output layer only (`1/37`)
- Demonstrated thinking generation: 137 tokens at 4.09 tokens/second, 380 reasoning characters, and the correct final answer `42`

These values document one validated run. They should be rechecked after changes to the model, PrismML llama.cpp fork, NDK, Vulkan headers/shaders, GPU driver, context parameters, or offload policy.
