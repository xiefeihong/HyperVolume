package com.xiefeihong.hypervolume.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置备份与恢复（JSON 存档，对齐 HyperCeiler 的「备份与恢复」职责）。
 *
 * 存档内容两块：
 *  - `prefs`：本模块 SharedPreferences 的全部键值（业务暂存配置 + 档位基线 + 语言/颜色等界面偏好），
 *    值带类型标记（SharedPreferences 支持 String/Int/Long/Boolean/StringSet 五种）；
 *  - `global`：系统侧**已生效**的配置 XML（`Settings.Global` 中本模块键的原文），
 *    恢复时据此比对「有未保存的修改」，并可选回写系统。
 *
 * 解析与写回都是纯数据操作（不碰 root）；root 推送由调用方经 [SaveEngine.pushConfig] 完成，
 * 便于单测覆盖格式校验与类型还原。
 */
class BackupEngine(context: Context, private val settings: SettingsStore) {

    private val appContext: Context = context.applicationContext

    /** 导出当前全部偏好 + 系统侧生效配置，返回可直接写盘的 JSON 文本。 */
    fun export(effectiveXml: String): String =
        BackupFormat.build(settings.allPreferences(), effectiveXml, appContext.packageName)

    /**
     * 解析存档文本；格式不符（非本模块备份 / 版本过新 / 记录结构非法）抛
     * [IllegalArgumentException]，由界面提示「不是有效的备份文件」。
     */
    fun parse(text: String): BackupContent = BackupFormat.parse(text)

    /** 把存档记录写回本地偏好（先清空，避免残留旧键与新配置冲突）。 */
    fun applyPreferences(content: BackupContent) {
        settings.restorePreferences(content.preferences)
    }
}

/**
 * 存档格式的编解码（纯数据，不碰 Context / root，便于单测直接断言 JSON 往返）。
 */
internal object BackupFormat {

    /** 存档格式标记：导入时据此拒绝任意 JSON 文件。 */
    private const val FORMAT = "format"
    private const val FORMAT_VALUE = "HyperVolume-backup"

    /** 存档结构版本（未来加字段时递增，过新的存档拒绝导入）。 */
    private const val VERSION = "version"
    private const val BACKUP_VERSION = 1

    private const val CREATED_AT = "createdAt"
    private const val PACKAGE = "package"
    private const val PREFS = "prefs"
    private const val GLOBAL = "global"

    /** 缩进两空格，便于用户直接查看/对比存档。 */
    private const val INDENT = 2

    private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** 组装存档文本（不受支持的偏好类型由 [PreferenceCodec] 跳过）。 */
    fun build(preferences: Map<String, Any?>, effectiveXml: String, packageName: String): String {
        val array = JSONArray()
        for ((key, value) in preferences) {
            PreferenceCodec.encode(key, value)?.let { array.put(it) }
        }
        return JSONObject().apply {
            put(FORMAT, FORMAT_VALUE)
            put(VERSION, BACKUP_VERSION)
            put(CREATED_AT, ISO_FORMAT.format(Date()))
            put(PACKAGE, packageName)
            put(PREFS, array)
            put(GLOBAL, effectiveXml)
        }.toString(INDENT)
    }

    /** 解析并校验存档；不合规则抛 [IllegalArgumentException]。 */
    fun parse(text: String): BackupContent {
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw IllegalArgumentException("不是 JSON 对象", t)
        }
        require(root.optString(FORMAT) == FORMAT_VALUE) { "备份格式标记不匹配" }
        require(root.optInt(VERSION) <= BACKUP_VERSION) { "备份版本过新（${root.optInt(VERSION)}）" }
        val records = root.optJSONArray(PREFS) ?: throw IllegalArgumentException("缺少偏好记录")
        val values = LinkedHashMap<String, Any?>()
        for (i in 0 until records.length()) {
            val decoded = PreferenceCodec.decode(records.optJSONObject(i)) ?: continue
            values[decoded.first] = decoded.second
        }
        require(values.isNotEmpty()) { "备份中没有可恢复的记录" }
        return BackupContent(
            recordCount = values.size,
            preferences = values,
            effectiveXml = root.optString(GLOBAL, ""),
        )
    }
}

/** 一份解析完成的备份内容。 */
data class BackupContent(
    val recordCount: Int,
    val preferences: Map<String, Any?>,
    /** 备份时的系统侧生效配置 XML（空串表示备份时无 root / 无配置）。 */
    val effectiveXml: String,
)

/**
 * SharedPreferences 值的 JSON 编解码：以单字符类型标记保住强类型
 * （`config_xml` 是 String、`system_default_steps` 是 Int、`system_default_ack` 是 Boolean）。
 */
internal object PreferenceCodec {

    private const val KEY = "k"
    private const val TYPE = "t"
    private const val VALUE = "v"

    private const val TYPE_STRING = "s"
    private const val TYPE_INT = "i"
    private const val TYPE_LONG = "l"
    private const val TYPE_FLOAT = "f"
    private const val TYPE_BOOLEAN = "b"
    private const val TYPE_SET = "u"

    /** 单条记录 → JSON；类型不受支持（理论上不会发生）返回 null 由调用方跳过。 */
    fun encode(key: String, value: Any?): JSONObject? = when (value) {
        null -> null
        is String -> JSONObject().put(KEY, key).put(TYPE, TYPE_STRING).put(VALUE, value)
        is Int -> JSONObject().put(KEY, key).put(TYPE, TYPE_INT).put(VALUE, value)
        is Long -> JSONObject().put(KEY, key).put(TYPE, TYPE_LONG).put(VALUE, value)
        is Float -> JSONObject().put(KEY, key).put(TYPE, TYPE_FLOAT).put(VALUE, value.toDouble())
        is Boolean -> JSONObject().put(KEY, key).put(TYPE, TYPE_BOOLEAN).put(VALUE, value)
        is Set<*> -> JSONObject().put(KEY, key).put(TYPE, TYPE_SET)
            .put(VALUE, JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    /** 单条 JSON → 键值；结构非法或类型未知返回 null（跳过该条，不影响其余记录）。 */
    fun decode(entry: JSONObject?): Pair<String, Any?>? {
        val key = entry?.optString(KEY)?.takeIf { it.isNotEmpty() } ?: return null
        // optString 对 JSON null 返回 "null" 字面量，这里一并挡掉
        if (key == "null") return null
        val value: Any? = when (entry.optString(TYPE)) {
            TYPE_STRING -> entry.optString(VALUE)
            TYPE_INT -> entry.optInt(VALUE)
            TYPE_LONG -> entry.optLong(VALUE)
            TYPE_FLOAT -> entry.optDouble(VALUE).toFloat()
            TYPE_BOOLEAN -> entry.optBoolean(VALUE)
            TYPE_SET -> {
                val array = entry.optJSONArray(VALUE) ?: return null
                buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) } }
            }
            else -> return null
        }
        return key to value
    }

    /** 按类型写回编辑器（`apply()` 由调用方决定）。 */
    fun putAll(editor: SharedPreferences.Editor, values: Map<String, Any?>) {
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
    }
}
