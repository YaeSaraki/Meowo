dependencies {
    api(project(":contexts:meowo-economy:domain"))
    api(project(":platform"))
    implementation(project(":shared"))
    // Shipped by MeowoCore at runtime via @RuntimeDependencies, not bundled here.
    // Vault is another plugin's classes, present at runtime only when the operator installs it.
    compileOnly(libs.vault)
    testImplementation(libs.vault)
    compileOnly(libs.sqlite)
    compileOnly(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    // The persistence tests exercise a real SQLite file, so the driver must be on the test classpath.
    testImplementation(libs.sqlite)
}
