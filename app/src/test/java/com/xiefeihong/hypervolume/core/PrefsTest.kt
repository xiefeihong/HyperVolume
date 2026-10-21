package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Prefs] 纯整数计算（clamp / 按键网格 / 步进吸附）的 JVM 单元测试。
 * 覆盖边界：keySteps≥levels（网格退化）、round 碰撞、越界钳制。
 */
class PrefsTest {

    @Test
    fun clampMediaSteps_boundsRange() {
        assertEquals(10, Prefs.clampMediaSteps(5))
        assertEquals(10, Prefs.clampMediaSteps(Prefs.MEDIA_STEPS_MIN))
        assertEquals(100, Prefs.clampMediaSteps(Prefs.MEDIA_STEPS_MAX))
        assertEquals(100, Prefs.clampMediaSteps(150))
        assertEquals(50, Prefs.clampMediaSteps(50))
    }

    @Test
    fun clampKeySteps_boundsRange() {
        assertEquals(10, Prefs.clampKeySteps(5))
        assertEquals(29, Prefs.clampKeySteps(40))
        assertEquals(15, Prefs.clampKeySteps(15))
    }

    @Test
    fun clampSystemStep_respectsMaxSteps() {
        assertEquals(0, Prefs.clampSystemStep(5, 0)) // maxSteps<=0 → 0
        assertEquals(0, Prefs.clampSystemStep(-3, 100))
        assertEquals(100, Prefs.clampSystemStep(150, 100))
        assertEquals(50, Prefs.clampSystemStep(50, 100))
    }

    @Test
    fun clampAbs_and_clampCurve() {
        assertEquals(0, Prefs.clampAbs(-1))
        assertEquals(127, Prefs.clampAbs(200))
        assertEquals(60, Prefs.clampAbs(60))
        assertEquals(0, Prefs.clampCurve(-1))
        assertEquals(Prefs.CURVE_SQRT, Prefs.clampCurve(9))
    }

    @Test
    fun keyDelta_basic() {
        assertEquals(4, Prefs.keyDelta(100, 25))
        assertEquals(10, Prefs.keyDelta(100, 10))
        assertEquals(1, Prefs.keyDelta(100, 200)) // round(0.5)=1，且下限 1
        assertEquals(100, Prefs.keyDelta(100, 0)) // keySteps<=0 → max(1,levels)
    }

    @Test
    fun keyStepLevel_endpointsAndGuards() {
        assertEquals(0, Prefs.keyStepLevel(0, 100, 25))
        assertEquals(100, Prefs.keyStepLevel(25, 100, 25))
        assertEquals(0, Prefs.keyStepLevel(5, 0, 25)) // levels<=0 → 0
        assertEquals(7, Prefs.keyStepLevel(7, 100, 0)) // keySteps<=0 → clamp(seg,0,levels)
    }

    @Test
    fun keyStepLevel_monotonicAndBounded() {
        val levels = 100
        val keySteps = 25
        var prev = -1
        for (seg in 0..keySteps) {
            val lvl = Prefs.keyStepLevel(seg, levels, keySteps)
            assertTrue("bounded: $lvl", lvl in 0..levels)
            assertTrue("monotonic at seg=$seg", lvl >= prev)
            prev = lvl
        }
        assertEquals(levels, prev)
    }

    @Test
    fun nextKeyStepUp_strictlyIncreasesWithinBounds() {
        val levels = 100
        val keySteps = 20
        for (cur in 0 until levels) {
            val nxt = Prefs.nextKeyStepUp(cur, levels, keySteps)
            assertTrue("$cur -> $nxt", nxt > cur && nxt <= levels)
        }
        assertEquals(levels, Prefs.nextKeyStepUp(levels, levels, keySteps))
    }

    @Test
    fun nextKeyStepDown_strictlyDecreasesWithinBounds() {
        val levels = 100
        val keySteps = 20
        for (cur in 1..levels) {
            val nxt = Prefs.nextKeyStepDown(cur, levels, keySteps)
            assertTrue("$cur -> $nxt", nxt < cur && nxt >= 0)
        }
        assertEquals(0, Prefs.nextKeyStepDown(0, levels, keySteps))
    }

    @Test
    fun nextKeyStep_gridDegenerateStepsOneByOne() {
        // keySteps>=levels → 网格间距<1，退化为逐级 ±1
        assertEquals(6, Prefs.nextKeyStepUp(5, 10, 10))
        assertEquals(4, Prefs.nextKeyStepDown(5, 10, 10))
        assertEquals(10, Prefs.nextKeyStepUp(10, 10, 10)) // 到顶
        assertEquals(0, Prefs.nextKeyStepDown(0, 10, 10)) // 到底
    }
}
