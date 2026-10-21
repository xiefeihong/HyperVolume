package com.xiefeihong.hypervolume.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份存档格式单测（导出 → 导入的往返一致性 + 非法存档拒绝）。
 *
 * 编解码已从 [BackupEngine] 拆到 [BackupFormat]（不碰 Context / root），因此可在 JVM 直接跑，
 * 无需 Robolectric；`org.json` 由 `testImplementation(libs.json)` 提供真实实现。
 */
class BackupFormatTest {

    private val xml = "<config><steps value=\"25\"/></config>"

    private fun backupOf(vararg pairs: Pair<String, Any?>): String =
        BackupFormat.build(linkedMapOf(*pairs), xml, "com.xiefeihong.hypervolume")

    @Test
    fun roundTripKeepsValueTypes() {
        val source = linkedMapOf<String, Any?>(
            "config_xml" to xml,
            "ui_language" to "en",
            "system_default_steps" to 15,
            "system_default_ack_at" to 1_730_000_000_000L,
            "curve_ratio" to 0.5f,
            "system_default_ack" to true,
            "favorite_devices" to setOf("WF-1000XM4", "AirPods Pro"),
        )
        val content = BackupFormat.parse(BackupFormat.build(source, xml, "com.xiefeihong.hypervolume"))

        assertEquals(source.size, content.recordCount)
        assertEquals(xml, content.preferences["config_xml"])
        assertEquals("en", content.preferences["ui_language"])
        assertEquals(15, content.preferences["system_default_steps"])
        assertEquals(1_730_000_000_000L, content.preferences["system_default_ack_at"])
        assertEquals(0.5f, content.preferences["curve_ratio"])
        assertEquals(true, content.preferences["system_default_ack"])
        assertEquals(setOf("WF-1000XM4", "AirPods Pro"), content.preferences["favorite_devices"])
        // 生效配置原文逐字保留（含引号与尖括号）
        assertEquals(xml, content.effectiveXml)
    }

    @Test
    fun exportIsIndentedReadableJsonWithEnvelope() {
        val text = backupOf("ui_language" to "zh")
        assertTrue(text, text.contains("\"format\": \"HyperVolume-backup\""))
        assertTrue(text, text.contains("\"version\": 1"))
        assertTrue(text, text.contains("\"package\": \"com.xiefeihong.hypervolume\""))
        assertTrue(text, text.contains("\"createdAt\""))
    }

    @Test
    fun unsupportedValueTypesAreSkipped() {
        // Double / 未知类型不属于 SharedPreferences 支持的取值，导出时被丢弃
        val content = BackupFormat.parse(backupOf("a" to 1.0, "b" to null, "ui_language" to "system"))
        assertEquals(1, content.recordCount)
        assertEquals("system", content.preferences["ui_language"])
    }

    @Test
    fun unknownRecordTypeMarkerIsIgnored() {
        val text = """
            {"format":"HyperVolume-backup","version":1,"global":"",
             "prefs":[{"k":"bad","t":"X","v":1},{"k":"ui_language","t":"s","v":"en"}]}
        """.trimIndent()
        val content = BackupFormat.parse(text)
        assertEquals(1, content.recordCount)
        assertNull(content.preferences["bad"])
    }

    @Test
    fun foreignJsonIsRejected() {
        assertRejected("""{"hello":"world"}""")
    }

    @Test
    fun newerArchiveVersionIsRejected() {
        assertRejected("""{"format":"HyperVolume-backup","version":9,"prefs":[{"k":"a","t":"s","v":"b"}]}""")
    }

    @Test
    fun missingOrEmptyRecordsAreRejected() {
        assertRejected("""{"format":"HyperVolume-backup","version":1}""")
        assertRejected("""{"format":"HyperVolume-backup","version":1,"prefs":[]}""")
    }

    @Test
    fun plainTextIsRejected() {
        assertRejected("not a json at all")
        assertRejected("")
    }

    private fun assertRejected(text: String) {
        try {
            BackupFormat.parse(text)
            assertTrue("应当拒绝：$text", false)
        } catch (t: IllegalArgumentException) {
            // 界面据此提示「不是有效的备份文件」，异常信息只进日志
            assertTrue(true)
        }
    }

    @Test
    fun codecWritesBackEachSupportedType() {
        val editor = FakeEditor()
        PreferenceCodec.putAll(
            editor,
            linkedMapOf(
                "s" to "text",
                "i" to 3,
                "l" to 4L,
                "f" to 0.25f,
                "b" to false,
                "u" to setOf("x", "y"),
            ),
        )
        assertEquals("text", editor.stored["s"])
        assertEquals(3, editor.stored["i"])
        assertEquals(4L, editor.stored["l"])
        assertEquals(0.25f, editor.stored["f"])
        assertEquals(false, editor.stored["b"])
        assertEquals(setOf("x", "y"), editor.stored["u"])
    }

    /** 只记录调用的假编辑器：验证类型分派，不需要真实 SharedPreferences。 */
    private class FakeEditor : SharedPreferences.Editor {
        val stored = LinkedHashMap<String, Any?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { stored[key!!] = value }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { stored[key!!] = values?.toSet() }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { stored[key!!] = value }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { stored[key!!] = value }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { stored[key!!] = value }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { stored[key!!] = value }

        override fun remove(key: String?): SharedPreferences.Editor = apply { stored.remove(key) }

        override fun clear(): SharedPreferences.Editor = apply { stored.clear() }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
