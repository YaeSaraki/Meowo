dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))
    api(project(":platform"))
    compileOnly(libs.sqlite)
    compileOnly(libs.paper.api)
    // The OpenAI-compatible codec. TabooLib already ships Gson at runtime, so this stays
    // `compileOnly` exactly as sqlite does — but it is declared rather than inherited from a
    // transitive edge, so a TabooLib change cannot silently remove it.
    compileOnly(libs.gson)
    // Every test here is pure Kotlin on purpose: the domain has no Bukkit dependency and MockBukkit
    // has no chat events at all, so an integration test would be testing nothing (docs/minimax/08 §19).
    testImplementation(libs.paper.api)
    testImplementation(libs.sqlite)
    testImplementation(libs.gson)
}

// The domain lives in its own source root so `verifyArchitecture` can scan it as a pure module
// (red line 01 §1) and so the layer boundary is visible in the directory tree rather than in
// convention. Gradle does not pick up `domain/` on its own.
sourceSets {
    main {
        kotlin.srcDir("domain/src/main/kotlin")
        kotlin.srcDir("application/src/main/kotlin")
        kotlin.srcDir("infrastructure/src/main/kotlin")
    }
    test {
        kotlin.srcDir("domain/src/test/kotlin")
        kotlin.srcDir("application/src/test/kotlin")
        kotlin.srcDir("infrastructure/src/test/kotlin")
    }
}