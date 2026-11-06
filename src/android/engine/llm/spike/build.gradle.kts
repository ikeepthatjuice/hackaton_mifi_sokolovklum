// Independent experiment only. Versions match bootstrap PR #3; no common build is edited.
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// Optional external output directory for isolated CI/large GGUF packaging.
providers.gradleProperty("llmSpikeBuildDir").orNull?.let {
    layout.buildDirectory.set(file(it))
}

android {
    namespace = "engine.llm.spike"
    compileSdk = 34
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "com.neurostenographer.llmspike"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DLLAMA_CPP_DIR=${file("../external/llama.cpp").absolutePath}" }
        }
    }
    externalNativeBuild {
        cmake { path = file("../src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "gguf" }
}

// Run runtime without inventing/copying the team's missing contracts.
kotlin.sourceSets.getByName("main").kotlin.apply {
    srcDir("../src/main/kotlin")
    include("**/SpikeActivity.kt", "**/LlamaCppTextGenerator.kt", "**/LlmException.kt", "**/TextGenerator.kt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
