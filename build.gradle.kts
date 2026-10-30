import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // `base` gives the root project a `check` of its own — the root has no code, so that task is
    // where the **gates** live and where every module's own `check` is gathered.
    id("base")
    jacoco
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.taboolib) apply false
}

/**
 * The version every module is built with.
 *
 * It is **not** written down here. The number lives in the release tag (`v1.2.3` → `1.2.3`), and
 * the release workflow passes it in as `-PreleaseVersion=1.2.3`. A version written down in the build
 * file and a version written down in the tag are two facts that can disagree, and the way they
 * disagreed was quiet: the jar said `Meowo-1.0.0.jar` while the release was called `v1.2.0`, and
 * the file a server downloaded had no version in its name that anyone could check it against.
 *
 * So the tag is the single source, and this fallback is what a local build with no tag uses. It is
 * a **development** version, not a release: nothing publishes it.
 */
val releaseVersion: String = providers.gradleProperty("releaseVersion").orElse("1.0.0").get()

subprojects {
    group = "com.meowo"
    version = releaseVersion
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    configurations.configureEach {
        if (isCanBeResolved) attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
    extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.14" }
    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        finalizedBy(tasks.named("jacocoTestReport"))
        /**
         * The coverage data of a run must be **this run's** coverage, not the union of this run and
         * every run before it.
         *
         * JaCoCo writes the execution data to `build/jacoco/test.exec`. Gradle 8.14 hardcodes
         * `append=true` for the agent (the `JacocoTaskExtension` bytecode appends that flag itself,
         * and the version exposes no way to turn it off), so the file is opened for append when the
         * test JVM exits. `cleanTest` does not delete that file — it only clears the test results —
         * so the file survives, and the next run adds to it. The coverage number therefore only ever
         * goes up: measured three times in a row on the same code, it read 82.52%, then 82.55%, then
         * 82.56%, with no code change in between.
         *
         * Declaring the file as an **output** of the test task is what fixes it, and it fixes the
         * worse half of the problem too. Without the declaration the file is not managed by Gradle
         * at all, so deleting it does not make the test task out-of-date: the task stays
         * UP-TO-DATE, does not re-run, and the module's coverage silently disappears from the
         * aggregate — which is why the same code measured 82.52%, 82.53% and 82.55% depending on
         * which modules happened to re-run. Declared as an output, Gradle deletes the stale file
         * before the task executes and writes a fresh one, so the number means what it says.
         *
         * The agent still appends to whatever file it finds — but it now always finds a file that
         * was just deleted, so there is nothing to append to.
         */
        val execFile = extensions.getByType(JacocoTaskExtension::class.java).destinationFile
        if (execFile != null) outputs.file(execFile)
    }
    tasks.withType<JacocoReport>().configureEach { reports { xml.required.set(true); html.required.set(true) } }
}
tasks.register("verifyArchitecture") {
    group = "verification"
    doLast {
        /**
         * The pure roots are **derived from the tree**, not written down.
         *
         * The list used to be six hand-typed paths, and it was wrong in the way a hand-typed list
         * always eventually is: five of the six were checked and one of those five had drifted, so
         * the gate was reading a rule that did not match the code. A rule that is *nearly* enforced
         * is worse than one that is not enforced at all, because everybody believes it is there.
         *
         * So the roots are found instead of listed:
         *
         * - `api` and `shared` — the two modules that exist to be depended on;
         * - every `contexts/<x>/domain` **source root** — the ones Gradle is told about explicitly;
         * - every `contexts/<x>/src/main/kotlin/com/meowo/<y>/domain` **package** — the ones that are
         *   just a folder inside a context, and which nothing else would ever tell us about.
         *
         * The second kind is the one the hand-written list missed entirely, and it is where the
         * nametag, menu, mine, pomodoro and render domains live: five real domains that no gate was
         * looking at.
         */
        val pureRoots = buildList {
            add("api/src/main/kotlin")
            add("shared/src/main/kotlin")
            file("contexts").listFiles()?.filter { it.isDirectory }?.forEach { context ->
                if (File(context, "domain/src/main/kotlin").isDirectory) {
                    add("${context.path}/domain/src/main/kotlin")
                }
                val packages = File(context, "src/main/kotlin/com/meowo")
                packages.listFiles()?.forEach { pkg ->
                    if (File(pkg, "domain").isDirectory) {
                        add("${context.path}/src/main/kotlin/com/meowo/${pkg.name}/domain")
                    }
                }
            }
        }
        check(pureRoots.size >= 6) { "the pure-root derivation found ${pureRoots.size} roots; it is broken" }
        /**
         * Adventure belongs here for the same reason Bukkit does, and the reason is measured rather
         * than stylistic.
         *
         * `net.kyori.adventure` decides **who may produce a rendered component**. The chat domain
         * emits BBCode (`[b]…[/b]`) precisely because it cannot emit a `Component`: keeping markup a
         * plain string is what lets `ChatLine.body` stay the one function that interprets it, and
         * what keeps a player's `§` from ever becoming a colour. Let the domain reach for Adventure
         * and that split stops being a rule — it becomes a convention nobody is checking.
         *
         * The doc already said so (`15-chat.md` §2, `01` §1) and the gate did not, which is the worse
         * half: a rule that reads as enforced and is not. See 08 §44.
         *
         * `com.meowo.platform` and `com.google.gson` joined it for the same reason: the platform
         * module *is* the Bukkit-facing layer, so a domain that imports it is a domain that has
         * already reached for the server; and Gson is the wire format, which belongs to the codec
         * and not to a tool contract. Both were reachable before this and neither was checked.
         */
        val forbidden = listOf(
            "org\\.bukkit", "io\\.papermc", "taboolib", "net\\.kyori",
            "com\\.meowo\\.platform", "com\\.google\\.gson",
        )
        pureRoots.forEach { folder ->
            fileTree(folder).matching { include("**/*.kt") }.forEach { file ->
                val text = file.readText()
                // Naming the offender, because "Platform import in <file>" sent the reader hunting
                // for which of the four it was.
                forbidden.firstOrNull { Regex("import ($it)\\.").containsMatchIn(text) }?.let { hit ->
                    error("pure source root must not depend on the platform: import $hit.* in $file")
                }
            }
        }
        /**
         * The contexts are derived from `settings.gradle.kts`, which is the only place that says
         * which contexts exist.
         *
         * The hand-written list had already drifted once — `shop` was missing — and a missing
         * context is a context whose cross-imports are never checked, which is precisely the hole
         * this rule exists to close.
         */
        val contexts = Regex("contexts:meowo-([a-z]+)")
            .findAll(file("settings.gradle.kts").readText())
            .map { it.groupValues[1] }
            .toSet()
            .toList()
        check(contexts.isNotEmpty()) { "the context derivation found nothing; it is broken" }
        contexts.forEach { name ->
            fileTree("contexts/meowo-$name/src/main").matching { include("**/*.kt") }.forEach { file ->
                val other = contexts - name
                check(other.none { Regex("import com\\.meowo\\.$it\\.").containsMatchIn(file.readText()) }) { "Cross-context import in $file" }
            }
        }
        /**
         * The menu framework lives in one module.
         *
         * `ui` exists so that a screen is data: a config file says which slot holds which icon and what it
         * does. That only holds while `ui` is the single place that knows how a menu is built — the moment a
         * context keeps its own TabooLib adapter, its layout is back in Kotlin, out of reach of the config,
         * and the migration has silently half-finished. See docs/ui-module.md.
         */
        // No allowance: `ui` is the only module allowed to know how a menu is built. A screen that kept its
        // own TabooLib adapter would hold its layout in Kotlin, out of the config's reach, and the migration
        // would have quietly half-finished — which is exactly what the three named exceptions used to permit.
        subprojects.filter { it.name != "ui" }.forEach { project ->
            fileTree("${project.projectDir}/src/main").matching { include("**/*.kt") }.forEach { file ->
                check(!Regex("import taboolib\\.module\\.ui").containsMatchIn(file.readText())) {
                    "TabooLib menu code outside the ui module: ${file.relativeTo(rootDir).path} " +
                        "(move the screen into ui.yml; see docs/ui-module.md)"
                }
            }
        }
    }
}

val allTests = subprojects.map { it.tasks.named("test") }

/**
 * The gates run as part of `check`.
 *
 * A gate that has to be remembered is a gate that is not there. Both `verifyArchitecture` and
 * `verifyCoverage` were separate tasks that a developer had to know the name of and type, and
 * nothing in the build stopped a change that broke either of them from being merged — the build
 * stayed green and the rule was simply not applied.
 *
 * So `check` depends on both of them, and on every module's own `check`, which is where the tests
 * live. `./gradlew check` is now the whole story, and CI runs exactly that.
 */
tasks.named("check") {
    dependsOn(subprojects.map { it.tasks.named("check") })
    dependsOn("verifyArchitecture")
    dependsOn("verifyCoverage")
}
tasks.register<JacocoReport>("coverageReport") {
    dependsOn(allTests)
    dependsOn(subprojects.map { it.tasks.named("jacocoTestReport") })
    executionData(provider { subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec").get().asFile }.filter { it.exists() } })
    classDirectories.from(subprojects.map { it.extensions.getByType<SourceSetContainer>()["main"].output })
    sourceDirectories.from(subprojects.map { it.file("src/main/kotlin") })
    reports { xml.required.set(true); html.required.set(true) }
}
jacoco { toolVersion = "0.8.14" }
tasks.register("verifyCoverage") {
    dependsOn("coverageReport")
    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        fun coverage(path: File): Double {
            val root = factory.newDocumentBuilder().parse(path).documentElement
            val counters = root.childNodes
            val line = (0 until counters.length).map { counters.item(it) }
                .filterIsInstance<org.w3c.dom.Element>().single { it.tagName == "counter" && it.getAttribute("type") == "LINE" }
            val covered = line.getAttribute("covered").toDouble()
            return covered / (covered + line.getAttribute("missed").toDouble())
        }
        val total = coverage(file("build/reports/jacoco/coverageReport/coverageReport.xml"))
        val domain = coverage(file("contexts/meowo-economy/domain/build/reports/jacoco/test/jacocoTestReport.xml"))
        logger.lifecycle("Line coverage: total=%.2f%%, domain=%.2f%%".format(total * 100, domain * 100))
        /**
         * Every module's **own** coverage, printed next to the total.
         *
         * The total counts a class as covered when *any* module's test touched it, so a module
         * whose classes are only covered by other modules' integration tests looks fine in the
         * total and is not covered at all by itself. That is not a hypothetical: four contexts
         * (`teleport`, `sit`, `home`, `shop`) had **zero** coverage of their own `Context` class
         * and were entirely at the mercy of core's integration tests. The total said 81% and
         * nobody could see the hole.
         *
         * Nothing is checked here — this is a reading, not a gate. A gate would need a number per
         * module, and a number per module is a number somebody has to keep updating by hand, which
         * is the thing this project keeps refusing to do.
         */
        subprojects.forEach { project ->
            val report = project.layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
            if (!report.exists()) return@forEach
            logger.lifecycle("  %-34s %.2f%%".format(project.name, coverage(report) * 100))
        }
        check(total >= 0.70) { "Total coverage below 70%" }
        check(domain >= 0.90) { "Domain coverage below 90%" }
    }
}
