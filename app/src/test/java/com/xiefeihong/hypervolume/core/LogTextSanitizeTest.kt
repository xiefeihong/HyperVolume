package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 日志文本 NUL 剥离的纯函数单测。
 *
 * 背景：历史版本用「写一个 0 字节」探测日志文件可写性，文件首字节是 NUL；`tail` 原样带出后
 * `trim()` 去不掉（NUL 非空白），复制到剪贴板会让接收端整段乱码。
 */
class LogTextSanitizeTest {

    @Test
    fun leadingNulIsStripped() {
        // 真实形态：段标题 + 换行 + NUL + 第一条日志
        assertEquals(
            "[系统框架日志]\n[I] 10-28 22:30:01.708 module loaded in system",
            sanitizeLogText("[系统框架日志]\n\u0000[I] 10-28 22:30:01.708 module loaded in system"),
        )
    }

    @Test
    fun multipleNulsAreStripped() {
        assertEquals("a\nb\nc", sanitizeLogText("a\n\u0000b\nc\u0000"))
    }

    @Test
    fun cleanTextIsReturnedAsIs() {
        val clean = "[I] 10-28 22:30:01.708 module loaded"
        assertSame(clean, sanitizeLogText(clean))
    }
}
