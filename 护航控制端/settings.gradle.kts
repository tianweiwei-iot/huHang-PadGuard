pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 高德（AMap）官方 Maven 源在国内常不可达；阿里云镜像稳定托管其 3dmap 包，
        // 故从此处解析 com.amap.api:3dmap，避免依赖官方源导致构建失败。
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "PadGuardParent"
include(":app")
include(":core:common")
// core:network / core:database / core:ui 为无源码空壳，已删除（2026-11-18）；
// 其传递依赖已改由 :app 直接声明，见 app/build.gradle.kts。
