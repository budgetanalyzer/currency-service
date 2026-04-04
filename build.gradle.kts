import org.springframework.boot.gradle.tasks.run.BootRun

// TC 1.21.4 fixes Docker 29.x compatibility (1.21.3 breaks with "client version 1.32 is too old").
// Spring Boot 3.5.7 manages TC to 1.21.3, so we override it here.
extra["testcontainers.version"] = libs.versions.testcontainers.get()

plugins {
    java
    checkstyle
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "org.budgetanalyzer"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
        mavenBom("org.springframework.modulith:spring-modulith-bom:${libs.versions.springModulith.get()}")
    }
}

dependencies {
    // Service-web provides core functionality and common utilities
    implementation(libs.service.web)

    // Stack-specific dependencies (required since service-web uses compileOnly)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.springdoc.openapi)

    // Service-specific base
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Add-ons: WebClient, Redis, Messaging, Modulith, ShedLock
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.spring.cloud.stream)
    implementation(libs.spring.cloud.stream.binder.rabbit)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jpa)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org", "com", "", "org.budgetanalyzer")
        removeUnusedImports()
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    config = resources.text.fromUri("https://raw.githubusercontent.com/budgetanalyzer/checkstyle-config/main/checkstyle.xml")
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}

val jvmArgsList = listOf(
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED"
)

tasks.withType<BootRun> {
    jvmArgs = jvmArgsList
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs = jvmArgsList
}

tasks.withType<Javadoc> {
    options {
        (this as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:all,-missing", "-quiet")
        }
    }
}
