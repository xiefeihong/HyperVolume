package com.xiefeihong.hypervolume.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * modeB 阻断日志的文本单测。
 *
 * 回归目标：旧实现按档位变化去重（`index != sLastBlockedIndex`）而拖动时档位持续变化，
 * 一次音量手势打出几十条，整份模块日志 64/98 行都是这一句。现在每次开机只留首条，
 * 之后按 3s 静默窗口汇总成一条计数。
 */
class BlockedLogTest {

    @Test
    fun first_hasSlotAndIndex() {
        assertEquals(
            "modeB: block avrcp volume forward (first, index=20)",
            blockedFirstText(20),
        )
    }

    @Test
    fun summary_hasCountSpanAndLastIndex() {
        assertEquals(
            "modeB: blocked 27 forwards in 8123ms (last=46)",
            blockedSummaryText(count = 27, spanMs = 8123, lastIndex = 46),
        )
    }

    @Test
    fun summary_singleEventIsSuppressed() {
        // 1 次阻断已由首条日志覆盖，再补一条「blocked 1 forwards in 0ms」只是噪声
        assertNull(blockedSummaryText(count = 1, spanMs = 0, lastIndex = 97))
        assertNull(blockedSummaryText(count = 0, spanMs = 0, lastIndex = 97))
    }
}
