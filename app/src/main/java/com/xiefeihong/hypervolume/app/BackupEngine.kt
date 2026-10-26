package com.xiefeihong.hypervolume.app

import android.content.Context
import android.content.SharedPreferences
import com.xiefeihong.hypervolume.core.VolumeConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置备份与恢复（JSON 存档，对齐 HyperCeiler 的「备份与恢复」职责）。
 *
 * 存档内容两块：
 *  - `global`：全应用**唯一的一份**配置——系统侧已生效的 `Settings.Global` 内容；
 *    恢复时只能落回系统（需 root），不再有本地暂存区可放；
 *  - `prefs`：本模块 SharedPreferences 的键值（**已不含配置**，只剩档位基线等环境事实
 *    与语言/颜色等界面偏好），值带类型标记（String/Int/Long/Float/Boolean/StringSet）。
 *
 * 配置本身就是一段 JSON，导出时一律**写成嵌套对象**（而非转义字符串），便于用户直接
 * 查看与改档位；导入时对象再经 [ConfigJson.normalize] 归一为全链路唯一的格式化串后推送系统。
 * 解析不出对象的（无配置 / 畸形）保持原文，不静默丢数据。
 *
 * 解析与写偏好都是纯数据操作（不碰 root）；root 推送由调用方经 [SaveEngine.pushConfig] 完成，
 * 便于单测覆盖格式校验与类型还原。
 */
class BackupEngine(context: Context, private val settings: SettingsStore) {

    private val appContext: Context = context.applicationContext

    /** 导出本地偏好（环境事实 + 界面偏好）与唯一的一份生效配置，返回可直接写盘的 JSON 文本。 */
    fun export(effectiveJson: String): String =
        BackupFormat.build(settings.allPreferences(), effectiveJson, appContext.packageName)

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

    /** 存档结构版本（未来加字段时递增，过新的存档拒绝导入）。
     * v2：配置值由转义字符串改写成嵌套 JSON 对象（v1 存档仍可导入）。
     * v3：偏好里不再含应用暂存配置，配置只有 `global` 一份（v1/v2 存档仍可导入，
     *     其中的 `config_json` 记录被丢弃）。 */
    private const val VERSION = "version"
    private const val BACKUP_VERSION = 3

    private const val CREATED_AT = "createdAt"
    private const val PACKAGE = "package"
    private const val PREFS = "prefs"
    private const val GLOBAL = "global"

    /** 缩进两空格，便于用户直接查看/对比存档。 */
    private const val INDENT = 2

    private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** 组装存档文本（不受支持的偏好类型由 [PreferenceCodec] 跳过）。 */
    fun build(preferences: Map<String, Any?>, effectiveJson: String, packageName: String): String {
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
            put(GLOBAL, ConfigJson.toObject(effectiveJson))
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
        val effectiveJson = ConfigJson.normalize(ConfigJson.toText(root.opt(GLOBAL)))
        // 旧存档的偏好里可能只剩被丢弃的 `config_json`：只要那唯一一份配置在，仍算可恢复
        require(values.isNotEmpty() || effectiveJson.isNotEmpty()) { "备份中没有可恢复的记录" }
        return BackupContent(
            recordCount = values.size,
            preferences = values,
            effectiveJson = effectiveJson,
        )
    }
}

/**
 * 配置文本与存档对象的互转：那唯一的一份配置（生效键）本身就是一段 JSON，
 * 导出时写成嵌套对象便于阅读与手工修改，导入时再归一回唯一的格式化串以便回写系统。
 *
 * 解析失败（空串、畸形、旧版直存文本）一律原样保留字符串，不静默丢数据。
 */
internal object ConfigJson {

    /** 文本 → 可解析则返回 [JSONObject]，否则返回原文（供 `JSONObject.put` 直接写入）。 */
    fun toObject(text: String): Any =
        runCatching { JSONObject(text) }.getOrElse { text }

    /** 存档里的值（对象或字符串）→ 配置文本；缺失返回空串。 */
    fun toText(value: Any?): String =
        if (value == null || value === JSONObject.NULL) "" else value.toString()

    /**
     * 配置文本的**归一化**：能解析成 [VolumeConfig] 的一律重写成全链路唯一的格式化串——
     * [toText] 拿到的是 `JSONObject.toString()`（无缩进），直接回写系统会让配置多出一种排版。
     * 解析不出的（空串 / 畸形 / 旧版直存文本）原样返回。
     */
    fun normalize(text: String): String = VolumeConfig.fromJson(text)?.toJson() ?: text
}

/** 一份解析完成的备份内容。 */
data class BackupContent(
    val recordCount: Int,
    val preferences: Map<String, Any?>,
    /** 备份时系统侧的那唯一一份配置（空串表示当时系统侧还没有配置）。 */
    val effectiveJson: String,
)

/**
 * SharedPreferences 值的 JSON 编解码：以单字符类型标记保住强类型
 * （`system_default_steps` 是 Int、`system_default_ack` 是 Boolean、语言 tag 是 String）。
 *
 * 配置已不再存于偏好，[LEGACY_CONFIG_KEY] 无论出现在导出还是旧存档里都直接丢弃，
 * 保证存档中只存在 `global` 那一份配置。
 */
internal object PreferenceCodec {

    private const val KEY = "k"
    private const val TYPE = "t"
    private const val VALUE = "v"

    /** 旧版的应用暂存键（升级安装后可能仍残在 settings.xml 里）：不导出也不写回。 */
    private const val LEGACY_CONFIG_KEY = "config_json"

    private const val TYPE_STRING = "s"
    private const val TYPE_INT = "i"
    private const val TYPE_LONG = "l"
    private const val TYPE_FLOAT = "f"
    private const val TYPE_BOOLEAN = "b"
    private const val TYPE_SET = "u"

    /** 单条记录 → JSON；遗留的配置键与不受支持的类型（理论上不会发生）返回 null 由调用方跳过。 */
    fun encode(key: String, value: Any?): JSONObject? = when (value) {
        null -> null
        is String -> if (key == LEGACY_CONFIG_KEY) null else JSONObject().put(KEY, key).put(TYPE, TYPE_STRING)
            .put(VALUE, value)
        is Int -> JSONObject().put(KEY, key).put(TYPE, TYPE_INT).put(VALUE, value)
        is Long -> JSONObject().put(KEY, key).put(TYPE, TYPE_LONG).put(VALUE, value)
        is Float -> JSONObject().put(KEY, key).put(TYPE, TYPE_FLOAT).put(VALUE, value.toDouble())
        is Boolean -> JSONObject().put(KEY, key).put(TYPE, TYPE_BOOLEAN).put(VALUE, value)
        is Set<*> -> JSONObject().put(KEY, key).put(TYPE, TYPE_SET)
            .put(VALUE, JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    /** 单条 JSON → 键值；遗留配置键、结构非法或类型未知返回 null（跳过该条，不影响其余记录）。 */
    fun decode(entry: JSONObject?): Pair<String, Any?>? {
        val key = entry?.optString(KEY)?.takeIf { it.isNotEmpty() } ?: return null
        // optString 对 JSON null 返回 "null" 字面量，这里一并挡掉
        if (key == "null" || key == LEGACY_CONFIG_KEY) return null
        val value: Any? = when (entry.optString(TYPE)) {
            TYPE_STRING -> ConfigJson.toText(entry.opt(VALUE))
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
