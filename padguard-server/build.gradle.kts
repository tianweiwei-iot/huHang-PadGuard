plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    kotlin("plugin.jpa") version "1.9.24"
    id("org.springframework.boot") version "3.3.2"
    id("io.spring.dependency-management") version "1.1.5"
}

group = "com.padguard"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    // 本地免 Docker 运行（local profile）使用 H2 内存库
    runtimeOnly("com.h2database:h2")
    implementation("org.springframework.security:spring-security-crypto:6.3.1")

    // Resilience4j（S14 限流）：只用核心 ratelimiter 模块。
    // 动态 key（按 IP / 账号）只能编程式经 Registry 管理，注解式 starter 不适用；
    // 核心模块即 Resilience4j 官方对动态场景的标准用法。
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.2.0")

    // JWT (parent auth) — jjwt 0.12.x
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // MQTT (EMQX bridge) — Paho MQTT v5 client
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    // JSON / Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 仅用于 testDirect 任务：提供 ConsoleLauncher 入口。
    // 之所以需要它：本机项目路径含中文（E:\项目），Gradle 的 Test worker 走
    // "类路径清单 jar" 时无法解析非 ASCII 路径，导致测试类 ClassNotFoundException。
    // 详见下方 testDirect 的说明。
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.10.2")
}

kotlin {
    // 显式锁定 JDK 17，与 Dockerfile 里的 gradle:8.8-jdk17 / eclipse-temurin:17-jre 保持一致
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
    archiveFileName.set("app.jar")
}

// 不写这段的话 `./gradlew test` 会"成功"但一个用例都不跑（报告里 tests=0）——
// Spring Boot 3.x 的 Gradle 插件不会自动启用 JUnit 5 平台，而 starter-test 带的正是 JUnit 5。
// 这种静默通过比报错危险得多：你会以为有测试在把关，实际上什么都没验证。
tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// 本机替代入口：`./gradlew testDirect`
//
// `./gradlew test` 在这台机器上跑不起来：项目路径含中文（E:\项目），
// Gradle 的 Test worker 会把长类路径打包成"清单 jar"，其 Class-Path 条目无法正确
// 解析非 ASCII 路径，于是每个测试类都 ClassNotFoundException（报告里 tests=0 或全失败，
// 而类文件其实编译得好好的）。这是环境问题，不是测试代码的问题。
//
// 本任务绕开 worker，直接在 JVM 里跑 JUnit ConsoleLauncher，可正常执行。
// 换到纯 ASCII 路径的环境下，`./gradlew test` 本身即可，无需本任务。
tasks.register<JavaExec>("testDirect") {
    group = "verification"
    description = "Runs unit tests directly (workaround for non-ASCII project path breaking the Gradle test worker)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args("--scan-classpath", "--details=tree", "--disable-banner")
}
