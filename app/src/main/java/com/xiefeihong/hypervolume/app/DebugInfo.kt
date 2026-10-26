package com.xiefeihong.hypervolume.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Shell

/**
 * 调试信息（故障排除页 / 关于页设备信息）。
 *
 * 采集与排版分离：[collect] 只负责把系统属性、root 探测结果、配置原文取回来，
 * [debugInfoLines] / [deviceInfoLines] 是纯函数（文案走 [AppText]），便于单测直接断言整行文本。
 */
data class DebugSnapshot(
    val moduleVersion: String,
    val versionCode: Long,
    /** `debug` / `release`（由 FLAG_DEBUGGABLE 判定，不依赖 BuildConfig）。 */
    val buildType: String,
    val packageName: String,
    val manufacturer: String,
    val model: String,
    val romDisplay: String,
    val androidRelease: String,
    val sdkInt: Int,
    val incremental: String,
    val rootGranted: Boolean,
    val lsposedInstalled: Boolean,
    val framework: String,
    /** 系统原本的媒体档位数（未受模块改写时的值），与主页档位行的默认值同源。 */
    val systemDefaultSteps: Int,
    val stepsSource: StepsSource,
    /** `Settings.Global` 中本模块键的原文（空串表示无）。 */
    val effectiveConfig: String,
    /** 应用内暂存配置 JSON（空串表示无）。 */
    val stagedConfig: String,
    val logPaths: List<String>,
)

/** 调试信息的全部行（含两段配置原文，便于反馈问题时直接粘贴）。 */
internal fun debugInfoLines(snapshot: DebugSnapshot): List<String> = buildList {
    addAll(deviceInfoLines(snapshot))
    add(AppText.str(R.string.debug_root, availability(snapshot.rootGranted)))
    add(AppText.str(R.string.debug_lsposed, installation(snapshot.lsposedInstalled)))
    add(AppText.str(R.string.debug_default_steps, snapshot.systemDefaultSteps.toString()))
    add(AppText.str(R.string.debug_steps_source, stepsSourceLabel(snapshot.stepsSource)))
    add(AppText.str(R.string.debug_log_paths, snapshot.logPaths.joinToString(AppText.str(R.string.list_separator))))
    add(AppText.str(R.string.debug_config_title))
    add(snapshot.effectiveConfig.ifBlank { AppText.str(R.string.debug_no_config) })
    add(AppText.str(R.string.debug_local_config_title))
    add(snapshot.stagedConfig.ifBlank { AppText.str(R.string.debug_no_config) })
}

/** 关于页「设备信息」的行集（调试信息的硬件/系统子集，不含配置原文）。 */
internal fun deviceInfoLines(snapshot: DebugSnapshot): List<String> = listOf(
    AppText.str(R.string.debug_module_version, snapshot.moduleVersion, snapshot.versionCode),
    AppText.str(R.string.debug_build_type, snapshot.buildType),
    AppText.str(R.string.debug_package, snapshot.packageName),
    AppText.str(R.string.debug_device, snapshot.manufacturer, snapshot.model),
    AppText.str(R.string.debug_rom, snapshot.romDisplay),
    AppText.str(R.string.debug_android, snapshot.androidRelease, snapshot.sdkInt),
    AppText.str(R.string.debug_incremental, snapshot.incremental),
    AppText.str(R.string.debug_framework, snapshot.framework),
)

/** Root 等能力类可用性措辞。 */
private fun availability(available: Boolean): String =
    AppText.str(if (available) R.string.debug_available else R.string.debug_unavailable)

/** 安装类可用性措辞（LSPosed 存在与否）。 */
private fun installation(installed: Boolean): String =
    AppText.str(if (installed) R.string.debug_installed else R.string.debug_not_installed)

/**
 * 采集调试信息快照。
 *
 * 内部走 root shell 读系统配置与 LSPosed 目录（**阻塞**），须与 [StatusProbe.probe] 一样放在 IO 协程里。
 */
class DebugInfoCollector(
    context: Context,
    private val store: ConfigStore,
    private val probe: StatusProbe,
) {

    private val appContext: Context = context.applicationContext

    fun collect(rootAvailable: Boolean): DebugSnapshot {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val effective = if (rootAvailable) {
            // `settings get global <key>` 无值时回显字面 null，归一为空串以触发「尚无配置记录」
            Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output.trim().takeUnless { it.isEmpty() || it == "null" }.orEmpty()
        } else {
            ""
        }
        return DebugSnapshot(
            moduleVersion = packageInfo.versionName ?: "",
            versionCode = packageInfo.longVersionCode,
            buildType = if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                "debug"
            } else {
                "release"
            },
            packageName = appContext.packageName,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            romDisplay = Build.DISPLAY.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            incremental = Build.ID.orEmpty(),
            rootGranted = rootAvailable,
            lsposedInstalled = Shell.isLsposedPresent(),
            framework = "libxposed API $XPOSED_API_VERSION",
            systemDefaultSteps = probe.systemDefaultSteps(),
            stepsSource = probe.stepsSource(),
            effectiveConfig = effective,
            stagedConfig = store.rawJson(),
            logPaths = listOf(Shell.SYS_LOG_FILE, Shell.BT_LOG_FILE, "/data/adb/lspd/log/"),
        )
    }

    companion object {

        /** 模块声明的 libxposed API 版本（与 manifest meta 一致，界面只做展示）。 */
        const val XPOSED_API_VERSION = 102
    }
}
