plugins {
    java
    id("com.diffplug.spotless") version "7.2.1"
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependencies) apply false
}
allprojects { group = "dev.tracescope"; version = "0.1.0"; repositories { mavenCentral() } }
subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java { target("src/**/*.java"); googleJavaFormat("1.28.0") }
    }
    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    tasks.withType<Test> { useJUnitPlatform(); testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
}

tasks.register("resolveDependencies") {
    description = "Resolve application artifacts in a reusable Docker build layer"
    doLast {
        subprojects.forEach { project ->
            project.configurations.filter { it.isCanBeResolved && it.name in setOf("compileClasspath", "runtimeClasspath") }.forEach { it.resolve() }
        }
    }
}
