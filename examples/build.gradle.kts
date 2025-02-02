plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "dev.vanutp.libcampusnet"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation(project(":libcampusnet"))
    implementation("ch.qos.logback:logback-classic:1.5.16")
}

application {
    mainClass = "dev.vanutp.libcampusnet.MainKt"
}
