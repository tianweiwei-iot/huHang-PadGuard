plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.padguard.core.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "BASE_URL", "\"https://api.padguard.com/v1/\"")
    }

    buildTypes {
        release {
            buildConfigField("String", "BASE_URL", "\"https://api.padguard.com/v1/\"")
        }
        debug {
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080/v1/\"")
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
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.retrofit2:converter-moshi:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")
    api("com.squareup.moshi:moshi-kotlin:1.15.1")
}
