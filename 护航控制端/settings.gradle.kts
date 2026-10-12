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
include(":core:network")
include(":core:database")
include(":core:ui")
