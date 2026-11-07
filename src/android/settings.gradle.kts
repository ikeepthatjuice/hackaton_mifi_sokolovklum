pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "neurostenographer"

include(":app")
include(":core:contracts")
include(":core:audio")
include(":core:pipeline")
<<<<<<< HEAD
include(":engine:asr")
include(":engine:diarization")
include(":engine:alignment")
// Front modules; addition agreed with back in B2/#5.
include(":feature:import", ":feature:transcript")
=======
include(":core:export")
// Front modules; addition agreed with back in B2/#5.
include(":feature:import", ":feature:transcript")
include(":engine:llm")
// Остальные engine/* подключаются PR владельцев.
>>>>>>> origin/main
