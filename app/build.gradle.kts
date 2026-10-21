import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 内置 Kotlin 支持，无需（也不再允许）org.jetbrains.kotlin.android 插件。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 发布签名：根目录 keystore.properties 存在时启用（该文件含口令、不入库）；
// 缺失时 release 退化为未签名包，避免 CI / 他机构建失败。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (!keystorePropsFile.exists()) null else
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.xiefeihong.hypervolume"
    // Miuix 0.9.x / Compose 1.12 / libxposed 102 均要求 compileSdk 37（本机已装 android-37.1）。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xiefeihong.hypervolume"
        minSdk = 29
        targetSdk = 35
        // 版本号单一来源：gradle.properties 的 appVersionName / appVersionCode。
        versionName = providers.gradleProperty("appVersionName").get()
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 共享数据类在 catch 中用 android.util.Log 记录日志；JVM 单测未 mock 框架，
    // returnDefaultValues 让 Log 退化为 no-op，避免畸形输入用例抛 "not mocked"。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        keystoreProps?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            keystoreProps?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // 产物命名由下方 copyApk 任务完成（AGP 9 已移除 applicationVariants 输出改名 API）。
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- YukiHook：libxposed 基座 + 核心 + KSP 编译器 + KavaRef 扩展 ----
    // 框架入口类与 META-INF/xposed/* 配置由 yukihook-compiler 在编译期生成，
    // 源码目录中不得手动声明这些文件。
    compileOnly(libs.libxposed.api)
    implementation(platform(libs.yukihook.bom))
    implementation(libs.yukihook.core)
    implementation(libs.yukihook.runtime.libxposed)
    implementation(libs.yukihook.extension.kavaref)
    ksp(libs.yukihook.compiler)

    // ---- KavaRef 反射（YukiHook 不自动传递，需显式引入）----
    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.android)
    implementation(libs.kavaref.extension)

    // ---- Miuix + Compose ----
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
}

/**
 * 产物归档命名：HyperVolume-v<versionName>[-<variant>].apk 复制到根目录 build/apk/，
 * 与旧项目交付习惯一致（AGP 9 下不再改 Gradle 内部输出名，只做事后复制）。
 *
 * 输出必须落在独立子目录：若直接 into(rootProject.projectDir)，Gradle 会把根目录推断为
 * 本任务输出，而根目录同时是 app/src 下全部编译输入的祖先目录，compileReleaseKotlin
 * 会因「uses this output of task ... without declaring an implicit dependency」直接失败。
 */
val appVersionName = providers.gradleProperty("appVersionName").get()
tasks.register<Copy>("copyApk") {
    group = "build"
    description = "复制构建产物到 build/apk 并按版本号命名"
    // 不声明依赖时 Gradle 可能先跑 copyApk（拿到旧/不完整的产物），这里显式串联两个变体。
    dependsOn("assembleDebug", "assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk")) {
        include("**/*.apk")
    }
    into(rootProject.layout.buildDirectory.dir("apk"))
    // outputs/apk/<variant>/*.apk 带有子目录层级，打平后统一按 HyperVolume-v<version>-<variant>.apk 命名。
    eachFile {
        relativePath = RelativePath(
            true,
            "HyperVolume-v$appVersionName-${name.substringBeforeLast(".apk").removePrefix("app-")}.apk",
        )
    }
    includeEmptyDirs = false
}
