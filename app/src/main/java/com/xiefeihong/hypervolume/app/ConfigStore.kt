package com.xiefeihong.hypervolume.app

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
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
 * 应用侧配置仓：全应用**只有一份配置**——写进系统的生效配置（`Settings.Global` 的
 * [Prefs.GLOBAL_KEY]），本类是它在应用内的唯一读取入口，不再另设本地暂存副本。
 *
 * 读取走 `ContentResolver`：Android 读全局设置不需要 root/权限，所以无 root 时界面照样能
 * 拿到生效值；只有写入才需要 root（见 [SaveEngine]）。界面编辑只改内存快照，与装载时读到的
 * 生效配置做语义比较得出是否有未保存修改（见 [EditorState.hasUnsavedChanges]）；未保存的编辑
 * 不落盘，重进应用回到生效值。
 *
 * 另持有三个环境事实键：
 *  - [Prefs.KEY_SYSTEM_DEFAULT_STEPS]：首次探测到的**未被模块改写**的系统原生媒体档位数；
 *  - [Prefs.KEY_SYSTEM_DEFAULT_ACK]：用户在「拿不到系统默认档位」门控里主动选过处理方式，
 *    与是否已固化档位数解耦（旧实现同样分两个键，避免误把引擎表值当系统值固化）；
 *  - [Prefs.KEY_BOOT_SCRIPT_ACTIVE] + [Prefs.KEY_BOOT_SCRIPT_STEPS]：上次烘进 post-fs-data
 *    开机脚本的 `(生效标志, 档位数)`，作为幂等基线——本轮参数未变即跳过脚本重写（见 [SaveEngine.pushConfig]）。
 */
class ConfigStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = Prefs.get(appContext)

    // ==================== 生效配置（全应用唯一一份） ====================

    /**
     * 生效配置原文：`ContentResolver` 直读 `Settings.Global`，无需 root/权限。
     * 键不存在、读取异常一律归一为空串，由调用方回落 [defaultConfig]。
     *
     * 注意：同进程内 SettingsProvider 带有值缓存，刚由 root 侧写入时这里可能读到旧值——
     * 因此写入后的回读校验走独立的 root `settings get`（见 [SaveEngine.pushConfig]），
     * 本方法只用于装载界面与展示原文。
     */
    fun readEffectiveJson(): String =
        runCatching { Settings.Global.getString(appContext.contentResolver, Prefs.GLOBAL_KEY).orEmpty() }
            .getOrDefault("")

    /** 生效配置模型；系统侧还没有配置/内容非法返回 `null`（由调用方回落 [defaultConfig]）。 */
    fun readEffective(): VolumeConfig? = parseEffective(readEffectiveJson())

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

    // ==================== 开机脚本幂等基线 ====================

    /**
     * 上次烘进 post-fs-data 开机脚本的 `(生效标志, 档位数)`；任一键缺失（旧存档 / 从未写过 /
     * 刚清除数据）返回 `null`，由 [SaveEngine.pushConfig] 按「未记录 → 必写」保守处理。
     */
    fun bootScriptRecord(): BootScriptRecord? {
        if (!prefs.contains(Prefs.KEY_BOOT_SCRIPT_ACTIVE) || !prefs.contains(Prefs.KEY_BOOT_SCRIPT_STEPS)) {
            return null
        }
        return BootScriptRecord(
            active = prefs.getBoolean(Prefs.KEY_BOOT_SCRIPT_ACTIVE, false),
            steps = prefs.getInt(Prefs.KEY_BOOT_SCRIPT_STEPS, 0),
        )
    }

    /** 记录本轮烘进开机脚本的 `(active, steps)`（同步写：紧接着的再保存要用它判幂等）。 */
    fun setBootScriptRecord(active: Boolean, steps: Int) {
        prefs.edit()
            .putBoolean(Prefs.KEY_BOOT_SCRIPT_ACTIVE, active)
            .putInt(Prefs.KEY_BOOT_SCRIPT_STEPS, steps)
            .commit()
    }

    /** 移除开机脚本幂等基线（「清除数据」成功后调用，否则清完改同样参数保存会漏写脚本）。 */
    fun clearBootScriptRecord() {
        prefs.edit()
            .remove(Prefs.KEY_BOOT_SCRIPT_ACTIVE)
            .remove(Prefs.KEY_BOOT_SCRIPT_STEPS)
            .commit()
    }

    // ==================== 性能日志开关 ====================

    /** 性能日志开关：开启时一次保存额外计一段耗时并写一条 `push perf` 模块日志（默认关）。 */
    var perfLogEnabled: Boolean
        get() = prefs.getBoolean(Prefs.KEY_PERF_LOG, false)
        set(value) {
            prefs.edit().putBoolean(Prefs.KEY_PERF_LOG, value).apply()
        }

    // ==================== 系统全局设置只读（无需 root） ====================

    /**
     * App 进程用 `ContentResolver` 读 `Settings.Global` 现值（读全局设置不需要 root），
     * 供电量跳过绝对音量开关写入前的比对；键不存在或读取异常返回 `null`。
     */
    fun readGlobalSetting(key: String): String? =
        runCatching { Settings.Global.getString(appContext.contentResolver, key) }.getOrNull()

    // ==================== 默认配置 ====================

    /**
     * 系统默认配置：总开关关闭、各设备默认直通、档位数/步进取系统原生级数。
     * 供系统侧还没有生效配置（首次安装使用）时的界面回落。
     */
    fun defaultConfig(systemDefaultSteps: Int): VolumeConfig {
        val steps = Prefs.clampMediaSteps(systemDefaultSteps)
        return VolumeConfig(
            false, steps, Prefs.clampKeySteps(steps),
            defaultDeviceConfig(false), defaultDeviceConfig(false), defaultDeviceConfig(true),
        )
    }

    /**
     * 一个默认设备配置：[withAbsolute]（仅蓝牙）时模式A 范围取 AVRCP 默认（可闻下限~127、对数曲线），
     * 其余设备置 null——该维度不落盘，给值只会留下无法往返的内存状态；
     * 模式B/默认各持一套独立的中立起点，`max` 用 [Prefs.MEDIA_STEPS_MAX]（≥任何档位数）
     * → 初值恒为直通，用户拖动才会收窄。
     */
    private fun defaultDeviceConfig(withAbsolute: Boolean): DeviceConfig {
        // 与 VolumeConfig 的持久化口径对齐：模式A 范围只有蓝牙会写进 JSON，非蓝牙留 null，
        // 这样「默认回落值」也能无损往返（写入后回读必然相等）。
        val absolute = if (withAbsolute) {
            Range(
                defaultMinAbs(), Prefs.AVRCP_MAX_VOLUME,
                defaultCurveType(VolumeMode.ABSOLUTE),
            )
        } else {
            null
        }
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

        /**
         * 生效配置原文 → 模型：空串、字面 `null`（`settings get` 无值时的回显）与畸形内容
         * 一律返回 `null`。纯函数，脏值口径由单测锁定。
         */
        internal fun parseEffective(raw: String): VolumeConfig? {
            val text = raw.trim()
            if (text.isEmpty() || text == "null") return null
            return VolumeConfig.fromJson(text)
        }
    }
}

/**
 * 上次烘进 post-fs-data 开机脚本的参数基线（[ConfigStore.bootScriptRecord] 的返回）：
 * [steps] 已经 [Prefs.clampMediaSteps]，与写入时的字面量口径一致。
 */
data class BootScriptRecord(val active: Boolean, val steps: Int)
