plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.neurostenographer.engine.diarization"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    sourceSets.getByName("main") {
        jniLibs.srcDir("$rootDir/../../external/sherpa-onnx-android/jniLibs")
    }

    val localModels = file("$rootDir/../../local_models")
    val whisperSamples = file("$rootDir/../../external/whisper.cpp/samples")
    sourceSets.getByName("androidTest") {
        if (localModels.exists()) {
            assets.srcDir(localModels)
        }
        if (whisperSamples.exists()) {
            assets.srcDir(whisperSamples)
        }
    }

    androidResources {
        noCompress += listOf("bin", "onnx", "wav")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
