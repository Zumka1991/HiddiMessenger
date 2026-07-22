plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories {
    mavenCentral()
    maven(url = "https://build-artifacts.signal.org/libraries/maven/")
}

dependencies {
    implementation("org.signal:libsignal-client:0.98.0")
    implementation("de.mkammerer:argon2-jvm:2.12")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

// libsignal-client 0.98.0 requires Java 21 on desktop platforms.
kotlin { jvmToolchain(21) }
application { mainClass.set("ru.hiddi.terminal.MainKt") }
