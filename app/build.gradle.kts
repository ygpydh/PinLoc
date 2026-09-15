plugins {
    id("com.android.application")
}

android {
    namespace = "com.pinloc.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pinloc.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 保留 META-INF/xposed/* 模块声明文件（libxposed 识别模块的依据）
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    // libxposed API 102（LSPosed 现代 Xposed API，发布于 Maven Central）
    compileOnly("io.github.libxposed:api:102.0.0")

    // osmdroid 原生地图控件
    // 排除 com.google.android:android（已废弃，与 compileSdk 冲突）
    implementation("org.osmdroid:osmdroid-android:6.1.18") {
        exclude(group = "com.google.android", module = "android")
    }
}
