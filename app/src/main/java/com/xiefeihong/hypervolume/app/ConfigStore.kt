package com.xiefeihong.hypervolume.app

import android.content.Context
import android.content.SharedPreferences
import com.xiefeihong.hypervolume.core.Avrcp
import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.NativeVolumeCurve
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.VolumeConfig
import com.xiefeihong.hypervolume.core.VolumeMode
import kotlin.math.roundToInt

/**
 * 应用侧配置仓（移植自旧 `MainActivity`/`ConfigEditor` 的 SharedPreferences 读写职责）。
 *
 * 界面上的所有编辑先落到内存 [VolumeConfig]，整体以 `toJson()` 暂存于**单一**偏好键
 * [Prefs.KEY_CONFIG_JSON]；只有「保存修改」才会推送到系统（见 [SaveEngine]）。因此这里
 * 只是「编辑暂存区」，不代表系统生效值——生效基线由 [VolumeConfig.toJson] 与
 * 上次成功写入的 JSON 比对得出（见 [EditorState.hasUnsavedChanges]）。
 *
 * 另持有两个环境事实键：
 *  - [Prefs.KEY_SYSTEM_DEFAULT_STEPS]：首次探测到的**未被模块改写**的系统原生媒体档位数；
 *  - [Prefs.KEY_SYSTEM_DEFAULT_ACK]：用户在「拿不到系统默认档位」门控里主动选过处理方式，
 *    与是否已固化档位数解耦（旧实现同样分两个键，避免误把引擎表值当系统值固化）。
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences = Prefs.get(context.applicationContext)

    // ==================== 编辑暂存 ====================

    /** 暂存的整份配置；缺失/非法返回 `null`（由调用方回落 [defaultConfig]）。 */
    fun loadModel(): VolumeConfig? = VolumeConfig.fromJson(rawJson())

    /** 暂存配置的原始 JSON（缺失返回空串）。 */
    fun rawJson(): String = prefs.getString(Prefs.KEY_CONFIG_JSON, "").orEmpty()

    /** 是否已存过配置（App 数据被清空后为 false，此时需按系统侧配置恢复界面）。 */
    fun hasStoredConfig(): Boolean = prefs.contains(Prefs.KEY_CONFIG_JSON)

    /** 异步落盘（内存即时可见，Hook 端读的是 Settings.Global/镜像，不依赖此处的同步写）。 */
    fun persist(model: VolumeConfig) {
        prefs.edit().putString(Prefs.KEY_CONFIG_JSON, model.toJson()).apply()
    }

    /**
     * 同步落盘：仅在「从系统采纳配置」时使用——紧接着还要读回并与系统侧比对，
     * 语义与旧实现的 `commit()` 一致。
     */
    fun persistBlocking(model: VolumeConfig) {
        prefs.edit().putString(Prefs.KEY_CONFIG_JSON, model.toJson()).commit()
    }

    // ==================== 系统默认档位数 ====================

    /** 已固化的系统原生媒体档位数；未探测到返回 `null`。 */
    fun capturedSystemDefaultSteps(): Int? =
        if (prefs.contains(Prefs.KEY_SYSTEM_DEFAULT_STEPS)) {
            prefs.getInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, Prefs.MEDIA_STEPS_DEFAULT)
        } else {
            null
        }

    /** 固化系统原生媒体档位数（同步写：紧接着的界面渲染要用它）。 */
    fun captureSystemDefaultSteps(steps: Int) {
        prefs.edit().putInt(Prefs.KEY_SYSTEM_DEFAULT_STEPS, steps).commit()
    }

    /** 用户是否已在「无法可靠调整档位」门控里选过处理方式（不再重复警告）。 */
    var systemDefaultAcknowledged: Boolean
        get() = prefs.getBoolean(Prefs.KEY_SYSTEM_DEFAULT_ACK, false)
        set(value) {
            prefs.edit().putBoolean(Prefs.KEY_SYSTEM_DEFAULT_ACK, value).apply()
        }

    // ==================== 默认配置 ====================

    /**
     * 系统默认配置：总开关关闭、各设备默认直通、档位数/步进取系统原生级数。
     * 供首次打开（或应用数据被清空）时尚无暂存内容的界面回落。
     */
    fun defaultConfig(systemDefaultSteps: Int): VolumeConfig {
        val steps = Prefs.clampMediaSteps(systemDefaultSteps)
        return VolumeConfig(
            false, steps, Prefs.clampKeySteps(steps),
            defaultDeviceConfig(), defaultDeviceConfig(), defaultDeviceConfig(),
        )
    }

    /**
     * 一个默认设备配置：模式A 范围取 AVRCP 默认（可闻下限~127、对数曲线）；
     * 模式B/默认各持一套独立的中立起点，`max` 用 [Prefs.MEDIA_STEPS_MAX]（≥任何档位数）
     * → 初值恒为直通，用户拖动才会收窄。
     */
    private fun defaultDeviceConfig(): DeviceConfig {
        val absolute = Range(
            defaultMinAbs(), Prefs.AVRCP_MAX_VOLUME,
            defaultCurveType(VolumeMode.ABSOLUTE),
        )
        val software = Range(0, Prefs.MEDIA_STEPS_MAX, defaultCurveType(VolumeMode.SOFTWARE))
        val default = Range(0, Prefs.MEDIA_STEPS_MAX, defaultCurveType(VolumeMode.SOFTWARE))
        return DeviceConfig(Prefs.BT_MODE_DEFAULT, absolute, software, default)
    }

    /** 模式默认曲线：绝对音量用对数增强拉开低档间距；相对音量用线性区间。 */
    private fun defaultCurveType(mode: VolumeMode): Int =
        if (mode == VolumeMode.SOFTWARE) Prefs.CURVE_LINEAR else Prefs.CURVE_TYPE_DEFAULT

    /** 模式默认最小音量＝AVRCP 满量程 × 可闻下限比例。 */
    private fun defaultMinAbs(): Int =
        Prefs.clampAbs((Prefs.AVRCP_MAX_VOLUME * Avrcp.MIN_VOLUME_FLOOR_RATIO).roundToInt())

    companion object {

        /** UI 设备维度显示顺序：蓝牙 → 有线耳机 → 外放（与 [OutputDevice] 序数解耦）。 */
        val DEVICE_BY_TAB_ORDER: List<OutputDevice> =
            listOf(OutputDevice.BT, OutputDevice.WIRED, OutputDevice.SPEAKER)

        /** 默认预览纵轴口径选项（与旧 `native_mode_options` 数组同序：感知响度、dB 线性）。 */
        val NATIVE_MODE_OPTIONS: List<NativeVolumeCurve.Mode> =
            listOf(NativeVolumeCurve.Mode.PERCEPTUAL, NativeVolumeCurve.Mode.DB_LINEAR)
    }
}
