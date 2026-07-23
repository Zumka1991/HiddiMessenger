import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.compose") version "1.11.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

repositories {
    mavenCentral()
    google()
    maven(url = "https://build-artifacts.signal.org/libraries/maven/")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.signal:libsignal-client:0.98.0")
    implementation("de.mkammerer:argon2-jvm:2.12")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
}

// libsignal-client 0.98.0 requires Java 21 on desktop platforms.
kotlin { jvmToolchain(21) }

compose.desktop {
    application {
        mainClass = "ru.hiddi.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "Hiddi"
            packageVersion = "0.1.0"
            description = "Безопасный семейный мессенджер Hiddi"
            vendor = "Hiddi"
            linux {
                menuGroup = "Network"
                shortcut = true
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
