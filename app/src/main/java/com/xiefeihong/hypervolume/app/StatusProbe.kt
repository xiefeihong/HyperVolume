package com.xiefeihong.hypervolume.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.core.NativeVolumeCurve
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Shell
import com.xiefeihong.hypervolume.core.VolumeConfig

/**
 * 运行态探测（移植自旧 `StatusProbe`）。
 *
 * 与旧实现的差别只在**不碰任何界面控件**：探测结果一次性打包成 [StatusSnapshot]
 * 交给 Compose 侧渲染；所有 root / 系统查询都在调用线程执行（UI 侧放到 IO 协程），
 * 不再有「后台线程 + Handler 回主线程」的手工调度。
 *
 * 判定链与旧实现逐字一致：系统原生档位数来源优先级为「已固化的服务实测值 >
 * ROM 引擎表 XML > 内置默认」，模块生效状态由「当前系统档位 vs 目标档位」与
 * 「当前档位 vs 系统默认」两组比对得出。
 *
 * 文案全部经 [AppText] 按资源取值，因此随语言切换（单测注入 XML 解析器即可继续
 * 用中文断言，见 `TestText`）。
 */
class StatusProbe(context: Context, private val store: ConfigStore) {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // ==================== 系统原生档位数 ====================

    /**
     * 系统原生媒体档位数：已捕获的服务实测值 &gt; ROM 引擎表 XML（`<indexMax>`）&gt;
     * 内置默认 [Prefs.MEDIA_STEPS_DEFAULT]。
     */
    fun systemDefaultStepsRaw(): Int =
        store.capturedSystemDefaultSteps()
            ?: NativeVolumeCurve.nativeMusicIndexMax().takeIf { it > 0 }
            ?: Prefs.MEDIA_STEPS_DEFAULT

    /** 用作滑块默认/恢复目标的系统原生档位数（限制在合法区间）。 */
    fun systemDefaultSteps(): Int = Prefs.clampMediaSteps(systemDefaultStepsRaw())

    /** 「默认档位数」取值来源，界面据此标注来源文案（与 [systemDefaultStepsRaw] 判定链一致）。 */
    fun stepsSource(): StepsSource = when {
        store.capturedSystemDefaultSteps() != null -> StepsSource.SERVICE
        NativeVolumeCurve.nativeMusicIndexMax() > 0 -> StepsSource.ENGINE_XML
        else -> StepsSource.BUILTIN
    }

    /** 读取媒体流当前生效的档位上限（读取失败返回 0）。 */
    fun readStreamMaxSafe(streamIndex: Int): Int = try {
        audioManager?.getStreamMaxVolume(streamIndex) ?: 0
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "readStreamMaxVolume failed: stream=$streamIndex", t)
        0
    }

    // ==================== 输出设备识别 ====================

    /** 按当前可用输出设备判定应聚焦的设备标签：蓝牙 &gt; 有线 &gt; 外放。 */
    fun detectCurrentDevice(): OutputDevice {
        val manager = audioManager ?: return OutputDevice.SPEAKER
        return try {
            var wired = false
            for (device in manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val type = device.type
                if (bluetoothTypeLabelRes(type) != 0) {
                    return OutputDevice.BT          // 蓝牙优先
                }
                if (type == TYPE_WIRED_HEADSET || type == TYPE_WIRED_HEADPHONES || type == TYPE_USB_HEADSET) {
                    wired = true
                }
            }
            if (wired) OutputDevice.WIRED else OutputDevice.SPEAKER
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "detectCurrentDevice failed, fallback to SPEAKER", t)
            OutputDevice.SPEAKER
        }
    }

    /** 汇总当前已连接的蓝牙音频输出设备（`名称（类型）`）；未连接返回空列表。 */
    fun bluetoothDevices(): List<String> {
        val manager = audioManager ?: return emptyList()
        return try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .mapNotNull { device ->
                    val typeRes = bluetoothTypeLabelRes(device.type)
                    if (typeRes == 0) {
                        return@mapNotNull null
                    }
                    AppText.str(R.string.status_bt_item, deviceLabel(device), AppText.str(typeRes))
                }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "buildBluetoothSummary failed", t)
            emptyList()
        }
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val name = device.productName
        return if (name.isNullOrEmpty()) AppText.str(R.string.status_bt_generic) else name.toString()
    }

    /** 蓝牙相关输出设备的类型名资源；非蓝牙设备返回 0（用字面值兼容低版本 API）。 */
    private fun bluetoothTypeLabelRes(type: Int): Int = when (type) {
        7 -> R.string.bt_type_sco            // AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        8 -> R.string.bt_type_a2dp           // AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        23 -> R.string.bt_type_hearing_aid   // AudioDeviceInfo.TYPE_HEARING_AID
        26 -> R.string.bt_type_ble_headset   // TYPE_BLE_HEADSET（API 31+）
        27 -> R.string.bt_type_ble_speaker   // TYPE_BLE_SPEAKER（API 31+）
        30 -> R.string.bt_type_ble_broadcast // TYPE_BLE_BROADCAST（API 33+）
        else -> 0
    }

    // ==================== 状态汇总 ====================

    /**
     * 一次完整探测：root/LSPosed 可用性、系统当前档位、蓝牙连接情况，并在 App 数据被
     * 清除后首次打开时以系统配置键为准恢复界面（[StatusSnapshot.adopted]）。
     *
     * 必须是阻塞调用（内部走 root shell），由 UI 侧的 IO 协程调用。
     */
    fun probe(input: StatusInput): StatusSnapshot {
        val root = Shell.isRootAvailable()
        val lsposed = Shell.isLsposedPresent()

        // 应用数据被清除后首次打开：以系统配置键为准恢复界面，避免误覆盖已生效的配置。
        var adopted: VolumeConfig? = null
        val globalConfig = if (root) VolumeConfig.fromXml(Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output) else null
        if (root && !store.hasStoredConfig() && globalConfig != null) {
            store.persistBlocking(globalConfig)
            adopted = globalConfig
        }

        val actualMedia = readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX)
        // 首次捕获系统原生媒体档位数：仅当本地与系统侧都未启用覆盖时，读到的才是未被
        // 模块改写过的真实原生值；否则（已推送生效配置）不固化，避免把覆盖值当默认。
        val stored = store.loadModel()
        val enabledNow = stored?.enabled == true
        val systemActive = globalConfig?.remapActive == true
        if (!enabledNow && !systemActive && store.capturedSystemDefaultSteps() == null &&
            actualMedia in 1..100
        ) {
            store.captureSystemDefaultSteps(actualMedia)
        }

        val systemDefault = systemDefaultSteps()
        val targetMedia = if (input.enabled) input.mediaSteps else actualMedia
        val verdict = when {
            input.enabled ->
                if (actualMedia == targetMedia) ModuleVerdict.ACTIVE else ModuleVerdict.PENDING_RESTART

            actualMedia == systemDefault || actualMedia < Prefs.MEDIA_STEPS_MIN ||
                actualMedia > Prefs.MEDIA_STEPS_MAX -> ModuleVerdict.INACTIVE_DEFAULT

            else -> ModuleVerdict.INACTIVE_PENDING_RESTORE
        }
        val pendingText = if (actualMedia == targetMedia) {
            AppText.str(R.string.pending_no)
        } else {
            AppText.str(R.string.pending_yes, actualMedia, targetMedia)
        }
        return StatusSnapshot(
            rootGranted = root,
            lsposedInstalled = lsposed,
            actualMediaSteps = actualMedia,
            systemDefaultSteps = systemDefault,
            stepsSource = stepsSource(),
            keySteps = input.keySteps,
            bluetoothMode = input.bluetoothMode,
            bluetoothDevices = bluetoothDevices(),
            verdict = verdict,
            pendingText = pendingText,
            adopted = adopted,
        )
    }

    // ==================== 模块日志 ====================

    /** 读取模块日志：模块自写日志文件优先（系统框架 + 蓝牙两份，root 直读），再合并 LSPosed 与 logcat。 */
    fun readModuleLogs(): String = Shell.readModuleLogs()

    /** 清理模块自写日志文件。 */
    fun clearModuleLogs() {
        Shell.clearModuleLogs()
    }

    companion object {

        /** App 侧日志 tag：catch 兜底记录，保证异常可见。 */
        const val LOG_TAG = "HyperVolumeUI"

        /** 模块日志标题（界面与剪贴板标签共用）。 */
        fun logTitle(): String = AppText.str(R.string.log_title)

        /** 日志为空时的排查提示（与旧 `log_empty` 一致）。 */
        fun logEmpty(): String = AppText.str(R.string.log_empty)

        // AudioDeviceInfo 类型字面值（避免引用高版本常量，与旧实现一致）。
        private const val TYPE_WIRED_HEADSET = 3
        private const val TYPE_WIRED_HEADPHONES = 4
        private const val TYPE_USB_HEADSET = 13
    }
}

/** 「默认档位数」取值来源。 */
enum class StepsSource { SERVICE, ENGINE_XML, BUILTIN }

// ==================== 状态区文案（纯函数，供界面逐行渲染） ====================

/**
 * 运行状态区的六行文案（复刻旧 `StatusProbe.renderStatus` + `strings.xml` 的 `status_*`）。
 *
 * 顺序与旧布局一致：Root → LSPosed → 模块配置 → 档位 → 蓝牙设备 → 音量模式。
 * 文案在 app 层经 [AppText] 取资源，单测注入 XML 解析器后可继续直接断言中文。
 */
internal fun statusLines(snapshot: StatusSnapshot): List<String> = listOf(
    statusRootText(snapshot.rootGranted),
    statusLsposedText(snapshot.lsposedInstalled),
    statusModuleText(snapshot.verdict),
    statusVolumeLine(snapshot),
    statusBluetoothText(snapshot.bluetoothDevices),
    statusModeText(snapshot.bluetoothMode),
)

/** 探测未完成时的占位行（等价旧 `status_detecting`）。 */
internal fun statusDetectingText(): String = AppText.str(R.string.status_detecting)

internal fun statusRootText(granted: Boolean): String =
    AppText.str(if (granted) R.string.status_root_ok else R.string.status_root_no)

internal fun statusLsposedText(installed: Boolean): String =
    AppText.str(if (installed) R.string.status_lsposed_ok else R.string.status_lsposed_no)

internal fun statusModuleText(verdict: ModuleVerdict): String = AppText.str(
    when (verdict) {
        ModuleVerdict.ACTIVE -> R.string.status_module_active
        ModuleVerdict.PENDING_RESTART -> R.string.status_module_pending
        ModuleVerdict.INACTIVE_DEFAULT -> R.string.status_module_inactive_default
        ModuleVerdict.INACTIVE_PENDING_RESTORE -> R.string.status_module_inactive_restore
    },
)

/** 「档位：当前 X · 默认 Y（来源）· 音量键步进 Z」。 */
internal fun statusVolumeLine(snapshot: StatusSnapshot): String = AppText.str(
    R.string.status_volume_line,
    snapshot.actualMediaSteps,
    snapshot.systemDefaultSteps,
    stepsSourceLabel(snapshot.stepsSource),
    snapshot.keySteps,
)

internal fun statusBluetoothText(devices: List<String>): String =
    if (devices.isEmpty()) {
        AppText.str(R.string.status_bt_none)
    } else {
        AppText.str(R.string.status_bt_devices, devices.joinToString(AppText.str(R.string.list_separator)))
    }

internal fun statusModeText(mode: Int): String = AppText.str(R.string.status_mode_line, modeLabelShort(mode))

/** 系统默认档位数的取值来源标注（服务实测 / 引擎表 / 内置默认）。 */
internal fun stepsSourceLabel(source: StepsSource): String = AppText.str(
    when (source) {
        StepsSource.SERVICE -> R.string.steps_source_service
        StepsSource.ENGINE_XML -> R.string.steps_source_engine
        StepsSource.BUILTIN -> R.string.steps_source_builtin
    },
)

/** 模块配置生效状态（界面据此显示「已生效 / 需重启 / 未启用」）。 */
enum class ModuleVerdict { ACTIVE, PENDING_RESTART, INACTIVE_DEFAULT, INACTIVE_PENDING_RESTORE }

/** 探测输入：当前界面编辑态中与状态判定相关的四个量（关闭态由宿主换算后传入）。 */
data class StatusInput(
    val enabled: Boolean,
    val mediaSteps: Int,
    val keySteps: Int,
    val bluetoothMode: Int,
)

/** 一次探测的完整结果，供 Compose 侧渲染状态区与「未生效」提示。 */
data class StatusSnapshot(
    val rootGranted: Boolean,
    val lsposedInstalled: Boolean,
    /** 系统当前媒体档位上限（0 表示读取失败）。 */
    val actualMediaSteps: Int,
    val systemDefaultSteps: Int,
    val stepsSource: StepsSource,
    val keySteps: Int,
    val bluetoothMode: Int,
    /** 已连接的蓝牙音频输出设备（`名称（类型）`）；为空表示未连接。 */
    val bluetoothDevices: List<String>,
    val verdict: ModuleVerdict,
    /** 「是否已生效」文案（已生效时为固定提示，未生效时含当前/目标档位数）。 */
    val pendingText: String,
    /** 非 null 表示 App 数据被清空后从系统采纳了现有配置，界面需重载并提示。 */
    val adopted: VolumeConfig?,
)
