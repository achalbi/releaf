//
// Releaf Android — Markdown round-trip spike.
//
// Plain Kotlin/JVM application (no Android Gradle Plugin). Proves that
// commonmark-java parses + renders our fixture corpus identically before
// and after a flexmark-java round-trip — i.e. the production stack
// (commonmark-java for display, flexmark-java for canonical save) preserves
// semantic meaning.
//
// Run:
//     gradle -p android/spikes/markdown-roundtrip run
//

plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Baseline parser + HTML renderer (what the production editor will
    // use for display). Version-aligned with what we'd adopt in the main
    // app build.gradle.kts once the editor ships.
    val commonmarkVersion = "0.22.0"
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-task-list-items:$commonmarkVersion")

    // Used only for the format/save path: parses to its own AST and emits
    // canonical CommonMark. flexmark-all pulls in every extension.
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
}

application {
    mainClass.set("app.releaf.spike.markdown.MarkdownRoundTripKt")
}

kotlin {
    jvmToolchain(17)
}
