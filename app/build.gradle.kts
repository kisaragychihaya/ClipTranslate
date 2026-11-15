import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.cliptranslate"
    // miuix 0.8.8 的 aar 声明 minCompileSdk=36，与 CI（setup-android 只装 platforms;android-36）
    // 以及 targetSdk = 36 对齐；AGP 的 checkAarMetadata 要求 compileSdk ≥ 36。
    compileSdk = 36

    // 与 .github/workflows/build-apk.yml 里 setup-android 安装的 NDK 版本保持一致。
    // 27.3.13750724 是 ubuntu-24.04 runner 镜像自带的默认 NDK（ANDROID_NDK_ROOT 指向它），
    // 写死它 CI 就不用额外下载 NDK。
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "dev.cliptranslate"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"

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
        // 固定 debug 签名（仓库内公开的标准调试 keystore，无安全用途）：
        // CI 每台 runner 随机生成 debug key，不固定的话每次 debug 包都要卸载重装。
        // 用 named("debug") 而不是 debug{}：buildTypes 的同名访问器会遮蔽签名配置的访问器。
        named("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        // 关于页的版本号（BuildConfig.VERSION_NAME）和调试日志的构建类型默认值
        // （BuildConfig.DEBUG）都来自生成类；AGP 8 起默认不生成，这里显式打开。
        buildConfig = true
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

    // OpenCL 的链接桩（src/main/cpp/CMakeLists.txt 里 configure 期生成）只是给链接器
    // 用的，运行时必须由设备 /vendor/lib64/libOpenCL.so 提供（它在
    // /vendor/etc/public.libraries.txt 里登记过）。AGP 会把 CMake 输出目录里的 *.so
    // 自动打包，把桩打进 APK 会让 OpenCL 后端拿到一堆空实现 —— 这里显式排除。
    // 桩在磁盘上叫 libOpenCL.linkstub（不以 .so 结尾，CMake 侧已经避开一次），
    // 两个名字都排除掉，算双保险。
    packaging {
        jniLibs {
            excludes += "**/libOpenCL.so"
            excludes += "**/libOpenCL.linkstub"
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
    implementation(libs.miuix)
    implementation(libs.miuix.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.navigationevent.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
