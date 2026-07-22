pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://build-artifacts.signal.org/libraries/maven/")
    }
}
rootProject.name = "hiddi-terminal"
