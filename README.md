# Bonsai Mobile

An offline Android demo for PrismML's end-to-end 1-bit Bonsai 8B model. The app runs the Apache-2.0 `Bonsai-8B-Q1_0.gguf` locally through the PrismML llama.cpp fork. Prompts, reasoning, and generated answers stay on the phone.

## Features

- Hybrid ARM64 CPU + Vulkan inference with safe CPU fallback
- Streaming chat with generated-token count and llama.cpp generation throughput
- Optional **Thinking** mode with reasoning and final answer displayed separately
- 160-token direct turns and 320-token thinking turns
- Direct, resumable download from the official PrismML Hugging Face repository
- Local GGUF import through Android's document picker
- Published SHA-256 verification before a downloaded or imported model is used
- A 4,096-token context and an automatic post-load inference check
- Private app storage with Android backup disabled

The verified Adreno configuration offloads the output projection (`1/37` model layers) to Vulkan and keeps the repeating transformer layers and KV cache on the CPU. See [ON_DEVICE_INFERENCE.md](ON_DEVICE_INFERENCE.md) for the complete runtime design, limitations, verification evidence, and troubleshooting guide.

## Build

Requirements: Windows, JDK 17, Android SDK 36, Android NDK `28.2.13676358`, Git, and PowerShell.

```powershell
git clone --recurse-submodules https://github.com/anup42/bonsai.git
cd bonsai
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-native.ps1
.\gradlew.bat :app:assembleDebug
```

The bootstrap script initializes the pinned PrismML llama.cpp submodule, applies the tracked Windows Vulkan host-tool patch, and downloads checksum-verified LLVM-MinGW and Vulkan-Hpp dependencies into the ignored `.tools` directory.

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
