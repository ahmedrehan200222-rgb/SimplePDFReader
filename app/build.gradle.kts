plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahmedrehan.simplepdfreader"
    compileSdk = 35

    // مفتاح توقيع ثابت: عشان كل نسخة جديدة تتركّب فوق القديمة من غير ما تفقد التمييزات
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.ahmedrehan.simplepdfreader"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
