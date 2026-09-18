plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.localsmsrelay"
    // Compose 1.12+/material3 1.5.0-alpha/Miuix 0.9.4 都要求编译到 API 37。
    // targetSdk 维持 36 不变，只提升编译用的 SDK。
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        // 换 Compose 界面后改了包名，与 v1.3.0 的 com.localsmsrelay 完全独立，
        // 两者可并存、数据互不影响。
        // namespace 保持 com.localsmsrelay 不变，因此代码一行未动。
        applicationId = "com.localsmsrelay.compose"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有配置正式签名；用调试密钥签名，否则 assembleRelease 产出的是
            // 未签名 APK，根本装不上。这与上游发 Debug APK 的取舍一致，
            // 只是这里换来了正确的 release 包名与版本号。
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 测试版用独立包名，可与已安装的正式版并存。
            // 两者的 Room 数据库、通知、SharedPreferences 各自隔离，互不影响。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-test"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // Compose 编译器由 org.jetbrains.kotlin.plugin.compose 提供
        compose = true
    }
}

kotlin {
    compilerOptions {
        // 必须与上面 compileOptions 的 Java 17 对齐，
        // 否则 Kotlin 会跟随 JDK 用 21，报 JVM target 不一致。
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // WebSocket 客户端：Android 没有内置的 WebSocket 实现，java.net.http 在 Android 上不可用。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")

    // ---- Compose ----
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 新版 material3 不再传递依赖图标库，需要显式引入。
    // 这里只引 material-icons-core（精选小集合），刻意不引 material-icons-extended：
    // extended 会把上万个图标编进 dex，实测让主 dex 涨到 44 MB、APK 涨到 27.6 MB。
    // 核心集里没有的图标在 ui/SmsIcons.kt 里按需自定义。
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
