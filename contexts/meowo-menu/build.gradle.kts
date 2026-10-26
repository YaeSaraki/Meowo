dependencies {
    api(project(":platform"))
    implementation(project(":api"))
    implementation(project(":shared"))
    compileOnly(libs.taboolib.bukkit.ui)
    compileOnly(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
}
