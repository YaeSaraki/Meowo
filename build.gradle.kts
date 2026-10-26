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
