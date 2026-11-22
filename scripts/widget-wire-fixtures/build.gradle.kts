// The widget binary format fixtures' generator: a JVM program over the app's psychoscript-mobile bindings and a host
// build of the library (see generate.sh).
plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    application
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
}

kotlin { jvmToolchain(21) }

sourceSets["main"].kotlin.srcDir("../../feature/transcript/src/main/kotlin/com/anomalyco/psychoscript")

application { mainClass.set("FixturesKt") }

tasks.named<JavaExec>("run") {
    systemProperty("jna.library.path", providers.gradleProperty("jnaPath").get())
}
