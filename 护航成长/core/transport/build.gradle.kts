plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.padguard.core.transport"
    compileSdk = project.findProperty("COMPILE_SDK").toString().toInt()

    defaultConfig {
        minSdk = project.findProperty("MIN_SDK").toString().toInt()
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"ssl://mqtt.padguard.cn:8883\"")
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://api.padguard.cn/api/v1/\"")

        // 服务端未就绪期间用本地 Mock 跑通全链路；release 强制走真实服务端。
        buildConfigField("boolean", "USE_MOCK_SERVER", "false")
    }

    buildTypes {
        debug {
            // 本地真机联调：关闭 Mock，直连局域网真实服务端
            buildConfigField("boolean", "USE_MOCK_SERVER", "false")
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://192.168.1.10:8090/api/v1/\"")
            buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"tcp://192.168.1.10:1883\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // HTTP
    api(libs.okhttp)
    api(libs.okhttp.logging)
    api(libs.retrofit)
    api(libs.retrofit.kotlinx.serialization)

    // MQTT
    api(libs.paho.service)

    // 单元测试（JVM：ApiCaller / ApiResult 逻辑不依赖 Android）
    testImplementation(libs.junit)
}
