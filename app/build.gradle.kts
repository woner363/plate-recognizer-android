// app 模块构建
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // parcelize 与 kotlin-android 同源，复用 Kotlin Gradle Plugin 的 classpath
    id("kotlin-parcelize")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.platerecognizer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.platerecognizer"
        minSdk = 24            // Android 7.0+，覆盖 ~98%
        targetSdk = 34
        // 版本号与 GitHub Release tag 对齐：versionCode 单调递增，
        // versionName 形如 "0.2.4-debug"。改版本时记得同步发对应 tag。
        versionCode = 24
        versionName = "0.2.4-debug"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    // Room schema JSON 入 git，供未来 Migration / MigrationTestHelper 使用。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Room 通过 KSP 导出 schema 到此目录。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit Text Recognition v2 - Chinese
    implementation(libs.mlkit.text.recognition.chinese)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Accompanist permissions (Compose)
    implementation(libs.accompanist.permissions)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
