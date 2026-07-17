# iBit

iBit is an offline Android app for PrismML's 1-bit and ternary Bonsai language models. It downloads and runs official Apache-2.0 GGUF artifacts locally through the PrismML llama.cpp fork. Prompts, reasoning, and generated answers stay on the phone.

## Features

- ARM64 inference with automatic, model-size-aware Vulkan output-layer offload and CPU safety fallback
- Streaming chat with generated-token count and llama.cpp generation throughput
- Optional **Thinking** mode with reasoning and final answer displayed separately
- 160-token direct turns and 512-token thinking turns with a reserved final-answer budget
- An eight-model catalog covering 1.7B, 4B, 8B, and 27B 1-bit and ternary variants
- Per-model selection, resumable background download, switching, and deletion
- Direct download from commit-pinned official PrismML Hugging Face repositories
- Foreground-service notifications with live progress and Stop actions for downloads and inference
- Local GGUF import through Android's document picker
- Published SHA-256 verification before a downloaded or imported model is used
- A 4,096-token context and an automatic post-load inference check
- Private app storage with Android backup disabled

The Vulkan backend is packaged and selected automatically. On the verified Adreno 830 device, model artifacts up to and including Ternary Bonsai 4B Q2_0 (1,074,969,344 bytes, shown as 1.00 GiB) use a conservative hybrid path that offloads the output projection to Vulkan. Larger artifacts stay on ARM CPU because the next catalog size, Bonsai 8B Q1_0, produced repeatable `VK_ERROR_DEVICE_LOST` crashes under the same one-layer strategy. Full-model Vulkan offload is not enabled because it produced invalid all-zero output even with Bonsai 1.7B. See [ON_DEVICE_INFERENCE.md](ON_DEVICE_INFERENCE.md) for the complete runtime design, safety policy, verification evidence, and troubleshooting guide.

## Build

The normal build requires JDK 17 and Android SDK 36. It packages the committed ARM64 runtime libraries, so it does not require Vulkan-Hpp, LLVM-MinGW, the NDK, or native bootstrap tooling.

```powershell
git clone https://github.com/anup42/bonsai.git
cd bonsai
.\gradlew.bat :app:assembleDebug
```

The 14 stripped `arm64-v8a` libraries are tracked under `llama/src/main/prebuilt`. They include the Vulkan backend and CPU fallback; Vulkan-Hpp was used only when compiling them and is not an Android runtime dependency. Their provenance and SHA-256 values are recorded in [llama/PREBUILT_NATIVE.md](llama/PREBUILT_NATIVE.md) and [llama/PREBUILT_NATIVE.sha256](llama/PREBUILT_NATIVE.sha256).

To intentionally rebuild the native runtime from the pinned llama.cpp source, install Android NDK `28.2.13676358`, Git, and PowerShell, then run:

```powershell
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-native.ps1
.\gradlew.bat :app:assembleDebug "-Pbonsai.native.buildFromSource=true"
```

The bootstrap script applies the tracked Windows Vulkan host-tool patch and downloads checksum-verified LLVM-MinGW and Vulkan-Hpp inputs into the ignored `.tools` directory. The property is deliberately explicit so an ordinary Gradle build never enters CMake or looks for those build-time dependencies.

Install on an authorized ARM64 Android device:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.samsung.ibit/.MainActivity
```

Models are deliberately not bundled in the APK. Select **Models** to see all supported text variants and their on-device state, then choose one to download, resume, activate, or delete. A `dataSync` foreground service keeps a selected download running when the app is backgrounded, displays live progress in an ongoing notification, and provides a Stop action that preserves the resumable `.part` file. Model generation uses a separate foreground service with its own progress notification and Stop action. Every artifact has an exact expected byte count and SHA-256 in the app catalog; a download or import is not loaded until both checks and the GGUF signature pass. The 27B entries are marked experimental because their storage and runtime-memory requirements exceed many phones. Bonsai Image is not listed because image generation needs a separate diffusion runtime rather than this app's llama.cpp text engine.

| Model family | Sizes | App GGUF format |
| --- | --- | --- |
| Bonsai 1-bit | 1.7B, 4B, 8B, 27B | `Q1_0` |
| Ternary Bonsai | 1.7B, 4B, 8B, 27B | `Q2_0` |

## Sources

- [PrismML announcement](https://prismml.com/news/bonsai-8b)
- [PrismML models on Hugging Face](https://huggingface.co/prism-ml/models)
- [PrismML llama.cpp fork](https://github.com/PrismML-Eng/llama.cpp)
