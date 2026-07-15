plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val nativeSourceProperty = providers.gradleProperty("bonsai.native.buildFromSource").orNull
val buildNativeFromSource = nativeSourceProperty?.toBooleanStrictOrNull()
    ?: if (nativeSourceProperty == null) {
        false
    } else {
        error("bonsai.native.buildFromSource must be either true or false")
    }

val requiredPrebuiltLibraries = listOf(
    "libbonsai-runtime.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu-android_armv8.0_1.so",
    "libggml-cpu-android_armv8.2_1.so",
    "libggml-cpu-android_armv8.2_2.so",
    "libggml-cpu-android_armv8.6_1.so",
    "libggml-cpu-android_armv9.0_1.so",
    "libggml-cpu-android_armv9.2_1.so",
    "libggml-cpu-android_armv9.2_2.so",
    "libggml-vulkan.so",
    "libllama.so",
    "libllama-common.so",
    "libomp.so",
)
val prebuiltLibraryDirectory = file("src/main/prebuilt/arm64-v8a")

if (!buildNativeFromSource) {
    val missingLibraries = requiredPrebuiltLibraries.filterNot {
        prebuiltLibraryDirectory.resolve(it).isFile
    }
    check(missingLibraries.isEmpty()) {
        "Missing committed ARM64 runtime libraries: ${missingLibraries.joinToString()}. " +
            "Restore the prebuilt files or build with \"-Pbonsai.native.buildFromSource=true\"."
    }
}

android {
    namespace = "com.prismml.bonsai.runtime"
    compileSdk = 36

    if (buildNativeFromSource) {
        ndkVersion = "28.2.13676358"
    }

    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += "arm64-v8a"
        }

        if (buildNativeFromSource) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DBUILD_SHARED_LIBS=ON",
                        "-DLLAMA_BUILD_APP=OFF",
                        "-DLLAMA_BUILD_COMMON=ON",
                        "-DLLAMA_BUILD_EXAMPLES=OFF",
                        "-DLLAMA_BUILD_TESTS=OFF",
                        "-DLLAMA_OPENSSL=OFF",
                        "-DGGML_NATIVE=OFF",
                        "-DGGML_BACKEND_DL=ON",
                        "-DGGML_CPU_ALL_VARIANTS=ON",
                        "-DGGML_VULKAN=ON",
                        "-DGGML_LLAMAFILE=OFF",
                    )
                }
            }
        }
    }

    if (buildNativeFromSource) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        sourceSets {
            getByName("main").jniLibs.srcDir("src/main/prebuilt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
