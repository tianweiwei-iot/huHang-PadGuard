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
        minSdk = project.findProperty("MIN_SDK").toString().toInt()
        targetSdk = project.findProperty("TARGET_SDK").toString().toInt()
        versionCode = project.findProperty("VERSION_CODE").toString().toInt()
        versionName = project.findProperty("VERSION_NAME").toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 服务端地址与 MQTT Broker 由构建期注入，正式环境在 release 中覆盖
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://api.padguard.cn/api/v1/\"")
        buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"ssl://mqtt.padguard.cn:8883\"")
    }

    signingConfigs {
        // 注意：Device Owner / Kiosk 场景下 APK 签名不可更换，
        // 请在 local.properties 或 CI 环境变量中配置正式 keystore。
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://192.168.1.10:8090/api/v1/\"")
            buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"tcp://192.168.1.10:1883\"")
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
