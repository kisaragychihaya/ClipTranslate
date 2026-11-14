import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.cliptranslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.cliptranslate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // CI 注入固定 Release keystore（GitHub Secrets）；本地无 secrets 时不创建，回退 debug 签名。
        val keystoreBase64 = providers.environmentVariable("RELEASE_KEYSTORE_BASE64").orNull
        if (keystoreBase64 != null) {
            create("release") {
                val keystoreFile = rootProject.layout.buildDirectory.file("ci-release.keystore").get().asFile
                keystoreFile.parentFile.mkdirs()
                if (!keystoreFile.exists()) {
                    keystoreFile.writeBytes(java.util.Base64.getDecoder().decode(keystoreBase64))
                }
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
