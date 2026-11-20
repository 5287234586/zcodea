plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "zsh.android.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.zcodea.client"
        minSdk = 26
        // targetSdk 28：targetSdk ≥29 时 Android W^X 策略禁止 exec 应用数据目录里的
        // proot 二进制（Termux 停在 28 同因）。侧载分发，不受商店 targetSdk 政策约束。
        targetSdk = 28
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9") // commons-compress 解 .xz 需要（Termux deb 是 data.tar.xz）
    implementation("commons-io:commons-io:2.16.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // 单测里用真实的 org.json（Android SDK 里的在 JVM 单测中只是 stub）
    testImplementation("org.json:json:20240303")
}
