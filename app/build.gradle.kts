plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.prismml.bonsai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.prismml.bonsai"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
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
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":llama"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    testImplementation(libs.junit)
}
