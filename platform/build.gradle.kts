dependencies {
    api(project(":api"))
    api(project(":shared"))
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
}
