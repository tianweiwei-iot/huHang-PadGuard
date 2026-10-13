// AGP 应用后，脚本顶层的 `java` 会被 JavaPluginExtension 访问器遮蔽，
// `java.util.Properties` 因此解析失败（Unresolved reference: util）——必须显式 import。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.padguard.child"
    compileSdk = project.findProperty("COMPILE_SDK").toString().toInt()

    defaultConfig {
        applicationId = "com.padguard.child"
        // 与护航控制端一致：属性缺失时落安全默认值，避免 build.gradle.kts 配置阶段
        // 因 findProperty(...).toString() 在 null 上 NPE 直接炸构建（而非给出可读报错）。
        minSdk = project.findProperty("MIN_SDK")?.toString()?.toInt() ?: 26
        targetSdk = project.findProperty("TARGET_SDK")?.toString()?.toInt() ?: 35
        // 属性缺失时不抛裸 NPE——下方 versionCode 单调守卫会以可读的
        // "versionCode 小于已发布基线" 报错拦住构建，比 Kotlin 空指针栈友好。
        // 默认值取历史首发版本（低于 baseline，保证缺失即被守卫拦下）。
        versionCode = project.findProperty("VERSION_CODE")?.toString()?.toInt() ?: 2
        versionName = project.findProperty("VERSION_NAME")?.toString() ?: "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 服务端地址与 MQTT Broker 由构建期注入。
        // 本项目是局域网/私有化部署（服务端跑在家长电脑上），不存在公网接入点，
        // 因此默认地址用内网示例地址，与 debug 保持一致。
        // 曾在此填写虚构公网域名 api.padguard.cn：DNS 根本解析不了，
        // 客户端只会得到"无法连接"，且用户完全无法判断是地址问题还是网络问题。
        // 真实部署请在 App 内「高级设置」改成家长电脑的实际 IP（或由打包脚本注入）。
        buildConfigField("String", "DEFAULT_BASE_URL", "\"http://8.148.68.183:8090/api/v1/\"")
        buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"tcp://8.148.68.183:1883\"")
    }

    signingConfigs {
        // 注意：Device Owner / Kiosk 场景下 APK 签名不可更换，
        // 请在 local.properties 或 CI 环境变量中配置正式 keystore。
        create("release") {
            val localProps = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            val ksFile = (findProperty("RELEASE_STORE_FILE") as? String) ?: localProps.getProperty("RELEASE_STORE_FILE")
            val ksPass = (findProperty("RELEASE_STORE_PASSWORD") as? String) ?: localProps.getProperty("RELEASE_STORE_PASSWORD")
            val ksAlias = (findProperty("RELEASE_KEY_ALIAS") as? String) ?: localProps.getProperty("RELEASE_KEY_ALIAS")
            val keyPass = (findProperty("RELEASE_KEY_PASSWORD") as? String) ?: localProps.getProperty("RELEASE_KEY_PASSWORD")
            if (ksFile != null && ksPass != null && ksAlias != null && keyPass != null) {
                storeFile = rootProject.file(ksFile)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://8.148.68.183:8090/api/v1/\"")
            buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"tcp://8.148.68.183:1883\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:transport"))

    // DataStore（操作密码闸门 GuardPasswordManager 直接持久化哈希，需本模块可见）
    implementation(libs.datastore.preferences)

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // 网络 / 序列化（app 层直接注入 OkHttp 以便统一管理拦截器）
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 扫码绑定（CameraX + MLKit，说明书 §4.3 方式①）
    implementation(libs.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    testImplementation(libs.junit)
}

// release 签名守卫（仅在实际打 release 包时校验，不影响 debug）。
// 孩子端为 Device Owner / Kiosk 场景，APK 签名不可更换，正式 keystore 必须就位，否则包无法安装。
afterEvaluate {
    val rel = android.signingConfigs.findByName("release")
    if (rel?.storeFile == null) {
        tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
            doFirst {
                throw GradleException(
                    "release 签名未配置：请在 local.properties 设置 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / " +
                    "RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD，或由 CI 注入同名环境变量。孩子端为 DO/Kiosk 场景，签名不可更换，务必使用正式 keystore。"
                )
            }
        }
    }
}

// versionCode 单调守卫。CI / 每次构建校验 versionCode 不低于已发布基线，
// 防止无意回退导致设备把新包当降级拒绝安装（表现：重装了 App 却还是旧行为）。
// 基线文件 versionCode.baseline 随每次发版手动 +1，并随代码一起提交。
afterEvaluate {
    val baselineFile = file("versionCode.baseline")
    if (baselineFile.exists() && baselineFile.readText().trim().isNotEmpty()) {
        val baseline = baselineFile.readText().trim().toIntOrNull() ?: 0
        val current = (findProperty("VERSION_CODE") as? String)?.trim()?.toIntOrNull() ?: 0
        tasks.named("preBuild") {
            doLast {
                check(current >= baseline) {
                    "versionCode=$current 小于已发布基线 $baseline，禁止回退。请递增 gradle.properties 中的 VERSION_CODE 后再构建。"
                }
            }
        }
    }
}
