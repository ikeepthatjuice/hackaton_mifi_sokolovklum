import java.nio.file.Files

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Real engines + model weights in assets: always in release; in debug only with -PrealEngines
// (full cycle on an emulator). Plain debug and CI APKs keep synthetic engines and no weights.
val realEnginesInDebug = providers.gradleProperty("realEngines").isPresent
val repoRoot = rootDir.resolve("../..")
val modelManifest = repoRoot.resolve("models/manifest.json")
val localModels = repoRoot.resolve("local_models") // filled by scripts/fetch-models.sh
@Suppress("UNCHECKED_CAST")
val manifestModels = (groovy.json.JsonSlurper().parse(modelManifest) as Map<String, Any>)["models"] as List<Map<String, Any>>
// The LLM entry comes with #23; until then real-engine builds stop in syncModelAssets.
val llmFile = "Qwen3-0.6B-Q4_K_M.gguf"
val llmSha256 = manifestModels.firstOrNull { it["target_file"] == llmFile }?.get("sha256") as String? ?: ""

/** assets/models/ = manifest.json + every manifest target_file from local_models/. Fails loudly if a weight is missing. */
abstract class SyncModelAssets : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputFiles abstract val weights: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Input abstract val llmInManifest: Property<Boolean>

    @TaskAction
    fun sync() {
        if (!llmInManifest.get()) throw GradleException("models/manifest.json has no Qwen3-0.6B-Q4_K_M.gguf entry: the release would ship without LLM")
        val missing = weights.files.filterNot { it.isFile }
        if (missing.isNotEmpty()) throw GradleException("Missing weights: $missing. Run: bash scripts/fetch-models.sh local_models")
        val dir = outputDir.get().asFile.resolve("models").apply { deleteRecursively(); mkdirs() }
        manifest.get().asFile.copyTo(dir.resolve("manifest.json"))
        // Hard links: weights are ~600 MB, a copy per variant fills small disks. Copy if linking is not possible.
        weights.files.forEach { w ->
            val link = dir.resolve(w.name).toPath()
            runCatching { Files.createLink(link, w.toPath()) }.getOrElse { w.copyTo(link.toFile()) }
        }
    }
}

val syncModelAssets = tasks.register<SyncModelAssets>("syncModelAssets") {
    manifest.set(modelManifest)
    llmInManifest.set(llmSha256.isNotEmpty())
    weights.from(manifestModels.map { localModels.resolve(it["target_file"] as String) })
    outputDir.set(layout.buildDirectory.dir("generated/modelAssets"))
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release" || realEnginesInDebug) {
            variant.sources.assets?.addGeneratedSourceDirectory(syncModelAssets, SyncModelAssets::outputDir)
        }
    }
}

android {
    namespace = "com.neurostenographer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neurostenographer.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        // Single source of truth for the LLM hash: models/manifest.json.
        buildConfigField("String", "LLM_SHA256", "\"$llmSha256\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // shortcut: RC is signed with the local debug key so experts can install it; a release keystore is needed for any store upload.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "REAL_ENGINES", "true")
        }
        debug {
            buildConfigField("boolean", "REAL_ENGINES", "$realEnginesInDebug")
        }
    }

    sourceSets {
        getByName("release") {
            java.srcDir("src/realEngines/kotlin")
        }
        getByName("debug") {
            java.srcDir(if (realEnginesInDebug) "src/realEngines/kotlin" else "src/fakeEngines/kotlin")
        }
        // jfk.wav (whisper.cpp submodule sample) for FullCycleTest; only real-engine debug builds need it.
        if (realEnginesInDebug) getByName("androidTest").assets.srcDir(repoRoot.resolve("external/whisper.cpp/samples"))
    }

    // Stored, not deflated: AssetModelStore needs openFd() sizes, and weights do not compress anyway.
    androidResources {
        noCompress += listOf("bin", "onnx", "gguf")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}


dependencies {
    implementation(project(":core:contracts"))
    implementation(project(":core:audio"))
    implementation(project(":core:pipeline"))
    implementation(project(":core:export"))
    implementation(project(":core:history"))
    implementation(project(":engine:asr"))
    implementation(project(":engine:diarization"))
    implementation(project(":engine:alignment"))
    implementation(project(":engine:llm"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":feature:import"))
    implementation(project(":feature:transcript"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.runtime)
    debugImplementation(libs.compose.runtime.saveable)
    debugImplementation(libs.androidx.lifecycle.viewmodel.ktx)
    debugImplementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // UI tests in app/src/androidTest (front); run by emulator CI via connectedDebugAndroidTest.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
