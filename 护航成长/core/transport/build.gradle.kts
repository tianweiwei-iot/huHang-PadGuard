plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// 与 app 模块「同一套」注入口径（Gradle 脚本无法引用 app 源码，只能各写一份、必须一致）：
//   * 优先级：-P 属性 > 环境变量 PADGUARD_ALLOW_CLEARTEXT_PUBLIC。
//   * 默认 false：孩子端**默认关死**公网明文，令牌与儿童数据绝不向公网明文传输。
//   * 仅「公网 IP 但走专线/VPN」这类需人工确认的特殊部署才显式开启，且 app 模块的
//     release 构建守卫（validateReleaseEndpoints）会同步校验并打告警日志。
// 关键闭环：app 模块把 -PALLOW_CLEARTEXT_PUBLIC 用于「出厂构建守卫」，本模块把它写进
// BuildConfig.ALLOW_CLEARTEXT_PUBLIC，运行时 EndpointSafety / TransportSettings 据此放行。
// 若只改一处、另一处硬编码 false，就会出现「构建守卫放行、运行期却仍拒绝」的割裂——
// 这正是第二十三轮遗留的接线缺口，此处补齐。
val transportAllowCleartextPublic: Boolean =
    (findProperty("ALLOW_CLEARTEXT_PUBLIC") as? String)?.equals("true", ignoreCase = true) == true ||
        System.getenv("PADGUARD_ALLOW_CLEARTEXT_PUBLIC")?.equals("true", ignoreCase = true) == true

android {
    namespace = "com.padguard.core.transport"
    compileSdk = project.findProperty("COMPILE_SDK")?.toString()?.toInt() ?: 35

    defaultConfig {
        minSdk = project.findProperty("MIN_SDK")?.toString()?.toInt() ?: 26
        consumerProguardFiles("consumer-rules.pro")

        // 与 app 模块保持一致：本项目为局域网/私有化部署，默认值仅作未绑定引导，
        // 实际由扫码绑定或高级设置覆盖。生产环境经 Nginx 终止 TLS，默认 https/ssl。
        buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"ssl://padguard.example.com:8883\"")
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://padguard.example.com/api/v1/\"")
        // 孩子端公网明文开关：默认关死，仅特殊部署经 -PALLOW_CLEARTEXT_PUBLIC=true 显式开启。
        buildConfigField("boolean", "ALLOW_CLEARTEXT_PUBLIC", transportAllowCleartextPublic.toString())

        // 服务端未就绪期间用本地 Mock 跑通全链路；release 强制走真实服务端。
        buildConfigField("boolean", "USE_MOCK_SERVER", "false")

        // 证书固定（Certificate Pinning）静态 pin 注入位：运维用 openssl 取生产证书
        // SPKI SHA-256 后写入 `-PcertPins=sha256/xxx,sha256/yyy`。为空时走 TOFU 兜底
        // （首次可信握手记下公钥，之后未知签发者即拒），同样可阻断自定义 CA 主动 MITM。
        val certPins = (findProperty("certPins") as? String)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()
        buildConfigField("String", "CERT_PINS", "\"${certPins.joinToString(",")}\"")
    }

    buildTypes {
        debug {
            // 本地真机联调：关闭 Mock，直连局域网真实服务端（示例内网地址，请改你电脑实际 IP）
            buildConfigField("boolean", "USE_MOCK_SERVER", "false")
            // 让 -PBASE_URL / -PMQTT_BROKER（Gradle -P 全局可见，所有模块共享同一属性名）真正落到
            // transport 的默认接入点：此前 app 模块注入的地址只写进 app 的 BuildConfig，
            // 而 TransportSettings 读的是 core:transport 的 BuildConfig，导致「给 app 注入 .11 后
            // APK 里 TransportSettings 读到的默认仍是 .100、必须靠人工手填」这一断链。
            // 未注入时回退 .100，保持原行为；真正的装机兜底仍是绑定页手填 + 连接自检。
            val transportBaseUrl = (findProperty("BASE_URL") as? String)?.takeIf { it.isNotBlank() }
                ?: "http://192.168.1.100:8090/api/v1/"
            val transportMqttBroker = (findProperty("MQTT_BROKER") as? String)?.takeIf { it.isNotBlank() }
                ?: "tcp://192.168.1.100:1883"
            buildConfigField("String", "DEFAULT_BASE_URL", "\"$transportBaseUrl\"")
            buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"$transportMqttBroker\"")
            // 与 release 保持同一开关口径：调试特殊部署时也能复现运行期放行逻辑。
            buildConfigField("boolean", "ALLOW_CLEARTEXT_PUBLIC", transportAllowCleartextPublic.toString())
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
    // mockito-core 5.x 默认 inline mock maker，可 mock final class：
    // CommandGate 契约测试用它替代 DataStore/Application 依赖的 CredentialStore/TransportSettings
    testImplementation(libs.mockito.core)
}
