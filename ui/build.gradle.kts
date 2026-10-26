dependencies {
    api(project(":api"))
    api(project(":shared"))
    api(project(":platform"))
    compileOnly(libs.paper.api)
    // The one module in the build that may build a menu with TabooLib; the architecture gate enforces it.
    compileOnly(libs.taboolib.bukkit.ui)
    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
}
