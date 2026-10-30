dependencies {
    implementation(project(":shared"))
    api(project(":platform"))
    compileOnly(libs.paper.api)
    // The wire format. TabooLib already ships Gson at runtime, so it stays compileOnly and is
    // not bundled into the jar.
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    testImplementation(libs.paper.api)
    // The snapshot builder reads the live server, so it is tested against a server that answers.
    testImplementation(libs.mockbukkit)
}
