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
        maven(url = "https://build-artifacts.signal.org/libraries/maven/") {
            name = "SignalBuildArtifacts"
        }
    }
}

rootProject.name = "HiddiAndroid"
include(":app")
