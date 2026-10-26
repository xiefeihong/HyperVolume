package com.xiefeihong.hypervolume.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.core.PushMarks
import com.xiefeihong.hypervolume.core.RootProbe
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
    /**
     * 生效配置原文——全应用唯一的一份配置，`Settings.Global` 中本模块键的值（空串表示无）。
     * 经 `ContentResolver` 直读，**不依赖 root**。
     */
    val effectiveConfig: String,
    val logPaths: List<String>,
    /**
     * 系统侧落点状态（已本地化的未命中落点名列表）：空=全部命中，非空=缺失（如「配置镜像」）；
     * `null` = root 不可用、无法探测。
     */
    val landingMissing: List<String>?,
)

/** 调试信息的全部行（含生效配置原文，便于反馈问题时直接粘贴）。 */
internal fun debugInfoLines(snapshot: DebugSnapshot): List<String> = buildList {
    addAll(deviceInfoLines(snapshot))
    add(AppText.str(R.string.debug_root, availability(snapshot.rootGranted)))
    add(AppText.str(R.string.debug_lsposed, installation(snapshot.lsposedInstalled)))
    add(AppText.str(R.string.debug_default_steps, snapshot.systemDefaultSteps.toString()))
    add(AppText.str(R.string.debug_steps_source, stepsSourceLabel(snapshot.stepsSource)))
    add(AppText.str(R.string.debug_log_paths, snapshot.logPaths.joinToString(AppText.str(R.string.list_separator))))
    add(
        AppText.str(
            R.string.debug_landing,
            when {
                snapshot.landingMissing == null -> AppText.str(R.string.landing_no_root)
                snapshot.landingMissing.isEmpty() -> AppText.str(R.string.debug_landing_ok)
                else -> snapshot.landingMissing.joinToString(AppText.str(R.string.list_separator))
            },
        ),
    )
    add(AppText.str(R.string.debug_config_title))
    add(snapshot.effectiveConfig.ifBlank { AppText.str(R.string.debug_no_config) })
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
 * root 与 LSPosed 事实由调用方一次 [Shell.rootAndLsposed] 探得后传入（与 [StatusProbe.probe] 共用同一次 su，
 * 不在此重复探测）；系统侧落点（[Shell.probeLanding]）仅在 root 可用时探测，否则置 `null`。
 * 生效配置经 [ConfigStore.readEffectiveJson] 直读（无需 root），因此无 root 时也能交出配置原文。
 * 本方法含阻塞 shell，须放在 IO 协程里。
 */
class DebugInfoCollector(
    context: Context,
    private val store: ConfigStore,
    private val probe: StatusProbe,
) {

    private val appContext: Context = context.applicationContext

    fun collect(probeResult: RootProbe): DebugSnapshot {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
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
            rootGranted = probeResult.rootGranted,
            lsposedInstalled = probeResult.lsposedInstalled,
            framework = "libxposed API $XPOSED_API_VERSION",
            systemDefaultSteps = probe.systemDefaultSteps(),
            stepsSource = probe.stepsSource(),
            effectiveConfig = store.readEffectiveJson(),
            logPaths = listOf(Shell.SYS_LOG_FILE, Shell.BT_LOG_FILE, "/data/adb/lspd/log/"),
            landingMissing = probeLandingLabels(probeResult.rootGranted),
        )
    }

    /** root 可用时探测落点并把未命中的标记映射为本地化落点名；不可用返回 `null`（无法探测）。 */
    private fun probeLandingLabels(rootGranted: Boolean): List<String>? {
        if (!rootGranted) {
            return null
        }
        return Shell.probeLanding().map { marker ->
            when (marker) {
                PushMarks.MIRROR -> AppText.str(R.string.landing_mirror)
                else -> AppText.str(R.string.landing_global)
            }
        }
    }

    companion object {

        /** 模块声明的 libxposed API 版本（与 manifest meta 一致，界面只做展示）。 */
        const val XPOSED_API_VERSION = 102
    }
}
