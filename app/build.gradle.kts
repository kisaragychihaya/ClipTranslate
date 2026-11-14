import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.cliptranslate"
    compileSdk = 36

    // 与 .github/workflows/build-apk.yml 里 setup-android 安装的 NDK 版本保持一致。
    // 27.3.13750724 是 ubuntu-24.04 runner 镜像自带的默认 NDK（ANDROID_NDK_ROOT 指向它），
    // 写死它 CI 就不用额外下载 NDK。
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "dev.cliptranslate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 本地推理只做 arm64（llama.cpp CPU 版）；x86 模拟器跑不了这个 .so。
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-O3"
            }
        }
    }

    signingConfigs {
        // CI 注入固定 Release keystore（GitHub Secrets）；本地无 secrets 时不创建，回退 debug 签名。
        val keystoreBase64 = providers.environmentVariable("RELEASE_KEYSTORE_BASE64").orNull
        if (keystoreBase64 != null) {
            create("release") {
                val keystoreFile = rootProject.layout.buildDirectory.file("ci-release.keystore").get().asFile
                keystoreFile.parentFile.mkdirs()
                if (!keystoreFile.exists()) {
                    keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
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

    // llama.cpp 用 CMake FetchContent 拉源码，构建脚本在 src/main/cpp。
    // version 必须和 CI 里 setup-android 安装的 `cmake;3.22.1` 一致，
    // 否则 AGP 会报 “CMake 3.22.1 not found in SDK”。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
