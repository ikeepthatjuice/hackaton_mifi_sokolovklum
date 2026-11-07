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
// Front modules; addition agreed with back in B2/#5.
include(":feature:import", ":feature:transcript")

// ML engine modules
include(":engine:asr")
include(":engine:diarization")
include(":engine:alignment")
