plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.padguard.core.data"
    compileSdk = project.findProperty("COMPILE_SDK").toString().toInt()

    defaultConfig {
        minSdk = project.findProperty("MIN_SDK").toString().toInt()
        consumerProguardFiles("consumer-rules.pro")

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
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

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 存储
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 纯数据模型（PolicyModels）的 JVM 单元测试，无需 Robolectric
    testImplementation(libs.junit)
}
