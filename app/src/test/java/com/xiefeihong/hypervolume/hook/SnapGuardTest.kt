package com.xiefeihong.hypervolume.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「拖后按键」吸附的 Δ 门限单测。
 *
 * 回归目标：旧判据只有下界（`|delta| < minKeyDelta` 放行），拖动**首条**采样没有上一条
 * 媒体变更可比、会漏过密集判据，于是 `request 71, cur 16`（|Δ|=55）被吸附成 20，
 * 音量从 71% 被拉回 20%（真机日志实证）。
 */
class SnapGuardTest {

    // levels=100 segs=25 → 步长 4、下界 max(2, 3) = 3 → 有效区间 [3, 8]
    @Test
    fun oneGridStep_isKeyPress() = assertTrue(isKeyPressDelta(4, 100, 25))

    @Test
    fun negativeOneGridStep_isKeyPress() = assertTrue(isKeyPressDelta(-4, 100, 25))

    @Test
    fun twoGridSteps_isStillKeyPress() = assertTrue(isKeyPressDelta(8, 100, 25))

    @Test
    fun threeGridSteps_isDrag() = assertFalse(isKeyPressDelta(9, 100, 25))

    @Test
    fun tinyDelta_isSystemWriteBack() {
        assertFalse(isKeyPressDelta(0, 100, 25))
        assertFalse(isKeyPressDelta(1, 100, 25))
        assertFalse(isKeyPressDelta(-1, 100, 25))
    }

    @Test
    fun realDragSample_isPassedThrough() {
        // 真机：snap up: request 71, cur 16 -> 20
        assertFalse(isKeyPressDelta(55, 100, 25))
        assertFalse(isKeyPressDelta(-55, 100, 25))
    }

    @Test
    fun stepRoundsDownWhenLevelsNotDivisible() {
        // segs=30 → 步长 3、下界 3 → 有效区间 [3, 6]
        assertEquals(3, gridStepOf(100, 30))
        assertTrue(isKeyPressDelta(6, 100, 30))
        assertFalse(isKeyPressDelta(7, 100, 30))
    }

    @Test
    fun denseGridOrUnknownLevels_disablesSnap() {
        // 网格不稀疏（segs >= levels）→ 逐级即正确，不需要吸附
        assertFalse(isKeyPressDelta(4, 100, 100))
        assertFalse(isKeyPressDelta(4, 0, 25))
        assertEquals(1, gridStepOf(100, 0))
        assertEquals(1, gridStepOf(3, 25))
    }
}
