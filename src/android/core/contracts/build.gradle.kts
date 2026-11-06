// Чистый Kotlin/JVM: контракты без Android-зависимостей, тесты идут быстро.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
