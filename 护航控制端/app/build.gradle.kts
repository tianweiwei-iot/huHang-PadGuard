// AGP 应用后，脚本顶层的 `java` 会被 JavaPluginExtension 访问器遮蔽，
// `java.util.Properties` 因此解析失败（Unresolved reference: util）——必须显式 import。
import java.security.MessageDigest
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// local.properties（不入库）统一加载点：签名 RELEASE_* 与高德 amapKey 都支持从这里注入。
// 注意 Gradle 的 findProperty 只认命令行 -P 与 gradle.properties，不读 local.properties——
// 若没有这个加载点，把 amapKey 写进 local.properties 是不生效的（曾因此底图不加载排查半天）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    // 必须用 UTF-8 Reader 加载：Properties.load(InputStream) 固定按 ISO-8859-1 解码，
    // 而 local.properties 以 UTF-8 保存，中文路径（如 D:/.../护航PadGuard/...）会被拆成
    // mojibake（æ¤èªPadGuard），导致 validateSigningRelease 报 keystore 不存在。
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

// ---------------------------------------------------------------------------
// 服务端地址：构建期注入，仓库内不再保留任何真实地址。
//
// 原实现把 `http://8.148.68.183:8090/v1/` 同时写死在 defaultConfig 与 debug 里，
// 而上方注释却写着「本项目是局域网/私有化部署，不存在公网接入点」——代码与文档互相矛盾，
// 且等于让 release 包默认把登录令牌与儿童数据以明文发往公网。已按 P0 安全项根治：
//   * 优先级：-PBASE_URL > 环境变量 PADGUARD_BASE_URL > gradle.properties / local.properties 的 BASE_URL；
//   * debug 未注入时回退到**明显的内网示例地址**，保证本机开发仍可直接运行；
//   * release 必须显式注入，且注入值必须是 https / 私网 IP（或显式开关），否则拒绝打包。
// 运行期还有第二道防线：data/remote/EndpointSafety.kt 会在保存地址与发请求前复核。
// 规则调整时两处必须同步（Gradle 脚本无法引用 app 源码）。
// 注意：⟦TBD-2⟧ 部署形态（云 SaaS / 家庭自建 / 双形态）尚未拍板，
// 这里只落实「公网不得明文」这条与口径无关的技术底线，不替产品决定最终域名或形态。
// ---------------------------------------------------------------------------
val configuredBaseUrl: String? =
    (findProperty("BASE_URL") as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv("PADGUARD_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("BASE_URL")?.takeIf { it.isNotBlank() }

val allowCleartextPublic: Boolean =
    (findProperty("ALLOW_CLEARTEXT_PUBLIC") as? String)?.equals("true", ignoreCase = true) == true ||
        System.getenv("PADGUARD_ALLOW_CLEARTEXT_PUBLIC")?.equals("true", ignoreCase = true) == true

// 证书固定（Certificate Pinning，P0-1 对称加固）：默认空 = 启用 TOFU 兜底
// （首次信任 + 同签发者轮换豁免），适合家长端地址可运行时更改 / LAN 私有化部署；
// 生产环境可用 -PcertPins=sha256/xxx,sha256/yyy 注入证书 SPKI 哈希做严格静态固定。
val certPins: Set<String> =
    (findProperty("certPins") as? String)?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
        ?: emptySet()

/**
 * 未显式注入 BASE_URL 时，构建期自动取本机私网 IPv4 作为 debug 默认地址。
 *
 * 原实现把兜底写死成内网示例 IP `192.168.1.10`：只要打包的人忘了加 `-PBASE_URL`，
 * 产出的包指向一台不存在的机器，装机后表现为「连不上服务器 / 注册不了」——
 * 这是最容易被误判成服务端故障的情形（11-07 实际发生过，排查花了整轮）。
 * 每次手写 IP 又会随 DHCP 变化失效，所以在构建期直接读网卡：
 *   * 只取私网 10/172.16~31/192.168，排除回环与 169.254 链路本地；
 *   * 排除常见虚拟网卡（VMware / VirtualBox / Hyper-V / WSL / Docker / Tailscale），
 *     否则会选中宿主机根本不可达的那块网卡；
 *   * 一个都取不到就退回原示例地址，与旧行为完全一致（不会让构建失败）。
 *
 * 只影响 debug：release 未显式注入时 `validateReleaseEndpoint` 仍直接拒绝打包，
 * 绝不会因为这里的自动探测就把内网地址带上出厂包。
 */
fun detectLocalLanIpv4(): String? = runCatching {
    val virtualHints = listOf(
        "vmware", "virtualbox", "vbox", "hyper-v", "vethernet", "wsl",
        "docker", "tailscale", "npcap", "loopback", "蓝牙", "bluetooth"
    )
    val nics = NetworkInterface.getNetworkInterfaces()
    Collections.list(nics).asSequence()
        .filter { ni -> ni.isUp && !ni.isLoopback }
        .filterNot { ni -> virtualHints.any { h -> ni.name.lowercase().contains(h) } }
        .flatMap { ni -> Collections.list(ni.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { addr -> addr.hostAddress }
        .firstOrNull { ip: String ->
            val p = ip.split('.').mapNotNull { it.toIntOrNull() }
            p.size == 4 && (
                p[0] == 10 ||
                    (p[0] == 172 && p[1] in 16..31) ||
                    (p[0] == 192 && p[1] == 168)
                )
        }
}.getOrNull()

/** 仅 debug 可用：优先本机真实私网地址；取不到才用内网示例 IP（提示家长去「高级设置」改）。 */
val debugFallbackBaseUrl = "http://${detectLocalLanIpv4() ?: "192.168.1.10"}:8090/v1/"
if (configuredBaseUrl == null) {
    println("[PadGuard] 未注入 BASE_URL，debug 默认地址取本机网卡：$debugFallbackBaseUrl（可用 -PBASE_URL 覆盖）")
}
val effectiveBaseUrl = (configuredBaseUrl ?: debugFallbackBaseUrl).trim().let {
    if (it.endsWith('/')) it else "$it/"
}

/** 与 EndpointSafety.isLanHost 的 IPv4 规则保持一致的构建期简化判定。 */
fun isLanBaseUrl(raw: String): Boolean {
    val host = raw.substringAfter("://").substringBefore('/').substringBefore(':')
        .trimStart('[').trimEnd(']').lowercase()
    if (host == "localhost" || host == "::1") return true
    val parts = host.split('.')
    if (parts.size != 4) return host.startsWith("fc") || host.startsWith("fd") ||
        host.startsWith("fe8") || host.startsWith("fe9") ||
        host.startsWith("fea") || host.startsWith("feb")
    val nums = parts.mapNotNull { it.toIntOrNull() }
    if (nums.size != 4 || nums.any { it !in 0..255 }) return false
    val (a, b) = nums
    return a == 127 || a == 10 || (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) || (a == 169 && b == 254)
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

        // 高德地图 SDK Key（占位）。注入优先级：-PAMAP_KEY 命令行 > gradle.properties amapKey > local.properties amapKey。
        // 默认空字符串 = 不加载真实底图，定位页回退到等距投影占位图。绝不把真实 Key 写进代码仓库。
        val amapKey = (findProperty("AMAP_KEY") as? String)?.takeIf { it.isNotBlank() }
            ?: (findProperty("amapKey") as? String)?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("amapKey")?.takeIf { it.isNotBlank() }
            ?: ""
        manifestPlaceholders["AMAP_KEY"] = amapKey

        // 服务端地址由脚本顶层统一解析（见上方「服务端地址」说明），此处只是把它交给 BuildConfig。
        // 真实部署既可用 -PBASE_URL 固化，也可在登录页「⚙ 高级设置」改成实际地址
        // （改完立即生效，无需重打包）。仓库里不再出现任何真实公网 IP。
        buildConfigField("String", "BASE_URL", "\"$effectiveBaseUrl\"")
        // 显式逃生开关：仅当「公网 IP 但实际走专线/VPN，且服务端确实没有 TLS」时由运维打开。
        // 默认 false；打开后 EndpointSafety 会把链路标记为 EXPLICITLY_INSECURE，须在运维侧留痕。
        buildConfigField("boolean", "ALLOW_CLEARTEXT_PUBLIC", "$allowCleartextPublic")
        // 证书固定（P0-1 对称加固）：默认空串 = TOFU 兜底；运维注入生产证书 SPKI 哈希后切换为严格静态固定。
        buildConfigField("String", "CERT_PINS", "\"${certPins.joinToString(",")}\"")
    }

    signingConfigs {
        create("release") {
            // release 签名必须从 local.properties / gradle.properties 或 CI 环境变量注入，禁止把明文密钥写进仓库。
            // 生成（一次性，妥善保管）：
            //   keytool -genkeypair -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias padguard
            // 在 local.properties（不入库）写入：
            //   RELEASE_STORE_FILE=release-key.jks
            //   RELEASE_STORE_PASSWORD=******
            //   RELEASE_KEY_ALIAS=padguard
            //   RELEASE_KEY_PASSWORD=******
            // localProps 已在脚本顶层加载（与 amapKey 同一来源）。
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
            // 不再单独覆盖 BASE_URL：继承 defaultConfig 的注入值，避免「debug 一套、release 一套」
            // 导致安全守卫只在一端生效（历史缺陷正是两处都写了明文公网地址）。
            // 本地联调用 -PBASE_URL=http://192.168.x.x:8090/v1/ 或在登录页改地址即可。
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
    // core:network / core:database / core:ui 三个空壳模块已删除（2026-11-18 阶段 2 架构收敛）：
    // 它们没有任何源码，却长期以 api/implementation 方式向 app 传递 retrofit/okhttp/room 等依赖，
    // 导致 app 明面上"看不到"网络栈依赖来源（依赖不可见债）。现全部改为 app 直接声明。
    implementation(project(":core:common"))

    // Network（原经空壳 :core:network 的 api 传递，现显式声明）
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

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

    // 微信 OpenSDK（APP 支付调起 + 支付结果回调）。
    // 商户 AppID 由服务端下单响应的 payParams 携带，本工程不硬编码；
    // WXPayEntryActivity 回调类固定位于 <applicationId>.wxapi 包下（微信侧硬性约定）。
    implementation("com.tencent.mm.opensdk:wechat-sdk-android:6.8.40")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // kxml2：org.xmlpull.v1.XmlPullParserFactory 在 JVM 单测里的真实实现
    // （Android 运行时内置，android.jar stub 的 android.util.Xml 会抛 not mocked）。
    // 导入解析器（DeviceImportFileParser）的回归测试依赖它。
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// 高德 Key 注入守卫 + release 签名守卫（仅在实际打 release 包时校验，不影响 debug）。
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
        ?: localProps.getProperty("amapKey")?.takeIf { it.isNotBlank() }
    if (amapKey.isNullOrBlank()) {
        tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
            doFirst {
                throw GradleException(
                    "release 缺少高德地图 Key：请用 -PAMAP_KEY=xxx 注入，或在 gradle.properties / local.properties 写入 amapKey=xxx；" +
                    "并在高德开放平台控制台把「release keystore 的 SHA1」加入该 Key 的签名绑定，否则定位页白屏/无底图（上线事故）。" +
                    "debug 构建允许空 Key（回退示意占位图）。"
                )
            }
        }
    } else {
        // 头号灰屏诱因守卫：高德「Web 服务 Key」与「Android 平台 Key」是两套、不可互换。
        // 把 Web Key 当 Android Key 注入，底图瓦片请求会被鉴权拒绝 → 灰屏且无法自愈。
        // 此处把已知的服务端 Web Key 列为黑名单，从源头拦截这种误配（Web Key 随服务端配置变化时需同步更新此指纹）。
        // 但绝不能把真实 Key 明文写进仓库——同文件 :91 / :109 两处都承诺过「不写死密钥」。
        // 改为只保存该 Key 的 SHA-256 指纹：比对效果相同，仓库里不再含可用凭据。
        val webKeySha256 = "2e84ec84265af313516b007f9a1464cff3f60c0d6da0492ec27b12d634304568"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(amapKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (fingerprint == webKeySha256) {
            throw GradleException(
                "检测到把「Web 服务 Key」当「Android 地图 Key」注入了（-PAMAP_KEY / amapKey）。" +
                "高德地图底图必须使用「Android 平台」类型 Key，用 Web Key 会导致底图鉴权失败、灰屏。" +
                "请到高德开放平台另行申请 Android 平台 Key，并绑定包名 com.padguard.parent 与签名 SHA1（debug/release 都加）。"
            )
        }
        // 格式守卫：高德 Key 为 32 位十六进制字符串，复制残缺/夹带空格会直接白屏。
        if (!amapKey.matches(Regex("^[0-9a-fA-F]{32}$"))) {
            throw GradleException(
                "AMAP_KEY 格式异常（应为 32 位十六进制字符串），请检查是否复制完整/正确：$amapKey"
            )
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

// ---------------------------------------------------------------------------
// release 出厂地址守卫（P0「明文公网 HTTP」的构建期防线）。
//
// 只在真正打 release 包时触发，不影响 debug / 单元测试。
// 与 data/remote/EndpointSafety.kt 的运行期防线互为兜底：
//   * 这里管「出厂包默认连哪里」，杜绝把 debug 示例地址或历史公网 IP 带上线；
//   * EndpointSafety 管「用户后来把地址改成什么」，杜绝运行时改出明文公网链路。
// ---------------------------------------------------------------------------
/** 与孩子端 isPlaceholderHost 同规则：占位域名一旦出厂，家长端必然连不上服务端。 */
fun isPlaceholderHost(raw: String): Boolean {
    val host = raw.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    return host.isEmpty() || host == "localhost" || host == "127.0.0.1" || host == "::1" ||
        host == "10.0.2.2" || host.endsWith("example.com") || host.endsWith("example.org") ||
        host.endsWith("example.net") || host.contains("your-domain") || host.contains("yourdomain") ||
        host.contains("your.domain")
}

/** 守卫本体抽成顶层函数，让「单独校验」与「打包前校验」共用同一份规则，避免两处逻辑漂移。 */
fun validateReleaseEndpoint(configured: Boolean, url: String, allowCleartextPublicFlag: Boolean) {
    if (!configured) {
        throw GradleException(
            "release 未注入服务端地址：请用 -PBASE_URL=https://<已备案域名>/v1/ " +
                "或环境变量 PADGUARD_BASE_URL 注入（也可写入 local.properties，不入库）。" +
                "禁止把地址写死在仓库，也禁止沿用 debug 的内网示例地址出厂。"
        )
    }
    if (isPlaceholderHost(url)) {
        throw GradleException(
            "release 服务端地址 $url 仍是占位值：example.* / localhost / 10.0.2.2 / your.domain " +
                "这类地址在真实网络里必然解析失败，禁止出厂。"
        )
    }
    if (!url.startsWith("https://", ignoreCase = true) &&
        !isLanBaseUrl(url) && !allowCleartextPublicFlag
    ) {
        throw GradleException(
            "release 服务端地址 $url 是明文 HTTP 且目标不是内网：登录令牌与儿童数据会被明文截获。" +
                "请改用 https（Nginx 终止 TLS），或确认目标确为内网 IP。" +
                "如为「公网 IP 但走专线/VPN」的特殊部署，需显式加 -PALLOW_CLEARTEXT_PUBLIC=true 并在运维侧留痕。"
        )
    }
}

/**
 * 可独立运行的守卫校验：不打整包也能在 CI / 本地验证出厂地址是否合规。
 * 用法：`./gradlew :app:verifyReleaseEndpoint -PBASE_URL=https://<已备案域名>/v1/`
 */
tasks.register("verifyReleaseEndpoint") {
    group = "verification"
    description = "校验 release 出厂服务端地址：必须显式注入，且公网必须 HTTPS"
    // 必须每次真跑：任务只读项目属性、不产出文件。不加这行时第二次调用会被判 UP-TO-DATE
    // 直接跳过 —— 于是"换成占位地址再跑一次"也能 BUILD SUCCESSFUL，守卫形同虚设
    // （孩子端同款任务已按此修正，两端保持一致）。
    outputs.upToDateWhen { false }
    doLast {
        validateReleaseEndpoint(!configuredBaseUrl.isNullOrBlank(), effectiveBaseUrl, allowCleartextPublic)
        logger.lifecycle("release 出厂地址校验通过：${effectiveBaseUrl}")
        if (allowCleartextPublic) {
            logger.warn(
                "⚠ ALLOW_CLEARTEXT_PUBLIC 已开启，release 包允许明文公网传输：${effectiveBaseUrl}。" +
                    "请确认这是经过审批的特殊部署，并在上线检查单中记录。"
            )
        }
    }
}

afterEvaluate {
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
        doFirst {
            validateReleaseEndpoint(!configuredBaseUrl.isNullOrBlank(), effectiveBaseUrl, allowCleartextPublic)
            if (allowCleartextPublic) {
                logger.warn(
                    "⚠ ALLOW_CLEARTEXT_PUBLIC 已开启，release 包允许明文公网传输：${effectiveBaseUrl}。" +
                        "请确认这是经过审批的特殊部署，并在上线检查单中记录。"
                )
            }
        }
    }
}
