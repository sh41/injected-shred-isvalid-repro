import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("263.7307.44")
        // From 2026.3, XML PSI is in the bundled XML plugin and RegExp is a separate product module.
        bundledPlugin("com.intellij.xml")
        bundledModule("intellij.regexp")
        // testJavadocSnippetSweep: the platform's JavadocInjector is in the bundled Java plugin.
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // Inside the project, the sandbox IDE appears to see its own writes; the refresh cancelled highlighting every 15 s.
    sandboxContainer = file("${System.getProperty("user.home")}/.cache/injected-shred-isvalid-repro/sandbox")
}

tasks.runIde {
    // Room for Snippet4000.java beside a full IDE; a 10,000-line snippet runs out even at 4 GB.
    maxHeapSize = "4g"
}

tasks.test {
    // The tests write their tables and JFR recordings here.
    systemProperty("repro.outputDir", layout.buildDirectory.dir("repro").get().asFile.absolutePath)
    // JFR keeps 64 frames by default, which cuts most highlighting stacks short of the pass that started them.
    jvmArgs("-XX:FlightRecorderOptions:stackdepth=512")
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = false
    }
}
