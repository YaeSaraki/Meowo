import io.izzel.taboolib.gradle.*

plugins { alias(libs.plugins.taboolib) }
listOf(":api", ":shared", ":platform", ":ui", ":contexts:meowo-economy:domain", ":contexts:meowo-economy", ":contexts:meowo-menu", ":contexts:meowo-render", ":contexts:meowo-teleport", ":contexts:meowo-spawn", ":contexts:meowo-sit", ":contexts:meowo-hat", ":contexts:meowo-shadowbox", ":contexts:meowo-nametag", ":contexts:meowo-home", ":contexts:meowo-mine", ":contexts:meowo-chat", ":contexts:meowo-agents", ":contexts:meowo-avatar", ":contexts:meowo-invsee", ":contexts:meowo-pomodoro", ":contexts:meowo-shop", ":contexts:meowo-webapi").forEach { evaluationDependsOn(it) }
taboolib {
    env {
        install(Basic, Bukkit, I18n, BukkitUI)
        // The bundle's optional NMS module is **not** a root module here: Standard Chest UI uses
        // public Bukkit APIs, and nothing in this plugin touches NMS at all.
        //
        // It still arrives on disk, because `bukkit-nms` is a **transitive dependency** of the
        // platform module — removing it from the root list stops it being asked for, not stops it
        // being needed. What actually keeps it inert is `disable-on-unsupported-version` below: the
        // module's version table ends at 1.21.11, so on Paper 26.2 TabooLib disables it rather than
        // loading it. The line is kept because a root module that is never asked for is one fewer
        // thing to resolve, and the comment is here because the line alone does **not** keep the
        // module off the disk — an earlier version of this comment said it did.
        modules.remove("bukkit-nms")
        repoCentral = "https://repo.maven.apache.org/maven2"
        repoTabooLib = "https://repo.tabooproject.org/repository/releases"
    }
    version { taboolib = libs.versions.taboolib.asProvider().get(); skipKotlinRelocate = true; coroutines = null }
}
dependencies {
    "taboo"(project(":api"))
    "taboo"(project(":shared"))
    "taboo"(project(":platform"))
    "taboo"(project(":ui"))
    "taboo"(project(":contexts:meowo-economy"))
    "taboo"(project(":contexts:meowo-menu"))
    "taboo"(project(":contexts:meowo-render"))
    "taboo"(project(":contexts:meowo-teleport"))
    "taboo"(project(":contexts:meowo-spawn"))
    "taboo"(project(":contexts:meowo-sit"))
    "taboo"(project(":contexts:meowo-hat"))
    "taboo"(project(":contexts:meowo-nametag"))
    "taboo"(project(":contexts:meowo-agents"))
    "taboo"(project(":contexts:meowo-mine"))
    "taboo"(project(":contexts:meowo-chat"))
    "taboo"(project(":contexts:meowo-avatar"))
    "taboo"(project(":contexts:meowo-shadowbox"))
    "taboo"(project(":contexts:meowo-home"))
    "taboo"(project(":contexts:meowo-invsee"))
    "taboo"(project(":contexts:meowo-pomodoro"))
    "taboo"(project(":contexts:meowo-shop"))
    "taboo"(project(":contexts:meowo-webapi"))
    compileOnly(libs.paper.api)
    // The `taboo` bucket is what gets shaded into the plugin jar; the tests build a real MeowoRuntime, which
    // reads the shipped ui.yml off the classpath, so the module has to be on the test classpath as well.
    testImplementation(project(":ui"))
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    // Production ships SQLite through @RuntimeDependencies; the integration tests exercise real
    // wallets and escrows, so the driver has to be present on the test classpath too.
    testImplementation(libs.sqlite)
}
/**
 * `plugin.yml` is the one file in the jar that carries the version, so the server can say which
 * build it is running.
 *
 * The expanded value has to be declared as a task **input**. Without that, `processResources` does
 * not know the version is part of what it produces, so it stays UP-TO-DATE when only the version
 * changes — and the jar gets built with the *previous* version inside it. That is not a cosmetic
 * problem: a jar called `Meowo-1.2.3.jar` that reports `1.0.0` is a jar nobody can tell apart from
 * the one before it, and the release workflow refuses to publish one (which is the check working,
 * but the fix belongs here, where the staleness is created).
 *
 * Declaring the input makes the task re-run when the version changes, so the jar and its name
 * always agree.
 */
tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
tasks.jar {
    archiveBaseName.set("Meowo")
    dependsOn(configurations.named("taboo"))
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.register<Exec>("runServer") {
    group = "application"
    dependsOn("build")
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/start-server.sh", "--skip-build")
    standardInput = System.`in`
}
