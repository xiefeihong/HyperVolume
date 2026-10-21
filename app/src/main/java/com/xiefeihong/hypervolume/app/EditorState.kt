package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.core.NativeVolumeCurve
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.VolumeConfig
import com.xiefeihong.hypervolume.core.VolumeMode
import kotlin.math.max
import kotlin.math.min

/** 默认预览横轴口径：档位（用当前档位数 + 按键次数）或百分比（用引擎表锚点百分比）。 */
enum class NativeAxis { STEPS, PERCENT }

/**
 * 编辑态快照（移植自旧 `ConfigEditor` + `MainActivity` 的共享控件状态）。
 *
 * 旧实现把「内存模型 + 控件当前值」两套状态分开（模型是权威，滑条是当前视图值），
 * 并用 `suppressListeners` 抑制程序化载入触发的回写。Compose 侧不需要这层抑制：
 * 本快照把两套状态合成一个不可变对象，控件值就是 [enabled]/[mediaSteps]/[keySteps]/
 * [rangeMin]/[rangeMax]/[curve] 字段，[writtenToModel] 即旧 `writeUiIntoModel`。
 * 每个交互返回新快照，语义与旧实现逐条对应：
 *  - 档位恒 ≥ 音量键步进（req-6，见 [changeMediaSteps]/[changeKeySteps]）
 *  - 最小/最大音量互不越过对方边界
 *  - 改档位/步进只同步 steps 不动范围，并按新档位数重载滑条满量程（旧 `writeRange=false` 分支）
 *  - 切设备标签只载入切片、不落盘
 */
data class EditorState(
    /** 权威配置模型：编辑即时写入此处，持久化与推送都以此为准。 */
    val model: VolumeConfig,
    /** 上一次成功写入系统（Settings.Global）的生效配置 JSON，作为「未保存修改」比较基线。 */
    val lastSavedJson: String = "",
    /** 当前编辑的设备维度（对应顶部选项卡）。 */
    val editingDevice: OutputDevice = OutputDevice.SPEAKER,
    /** 当前滑条/曲线所代表的模式（ABSOLUTE 编辑 absolute 范围，SOFTWARE 编辑 software/默认范围）。 */
    val editingMode: VolumeMode = VolumeMode.SOFTWARE,
    /** 当前设备是否处于「默认直通」：true 时隐藏曲线区、按原生曲线口径展示范围。 */
    val showingDefault: Boolean = false,
    /** 总开关（界面开关控件的当前值，可能尚未写回模型）。 */
    val enabled: Boolean = model.enabled,
    /** 档位滑条值（10~100）。 */
    val mediaSteps: Int = model.mediaSteps,
    /** 音量键步进滑条值（按键段数 10~29）。 */
    val keySteps: Int = model.keySteps,
    /** 最小音量滑条值（口径随 [editingMode]：模式A 为 AVRCP 0~127，其余为系统档位）。 */
    val rangeMin: Int = 0,
    /** 最大音量滑条值。 */
    val rangeMax: Int = Prefs.MEDIA_STEPS_MAX,
    /** 映射曲线类型。 */
    val curve: Int = Prefs.CURVE_TYPE_DEFAULT,
    /** 系统原生档位数（关闭态滑条回落目标、默认配置构造都用它）。 */
    val systemDefaultSteps: Int = Prefs.MEDIA_STEPS_DEFAULT,
    /** 默认预览纵轴换算口径。 */
    val nativeMode: NativeVolumeCurve.Mode = NativeVolumeCurve.Mode.PERCEPTUAL,
    /** 默认预览横轴口径。 */
    val nativeAxis: NativeAxis = NativeAxis.STEPS,
) {

    /** 当前编辑设备在生效模式下的存储范围（默认态取 defaultRange）；未设置时回落直通满量程。 */
    private val activeStoredRange: Range
        get() {
            val dc = model.deviceFor(editingDevice)
            val fallback = Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_TYPE_DEFAULT)
            return when {
                showingDefault -> dc.defaultRange ?: fallback
                editingMode == VolumeMode.ABSOLUTE -> dc.absolute ?: fallback
                else -> dc.software ?: fallback
            }
        }

    /** 当前编辑口径下 min/max 滑条的满量程值：模式A=127（AVRCP），模式B/默认=当前档位数。 */
    val rangeValueMax: Int
        get() = if (absoluteEditing) Prefs.AVRCP_MAX_VOLUME else mediaSteps

    /** 是否处于「蓝牙 + 绝对音量」口径（外放/有线即使选中模式A 也会被归一，见 [clampedModeForDevice]）。 */
    val absoluteEditing: Boolean
        get() = editingMode == VolumeMode.ABSOLUTE && editingDevice.supportsAbsolute()

    /** 模式A 是否对当前设备可用（仅蓝牙）。 */
    val absoluteAllowed: Boolean get() = editingDevice.supportsAbsolute()

    /** 当前模型与已生效基线不一致（存在未保存修改）。 */
    val hasUnsavedChanges: Boolean get() = model.toJson() != lastSavedJson

    /** 生效后的物理档位数：启用时=用户 [mediaSteps]，关闭时=系统默认；用于判定是否需重启系统框架。 */
    fun targetPhysicalSteps(cfg: VolumeConfig?): Int =
        if (cfg == null) systemDefaultSteps else if (cfg.enabled) Prefs.clampMediaSteps(cfg.mediaSteps) else systemDefaultSteps

    // ==================== 交互：设备 / 模式 ====================

    /** 切换编辑设备：仅载入该设备的切片（标签切换本身不落盘）。 */
    fun selectDevice(device: OutputDevice): EditorState =
        copy(editingDevice = device).slicedFrom(device)

    /** 选择绝对/相对模式（由模式芯片决定）：改该设备模式并载入对应范围。 */
    fun selectMode(target: VolumeMode): EditorState {
        val updated = model.withDevice(
            editingDevice,
            model.deviceFor(editingDevice).withMode(clampedModeForDevice(editingDevice, target.modeId)),
        )
        return copy(showingDefault = false, editingMode = target, model = updated).reloadedRange()
    }

    /** 选中「默认」：把当前设备设为默认直通（可编辑最小/最大、保留窗内原生形状），隐藏曲线区。 */
    fun selectDefault(): EditorState {
        val updated = model.withDevice(
            editingDevice,
            model.deviceFor(editingDevice).withMode(Prefs.BT_MODE_DEFAULT),
        )
        return copy(showingDefault = true, editingMode = VolumeMode.SOFTWARE, model = updated).reloadedRange()
    }

    // ==================== 交互：总开关 / 档位 / 步进 ====================

    /**
     * 启用开关切换：关闭时把档位/音量键步进锁到系统默认（音量范围编辑区仍可用），
     * 开启后从模型恢复已存的档位/步进（不丢配置），并同步写回模型的启用/档位字段。
     */
    fun toggleEnabled(value: Boolean): EditorState {
        val media = Prefs.clampMediaSteps(if (value) model.mediaSteps else systemDefaultSteps)
        val key = Prefs.clampKeySteps(if (value) model.keySteps else systemDefaultSteps)
        val stepped = stepsWithFloor(media, key)
        val next = copy(
            enabled = value,
            mediaSteps = stepped.first,
            keySteps = stepped.second,
        )
        return next.copy(model = next.writtenToModel(writeRange = false)).reloadedRange()
    }

    /** 拖动档位滑条：档位不得低于步进（低于则顶到步进），并按新档位数重载范围滑条满量程。 */
    fun changeMediaSteps(value: Int): EditorState {
        val stepped = stepsWithFloor(Prefs.clampMediaSteps(value), keySteps)
        val next = copy(mediaSteps = stepped.first, keySteps = stepped.second)
        return next.copy(model = next.writtenToModel(writeRange = false)).reloadedRange()
    }

    /** 拖动音量键步进滑条：步进超过档位时把档位顶到步进，同样重载范围滑条满量程。 */
    fun changeKeySteps(value: Int): EditorState {
        val stepped = stepsWithFloor(mediaSteps, Prefs.clampKeySteps(value))
        val next = copy(mediaSteps = stepped.first, keySteps = stepped.second)
        return next.copy(model = next.writtenToModel(writeRange = false)).reloadedRange()
    }

    // ==================== 交互：音量范围 / 曲线 ====================

    /** 拖动最小音量滑条：禁止越过最大（停在对方边界）。 */
    fun changeRangeMin(value: Int): EditorState = changeRange(min(value, rangeMax), rangeMax)

    /** 拖动最大音量滑条：禁止越过最小（停在对方边界）。 */
    fun changeRangeMax(value: Int): EditorState = changeRange(rangeMin, max(value, rangeMin))

    /** 范围变更后写回当前设备在当前模式下的范围切片。 */
    fun changeRange(minValue: Int, maxValue: Int): EditorState {
        val next = copy(rangeMin = minValue, rangeMax = maxValue)
        return next.copy(model = next.writtenToModel(writeRange = true))
    }

    /** 切换映射曲线类型（恒代表当前 [editingMode] 的切片）。 */
    fun changeCurve(curveType: Int): EditorState {
        val next = copy(curve = Prefs.clampCurve(curveType))
        return next.copy(model = next.writtenToModel(writeRange = true))
    }

    /** 切换默认预览纵轴口径（仅默认设备可见）。 */
    fun changeNativeMode(mode: NativeVolumeCurve.Mode): EditorState = copy(nativeMode = mode)

    /** 切换默认预览横轴口径（仅默认设备可见）。 */
    fun changeNativeAxis(axis: NativeAxis): EditorState = copy(nativeAxis = axis)

    /** 标记本轮改动已成功写入系统（刷新「未保存修改」基线）。 */
    fun markSaved(json: String): EditorState = copy(lastSavedJson = json)

    /** 采纳/重置整份配置后重载界面（等价旧 `applyConfigToPrefs` + `loadConfigIntoUi(false)`）。 */
    fun replaceModel(cfg: VolumeConfig): EditorState =
        copy(model = cfg, enabled = cfg.enabled, mediaSteps = cfg.mediaSteps, keySteps = cfg.keySteps)
            .slicedFrom(editingDevice)

    // ==================== 模型读写 ====================

    /**
     * 把当前编辑值写回模型（旧 `writeUiIntoModel`）：`writeRange=false` 时仅同步启用/档位/步进
     * （改档位不应把中立满量程范围压扁），`true` 时按 A/B/默认 各写自己独立的范围切片。
     */
    fun writtenToModel(writeRange: Boolean): VolumeConfig {
        var m = model.withEnabled(enabled)
        if (enabled) {
            m = m.withSteps(mediaSteps, keySteps)
        }
        if (!writeRange) {
            return m
        }
        val cur = m.deviceFor(editingDevice)
        val valueMax = rangeValueMax
        val lo = if (absoluteEditing) Prefs.clampAbs(rangeMin) else Prefs.clampSystemStep(rangeMin, valueMax)
        val hi = if (absoluteEditing) Prefs.clampAbs(rangeMax) else Prefs.clampSystemStep(rangeMax, valueMax)
        val updated = Range(lo, hi, Prefs.clampCurve(curve))
        val slice = when {
            showingDefault -> cur.copy(defaultRange = updated)
            absoluteEditing -> cur.copy(absolute = updated)
            else -> cur.copy(software = updated)
        }
        return m.withDevice(editingDevice, slice)
    }

    // ==================== 内部 ====================

    /** 载入 [editingDevice] 在当前模式下的切片到滑条/曲线字段（不改模型）。 */
    private fun slicedFrom(device: OutputDevice): EditorState {
        val dc = model.deviceFor(device)
        val allowAbs = device.supportsAbsolute()
        val mode = clampedModeForDevice(device, dc.mode)
        val default = mode == Prefs.BT_MODE_DEFAULT
        val editing = if (default) {
            VolumeMode.SOFTWARE     // 默认态用系统档位口径编辑最小/最大
        } else {
            if (mode == Prefs.BT_MODE_ABSOLUTE && allowAbs) VolumeMode.ABSOLUTE else VolumeMode.SOFTWARE
        }
        val next = copy(
            editingDevice = device,
            showingDefault = default,
            editingMode = editing,
        )
        return next.reloadedRange()
    }

    /** 按当前设备/模式/档位数重载 min/max 滑条与曲线（滑条满量程随编辑口径变化）。 */
    private fun reloadedRange(): EditorState {
        val stored = activeStoredRange
        val valueMax = rangeValueMax
        return copy(
            rangeMin = Prefs.clampAbs(min(valueMax, stored.min)),
            rangeMax = Prefs.clampAbs(min(valueMax, stored.max)),
            curve = Prefs.clampCurve(stored.curve),
        )
    }

    companion object {

        /**
         * 从暂存区载入整份配置并定位设备（旧 `loadConfigIntoUi`）。
         *
         * @param systemDefaultSteps 系统原生档位数（关闭态滑条回落目标）。
         * @param detectedDevice 非 null 表示按当前音频输出定位设备标签（仅冷启动时传）；
         *                       null 表示保留当前编辑设备（保存后重载，不因当前输出切走）。
         * @param keepDevice 保留快照里已有的编辑设备（`detectedDevice == null` 时生效）。
         */
        fun load(
            store: ConfigStore,
            systemDefaultSteps: Int,
            detectedDevice: OutputDevice?,
            keepDevice: OutputDevice = OutputDevice.SPEAKER,
            lastSavedJson: String = "",
        ): EditorState {
            val cfg = store.loadModel() ?: store.defaultConfig(systemDefaultSteps)
            val enabled = cfg.enabled
            val media = Prefs.clampMediaSteps(if (enabled) cfg.mediaSteps else systemDefaultSteps)
            val key = Prefs.clampKeySteps(if (enabled) cfg.keySteps else systemDefaultSteps)
            val stepped = stepsWithFloor(media, key)
            val base = EditorState(
                model = cfg,
                lastSavedJson = lastSavedJson,
                editingDevice = detectedDevice ?: keepDevice,
                enabled = enabled,
                mediaSteps = stepped.first,
                keySteps = stepped.second,
                systemDefaultSteps = systemDefaultSteps,
            )
            return base.slicedFrom(base.editingDevice)
        }

        /** 档位 ≥ 音量键步进（req-6 初始态自洽）：当前档位 < 步进时把档位顶到步进。 */
        private fun stepsWithFloor(media: Int, key: Int): Pair<Int, Int> = max(media, key) to key

        /** 外放/有线不支持绝对音量：把非法的 ABSOLUTE 归一为 SOFTWARE，其余原样。 */
        private fun clampedModeForDevice(device: OutputDevice, mode: Int): Int =
            if (!device.supportsAbsolute() && mode == Prefs.BT_MODE_ABSOLUTE) {
                Prefs.BT_MODE_SOFTWARE
            } else {
                mode
            }
    }
}
