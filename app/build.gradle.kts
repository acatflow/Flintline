plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tvvpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvvpn.app"
        // 支持到 Android 6.0（mihomo 核心实测可到 API 21；前台服务通知渠道在 API 26 有版本判断）。
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-oss"
    }

    buildTypes {
        release {
            // 开源模板：默认关闭 R8（无 proguard 规则、无字符串混淆），保证 clone 即可构建。
            // 生产分发请自行配置签名与混淆。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 老盒子多为 Android 7（API 24）。desugaring 把 java.time / java.util.Base64 等
        // API 26+ 类在编译期重写到 backport 实现，覆盖自身代码 + 依赖库。
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose for TV —— D-pad 焦点语义用官方组件
    val composeTvVersion = "1.0.0-beta01"
    implementation("androidx.tv:tv-foundation:$composeTvVersion")
    implementation("androidx.tv:tv-material:$composeTvVersion")

    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Clash 核心模型用 kotlinx-serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
