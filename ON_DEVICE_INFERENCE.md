# iBit on-device inference

iBit runs official 1-bit and ternary Bonsai GGUF language models locally on a 64-bit Android device through the PrismML llama.cpp fork. The runtime uses an **adaptive CPU/Vulkan policy**. ARM CPU inference is always available; a supported GPU may receive the model's output projection while the repeating transformer layers and KV cache stay on CPU. On Adreno 830, artifacts up to the verified Ternary Bonsai 4B Q2_0 size of 1,074,969,344 bytes use that hybrid path. Larger models stay on CPU because on-device crash records with the next catalog size, Bonsai 8B Q1_0, show repeatable Vulkan device loss during prompt decoding.

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
        |     +-- optimized ARM64 CPU variants (all work on blocked/fallback devices)
        |     `-- Vulkan backend (output projection when the device/model policy permits)
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

Embedded chat templates are applied to a complete system-plus-user conversation rather than to the system prompt in isolation. This is required by the Qwen3.5 template in Bonsai 27B, which deliberately rejects a system-only message list with `No user query found in messages`. The app renders structured history again for each turn and catches exceptions during per-turn template rendering, so a rendering failure is reported as a model error instead of terminating the Android process.

## Model catalog, format, and storage

The APK contains no model weights. **Models** opens a catalog of eight official text artifacts:

| Model | Format | Exact bytes | On-phone status |
| --- | --- | ---: | --- |
| Bonsai 1.7B | 1-bit `Q1_0` | 248,302,272 | Supported |
| Bonsai 4B | 1-bit `Q1_0` | 572,270,624 | Supported |
| Bonsai 8B | 1-bit `Q1_0` | 1,158,654,496 | Device-verified |
| Bonsai 27B | 1-bit `Q1_0` | 3,803,452,480 | Experimental; high memory |
| Ternary Bonsai 1.7B | ternary `Q2_0` | 463,290,464 | Supported |
| Ternary Bonsai 4B | ternary `Q2_0` | 1,074,969,344 | Supported |
| Ternary Bonsai 8B | ternary `Q2_0` | 2,182,184,672 | Supported |
| Ternary Bonsai 27B | ternary `Q2_0` | 7,165,121,600 | Experimental; high memory |

The complete commit-pinned repository, filename, byte count, and published SHA-256 catalog is maintained in `app/src/main/java/com/samsung/ibit/BonsaiModel.kt`. `Q1_0` and `Q2_0` describe low-bit weight representations; they do not mean that every activation, cache entry, or runtime operation uses one or two bits. Bonsai Image is intentionally excluded because it requires a diffusion/image runtime rather than this text engine.

Selecting a missing model shows its exact download size and starts a resumable official Hugging Face download. The download runs in `ModelDownloadService`, an Android `dataSync` foreground service, so it continues after the activity is backgrounded. Its low-importance ongoing notification reports connecting, byte progress, and checksum verification, opens iBit when tapped, and includes **Stop download**. Stopping disconnects the active HTTP request but deliberately keeps the model-specific `.part` file for the next resume. Selecting a downloaded, inactive model can activate or delete it. Switching models resets the local conversation so KV state from one tokenizer/model is never reused with another. The existing document-picker action imports the currently selected model artifact.

Before any downloaded or imported file is loaded, the app checks the exact byte count, the `GGUF` signature, and that artifact's published SHA-256 on the background I/O dispatcher. Each model has independent final, `.part`, and `.import` paths under:

```text
<application files directory>/models/<official GGUF filename>
```

The loader can also discover a valid copy with the same filename under the app-specific external-files `models` directory. Keep additional free space available during download or import: the app requires the remaining model bytes plus 64 MiB of headroom.

Resume responses are accepted only when `Content-Range` begins at the saved byte offset and reports the expected total size. If the server ignores a range request or returns HTTP 416, the app safely restarts from the full response; the resume dialog also offers an explicit **Restart** action for other persistent server failures. The service accepts one active download at a time. A complete `.part` file left by an interrupted finalization is verified and promoted without requesting an invalid end-of-file range; invalid complete partials are discarded. Download and import loops are bounded to the published model size, and final replacement uses a same-directory atomic move when the filesystem supports it.

Generation runs independently in `InferenceService`, a `specialUse` foreground service. Its ongoing notification shows token progress and exposes the same Stop operation as the in-app button. Download and inference services both use `stopWithTask="false"`; leaving or dismissing the activity therefore does not stop active user-requested work. Android 13 and newer ask for notification permission before starting either operation so the foreground work remains visible.

## CPU and Vulkan execution

Vulkan support is built into the `:llama` Android library with `GGML_VULKAN=ON`. At startup, GGML dynamically loads `libggml-vulkan.so` from the installed application's native-library directory. Packaging or detecting that backend does not mean it will be used for model computation. If no suitable GPU is found, the GPU is blocked, or a GPU model load fails, the runtime uses its ARM CPU path.

The current native configurations are:

```text
Adreno 830, model <= 1,074,969,344 bytes:
n_gpu_layers   = 1       # output projection only
n_batch        = 1       # conservative hybrid decode/prefill batch

Adreno 830, model > 1,074,969,344 bytes:
n_gpu_layers   = 0       # CPU safety fallback
n_batch        = 256

Eligible GPU:
n_gpu_layers   = 1       # output projection only
n_batch        = 1       # conservative hybrid decode/prefill batch

offload_kqv    = false   # KV/attention work stays on CPU
op_offload     = false
flash_attn     = disabled
CPU threads    = 2..6, leaving two detected cores as headroom
```

With an eligible Bonsai model on the connected Snapdragon/Adreno device, llama.cpp reported:

```text
GPU backend detected          = Vulkan / Adreno (TM) 830
offloaded                     = 1/<model layer count> layers to GPU
active compute backend        = Hybrid CPU + Vulkan / Adreno (TM) 830
```

For the 1.7B architecture, `1/29` means the output layer; it does not mean one of the 28 repeating transformer layers is on the GPU. Both tested 4B artifacts reported `1/37`. The KV cache, attention operations, and repeating transformer layers remain on CPU.

### Why Adreno 830 uses a size-aware policy

Full `37/37` offload was tested with Bonsai 8B on the connected Adreno 830 device. The model could be loaded, but prompt prefill caused a Vulkan `DeviceLost` failure. Reducing the GPU batch to one avoided that driver failure but produced invalid output; an attempted floating-point norm override also produced invalid activations. Full `29/29` offload with Bonsai 1.7B also completed without a driver crash but generated all-zero characters. Those results are not acceptable inference.

Output-projection-only offload of Bonsai 8B initially produced coherent answers, but subsequent real use crashed in `nativePreparePrompt` with `vk::DeviceLostError: vk::Queue::submit: ErrorDeviceLost`. By contrast, Bonsai 1.7B, Bonsai 4B Q1_0 (572,270,624 bytes), and Ternary Bonsai 4B Q2_0 (1,074,969,344 bytes) completed direct and thinking prompts with coherent answers and surviving processes. The ternary 4B artifact is the largest catalog model below the failing 8B Q1_0 artifact, so its exact byte count is the Adreno 830 ceiling. The runtime blocks larger files on this GPU. It also catches C++ exceptions around every `llama_decode` call so a backend exception can be returned to Kotlin instead of crossing JNI and aborting the process with `SIGABRT`.

Other GPU descriptions remain eligible for the conservative output-layer path, but that is a capability path rather than a blanket stability claim. Any device/backend revision must pass repeated prompt-prefill, direct-answer, thinking-mode, and process-survival checks before being described as verified.

## Performance expectations

On the verified Galaxy `SM-F966B` running Android 16, a clean Bonsai 1.7B hybrid run answered `17 + 25 = 42` correctly and generated 18 tokens at **20.98 tokens/second**. A thinking-enabled run answered `9 x 9 = 81` correctly and generated 9 tokens at **16.51 tokens/second**. At the new ceiling, Ternary Bonsai 4B answered `17 + 25 = 42` at **3.98 tokens/second**, repeated `9 x 9 = 81` at **3.67 tokens/second**, and completed a thinking-enabled `2 + 2 = 4` turn at **3.38 tokens/second**. Treat these as correctness and crash-regression checks, not general benchmarks.

Optional hybrid GPU use does **not** guarantee a speedup. Moving only the output projection introduces CPU/GPU synchronization and transfer overhead, and the optimized ARM-only route can be faster for this workload. Performance also varies with prompt length, context occupancy, thermal state, power mode, driver version, and sampling behavior.

The sampler uses a fixed seed of `42`, temperature `0.2`, top-k `20`, and top-p `0.85` to make this heavily quantized demo less variable and easier to verify. Direct UI turns and the automatic demo allow 160 generated tokens. Thinking turns allow the engine maximum of 512 tokens; the native runtime limits an open reasoning section to 256 generated tokens so the remaining budget is available for the final answer.

## Thinking and visible reasoning

Thinking is an optional, persistent UI setting and is off by default. Each turn reads the current switch state, so it can be changed without starting a new chat:

- **Off:** the embedded Qwen3 chat template supplies its normal empty `<think></think>` prefix and the model answers directly. The UI shows only the final answer.
- **On:** the native prompt builder verifies and replaces that exact terminal disabled-thinking suffix with an open `<think>` marker. The model can then generate a reasoning section, close it with `</think>`, and continue with its final answer.

The streaming parser handles a prefilled opening marker, an explicitly emitted marker, and tag boundaries split across tokens. It sends reasoning to a separate tinted **On-device reasoning** panel and the answer to the normal assistant body. Conversation history also stores the two fields separately so subsequent chat-template formatting remains valid.

Thinking mode uses the same model, sampler, context, and currently selected CPU or hybrid backend. It changes prompt formatting and raises the per-turn generation allowance from 160 to 512 tokens; it does not enable a second inference engine or add GPU layers. If the model has not emitted `</think>` after 256 generated tokens, or tries to end generation while the reasoning block is still open, the native runtime decodes that closing marker into the same model context and lets the model generate the final answer from the remaining budget. The UI reports the token-limit message only when the measured generation count actually reaches the requested allowance; an earlier incomplete stop is reported separately. The displayed reasoning is still model-generated and should not be treated as a guarantee that the answer is correct. If the pinned model's template no longer matches the expected suffix, the native layer fails explicitly instead of silently using the wrong response mode.

## Build and install

The default build requires JDK 17 and Android SDK 36. Gradle packages 14 committed, stripped `arm64-v8a` libraries from `llama/src/main/prebuilt`, including `libggml-vulkan.so`, all optimized CPU variants, their dependencies, and the Bonsai JNI bridge:

```powershell
.\gradlew.bat :app:assembleDebug
```

This path does not configure CMake and does not require the Android NDK, Vulkan-Hpp, LLVM-MinGW, or an initialized llama.cpp submodule. Vulkan-Hpp is a header-only C++ build dependency; the installed app uses the compiled `libggml-vulkan.so` and the device's system `libvulkan.so`. See `llama/PREBUILT_NATIVE.md` and `llama/PREBUILT_NATIVE.sha256` for provenance and hashes.

Maintainers can explicitly rebuild the runtime from the pinned PrismML source on Windows:

```powershell
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-native.ps1
.\gradlew.bat :app:assembleDebug "-Pbonsai.native.buildFromSource=true"
```

The source build requires Android NDK `28.2.13676358`. The NDK supplies the Android Vulkan loader, SPIR-V headers, and Windows `glslc`; LLVM-MinGW builds llama.cpp's Windows host-side Vulkan shader generator. The idempotent bootstrap downloads checksum-verified LLVM-MinGW 20260616 and Vulkan-Hpp 1.3.275 into ignored `.tools`, and applies `patches/llama-vulkan-host-ninja.patch`. These host-side inputs are never packaged into the app.

Confirm that adb can see an authorized device, then install and launch:

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.samsung.ibit/.MainActivity
```

The APK supports only `arm64-v8a`, and the app's minimum Android version is API 28. Vulkan is optional at runtime because the CPU backend remains available, but GPU behavior depends on the device's Vulkan driver. Model size is not total memory use: weights, the 4,096-token KV cache, native buffers, and the Android process require additional memory. The 27B variants are therefore offered with an experimental warning rather than claimed to work on every phone.

## Using the app

1. Launch the app and choose **Models**.
2. Select a model, review its size and any high-memory warning, then choose **Download**. The status list distinguishes active, downloaded, resumable, and missing artifacts.
3. You may leave the app while it downloads and verifies; follow progress or stop it from the foreground-service notification. If stopped or interrupted, reopen **Models**, select the same entry, and choose **Resume download**; each model keeps independent progress.
4. Wait for model loading to finish. The status area reports the active model and backend, and the app runs a short direct-mode inference check after successful acquisition.
5. Leave **Thinking** off for a direct response, or enable it to stream model-generated reasoning into a separate panel before the final answer.
6. Enter a prompt or use one of the example prompt chips. Text streams into the assistant message.
7. Read the generated-token count and generation tokens/second shown under the response.
8. Use **New chat** to clear conversation history, or choose another downloaded model to switch and reset the native KV state.

## Verification

Do not infer GPU use merely from Vulkan being packaged in the APK. Verify both backend selection and coherent inference:

```powershell
adb logcat -c
adb shell am force-stop com.samsung.ibit
adb shell am start -n com.samsung.ibit/.MainActivity
adb logcat -d | Select-String "GPU backend detected|offloaded|active compute backend|preparing prompt|BONSAI_DEMO_INFERENCE|BONSAI_THINKING_INFERENCE"
```

For an eligible model on the verified Adreno 830 path, a successful run should contain lines equivalent to:

```text
GPU backend detected: Vulkan / Adreno (TM) 830
offloaded 1/<model layer count> layers to GPU
active compute backend: Hybrid CPU + Vulkan / Adreno (TM) 830
preparing prompt: thinking=enabled max_tokens=512
BONSAI_THINKING_INFERENCE_OK ... backend=Hybrid_CPU_+_Vulkan_/_Adreno_(TM)_830 ... answer_chars=9
```

For a model larger than 1,074,969,344 bytes on Adreno 830, the expected safety path instead logs `Vulkan offload disabled`, `offloaded 0/<layer count>`, and `CPU (Adreno Vulkan safety fallback)`. Direct turns log `thinking=disabled max_tokens=160` and normally report `reasoning_chars=0`. The completion line is important: backend initialization alone does not prove that inference produced valid text. Also inspect the response on screen, repeat prompt prefill more than once, and confirm that the app process remains alive after generation.

## Troubleshooting

### The app says `CPU`

- Confirm the device is ARM64 and that the installed APK contains `lib/arm64-v8a/libggml-vulkan.so`.
- Check logcat for `load_backend` and `GPU backend detected` messages.
- `CPU (Adreno Vulkan safety fallback)` is intentional for models larger than 1,074,969,344 bytes on Adreno 830 because Bonsai 8B produced native `DeviceLost` crashes.
- The Vulkan driver may not expose a GGML-compatible GPU device; CPU fallback is expected in that case.

### Model download or import fails

- Confirm that the filename, exact size, and SHA-256 match the selected entry in `BonsaiModel.kt`; validation is deliberately model-specific.
- Free enough storage for the model, the temporary file, and the app's 64 MiB safety margin.
- A download can resume from its `.part` file. Ignored ranges and HTTP 416 restart safely; use **Restart** in the resume dialog if another server error persists.
- On Android 13 or newer, allow iBit notifications so background download and inference progress remains visible.

### Model load fails or the app exits

- Capture `bonsai-runtime`, `AndroidRuntime`, `AdrenoVK`, and Vulkan messages from logcat.
- Check for memory pressure; the 4,096-token CPU KV cache alone was 576 MiB on the verified model.
- Do not remove the Adreno 830 size block or increase `n_gpu_layers` without repeated prompt-prefill and coherent-output verification. `VK_ERROR_DEVICE_LOST`, a surviving process with blank text, or zeroed/garbled output all count as failures.

### Generation returns no visible answer

With thinking enabled, the runtime reserves final-answer capacity by closing an open reasoning section after 256 generated tokens. A missing answer therefore means the model used the rest of the 512-token turn without completing its final response or native generation failed. Check the filtered logs, retry with a narrower prompt, start a new chat to clear context, or turn Thinking off for a direct answer.

## Privacy and network behavior

Model inference, tokenization, chat history, and generated text run inside the Android application process. Prompts are not sent to a remote inference service. Network access is used to download the model only when the user selects the download action.

The Android manifest disables backup, and the data-extraction rules exclude app files, databases, preferences, and app-specific external files from cloud backup and device transfer. This keeps the large model and local chat state out of Android's backup pipeline. As with any application, device compromise, debugging access, screenshots, or user-installed monitoring software are outside this app-level privacy boundary.

## Verified configuration

Local verification artifacts were captured with filtered logcat output, UI hierarchy, and screenshots. They are deliberately excluded from Git because they contain device-specific metadata. The observed configuration was:

- Device: Samsung Galaxy `SM-F966B`
- OS: Android 16
- Detected GPU: Adreno 830 via Vulkan
- Active backend label: `Hybrid CPU + Vulkan / Adreno (TM) 830`
- Active model: Ternary Bonsai 4B Q2_0, 1,074,969,344 bytes
- Offload: output projection (`1/37`)
- Direct-mode results: 17 tokens at 3.98 tokens/second, `17 + 25 = 42`; repeated with 15 tokens at 3.67 tokens/second, `9 x 9 = 81`
- Thinking-enabled result: 14 tokens at 3.38 tokens/second, `2 + 2 = 4`
- Post-fix process state: alive, with no new native crash entry after repeated direct and thinking runs

These values document one validated run. They should be rechecked after changes to the model, PrismML llama.cpp fork, NDK, Vulkan headers/shaders, GPU driver, context parameters, or offload policy.
