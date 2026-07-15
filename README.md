# Bonsai Mobile

An offline Android demo for PrismML's end-to-end 1-bit Bonsai 8B model. The app runs the Apache-2.0 `Bonsai-8B-Q1_0.gguf` locally through the PrismML llama.cpp fork. Prompts, reasoning, and generated answers stay on the phone.

## Features

- Hybrid ARM64 CPU + Vulkan inference with safe CPU fallback
- Streaming chat with generated-token count and llama.cpp generation throughput
- Optional **Thinking** mode with reasoning and final answer displayed separately
- 160-token direct turns and 512-token thinking turns with a reserved final-answer budget
- Direct, resumable download from the official PrismML Hugging Face repository
- Local GGUF import through Android's document picker
- Published SHA-256 verification before a downloaded or imported model is used
- A 4,096-token context and an automatic post-load inference check
- Private app storage with Android backup disabled

The verified Adreno configuration offloads the output projection (`1/37` model layers) to Vulkan and keeps the repeating transformer layers and KV cache on the CPU. See [ON_DEVICE_INFERENCE.md](ON_DEVICE_INFERENCE.md) for the complete runtime design, limitations, verification evidence, and troubleshooting guide.

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
adb shell am start -n com.prismml.bonsai/.MainActivity
```

The 1.08 GiB model is deliberately not bundled in the APK. If it is missing, the app presents **Download 1.08 GiB** and **Import file** actions. Download shows the source and size before starting, stores resumable progress in private app storage, verifies the publisher's SHA-256, then loads the model and runs an automatic local inference check. An interrupted download can be resumed on the next launch.

## Sources

- [PrismML announcement](https://prismml.com/news/bonsai-8b)
- [Official 1-bit GGUF model](https://huggingface.co/prism-ml/Bonsai-8B-gguf)
- [PrismML llama.cpp fork](https://github.com/PrismML-Eng/llama.cpp)
