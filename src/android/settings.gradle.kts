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
// Front modules; addition agreed with back in B2/#5.
include(":feature:import", ":feature:transcript")
// engine/* и остальные core/* модули подключаются PR владельцев (согласование с back).
