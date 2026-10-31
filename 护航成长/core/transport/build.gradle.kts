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

        // 与 app 模块保持一致：本项目为局域网/私有化部署，默认值仅作未绑定引导，
        // 实际由扫码绑定或高级设置覆盖。生产环境经 Nginx 终止 TLS，默认 https/ssl。
        buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"ssl://padguard.example.com:8883\"")
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://padguard.example.com/api/v1/\"")

        // 服务端未就绪期间用本地 Mock 跑通全链路；release 强制走真实服务端。
        buildConfigField("boolean", "USE_MOCK_SERVER", "false")
    }

    buildTypes {
        debug {
            // 本地真机联调：关闭 Mock，直连局域网真实服务端（示例内网地址，请改你电脑实际 IP）
            buildConfigField("boolean", "USE_MOCK_SERVER", "false")
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://192.168.1.100:8090/api/v1/\"")
            buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"tcp://192.168.1.100:1883\"")
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
