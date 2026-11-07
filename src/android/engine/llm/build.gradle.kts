plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.neurostenographer.engine.llm"
    compileSdk = 34
    // Agrees with bootstrap's environment check; common NDK selection remains with back.
    ndkVersion = "26.1.10909125"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            // Native engines are always optimized: ggml at -O0 (AGP debug default) made ASR ~30x slower on A51.
            cmake { arguments += listOf("-DLLAMA_CPP_DIR=${project.layout.projectDirectory.dir("external/llama.cpp").asFile}", "-DCMAKE_BUILD_TYPE=Release") }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
