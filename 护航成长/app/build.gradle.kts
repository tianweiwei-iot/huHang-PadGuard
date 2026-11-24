// AGP 应用后，脚本顶层的 `java` 会被 JavaPluginExtension 访问器遮蔽，
// `java.util.Properties` 因此解析失败（Unresolved reference: util）——必须显式 import。
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// 出厂接入点（服务端 + MQTT Broker）注入与 release 守卫（U-07）
//
// 孩子端是**全家唯一一台必须开箱能用**的设备：家长端连错地址还能改，孩子端一旦
// 出厂地址是占位域名，装到平板上就是"永远连不上"，而孩子端恰恰没有键盘、也没法
// 让小学生自己去改高级设置。原实现把 `https://padguard.example.com` 直接写死在
// defaultConfig 里，且**没有任何构建期守卫**（家长端早在上一轮就有了
// validateReleaseEndpoint）——两者严重不对称，等于给"忘了注入"留了一条静默出厂的路。
//
// 与家长端保持同一套规则（Gradle 脚本无法引用 app 源码，只能各写一份、口径必须一致）：
//   * 优先级：-P 属性 > 环境变量 > gradle.properties / local.properties（不入库）；
//   * release 必须显式注入，且不允许占位域名；
//   * 公网不允许明文 http/tcp（内网 IP 例外，家庭部署本来就跑在同一信任域）；
//   * debug 未注入时按网卡探测本机私网 IP，避免"打包的人忘了加 -P，装机后连不上"。
// 运行期还有第二道防线：绑定页/高级设置可覆盖，且扫码绑定会带服务器地址。
// ---------------------------------------------------------------------------
val padguardLocalProps = Properties().apply {
    val f = rootProject.file("local.properties")
    // 必须用 UTF-8 Reader：Properties.load(InputStream) 固定 ISO-8859-1 解码，
    // 会把中文路径（护航PadGuard）解成 mojibake。
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

fun propOrEnv(prop: String, env: String): String? =
    (findProperty(prop) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: padguardLocalProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val configuredBaseUrl: String? = propOrEnv("BASE_URL", "PADGUARD_BASE_URL")
val configuredMqttBroker: String? = propOrEnv("MQTT_BROKER", "PADGUARD_MQTT_BROKER")

val allowCleartextPublic: Boolean =
    (findProperty("ALLOW_CLEARTEXT_PUBLIC") as? String)?.equals("true", ignoreCase = true) == true ||
        System.getenv("PADGUARD_ALLOW_CLEARTEXT_PUBLIC")?.equals("true", ignoreCase = true) == true

/** 本机私网 IPv4（排除回环、169.254 与常见虚拟网卡），取不到返回 null。 */
fun detectLocalLanIpv4(): String? = runCatching {
    val virtualHints = listOf(
        "vmware", "virtualbox", "vbox", "hyper-v", "vethernet", "wsl",
        "docker", "tailscale", "npcap", "loopback", "bluetooth"
    )
    Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
        .filter { ni -> ni.isUp && !ni.isLoopback }
        .filterNot { ni -> virtualHints.any { h -> ni.name.lowercase().contains(h) } }
        .flatMap { ni -> Collections.list(ni.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { addr -> addr.hostAddress }
        .firstOrNull { ip: String ->
            val p = ip.split('.').mapNotNull { it.toIntOrNull() }
            p.size == 4 && (p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168))
        }
}.getOrNull()

val debugLanIp = detectLocalLanIpv4()
// 兜底用 .100 而不是 .10：孩子端历史默认值就是 .100，改动它会让既有联调机"突然连不上"，
// 而这只影响未注入时的 debug 包。真正的修复是"按网卡探测"，不是换一个猜的 IP。
val debugFallbackBaseUrl = "http://${debugLanIp ?: "192.168.1.100"}:8090/api/v1/"
val debugFallbackMqtt = "tcp://${debugLanIp ?: "192.168.1.100"}:1883"

/** 占位域名/回环一律拒绝：这些值一旦出厂，孩子端在真实网络里必然解析失败。 */
fun isPlaceholderHost(raw: String): Boolean {
    val host = raw.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    return host.isEmpty() || host == "localhost" || host == "127.0.0.1" || host == "::1" ||
        host == "10.0.2.2" || host.endsWith("example.com") || host.endsWith("example.org") ||
        host.endsWith("example.net") || host.contains("your-domain") || host.contains("yourdomain") ||
        // 与家长端 isPlaceholderHost 同规则：少这一条时 -PBASE_URL=https://your.domain/api/v1/
        // 会绕过守卫出厂，孩子端连不上服务端且没有任何构建期提示。
        host.contains("your.domain")
}

/** 与家长端 EndpointSafety.isLanHost 的 IPv4/IPv6 规则保持一致的构建期简化判定。 */
fun isLanHost(raw: String): Boolean {
    val host = raw.substringAfter("://").substringBefore('/').substringBefore(':')
        .trimStart('[').trimEnd(']').lowercase()
    if (host == "localhost" || host == "::1") return true
    val parts = host.split('.')
    if (parts.size != 4) {
        return host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb")
    }
    val nums = parts.mapNotNull { it.toIntOrNull() }
    if (nums.size != 4 || nums.any { it !in 0..255 }) return false
    val (a, b) = nums
    return a == 127 || a == 10 || (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) || (a == 169 && b == 254)
}

fun validateReleaseEndpoints(baseUrl: String?, mqttBroker: String?) {
    if (baseUrl.isNullOrBlank()) {
        throw GradleException(
            "release 未注入服务端地址：请用 -PBASE_URL=https://<域名>/api/v1/ " +
                "或环境变量 PADGUARD_BASE_URL 注入（也可写入 local.properties，不入库）。" +
                "孩子端没有键盘、也没法让孩子自己改地址，出厂地址错一次就是一整批设备连不上。"
        )
    }
    if (mqttBroker.isNullOrBlank()) {
        throw GradleException(
            "release 未注入 MQTT Broker：请用 -PMQTT_BROKER=ssl://<域名>:8883 " +
                "或环境变量 PADGUARD_MQTT_BROKER 注入。指令下行依赖它，缺了等于只能靠轮询，实时管控失效。"
        )
    }
    if (isPlaceholderHost(baseUrl) || isPlaceholderHost(mqttBroker)) {
        throw GradleException(
            "release 接入点仍是占位值：base=$baseUrl, mqtt=$mqttBroker。" +
                "example.com / localhost / 10.0.2.2 这类地址在真实网络里必然解析失败，禁止出厂。"
        )
    }
    val baseCleartext = !baseUrl.startsWith("https://", ignoreCase = true)
    if (baseCleartext && !isLanHost(baseUrl) && !allowCleartextPublic) {
        throw GradleException(
            "release 服务端地址 $baseUrl 是明文 HTTP 且目标不是内网：绑定令牌与孩子数据会被明文截获。" +
                "请改用 https（Nginx 终止 TLS），或确认目标确为内网 IP。" +
                "如为「公网 IP 但走专线/VPN」，需显式加 -PALLOW_CLEARTEXT_PUBLIC=true 并在运维侧留痕。"
        )
    }
    val mqttCleartext = !mqttBroker.startsWith("ssl://", ignoreCase = true)
    if (mqttCleartext && !isLanHost(mqttBroker) && !allowCleartextPublic) {
        throw GradleException(
            "release MQTT Broker $mqttBroker 未启用 TLS 且目标不是内网：指令通道可被窃听/注入。" +
                "请改用 ssl://（8883），或确认目标确为内网 IP。"
        )
    }
    val baseHost = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
    val mqttHost = mqttBroker.substringAfter("://").substringBefore('/').substringBefore(':')
    if (!baseHost.equals(mqttHost, ignoreCase = true)) {
        logger.warn(
            "⚠ release 的 HTTP 与 MQTT 不同主机：base=$baseHost, mqtt=$mqttHost。" +
                "若这是有意的分离部署请忽略；否则设备会连得上服务器却收不到指令。"
        )
    }
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

        // 服务端地址与 MQTT Broker 默认接入点（构建期注入）。
        // 本项目为局域网/私有化部署，默认值仅作「未绑定前引导页」使用；
        // 真实部署由扫码绑定（二维码含服务器地址）或「高级设置」覆盖，无需改代码发版。
        // 生产环境（云部署）经 Nginx 终止 TLS（见 docs/生产部署TLS配置说明.md），
        // 此处默认 https/ssl，公网明文传输由反向代理消除；家庭局域网部署走 http/tcp 内网（同一信任域）。
        // 出厂接入点（详见文件顶部注释）。未注入时先落占位值让配置阶段跑完，
        // `assembleRelease` 会被 verifyReleaseEndpoint 直接拦下 —— 绝不让占位值真的出厂。
        buildConfigField("String", "DEFAULT_BASE_URL", "\"${configuredBaseUrl ?: "https://padguard.example.com/api/v1/"}\"")
        buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"${configuredMqttBroker ?: "ssl://padguard.example.com:8883"}\"")
    }

    signingConfigs {
        // 注意：Device Owner / Kiosk 场景下 APK 签名不可更换，
        // 请在 local.properties 或 CI 环境变量中配置正式 keystore。
        create("release") {
            val localProps = Properties().apply {
                val f = rootProject.file("local.properties")
                // 必须用 UTF-8 Reader 加载：Properties.load(InputStream) 固定按 ISO-8859-1 解码，
                // local.properties 中的中文路径（护航PadGuard）会被解码成 mojibake，
                // 导致 release 签名校验报 keystore 文件不存在。
                if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
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
            // 局域网联调：优先按网卡探测本机私网 IP（换 DHCP 也不会失效），
            // 取不到才退回内网示例地址。release 不走这里，仍由守门任务保证不会带内网地址出厂。
            buildConfigField("String", "DEFAULT_BASE_URL", "\"${configuredBaseUrl ?: debugFallbackBaseUrl}\"")
            buildConfigField("String", "DEFAULT_MQTT_BROKER", "\"${configuredMqttBroker ?: debugFallbackMqtt}\"")
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
    // UsageSessionManager 等需要 mock final 类（Context / SharedPreferences / TimeProvider）：
    // Mockito 5 inline mockmaker 直接支持 final 类，与 core:engine 的测试栈保持一致。
    testImplementation(libs.mockito.core)
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

/**
 * 可独立运行的出厂接入点守卫：不打整包也能在 CI / 本地验证。
 *
 * 用法（四个用例即为回归口径）：
 *   ./gradlew :app:verifyReleaseEndpoint                                   # 未注入 → FAILED
 *   ./gradlew :app:verifyReleaseEndpoint -PBASE_URL=https://a.example.com/api/v1/ -PMQTT_BROKER=ssl://a.example.com:8883
 *                                                                          # 占位域名 → FAILED
 *   ./gradlew :app:verifyReleaseEndpoint -PBASE_URL=http://192.168.1.50:8090/api/v1/ -PMQTT_BROKER=tcp://192.168.1.50:1883
 *                                                                          # 内网明文 → 通过
 *   ./gradlew :app:verifyReleaseEndpoint -PBASE_URL=https://pad.example.cn/api/v1/ -PMQTT_BROKER=ssl://pad.example.cn:8883
 *                                                                          # 公网 HTTPS → 通过
 */
tasks.register("verifyReleaseEndpoint") {
    group = "verification"
    description = "校验 release 出厂接入点：必须显式注入、不得是占位域名，公网必须 HTTPS/TLS"
    // 必须每次真跑：任务只读项目属性、不产出文件，若不加这一行，第二次调用会被判 UP-TO-DATE
    // 直接跳过 —— 于是"换成占位值再跑一次"也能 BUILD SUCCESSFUL，守卫形同虚设。
    outputs.upToDateWhen { false }
    doLast {
        validateReleaseEndpoints(configuredBaseUrl, configuredMqttBroker)
        logger.lifecycle("release 出厂接入点校验通过：base=${configuredBaseUrl}，mqtt=${configuredMqttBroker}")
        if (allowCleartextPublic) {
            logger.warn(
                "⚠ ALLOW_CLEARTEXT_PUBLIC 已开启，release 允许明文公网传输：${configuredBaseUrl} / ${configuredMqttBroker}。" +
                    "请确认这是经过审批的特殊部署，并在上线检查单中记录。"
            )
        }
    }
}

// 打包入口同样校验：只挂 verify 任务还不够 —— `./gradlew assembleRelease` 是大家实际会敲的命令，
// 它不认识"另一个任务的依赖关系"，必须显式 doFirst 拦一刀。
afterEvaluate {
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
        doFirst { validateReleaseEndpoints(configuredBaseUrl, configuredMqttBroker) }
    }
}
