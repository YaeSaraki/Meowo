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
subprojects {
    group = "com.meowo"
    version = "1.0.0"
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
