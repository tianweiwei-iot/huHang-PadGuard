plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.padguard.core.common"
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
    // DI（提供 javax.inject 注解 + Hilt 代码生成，TimeProvider 使用 @Inject/@Singleton）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
