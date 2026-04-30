plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "askrepo"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.html)

    implementation(libs.slack.bolt.socket.mode)
    implementation(libs.websocket.api)
    implementation(libs.tyrus.standalone.client)
    implementation(libs.slf4j.simple)
    implementation(libs.postgresql)
    implementation(libs.aws.sdk.bedrockruntime)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("askrepo.MainKt")
    applicationName = "ask-the-repo"
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
