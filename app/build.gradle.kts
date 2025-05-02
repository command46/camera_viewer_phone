plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // WorkManager
    implementation(libs.work.runtime) // Java 版本

    // Networking (Retrofit + OkHttp + Gson)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp) // 或者使用 OkHttp BOM
    implementation(libs.logging.interceptor) // 可选，用于调试

    // Lifecycle components (可选，但 WorkManager 可能需要)
    implementation(libs.lifecycle.process)

    // Google Guava (ListenableFuture 相关，WorkManager 可能依赖)
    implementation(libs.guava) // 使用适合 Android 的版本

    // Material Components (如果使用 Material Design 控件，如 Button)
    implementation(libs.material.v1100)

    // AppCompat (标准 Activity 库)
    implementation(libs.appcompat.v161)
    implementation(libs.constraintlayout.v214) // 如果使用 ConstraintLayout

    implementation(libs.okhttp) // 请检查并使用最新的稳定版本
    implementation(libs.gson)
    implementation(libs.cardview)
    implementation(libs.mpandroidchart)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.camera.core)
    implementation(libs.camera.lifecycle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
