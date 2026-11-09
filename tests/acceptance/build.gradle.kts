dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.19.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { systemProperty("tracescope.root", rootProject.projectDir.absolutePath); onlyIf { System.getenv("TRACESCOPE_ACCEPTANCE") == "true" }; systemProperty("tracescope.url", System.getenv("TRACESCOPE_URL") ?: "http://localhost:8080") }
