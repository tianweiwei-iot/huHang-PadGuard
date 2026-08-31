plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.padguard.core.engine"
    compileSdk = project.findProperty("COMPILE_SDK").toString().toInt()

    defaultConfig {
        minSdk = project.findProperty("MIN_SDK").toString().toInt()
        consumerProguardFiles("consumer-rules.pro")
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
    // 用 api 而非 implementation：engine 的公开 API 直接暴露了这两个模块的类型
    // （PolicyEngine.applyAll(PolicyPackage)、execute(Command): CommandAck、TimeProvider 等）。
    // 若用 implementation，app 层调用 policyEngine.execute(...) 时会因为看不到 Command 类型而编译失败，
    // 报错还会指向 app 自己的代码，排查方向完全跑偏。
    api(project(":core:common"))
    api(project(":core:data"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    // 纯逻辑（ScheduleEvaluator）的 JVM 单元测试，无需 Robolectric
    testImplementation(libs.junit)
}
