rootProject.name = "padguard-server"

pluginManagement {
    repositories {
        // 国内镜像优先，避免 Gradle Plugin Portal 拉取超时
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        mavenCentral()
    }
}

// 自动下载缺失的 JDK toolchain，避免本机只有 JDK 25 时无法编译 JDK 17 项目
// 注意：settings.gradle.kts 中 plugins 块必须放在 pluginManagement 之后
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    // 统一仓库，禁止子项目再声明，确保全部走国内镜像
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}
