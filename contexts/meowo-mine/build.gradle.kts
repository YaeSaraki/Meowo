dependencies {
    implementation(project(":shared"))
    api(project(":platform"))
    // Shipped by MeowoCore at runtime via @RuntimeDependencies, not bundled here.
    compileOnly(libs.sqlite)
    compileOnly(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    // The persistence tests exercise a real SQLite file, so the driver must be on the test classpath.
    testImplementation(libs.sqlite)
}
