plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.transcript"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

// The widget runner's libraries, built by scripts/psychoscript.sh (gitignored).
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(rootProject.file("vendor/psychoscript/jniLibs").path)
    }
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    api(project(":feature:media"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jlatexmath.android)
    // The aar carries JNA's native dispatch library for every ABI.
    implementation(libs.jna) { artifact { type = "aar" } }
    // android.icu (time stamps), MimeTypeMap/Uri/ClipData (drag payloads) in JVM tests.
    testImplementation(libs.robolectric)
    // JNA's desktop dispatch library, for the widget runtime's desktop build in JVM tests.
    testImplementation(libs.jna)
}

// JVM tests run the committed widget workloads on the runtime built for this machine (scripts/psychoscript.sh --host),
// and skip them without it.
tasks.withType<Test>().configureEach {
    val host = rootProject.file("vendor/psychoscript/host")
    systemProperty("jna.library.path", host.path)
    systemProperty("opencode.widgetWorkloads", rootProject.file(".psychoscript/rust/crates/psychoscript-mobile/tests/fixtures/workloads").path)
    systemProperty("opencode.widgetWireFixtures", rootProject.file(".psychoscript/rust/crates/widget-wire/tests/fixtures/wire").path)
    inputs.property("widgetRuntime", providers.provider { host.resolve("REVISION").takeIf { it.isFile }?.readText() ?: "" })
}
