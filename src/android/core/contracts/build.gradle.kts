// Чистый Kotlin/JVM: контракты без Android-зависимостей, тесты идут быстро.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api: StateFlow is part of the public contract, consumers need it on their classpath.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
