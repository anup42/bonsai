# Prebuilt Android native runtime

Normal Bonsai Android builds package the stripped ARM64 libraries in `src/main/prebuilt/arm64-v8a`. This avoids compiling llama.cpp during every app build and means consumers do not need Vulkan-Hpp, LLVM-MinGW, CMake, or the Android NDK.

The libraries were produced from:

- PrismML llama.cpp submodule commit `62061f91088281e65071cc38c5f69ee95c39f14e`
- Android NDK `28.2.13676358`
- CMake `3.22.1`
- Vulkan-Hpp `1.3.275`
- LLVM-MinGW `20260616`
- ABI `arm64-v8a`
- Release CMake configuration defined in `llama/build.gradle.kts`

`libggml-vulkan.so` contains the compiled GGML Vulkan backend and embedded shaders. Vulkan-Hpp is used only to compile that library. At runtime the backend dynamically links Android's system `libvulkan.so`, so Vulkan-Hpp is not installed on the phone.

Third-party license and toolchain notices accompanying these binaries are tracked in `llama/licenses`.

The directory intentionally contains all seven optimized ARM CPU variants in addition to Vulkan. GGML selects a compatible CPU backend at runtime and safely falls back to CPU if Vulkan is unavailable.

Verify the committed artifacts from the repository root:

```powershell
Get-Content .\llama\PREBUILT_NATIVE.sha256 | ForEach-Object {
    $expected, $relativePath = $_ -split '  ', 2
    $actual = (Get-FileHash $relativePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Checksum mismatch: $relativePath" }
}
```

To rebuild from source instead of using these files:

```powershell
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-native.ps1
.\gradlew.bat :app:assembleDebug "-Pbonsai.native.buildFromSource=true"
```

After a native upgrade, replace the files with the stripped release outputs and update `PREBUILT_NATIVE.sha256`. Do not commit the much larger unstripped `.cxx` or app-intermediate libraries.
