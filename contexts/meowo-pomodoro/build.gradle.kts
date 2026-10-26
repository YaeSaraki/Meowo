dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))
    api(project(":platform"))
    // Shipped by MeowoCore at runtime via @RuntimeDependencies, not bundled here.
    compileOnly(libs.sqlite)
    compileOnly(libs.paper.api)
    // PacketEvents is a soft dependency: the context stays usable without it, only TextDisplay is
    // skipped. See TextDisplayRenderer for the runtime check.
    compileOnly(libs.packetevents)
    compileOnly(libs.taboolib.bukkit.ui)
    testImplementation(libs.paper.api)
    // The persistence tests exercise a real SQLite file, so the driver must be on the test classpath.
    testImplementation(libs.sqlite)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.packetevents)
}
