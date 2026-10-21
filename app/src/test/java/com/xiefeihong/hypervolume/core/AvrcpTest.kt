package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [Avrcp] 曲线正/反函数与档位映射的 JVM 单元测试。
 * 覆盖：端点精确命中、单调性、值域钳制、applyCurve↔invertCurve 往返（线性口径）。
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

    companion object {
        private const val MAX_STEPS = 100
        private const val MIN_ABS = 10 // 避开 minAbs=0 的自适应下限，端点确定
        private const val MAX_ABS = 127
    }
}
