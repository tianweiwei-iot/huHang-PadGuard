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

        // 服务端地址构建期注入。
        // 本项目是局域网/私有化部署（服务端跑在家长电脑上），不存在公网接入点。
        // 曾在此填写虚构公网域名 api.padguard.com：DNS 根本解析不了，家长端所有接口
        // 一律"连不上"，且用户完全无法判断是地址问题还是网络问题。改为与 debug 一致的内网地址，
        // 真实部署在登录页「⚙ 高级设置」改成家长电脑的实际 IP 即可（改完立即生效，无需重打包）。
        buildConfigField("String", "BASE_URL", "\"http://192.168.1.12:8090/v1/\"")
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
            // Debug API endpoint (can be overridden with local server)
            buildConfigField("String", "BASE_URL", "\"http://192.168.1.12:8090/v1/\"")
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

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
