package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.TextBackedTest
import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.VolumeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PreviewBuilder] 三个分支（默认·百分比轴 / 默认·档位轴 / 绝对-相对模式）的预览数据测试。
 *
 * JVM 环境下 [com.xiefeihong.hypervolume.core.NativeVolumeCurve] 未 `load()`，一律走
 * AOSP 参考点表，因此断言只依赖纯计算结果（窗口化横轴、图注、曲线参数），不依赖本机 ROM。
 */
class PreviewBuilderTest : TextBackedTest() {

    /** 列数估算交回自然换行（等价旧实现测不到宽度时返回 0）。 */
    private val noColumns = ColumnEstimator { 0 }

    private fun device(mode: Int, abs: Range?, soft: Range?, default: Range?): DeviceConfig =
        DeviceConfig(mode, abs, soft, default)

    private fun state(
        mode: Int,
        range: Range,
        axis: NativeAxis = NativeAxis.STEPS,
        media: Int = 60,
        key: Int = 15,
    ): EditorState {
        val cfg = VolumeConfig(
            true, media, key,
            device(Prefs.BT_MODE_DEFAULT, null, Range(0, media, Prefs.CURVE_LINEAR), Range(0, media, Prefs.CURVE_LINEAR)),
            device(Prefs.BT_MODE_SOFTWARE, null, Range(0, media, Prefs.CURVE_LINEAR), Range(0, media, Prefs.CURVE_LINEAR)),
            device(
                mode,
                if (mode == Prefs.BT_MODE_ABSOLUTE) range else Range(0, Prefs.AVRCP_MAX_VOLUME, Prefs.CURVE_LOG),
                if (mode == Prefs.BT_MODE_SOFTWARE) range else Range(0, media, Prefs.CURVE_LINEAR),
                if (mode == Prefs.BT_MODE_DEFAULT) range else Range(0, media, Prefs.CURVE_LINEAR),
            ),
        )
        return EditorState(
            model = cfg,
            editingDevice = OutputDevice.BT,
            nativeAxis = axis,
            systemDefaultSteps = Prefs.MEDIA_STEPS_DEFAULT,
        ).replaceModel(cfg)
    }

    @Test
    fun defaultPercentAxis_windowsChartToSelectedRange() {
        val s = state(Prefs.BT_MODE_DEFAULT, Range(10, 50, Prefs.CURVE_LINEAR), NativeAxis.PERCENT)
        assertTrue(s.showingDefault)
        val preview = PreviewBuilder.build(s, "蓝牙", noColumns)
        val chart = preview.chart as ChartSpec.Native
        // 10/60→17%、50/60→83%：曲线横轴窗口化到设定范围
        assertEquals(17, chart.xMin)
        assertEquals(83, chart.xMax)
        assertTrue(chart.percentAxis)
        assertEquals("蓝牙 · AOSP 参考 · 纵轴感知响度 · 范围17%~83%", chart.caption)
        assertEquals("最小音量：10 · 约 17%", preview.minLabel)
        assertEquals("最大音量：50 · 约 83%", preview.maxLabel)
        assertTrue(preview.nativeSelectors)
        assertTrue(preview.mappingTable.isNotEmpty())
    }

    @Test
    fun defaultStepsAxis_remapsNativeCurveAndMarksPressDots() {
        val s = state(Prefs.BT_MODE_DEFAULT, Range(10, 50, Prefs.CURVE_LINEAR), NativeAxis.STEPS)
        val preview = PreviewBuilder.build(s, "蓝牙", noColumns)
        val chart = preview.chart as ChartSpec.Native
        assertEquals(0, chart.xMin)
        assertEquals(60, chart.xMax)
        assertEquals(61, chart.gainByX.size)
        // 落点圆点＝每次按键的落档（含 0 档）：段数 + 1
        assertEquals(16, chart.dots.size)
        assertEquals(0, chart.dots[0])
        assertEquals(60, chart.dots.last())
        assertEquals("蓝牙 · AOSP 参考 · 纵轴感知响度 · 范围10~50", chart.caption)
        assertFalseCurveBlock(preview)
    }

    @Test
    fun softwareMode_buildsMappedChartWithSystemIndex() {
        val s = state(Prefs.BT_MODE_SOFTWARE, Range(5, 40, Prefs.CURVE_LOG))
        val preview = PreviewBuilder.build(s, "蓝牙", noColumns)
        val chart = preview.chart as ChartSpec.Mapped
        assertEquals(60, chart.maxSteps)
        assertEquals(5, chart.minAbs)
        assertEquals(40, chart.maxAbs)
        assertTrue(chart.useSystemIndex) // 相对音量在 system_server 内衰减
        assertFalse(preview.nativeSelectors)
        assertTrue(preview.curveBlock)
        assertEquals("最小音量：5 · 约 8%", preview.minLabel)
    }

    @Test
    fun absoluteMode_buildsMappedChartWithAvrcpScale() {
        val s = state(Prefs.BT_MODE_ABSOLUTE, Range(8, 120, Prefs.CURVE_SQRT))
        val preview = PreviewBuilder.build(s, "蓝牙", noColumns)
        val chart = preview.chart as ChartSpec.Mapped
        assertEquals(120, chart.maxAbs)
        assertFalse(chart.useSystemIndex) // 绝对音量由蓝牙进程按 AVRCP 下发
        assertEquals(Prefs.AVRCP_MAX_VOLUME, s.rangeValueMax)
        // 8/127 ≈ 6%
        assertEquals("最小音量：8 · 约 6%", preview.minLabel)
    }

    @Test
    fun labels_explainStepsAndKeyDelta() {
        val preview = PreviewBuilder.build(state(Prefs.BT_MODE_SOFTWARE, Range(0, 60, Prefs.CURVE_LINEAR)), "蓝牙", noColumns)
        assertEquals("档位：60 档（范围 10~100）", preview.labels.mediaSteps)
        assertTrue(preview.labels.keySteps.startsWith("音量键步进：15 段（每次约跳 "))
    }

    private fun assertFalseCurveBlock(preview: Preview) {
        assertTrue(!preview.curveBlock)
        assertTrue(preview.nativeSelectors)
    }
}
