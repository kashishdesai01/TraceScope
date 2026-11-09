plugins { alias(libs.plugins.spring.boot); alias(libs.plugins.spring.dependencies) }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
val agent by configurations.creating
dependencies { agent("io.opentelemetry.javaagent:opentelemetry-javaagent:2.22.0") }
tasks.register<Copy>("copyAgent") { from(agent); into(layout.buildDirectory.dir("otel")); rename { "opentelemetry-javaagent.jar" } }
