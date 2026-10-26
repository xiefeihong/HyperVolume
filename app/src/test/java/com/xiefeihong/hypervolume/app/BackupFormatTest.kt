package com.xiefeihong.hypervolume.app

import android.content.SharedPreferences
import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.VolumeConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份存档格式单测（导出 → 导入的往返一致性 + 非法存档拒绝）。
 *
 * 编解码已从 [BackupEngine] 拆到 [BackupFormat]（不碰 Context / root），因此可在 JVM 直接跑，
 * 无需 Robolectric；`org.json` 由 `testImplementation(libs.json)` 提供真实实现。
 *
 * v3 口径：配置只有 `global` 那一份，旧版残留的 `config_json` 偏好不导出也不写回。
 */
class BackupFormatTest {

    /** 真配置序列化出的格式化串：既能被 [VolumeConfig.fromJson] 解析，也保证 `global` 走「嵌套对象」分支。 */
    private val config = VolumeConfig(
        true, 50, 15,
        DeviceConfig(Prefs.BT_MODE_DEFAULT, Range(0, 127, 0), Range(0, 100, 0), Range(0, 50, 0)),
        DeviceConfig(Prefs.BT_MODE_SOFTWARE, Range(0, 127, 0), Range(1, 40, 1), Range(0, 100, 0)),
        DeviceConfig(Prefs.BT_MODE_ABSOLUTE, Range(10, 127, 2), Range(0, 100, 0), Range(0, 100, 0)),
    )
    private val configJson: String = config.toJson()

    private fun backupOf(vararg pairs: Pair<String, Any?>): String =
        BackupFormat.build(linkedMapOf(*pairs), configJson, PACKAGE)

    /** 配置值经「对象 → 串」往返后键序可能变化（JSONObject 内部不保证顺序），故按解析语义比对。 */
    private fun sameConfig(left: String?, right: String?): Boolean =
        VolumeConfig.fromJson(left)?.toJson() == VolumeConfig.fromJson(right)?.toJson()

    @Test
    fun roundTripKeepsValueTypesAndDropsLegacyConfigKey() {
        val source = linkedMapOf<String, Any?>(
            "config_json" to configJson,
            "ui_language" to "en",
            "system_default_steps" to 15,
            "system_default_ack_at" to 1_730_000_000_000L,
            "curve_ratio" to 0.5f,
            "system_default_ack" to true,
            "favorite_devices" to setOf("WF-1000XM4", "AirPods Pro"),
        )
        val content = BackupFormat.parse(BackupFormat.build(source, configJson, PACKAGE))

        // 遗留的 config_json 不导出也不写回：存档里只有 global 那一份配置
        assertEquals(source.size - 1, content.recordCount)
        assertNull("遗留配置键不该进存档", content.preferences["config_json"])
        assertEquals(configJson, content.effectiveJson)
        assertEquals("en", content.preferences["ui_language"])
        assertEquals(15, content.preferences["system_default_steps"])
        assertEquals(1_730_000_000_000L, content.preferences["system_default_ack_at"])
        assertEquals(0.5f, content.preferences["curve_ratio"])
        assertEquals(true, content.preferences["system_default_ack"])
        assertEquals(setOf("WF-1000XM4", "AirPods Pro"), content.preferences["favorite_devices"])
        // 系统侧那唯一一份配置进出无损
        assertTrue(sameConfig(configJson, content.effectiveJson))
    }

    @Test
    fun theSingleConfigIsWrittenAsObjectAndNoCopyRidesInPrefs() {
        val text = backupOf("ui_language" to "en", "config_json" to configJson)
        // 唯一一份配置写成嵌套对象，用户可直接阅读/改档位
        assertTrue(JSONObject(text).get("global") is JSONObject)
        // 偏好里残有旧暂存键时也不写进存档（不至于出现第二份配置）
        assertFalse(text, text.contains("\"config_json\""))
        // 对象形式落盘，全文不该出现转义引号
        assertFalse(text, text.contains("\\\""))
    }

    @Test
    fun versionOneAndTwoArchivesStillImport() {
        // 旧存档把配置存成转义字符串且往 prefs 里写了一份副本：配置照旧从 global 恢复，副本丢弃
        val archive = JSONObject()
            .put("format", "HyperVolume-backup")
            .put("version", 1)
            .put("global", configJson)
            .put(
                "prefs",
                JSONArray().put(
                    JSONObject().put("k", "config_json").put("t", "s").put("v", configJson),
                ),
            )
            .toString()
        val content = BackupFormat.parse(archive)
        assertEquals(configJson, content.effectiveJson)
        assertNull(content.preferences["config_json"])
        // 偏好记录全被丢弃时，只要 global 有配置仍算可恢复
        assertEquals(0, content.recordCount)
    }

    @Test
    fun unparsableConfigTextIsKeptAsPlainString() {
        // 畸形配置文本解析不出对象：原样保留，不静默丢数据；无生效配置时 global 为空串
        val text = BackupFormat.build(linkedMapOf("ui_language" to "en"), "{oops", PACKAGE)
        assertEquals("{oops", JSONObject(text).get("global"))
        assertEquals("{oops", BackupFormat.parse(text).effectiveJson)
        val empty = BackupFormat.build(linkedMapOf("ui_language" to "en"), "", PACKAGE)
        assertEquals("", BackupFormat.parse(empty).effectiveJson)
    }

    @Test
    fun exportIsIndentedReadableJsonWithEnvelope() {
        val text = backupOf("ui_language" to "zh")
        assertTrue(text, text.contains("\"format\": \"HyperVolume-backup\""))
        assertTrue(text, text.contains("\"version\": 3"))
        assertTrue(text, text.contains("\"package\": \"$PACKAGE\""))
        assertTrue(text, text.contains("\"createdAt\""))
    }

    @Test
    fun codecFiltersLegacyConfigKeyOnBothEnds() {
        // 导出端：遗留配置键直接不成记录；导入端：旧存档带着它也走不到写回偏好
        assertNull(PreferenceCodec.encode("config_json", configJson))
        assertNull(PreferenceCodec.decode(JSONObject().put("k", "config_json").put("t", "s").put("v", configJson)))
        // 其余字符串键照常保留（语言 tag 等）
        assertEquals("ui_language" to "en", PreferenceCodec.decode(JSONObject().put("k", "ui_language").put("t", "s").put("v", "en")))
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
            {"format":"HyperVolume-backup","version":2,"global":"",
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
        assertRejected("""{"format":"HyperVolume-backup","version":2}""")
        assertRejected("""{"format":"HyperVolume-backup","version":2,"prefs":[]}""")
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
    fun bootAndPerfFactKeysRoundTripWithStrongTypes() {
        // v1.1.5 新增的三个环境事实键（开机脚本 active/steps + 性能日志开关）属 Boolean/Int，
        // 与 system_default_steps 同类，自动被 codec 强类型往返覆盖（不降级为字符串）。
        val source = linkedMapOf<String, Any?>(
            "boot_script_active" to true,
            "boot_script_steps" to 45,
            "perf_log" to false,
        )
        val content = BackupFormat.parse(BackupFormat.build(source, configJson, PACKAGE))
        assertEquals(3, content.recordCount)
        assertEquals(true, content.preferences["boot_script_active"])
        assertEquals(45, content.preferences["boot_script_steps"])
        assertEquals(false, content.preferences["perf_log"])
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

    private companion object {
        const val PACKAGE = "com.xiefeihong.hypervolume"
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
