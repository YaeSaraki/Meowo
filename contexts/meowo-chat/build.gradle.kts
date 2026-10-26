dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))
    api(project(":platform"))
    // Shipped by MeowoCore at runtime via @RuntimeDependencies, not bundled here.
    compileOnly(libs.sqlite)
    compileOnly(libs.paper.api)
    // The domain is pure Kotlin, so its tests need no MockBukkit: MockBukkit has no chat events at
    // all, so an integration test built on it would be testing nothing (docs/minimax/08 §19).
    testImplementation(libs.paper.api)
    testImplementation(libs.sqlite)
}

// The domain lives in its own source root so `verifyArchitecture` can scan it as a pure module
// (red line 01 §1) and so the layer boundary is visible in the directory tree rather than in
// convention. Gradle does not pick up `domain/` on its own.
sourceSets {
    main { kotlin.srcDir("domain/src/main/kotlin") }
    test { kotlin.srcDir("domain/src/test/kotlin") }
}
