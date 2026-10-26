dependencies {
    api(project(":platform"))
    implementation(project(":shared"))
    compileOnly(libs.paper.api)
    compileOnly(libs.packetevents)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    testImplementation(libs.packetevents)
}
