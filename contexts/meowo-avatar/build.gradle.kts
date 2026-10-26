dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))
    api(project(":platform"))
    compileOnly(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
}

// The pure half lives in its own source root so `verifyArchitecture` scans it as a pure module
// (red line 01 §1). Gradle does not pick up `domain/` on its own.
sourceSets {
    main { kotlin.srcDir("domain/src/main/kotlin") }
    test { kotlin.srcDir("domain/src/test/kotlin") }
}
