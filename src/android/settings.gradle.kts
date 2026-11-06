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
// engine/* и остальные core/* модули подключаются PR владельцев (согласование с back).
