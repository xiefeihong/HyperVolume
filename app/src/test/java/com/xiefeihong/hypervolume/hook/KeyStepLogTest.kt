package com.xiefeihong.hypervolume.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 音量键步进日志文本的纯函数单测。
 *
 * 回归目标：旧实现 `"key step: " + if (raise) "+" else "-" + (newIndex - oldIndex)` 因 Kotlin 的
 * `+` 优先级把 else 分支吞进字符串拼接，导致升档只剩 `+`、降档出现 `--4`（真机日志实证）。
 */
class KeyStepLogTest {

    @Test
    fun firstRaise_hasDirectionValuesAndPercent() {
        assertEquals(
            "key step applied: RAISE 43 -> 47 (+4) levels=100 segs=25 (~47%)",
            keyStepLog(raise = true, oldIndex = 43, newIndex = 47, levels = 100, segs = 25, first = true),
        )
    }

    @Test
    fun firstLower_hasDirectionValuesAndPercent() {
        assertEquals(
            "key step applied: LOWER 47 -> 43 (-4) levels=100 segs=25 (~43%)",
            keyStepLog(raise = false, oldIndex = 47, newIndex = 43, levels = 100, segs = 25, first = true),
        )
    }

    @Test
    fun laterRaise_keepsDelta() {
        assertEquals(
            "key step: 43 -> 47 (+4)",
            keyStepLog(raise = true, oldIndex = 43, newIndex = 47, levels = 100, segs = 25, first = false),
        )
    }

    @Test
    fun laterLower_neverDoubleMinus() {
        val text = keyStepLog(raise = false, oldIndex = 40, newIndex = 36, levels = 100, segs = 25, first = false)
        assertEquals("key step: 40 -> 36 (-4)", text)
        assertFalse(text.contains("--"))
        assertFalse(text.endsWith(" "))
    }
}
