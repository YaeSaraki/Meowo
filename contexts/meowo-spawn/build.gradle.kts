dependencies {
    implementation(project(":shared"))
    api(project(":platform"))
    compileOnly(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
}
