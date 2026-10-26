package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.VolumeConfig
import com.xiefeihong.hypervolume.core.VolumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EditorState] 交互语义的 JVM 单元测试：档位/步进互锁、min/max 互不越过、
 * 设备与模式切换只动对应切片、关闭态锁档位的三条不变量（对应旧 `ConfigEditor` 行为）。
 */
class EditorStateTest {

    private val fullAbs = Range(0, Prefs.AVRCP_MAX_VOLUME, Prefs.CURVE_TYPE_DEFAULT)
    private val fullSoft = Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_LINEAR)
    private val fullDefault = Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_LINEAR)

    private fun device(mode: Int, abs: Range, soft: Range, default: Range): DeviceConfig =
        DeviceConfig(mode, abs, soft, default)

    private fun config(
        enabled: Boolean = true,
        media: Int = 60,
        key: Int = 15,
        btMode: Int = Prefs.BT_MODE_SOFTWARE,
        speakerMode: Int = Prefs.BT_MODE_DEFAULT,
    ): VolumeConfig = VolumeConfig(
        enabled, media, key,
        device(speakerMode, fullAbs, fullSoft, fullDefault),
        device(Prefs.BT_MODE_SOFTWARE, fullAbs, fullSoft, fullDefault),
        device(btMode, fullAbs, fullSoft, fullDefault),
    )

    /** 构造「界面已载入模型切片」的快照：等价旧 `loadConfigIntoUi`（走 [EditorState.replaceModel]）。 */
    private fun loaded(
        cfg: VolumeConfig = config(),
        editingDevice: OutputDevice = OutputDevice.BT,
        systemDefaultSteps: Int = 15,
    ): EditorState = EditorState(
        model = cfg,
        lastSaved = cfg,
        editingDevice = editingDevice,
        systemDefaultSteps = systemDefaultSteps,
    ).replaceModel(cfg)

    // ==================== 档位 / 音量键步进互锁 ====================

    @Test
    fun mediaStepsBelowKeySteps_isLiftedToKeySteps() {
        val s = loaded(config(media = 60, key = 15)).changeMediaSteps(12)
        assertEquals(15, s.mediaSteps)
        assertEquals(15, s.keySteps)
        assertEquals(15, s.model.mediaSteps)
        assertEquals(15, s.model.keySteps)
    }

    @Test
    fun keyStepsAboveMediaSteps_liftsMediaSteps() {
        val s = loaded(config(media = 20, key = 15)).changeKeySteps(25)
        assertEquals(25, s.mediaSteps)
        assertEquals(25, s.keySteps)
        assertEquals(25, s.model.mediaSteps)
    }

    @Test
    fun stepsChange_doesNotSquashStoredRange() {
        // 改档位只同步 steps，不把中立满量程范围压扁（旧 writeRange=false 分支）
        val s = loaded(config(media = 60)).changeMediaSteps(80)
        assertEquals(Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_LINEAR), s.model.bluetooth.software)
        assertEquals(80, s.model.mediaSteps)
    }

    @Test
    fun rangeSliderReloaded_afterStepsChange() {
        val s = loaded(config(media = 60)).changeMediaSteps(40)
        assertEquals(40, s.mediaSteps)
        assertEquals(40, s.rangeMax) // min(新档位数, 存储 max=100)
        assertEquals(40, s.rangeValueMax)
    }

    // ==================== 总开关 ====================

    @Test
    fun toggleOff_locksStepsToSystemDefault_andOnRestoresStored() {
        val off = loaded(config(enabled = true, media = 60, key = 20)).toggleEnabled(false)
        assertFalse(off.model.enabled)
        assertEquals(15, off.mediaSteps)
        assertEquals(15, off.keySteps)
        // 关闭态不写 steps，模型仍保留原档位 → 重新开启可恢复
        val on = off.toggleEnabled(true)
        assertTrue(on.model.enabled)
        assertEquals(60, on.mediaSteps)
        assertEquals(20, on.keySteps)
    }

    // ==================== 音量范围互锁 ====================

    @Test
    fun rangeMin_cannotCrossRangeMax() {
        val s = loaded()
        val moved = s.changeRangeMin(s.rangeMax + 20)
        assertEquals(moved.rangeMax, moved.rangeMin)
    }

    @Test
    fun rangeMax_cannotCrossRangeMin() {
        val s = loaded().changeRange(20, 50)
        val moved = s.changeRangeMax(5)
        assertEquals(20, moved.rangeMin)
        assertEquals(20, moved.rangeMax)
    }

    // ==================== 模式 / 默认 / 设备切换 ====================

    @Test
    fun bluetoothAbsoluteMode_writesAbsoluteSliceOnly() {
        val abs = loaded().selectMode(VolumeMode.ABSOLUTE)
        assertEquals(Prefs.BT_MODE_ABSOLUTE, abs.model.bluetooth.mode)
        assertTrue(abs.absoluteEditing)
        assertEquals(Prefs.AVRCP_MAX_VOLUME, abs.rangeValueMax)
        val moved = abs.changeRange(10, 120)
        assertEquals(Range(10, 120, moved.curve), moved.model.bluetooth.absolute)
        assertEquals(fullSoft, moved.model.bluetooth.software)
    }

    @Test
    fun speakerAbsoluteMode_clampedToSoftwareAndWritesSoftwareSlice() {
        val s = loaded(config(), editingDevice = OutputDevice.SPEAKER)
            .selectMode(VolumeMode.ABSOLUTE)
        assertEquals(Prefs.BT_MODE_SOFTWARE, s.model.speaker.mode)
        assertFalse(s.absoluteEditing)
        assertFalse(s.absoluteAllowed)
        val moved = s.changeRange(5, 40)
        assertEquals(Range(5, 40, moved.curve), moved.model.speaker.software)
        assertEquals(fullAbs, moved.model.speaker.absolute)
    }

    @Test
    fun selectDefault_usesSystemStepScaleAndWritesDefaultRange() {
        val d = loaded().selectDefault()
        assertTrue(d.showingDefault)
        assertEquals(Prefs.BT_MODE_DEFAULT, d.model.bluetooth.mode)
        assertEquals(VolumeMode.SOFTWARE, d.editingMode)
        assertEquals(60, d.rangeValueMax) // 默认态按当前档位数口径
        val moved = d.changeRange(3, 50)
        assertEquals(Range(3, 50, moved.curve), moved.model.bluetooth.defaultRange)
        assertEquals(fullSoft, moved.model.bluetooth.software)
    }

    @Test
    fun selectDevice_loadsThatDeviceSlice() {
        val cfg = VolumeConfig(
            true, 60, 15,
            device(Prefs.BT_MODE_DEFAULT, fullAbs, fullSoft, Range(7, 40, Prefs.CURVE_LINEAR)),
            device(Prefs.BT_MODE_SOFTWARE, fullAbs, fullSoft, fullDefault),
            device(Prefs.BT_MODE_ABSOLUTE, Range(20, 100, Prefs.CURVE_LOG), fullSoft, fullDefault),
        )
        val bt = loaded(cfg, editingDevice = OutputDevice.BT)
        assertEquals(20, bt.rangeMin)
        assertEquals(100, bt.rangeMax)
        val speaker = bt.selectDevice(OutputDevice.SPEAKER)
        assertTrue(speaker.showingDefault)
        assertEquals(7, speaker.rangeMin)
        assertEquals(40, speaker.rangeMax)
        // 标签切换本身不改模型
        assertEquals(cfg, speaker.model)
    }

    // ==================== 曲线 / 基线 / 生效档位 ====================

    @Test
    fun load_takesEffectiveConfigAndFallsBackToDefault() {
        val cfg = config()
        val defaults = config(enabled = false, media = 15, key = 15)
        // 有生效配置：界面模型就是它（全应用唯一一份，不再有本地暂存副本）
        val fromEffective = EditorState.load(
            effective = cfg,
            defaultConfig = defaults,
            systemDefaultSteps = 15,
            detectedDevice = OutputDevice.BT,
        )
        assertEquals(cfg, fromEffective.model)
        assertEquals(60, fromEffective.mediaSteps)
        // 系统侧还没有配置：回落默认配置；基线仍为 null，由调用方冷启动时 markSaved 避免误提示
        val fromNone = EditorState.load(
            effective = null,
            defaultConfig = defaults,
            systemDefaultSteps = 15,
            detectedDevice = null,
        )
        assertEquals(defaults, fromNone.model)
        assertTrue(fromNone.hasUnsavedChanges)
        assertFalse(fromNone.markSaved(fromNone.model).hasUnsavedChanges)
    }

    @Test
    fun curveChange_writesCurrentSlice() {
        val s = loaded().changeCurve(Prefs.CURVE_SQRT)
        assertEquals(Prefs.CURVE_SQRT, s.curve)
        assertEquals(Prefs.CURVE_SQRT, s.model.bluetooth.software!!.curve)
    }

    @Test
    fun unsavedBaseline_followsMarkSaved() {
        val s = loaded()
        assertFalse(s.hasUnsavedChanges)
        val changed = s.changeMediaSteps(80)
        assertTrue(changed.hasUnsavedChanges)
        assertFalse(changed.markSaved(changed.model).hasUnsavedChanges)
    }

    @Test
    fun unsavedBaseline_comparesSemanticsNotLayout() {
        val cfg = config()
        // 基线取自排版不同的等价串（旧版暂存的紧凑串 / 手写单行）：语义相同就不提示未保存
        val singleLine = cfg.toJson().replace("\n", "").replace(", ", ",").replace(": ", ":")
        val againstSingleLine = loaded().copy(lastSaved = VolumeConfig.fromJson(singleLine))
        assertFalse(againstSingleLine.hasUnsavedChanges)
        // 尚无生效基线（首次启动 / 回写失败）如实提示有未保存修改
        assertTrue(loaded().copy(lastSaved = null).hasUnsavedChanges)
    }

    @Test
    fun targetPhysicalSteps_followsEnabledFlag() {
        val s = loaded(config(enabled = true, media = 60))
        assertEquals(60, s.targetPhysicalSteps(s.model))
        assertEquals(15, s.targetPhysicalSteps(s.model.withEnabled(false)))
        assertEquals(15, s.targetPhysicalSteps(null))
    }
}
