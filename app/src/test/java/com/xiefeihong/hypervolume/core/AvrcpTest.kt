package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [Avrcp] 曲线正/反函数与档位映射的 JVM 单元测试。
 * 覆盖：端点精确命中、单调性、值域钳制、applyCurve↔invertCurve 往返（线性全程、对数 min=0 上半程）。
 */
class AvrcpTest {

    @Test
    fun absolute_endpointsExactAndGuard() {
        assertEquals(0, Avrcp.curveToAbsoluteVolume(0, MAX_STEPS, MIN_ABS, MAX_ABS, Prefs.CURVE_LOG))
        assertEquals(MIN_ABS, Avrcp.curveToAbsoluteVolume(1, MAX_STEPS, MIN_ABS, MAX_ABS, Prefs.CURVE_LOG))
        assertEquals(MAX_ABS, Avrcp.curveToAbsoluteVolume(MAX_STEPS, MAX_STEPS, MIN_ABS, MAX_ABS, Prefs.CURVE_LOG))
    }

    @Test
    fun absolute_monotonicAndBounded_allCurves() {
        val curves = intArrayOf(Prefs.CURVE_LOG, Prefs.CURVE_LINEAR, Prefs.CURVE_SQRT)
        for (curve in curves) {
            var prev = -1
            for (s in 1..MAX_STEPS) {
                val v = Avrcp.curveToAbsoluteVolume(s, MAX_STEPS, MIN_ABS, MAX_ABS, curve)
                assertTrue("curve=$curve s=$s v=$v", v in 0..127)
                assertTrue("monotonic curve=$curve s=$s", v >= prev)
                prev = v
            }
            assertEquals("max endpoint curve=$curve", MAX_ABS, prev)
        }
    }

    /** 线性曲线正/反函数往返：整数舍入误差应落在 ±1 档内。 */
    @Test
    fun linear_roundTripWithinOneStep() {
        for (s in 1..MAX_STEPS) {
            val v = Avrcp.curveToAbsoluteVolume(s, MAX_STEPS, MIN_ABS, MAX_ABS, Prefs.CURVE_LINEAR)
            val back = Avrcp.curveToSystemStep(v, MAX_STEPS, MIN_ABS, MAX_ABS, Prefs.CURVE_LINEAR)
            assertTrue("s=$s back=$back", abs(back - s) <= 1)
        }
    }

    @Test
    fun systemStep_inverseGuard() {
        assertEquals(0, Avrcp.curveToSystemStep(0, MAX_STEPS, MIN_ABS, MAX_ABS, Prefs.CURVE_LOG))
        assertEquals(MAX_STEPS, Avrcp.curveToSystemStep(MAX_ABS, MAX_STEPS, MIN_ABS, MAX_ABS, Prefs.CURVE_LOG))
    }

    /** 默认模式满量程（min=0、max=maxSteps）应恒等于直通。 */
    @Test
    fun defaultRangeToIndex_fullSpanPassthrough() {
        for (s in 1..MAX_STEPS) {
            assertEquals(s, Avrcp.defaultRangeToIndex(s, MAX_STEPS, 0, MAX_STEPS))
        }
    }

    @Test
    fun defaultRangeToIndex_monotonicAndBounded() {
        var prev = -1
        for (s in 1..MAX_STEPS) {
            val v = Avrcp.defaultRangeToIndex(s, MAX_STEPS, 5, 80)
            assertTrue("bounded $v", v in 0..MAX_STEPS)
            assertTrue("monotonic s=$s", v >= prev)
            prev = v
        }
    }

    /** 模式B 直映射端点：step=1 命中下限（min≥1 时精确=min），step=maxSteps 命中上限。 */
    @Test
    fun curveToSystemIndex_endpoints() {
        assertEquals(0, Avrcp.curveToSystemIndex(0, MAX_STEPS, 5, 80, Prefs.CURVE_LOG))
        assertEquals(5, Avrcp.curveToSystemIndex(1, MAX_STEPS, 5, 80, Prefs.CURVE_LOG))
        assertEquals(80, Avrcp.curveToSystemIndex(MAX_STEPS, MAX_STEPS, 5, 80, Prefs.CURVE_LOG))
    }

    /** v1.1.8：min=0 不再自适应抬底，第 1 档即 0（三种曲线都成立）。 */
    @Test
    fun minZero_firstStepIsZero_allCurves() {
        for (curve in intArrayOf(Prefs.CURVE_LOG, Prefs.CURVE_LINEAR, Prefs.CURVE_SQRT)) {
            assertEquals("abs curve=$curve", 0, Avrcp.curveToAbsoluteVolume(1, MAX_STEPS, 0, MAX_ABS, curve))
            assertEquals("sys curve=$curve", 0, Avrcp.curveToSystemIndex(1, MAX_STEPS, 0, 80, curve))
        }
    }

    /** min=0 时上限仍精确命中，且单调不降（模式A/B 同口径）。 */
    @Test
    fun minZero_maxEndpointAndMonotonic() {
        assertEquals(MAX_ABS, Avrcp.curveToAbsoluteVolume(MAX_STEPS, MAX_STEPS, 0, MAX_ABS, Prefs.CURVE_LOG))
        assertEquals(80, Avrcp.curveToSystemIndex(MAX_STEPS, MAX_STEPS, 0, 80, Prefs.CURVE_LOG))
        var prev = -1
        for (s in 1..MAX_STEPS) {
            val v = Avrcp.curveToSystemIndex(s, MAX_STEPS, 0, 80, Prefs.CURVE_LOG)
            assertTrue("monotonic s=$s v=$v", v >= prev)
            prev = v
        }
        prev = -1
        for (s in 1..MAX_STEPS) {
            val v = Avrcp.curveToAbsoluteVolume(s, MAX_STEPS, 0, MAX_ABS, Prefs.CURVE_LOG)
            assertTrue("monotonic abs s=$s v=$v", v >= prev)
            prev = v
        }
    }

    /** min=0 对数曲线保持曲线形状（(v+1) 空间等比）：中点/四分点明显低于线性对应值，不退化为直线。 */
    @Test
    fun minZero_logKeepsCurveShape() {
        // 线性中点≈64 / 40；(128)^e-1 与 (81)^e-1 在 e≈0.495 处≈10 / 8
        val midAbs = Avrcp.curveToAbsoluteVolume(50, MAX_STEPS, 0, MAX_ABS, Prefs.CURVE_LOG)
        assertTrue("abs mid=$midAbs", midAbs < 20)
        val midSys = Avrcp.curveToSystemIndex(50, MAX_STEPS, 0, 80, Prefs.CURVE_LOG)
        assertTrue("sys mid=$midSys", midSys < 15)
        val quarterAbs = Avrcp.curveToAbsoluteVolume(25, MAX_STEPS, 0, MAX_ABS, Prefs.CURVE_LOG)
        assertTrue("abs quarter=$quarterAbs", quarterAbs < 16) // 线性四分点≈32
    }

    /** min=0 对数正/反往返：严格递增的上半程舍入误差≤±1 档（低音量平台区多档同值属预期）。 */
    @Test
    fun minZero_log_roundTripUpperHalf() {
        for (s in 60..MAX_STEPS) {
            val v = Avrcp.curveToAbsoluteVolume(s, MAX_STEPS, 0, MAX_ABS, Prefs.CURVE_LOG)
            val back = Avrcp.curveToSystemStep(v, MAX_STEPS, 0, MAX_ABS, Prefs.CURVE_LOG)
            assertTrue("abs s=$s v=$v back=$back", abs(back - s) <= 1)
            val y = Avrcp.curveToSystemIndex(s, MAX_STEPS, 0, 80, Prefs.CURVE_LOG)
            val backSys = Avrcp.curveToSystemStep(y, MAX_STEPS, 0, 80, Prefs.CURVE_LOG)
            assertTrue("sys s=$s y=$y back=$backSys", abs(backSys - s) <= 1)
        }
    }

    /** min≥1 行为不变：第 1 档精确=min。 */
    @Test
    fun minPositive_firstStepExact() {
        assertEquals(5, Avrcp.curveToAbsoluteVolume(1, MAX_STEPS, 5, MAX_ABS, Prefs.CURVE_LOG))
        assertEquals(5, Avrcp.curveToSystemIndex(1, MAX_STEPS, 5, 80, Prefs.CURVE_LOG))
    }

    companion object {
        private const val MAX_STEPS = 100
        private const val MIN_ABS = 10 // 端点确定（min≥1 精确命中）
        private const val MAX_ABS = 127
    }
}
