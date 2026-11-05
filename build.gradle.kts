plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "local.poc"
version = "0.1.0"

val ktorVersion = "3.6.0"

dependencies {
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.11.2")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("poc.MainKt")
}
