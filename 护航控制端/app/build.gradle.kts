// AGP 应用后，脚本顶层的 `java` 会被 JavaPluginExtension 访问器遮蔽，
// `java.util.Properties` 因此解析失败（Unresolved reference: util）——必须显式 import。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.padguard.parent"
    compileSdk = project.findProperty("COMPILE_SDK")?.toString()?.toInt() ?: 35

    defaultConfig {
        applicationId = "com.padguard.parent"
        minSdk = project.findProperty("MIN_SDK")?.toString()?.toInt() ?: 26
        targetSdk = project.findProperty("TARGET_SDK")?.toString()?.toInt() ?: 35
        // 版本号必须能区分新旧：原先写死为 1 / "1.0.0"，新旧包在系统层面完全同版本，
        // 无法判断装上去的是哪次构建（表现为"重新 Run 了却还是旧行为"）。
        // versionCode 单调递增：设备上已装的是 1，改成 2 才会被系统认定为「升级」，
        // 否则同版本可能被跳过、或被当作降级而装不上。后续每次发版继续手动 +1。
        versionCode = project.findProperty("VERSION_CODE")?.toString()?.toInt() ?: 2
        // versionName 同步递增，手机「应用信息」里可直接核对（旧包显示 1.0.0）
        versionName = project.findProperty("VERSION_NAME")?.toString() ?: "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 高德地图 SDK Key（占位）。真实部署时由运维通过 -PAMAP_KEY=xxx 传入，
        // 或在 gradle.properties 写入 amapKey=xxx；默认空字符串 = 不加载真实底图，定位页回退到等距投影占位图。
        // 绝不把真实 Key 写进代码仓库。
        val amapKey = (findProperty("AMAP_KEY") as? String)?.takeIf { it.isNotBlank() }
            ?: (findProperty("amapKey") as? String)?.takeIf { it.isNotBlank() }
            ?: ""
        manifestPlaceholders["AMAP_KEY"] = amapKey

        // 服务端地址构建期注入。
        // 本项目是局域网/私有化部署（服务端跑在家长电脑上），不存在公网接入点。
        // 曾在此填写虚构公网域名 api.padguard.com：DNS 根本解析不了，家长端所有接口
        // 一律"连不上"，且用户完全无法判断是地址问题还是网络问题。改为与 debug 一致的内网地址，
        // 真实部署在登录页「⚙ 高级设置」改成家长电脑的实际 IP 即可（改完立即生效，无需重打包）。
        buildConfigField("String", "BASE_URL", "\"http://8.148.68.183:8090/v1/\"")
    }

    signingConfigs {
        create("release") {
            // A1：release 签名必须从 local.properties / gradle.properties 或 CI 环境变量注入，禁止把明文密钥写进仓库。
            // 生成（一次性，妥善保管）：
            //   keytool -genkeypair -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias padguard
            // 在 local.properties（不入库）写入：
            //   RELEASE_STORE_FILE=release-key.jks
            //   RELEASE_STORE_PASSWORD=******
            //   RELEASE_KEY_ALIAS=padguard
            //   RELEASE_KEY_PASSWORD=******
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
            // 缺字段时此处不抛错（避免连 debug 都跑不起来）；真正的守卫在文件末尾 afterEvaluate：
            // 仅当真正 assembleRelease 且仍未配置时，才给出明确的"缺签名"报错。
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
            // Debug API endpoint (can be overridden with local server)
            buildConfigField("String", "BASE_URL", "\"http://8.148.68.183:8090/v1/\"")
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
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR code generation (ZXing core, pure Java, no camera dependency)
    implementation("com.google.zxing:core:3.5.3")

    // JSON
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // 高德地图 SDK（定位页真实底图）。3dmap 已包含地图核心与定位组件，版本经阿里云镜像托管。
    implementation("com.amap.api:3dmap:10.0.600")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// A4：高德 Key 注入守卫 + release 签名守卫（仅在实际打 release 包时校验，不影响 debug）。
// 缺少高德 Key 会导致定位页白屏/无底图（上线事故）；缺少签名则包无法安装。
afterEvaluate {
    val rel = android.signingConfigs.findByName("release")
    if (rel?.storeFile == null) {
        tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
            doFirst {
                throw GradleException(
                    "release 签名未配置：请在 local.properties 设置 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / " +
                    "RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD，或由 CI 注入同名环境变量。禁止将明文密钥提交到仓库。"
                )
            }
        }
    }
    val amapKey = (findProperty("AMAP_KEY") as? String)?.takeIf { it.isNotBlank() }
        ?: (findProperty("amapKey") as? String)?.takeIf { it.isNotBlank() }
    if (amapKey.isNullOrBlank()) {
        tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
            doFirst {
                throw GradleException(
                    "release 缺少高德地图 Key：请用 -PAMAP_KEY=xxx 注入，或在 gradle.properties 写入 amapKey=xxx；" +
                    "并在高德开放平台控制台把「release keystore 的 SHA1」加入该 Key 的签名绑定，否则定位页白屏/无底图（上线事故）。" +
                    "debug 构建允许空 Key（回退示意占位图）。"
                )
            }
        }
    }
}

// W80：versionCode 单调守卫。CI / 每次构建校验 versionCode 不低于已发布基线，
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
                    "W80: versionCode=$current 小于已发布基线 $baseline，禁止回退。请递增 gradle.properties 中的 VERSION_CODE 后再构建。"
                }
            }
        }
    }
}
