import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 根 gradle.properties 的属性会自动注入子项目
val enableSmb: Boolean = (findProperty("ENABLE_SMB") as? String ?: "true").toBoolean()

// API 凭证从 local.properties 读取（已 gitignore），不在源码里出现明文
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    FileInputStream(localPropsFile).use { localProps.load(it) }
}
val assrtToken: String = localProps.getProperty("ASSRT_TOKEN", "")
val opensubKey: String = localProps.getProperty("OPENSUB_KEY", "")
val smbHost: String = localProps.getProperty("SMB_HOST", "")
val smbShare: String = localProps.getProperty("SMB_SHARE", "")
val smbUser: String = localProps.getProperty("SMB_USER", "")
val smbPass: String = localProps.getProperty("SMB_PASS", "")

android {
    namespace = "com.tvplayer.universal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvplayer.universal"
        minSdk = 22      // 小米电视 Mivou 3 (Android 5.1)
        targetSdk = 28   // 刻意不高：老设备上避免分区存储/前台服务限制带来的兼容分支，减小代码体积
        versionCode = 1
        versionName = "0.1.0"

        // 体积优化 1：只打一种 ABI。Cortex-A17 只支持 armeabi-v7a。
        ndk {
            abiFilters += "armeabi-v7a"
        }

        // 体积优化 5：只保留中英语言资源，剔除 appcompat/leanback 带入的几十种 values-xx
        resourceConfigurations += listOf("zh", "en")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // 调试包不混淆，方便真机定位问题
        }
        release {
            // 体积优化 2：R8 全量混淆 + 资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 体积优化 3：native 库在 APK 内保持压缩（安装到 8GB eMMC 时再解压）
            packaging {
                jniLibs.useLegacyPackaging = true
            }
        }
    }

    // 体积优化 4：剔除依赖自带的冗余元数据与语言资源
    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.version", "META-INF/*.kotlin_module",
                "kotlin/**", "DebugProbesKt.bin",
                "META-INF/{AL2.0,LGPL2.1}",
                "kotlinx/coroutines/debug/**"
            )
        }
    }

    lint {
        // 侧载安装，不上 Google Play；targetSdk 28 是兼容性刻意选择
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        if (enableSmb) isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // SMB 依赖 smbj 需要 java.time 脱糖；关闭 ENABLE_SMB 时一并去掉 ~2MB 的 bouncycastle
    if (enableSmb) {
        defaultConfig {
            buildConfigField("boolean", "ENABLE_SMB", "true")
        }
    } else {
        defaultConfig {
            buildConfigField("boolean", "ENABLE_SMB", "false")
        }
    }

    // 字幕站 API 凭证：从 local.properties 注入，源码不含明文
    defaultConfig {
        buildConfigField("String", "ASSRT_TOKEN", "\"$assrtToken\"")
        buildConfigField("String", "OPENSUB_KEY", "\"$opensubKey\"")
        buildConfigField("String", "SMB_HOST", "\"$smbHost\"")
        buildConfigField("String", "SMB_SHARE", "\"$smbShare\"")
        buildConfigField("String", "SMB_USER", "\"$smbUser\"")
        buildConfigField("String", "SMB_PASS", "\"$smbPass\"")
    }
}

dependencies {
    // 仅启用脱糖所需的最小集（smbj 的 java.time）
    if (enableSmb) {
        coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
        implementation("com.hierynomus:smbj:0.11.5") {
            exclude(group = "org.slf4j", module = "slf4j-simple")
        }
        // bcprov 由 smbj 传递依赖提供（jdk15on:1.70），不再显式声明避免重复类
        implementation("org.slf4j:slf4j-android:1.7.36")
    }

    // TV 遥控器 UI 框架（焦点管理是自制成本最高的部分，用 leanback 划算）
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.core:core-ktx:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    // 字幕接口不用 Gson 门面（无反射填充），但用其 JSON 树 API（JsonObject/JsonParser）
    implementation("com.google.code.gson:gson:2.8.9")

    // 播放器内核：Java 层用 maven 的，native 层用 scripts/build-ijk.sh 自编译的完整版
    // （app/src/main/jniLibs/armeabi-v7a/ 里那三个 .so）。maven 的 ijkplayer-armv7a 是
    // --disable-decoders 白名单编译，没有 AC3/DTS/MPEG-2，播 DTS 片源必然没声音，
    // 所以这里不再依赖它；两者版本必须一致（都是 0.8.8，否则 JNI 方法表对不上）。
    implementation("tv.danmaku.ijk.media:ijkplayer-java:0.8.8")

    // 字幕匹配/编码嗅探是纯字符串逻辑，真机上验证一轮要装一次包，放本地跑
    testImplementation("junit:junit:4.13.2")
}
