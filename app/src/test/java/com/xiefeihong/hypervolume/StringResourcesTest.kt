package com.xiefeihong.hypervolume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字符串资源完整性单测：双语落地后，资源表就是文案的唯一来源，因此把「键齐全、
 * 参数对齐、百分号转义正确」这四条固化为测试，避免以后加文案时漏翻译或写错占位符
 * （这类错误在编译期不报错，只在切换到英文时静默显示异常）。
 */
class StringResourcesTest {

    private val zh by lazy { TestText.defaultEntries() }
    private val en by lazy { TestText.englishEntries() }

    @Test
    fun everyRStringKeyIsDefinedInDefaultLocale() {
        val fields = R.string::class.java.fields.map { it.name }.toSet()
        assertEquals("R.string 字段数与默认资源条目数不一致", fields.size, zh.size)
        val missing = fields.filter { it !in zh }
        assertTrue("默认资源缺失：$missing", missing.isEmpty())
    }

    @Test
    fun englishCoversEveryKey() {
        assertTrue("英文资源缺失键：${zh.keys - en.keys}", (zh.keys - en.keys).isEmpty())
        assertTrue("英文资源多出键（默认语言没有）：${en.keys - zh.keys}", (en.keys - zh.keys).isEmpty())
    }

    @Test
    fun positionalArgsMatchAcrossLocales() {
        val mismatched = zh.keys.filter { key ->
            TestText.argSpecs(zh.getValue(key)) != TestText.argSpecs(en.getValue(key))
        }
        assertTrue("中英位置参数不一致：$mismatched", mismatched.isEmpty())
    }

    @Test
    fun percentSignsAreWellFormed() {
        // 带实参的资源会被 String.format：字面百分号必须写成 %%；不带实参的原样输出：不得出现 %%
        val bad = buildList {
            for ((key, value) in zh) {
                val formatted = TestText.argSpecs(value).isNotEmpty()
                val rest = value.replace(Regex("%\\d\\$[ds]"), "").replace("%%", "")
                if (formatted && rest.contains('%')) add("$key：未转义的 %")
                if (!formatted && value.contains("%%")) add("$key：无实参却写了 %%")
            }
        }
        assertTrue("百分号用法错误：$bad", bad.isEmpty())
    }

    @Test
    fun noBlankEntries() {
        val blank = (zh.entries + en.entries).filter { it.value.isBlank() }.map { it.key }
        assertTrue("存在空文案：$blank", blank.isEmpty())
    }

    @Test
    fun lineBreaksAreEscapedNotRaw() {
        // 真实换行会被 aapt2 折叠成空格，多行文案（使用说明、备份范围等）因此整段铺成一行
        val offenders = TestText.rawNewlineKeys().filterValues { it.isNotEmpty() }
        assertTrue("这些条目写了真实换行，应改为 \\n 转义：$offenders", offenders.isEmpty())
    }
}
