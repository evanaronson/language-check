import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.evanaronson.linguize"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.evanaronson.linguize"
        // Galaxy S21 runs Android 15; nothing older needs supporting.
        minSdk = 30
        targetSdk = 36
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toInt() ?: 1
        versionName = "0.1.$versionCode"
    }

    signingConfigs {
        // A fixed key so each new build installs over the previous one.
        create("sideload") {
            storeFile = file("signing/sideload.jks")
            storePassword = "language-check"
            keyAlias = "sideload"
            keyPassword = "language-check"
        }
    }

    buildTypes {
        release {
            // Shrunk release builds start noticeably faster than debug builds,
            // which matters when every check may cold-start the app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
