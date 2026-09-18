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
        applicationId = "com.localsmsrelay"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
