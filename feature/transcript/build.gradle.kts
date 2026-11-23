plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.transcript"
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    api(project(":feature:media"))
    implementation(libs.coil.compose)
    implementation(libs.jlatexmath.android)
    // android.icu (time stamps), MimeTypeMap/Uri/ClipData (drag payloads) in JVM tests.
    testImplementation(libs.robolectric)
    // A ComponentActivity to host Markdown views in Robolectric tests.
    testImplementation(libs.androidx.activity.compose)
}
