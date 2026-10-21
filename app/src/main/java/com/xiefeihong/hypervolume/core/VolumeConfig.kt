package com.xiefeihong.hypervolume.core

import android.util.Log
import org.json.JSONObject

/**
 * 音量配置的不可变值对象（纯逻辑，禁止引用任何 Xposed API）。
 *
 * 按「外放 / 有线 / 蓝牙」三设备各自持有一份 [DeviceConfig]（生效模式 + 模式A/B 范围），
 * 加全局的启用开关、档位数、音量键步进。整体以**自描述 JSON** 序列化，供 App 单键持久化
 * 与跨进程（Settings.Global / 镜像文件 / boot 脚本）传递。
 *
 * **不兼容任何历史格式**：位置式 `;` 串与早前的 XML 均已删除，全链路只认 JSON。
 * [fromJson] 解析失败或字段缺失一律回落到默认值。
 *
 * 写出用 [JsonBuilder]（手写、键序固定、零依赖，紧凑串可直接用作差异比对基线），
 * 读入用框架自带的 [JSONObject]（system_server / 蓝牙进程均可用，无需引入第三方 JSON）。
 *
 * 与 [Prefs]/[Avrcp]/[VolumeMode]/[DeviceConfig] 一样同时被 App 进程与 Hook 端
 * （system_server / 蓝牙进程）使用。
 */
class VolumeConfig(
    /** 模块总开关：关闭时全部设备直通（不重写档位数、不重映射）。 */
    val enabled: Boolean,
    /** 媒体音量级数（已限制在 10~100），全局共用。 */
    mediaSteps: Int,
    /** 音量键步进（跨完整音量条需要的按键段数，已限制在 10~29），全局共用。 */
    keySteps: Int,
    /** 外放（内置扬声器/受话器）配置。 */
    val speaker: DeviceConfig,
    /** 有线耳机/USB/底座配置。 */
    val wired: DeviceConfig,
    /** 蓝牙配置。 */
    val bluetooth: DeviceConfig,
) {

    val mediaSteps: Int = Prefs.clampMediaSteps(mediaSteps)
    val keySteps: Int = Prefs.clampKeySteps(keySteps)

    // ==================== 按设备访问 ====================

    /** 取指定设备的配置。 */
    fun deviceFor(d: OutputDevice): DeviceConfig = when (d) {
        OutputDevice.WIRED -> wired
        OutputDevice.BT -> bluetooth
        OutputDevice.SPEAKER -> speaker
    }

    /** 指定设备的生效模式 id（0/1/2）。 */
    fun modeFor(d: OutputDevice): Int = deviceFor(d).mode

    /**
     * 指定设备当前生效的范围：总开关开启时，非默认设备返回其模式对应范围；默认设备仅当
     * 默认范围（[DeviceConfig.defaultRange]）被收窄（非满量程）时返回该范围，满量程/关闭=直通返回 `null`。
     */
    fun activeRangeFor(d: OutputDevice): Range? {
        if (!enabled) {
            return null
        }
        val c = deviceFor(d)
        return if (c.mode == Prefs.BT_MODE_DEFAULT) {
            if (isFullSpan(c.defaultRange)) null else c.defaultRange
        } else {
            c.activeRange()
        }
    }

    /** 满量程（等价直通）：`min<=0 且 max>=mediaSteps`；null 视为满量程。 */
    private fun isFullSpan(r: Range?): Boolean =
        r == null || (r.min <= 0 && r.max >= mediaSteps)

    // ==================== 生效判据 ====================

    /**
     * Hook 端是否应改写系统全局媒体档位数（[mediaSteps] 是否落地）：
     * 「启用档位修改」即 [enabled] 为真时应用用户档位数，关闭时保持系统默认（系统直通）。
     *
     * 本判据只决定**全局档位数**是否改写；单个设备是否做音量重映射另由
     * [activeRangeFor] 决定（默认满量程返回 `null` → 该设备直通），二者解耦。
     */
    val remapActive: Boolean get() = enabled

    /** 蓝牙是否处于「相对音量（模式B）」：需抑制 AVRCP 绝对音量、走系统软件衰减。 */
    fun suppressBtAbsoluteVolume(): Boolean =
        enabled && bluetooth.mode == Prefs.BT_MODE_SOFTWARE

    /** 蓝牙是否处于「绝对音量（模式A）」：由蓝牙进程经 AVRCP 映射，返回 [VolumeMode.ABSOLUTE]；否则 `null`。 */
    fun bluetoothAvrcpMode(): VolumeMode? =
        if (enabled && bluetooth.mode == Prefs.BT_MODE_ABSOLUTE) VolumeMode.ABSOLUTE else null

    /** 三设备中是否存在任一非默认（选了绝对/相对）模式。 */
    fun anyDeviceNonDefault(): Boolean =
        !speaker.isDefault || !wired.isDefault || !bluetooth.isDefault

    /** 返回把三设备模式全部改为默认直通（保留各自 A/B 范围）后的副本，供门控强制回落。 */
    fun withAllDevicesDefault(): VolumeConfig = VolumeConfig(
        enabled, mediaSteps, keySteps,
        speaker.withMode(Prefs.BT_MODE_DEFAULT),
        wired.withMode(Prefs.BT_MODE_DEFAULT),
        bluetooth.withMode(Prefs.BT_MODE_DEFAULT),
    )

    // ==================== 不可变编辑辅助（供 UI） ====================

    fun withEnabled(value: Boolean): VolumeConfig =
        VolumeConfig(value, mediaSteps, keySteps, speaker, wired, bluetooth)

    fun withSteps(media: Int, key: Int): VolumeConfig =
        VolumeConfig(enabled, media, key, speaker, wired, bluetooth)

    /** 返回把指定设备替换为新配置后的副本。 */
    fun withDevice(d: OutputDevice, c: DeviceConfig): VolumeConfig = when (d) {
        OutputDevice.WIRED -> VolumeConfig(enabled, mediaSteps, keySteps, speaker, c, bluetooth)
        OutputDevice.BT -> VolumeConfig(enabled, mediaSteps, keySteps, speaker, wired, c)
        OutputDevice.SPEAKER -> VolumeConfig(enabled, mediaSteps, keySteps, c, wired, bluetooth)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is VolumeConfig) {
            return false
        }
        return enabled == other.enabled && mediaSteps == other.mediaSteps &&
            keySteps == other.keySteps && speaker == other.speaker &&
            wired == other.wired && bluetooth == other.bluetooth
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + mediaSteps
        result = 31 * result + keySteps
        result = 31 * result + speaker.hashCode()
        result = 31 * result + wired.hashCode()
        result = 31 * result + bluetooth.hashCode()
        return result
    }

    /** 供日志输出的人类可读摘要。 */
    override fun toString(): String =
        (if (enabled) "on" else "off") +
            ",steps=$mediaSteps" +
            ",keySteps=$keySteps" +
            ",speaker=" + deviceDesc(speaker) +
            ",wired=" + deviceDesc(wired) +
            ",bt=" + deviceDesc(bluetooth)

    // ==================== JSON 序列化 ====================

    /**
     * 序列化为紧凑单行 JSON（无空白、键序固定）。供 SharedPreferences 暂存与差异比对基线
     * （两次序列化相等即内容一致，与排版无关）。
     */
    fun toJson(): String = buildJson(false)

    /**
     * 序列化为缩进两空格的易读 JSON，内容与 [toJson] 解析等价，仅排版不同。供写入
     * `Settings.Global` 与镜像文件，便于人工 `cat`/`settings get` 直接查看。
     * 全程只用双引号、不含单引号，可安全嵌入 `settings put global '<v>'` 与 `echo '<v>'`
     * （POSIX 单引号原样保留其中的换行）。
     */
    fun toPrettyJson(): String = buildJson(true)

    private fun buildJson(pretty: Boolean): String {
        val json = JsonBuilder(pretty)
        json.obj {
            bool(KEY_ENABLED, enabled)
            int(KEY_MEDIA_STEPS, Prefs.clampMediaSteps(mediaSteps))
            int(KEY_KEY_STEPS, Prefs.clampKeySteps(keySteps))
            obj(KEY_DEVICES) {
                device(KEY_SPEAKER, speaker, false)
                device(KEY_WIRED, wired, false)
                device(KEY_BT, bluetooth, true)
            }
        }
        return json.build()
    }

    /** 写单设备：模式 + 模式B/默认 两套范围；[withAbsolute]（仅蓝牙）额外写模式A 范围。 */
    private fun JsonBuilder.device(name: String, c: DeviceConfig, withAbsolute: Boolean) {
        obj(name) {
            int(KEY_MODE, c.mode)
            if (withAbsolute) {
                range(KEY_ABSOLUTE, c.absolute, Prefs.AVRCP_MAX_VOLUME)
            }
            range(KEY_SOFTWARE, c.software, Prefs.MEDIA_STEPS_MAX)
            range(KEY_DEFAULT, c.defaultRange, Prefs.MEDIA_STEPS_MAX)
        }
    }

    /**
     * 写单个范围。[hi] 决定端点 clamp 口径：AVRCP（[Prefs.AVRCP_MAX_VOLUME]）或
     * 系统档位（[Prefs.MEDIA_STEPS_MAX]）——两者现值域不重叠（当前上限 100<127）故等价，
     * 按口径区分避免未来放宽档位数上限时静默截断。
     */
    private fun JsonBuilder.range(key: String, r: Range?, hi: Int) {
        val safe = r ?: FALLBACK_RANGE
        obj(key) {
            int(KEY_MIN, safe.min.coerceAtLeast(0))
            int(KEY_MAX, safe.max.coerceIn(0, hi))
            int(KEY_CURVE, Prefs.clampCurve(safe.curve))
        }
    }

    /**
     * 日志摘要里的设备简写：模式用 A/B/D 字母（与 [Avrcp.curveKey] 同口径，保持
     * ASCII 不受界面语言影响）；它同时会进 system_server 侧的日志。
     */
    private fun deviceDesc(c: DeviceConfig): String {
        val m = when (c.mode) {
            Prefs.BT_MODE_SOFTWARE -> "B"
            Prefs.BT_MODE_ABSOLUTE -> "A"
            else -> "D"
        }
        val r = if (c.mode == Prefs.BT_MODE_DEFAULT) c.defaultRange else c.activeRange()
        return if (r == null) m else "$m[${r.min}~${r.max}/${r.curve}]"
    }

    companion object {

        // ==== JSON 键名（写出与读入共用，避免字面量漂移） ====

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MEDIA_STEPS = "mediaSteps"
        private const val KEY_KEY_STEPS = "keySteps"
        private const val KEY_DEVICES = "devices"
        private const val KEY_SPEAKER = "speaker"
        private const val KEY_WIRED = "wired"
        private const val KEY_BT = "bt"
        private const val KEY_MODE = "mode"
        private const val KEY_ABSOLUTE = "a"
        private const val KEY_SOFTWARE = "b"
        private const val KEY_DEFAULT = "d"
        private const val KEY_MIN = "min"
        private const val KEY_MAX = "max"
        private const val KEY_CURVE = "curve"

        /** 缺字段时的兜底范围（App 正常写入总会给出完整三元组，此处仅防御畸形 JSON）。 */
        private val FALLBACK_RANGE = Range(
            Prefs.ABS_VOLUME_MIN_DEFAULT, Prefs.ABS_VOLUME_MAX_DEFAULT, Prefs.CURVE_TYPE_DEFAULT,
        )

        /** 默认范围的兜底：满量程直通（min=0、max=最大档位数），curve 对默认无效。 */
        private val DEFAULT_FALLBACK_RANGE = Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_LINEAR)

        /** 共享数据类不得引 Hook 框架类（App 侧会因 compileOnly libxposed 加载失败），故用框架级 Log。 */
        private const val LOG_TAG = "HyperVolume"

        /** 默认配置：关闭直通 + 兜底档位数 + 三设备默认范围直通。 */
        val DEFAULT: VolumeConfig = VolumeConfig(
            false, Prefs.MEDIA_STEPS_DEFAULT, Prefs.KEY_STEP_DEFAULT,
            DeviceConfig(Prefs.BT_MODE_DEFAULT, FALLBACK_RANGE, FALLBACK_RANGE, DEFAULT_FALLBACK_RANGE),
            DeviceConfig(Prefs.BT_MODE_DEFAULT, FALLBACK_RANGE, FALLBACK_RANGE, DEFAULT_FALLBACK_RANGE),
            DeviceConfig(Prefs.BT_MODE_DEFAULT, FALLBACK_RANGE, FALLBACK_RANGE, DEFAULT_FALLBACK_RANGE),
        )

        /**
         * 解析配置 JSON。字段缺失按默认值、未知键忽略；[json] 为 null/空白、解析异常，
         * 或不含任何本模块顶层键（不是本模块的配置）时返回 `null`（调用方回落默认配置）。
         */
        @JvmStatic
        fun fromJson(json: String?): VolumeConfig? {
            if (json == null) {
                return null
            }
            val trimmed = json.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            return try {
                val root = JSONObject(trimmed)
                if (!isConfigObject(root)) {
                    return null
                }
                val devices = root.optJSONObject(KEY_DEVICES)
                VolumeConfig(
                    boolField(root, KEY_ENABLED, false),
                    Prefs.clampMediaSteps(
                        intField(root, KEY_MEDIA_STEPS, Prefs.MEDIA_STEPS_DEFAULT),
                    ),
                    Prefs.clampKeySteps(
                        intField(root, KEY_KEY_STEPS, Prefs.KEY_STEP_DEFAULT),
                    ),
                    readDevice(devices, KEY_SPEAKER, false),
                    readDevice(devices, KEY_WIRED, false),
                    readDevice(devices, KEY_BT, true),
                )
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "VolumeConfig.fromJson parse failed", t)
                null
            }
        }

        /** 顶层四个键一个都没有 = 不是本模块的配置（拒绝无关 JSON 被当成空配置生效）。 */
        private fun isConfigObject(root: JSONObject): Boolean =
            root.has(KEY_ENABLED) || root.has(KEY_MEDIA_STEPS) ||
                root.has(KEY_KEY_STEPS) || root.has(KEY_DEVICES)

        /** 读取指定 name 的设备；缺失时给出默认（直通 + 兜底范围）。 */
        private fun readDevice(devices: JSONObject?, name: String, withAbsolute: Boolean): DeviceConfig {
            val el = devices?.optJSONObject(name) ?: return DeviceConfig(
                Prefs.BT_MODE_DEFAULT, FALLBACK_RANGE, FALLBACK_RANGE, DEFAULT_FALLBACK_RANGE,
            )
            val mode = normalizeMode(intField(el, KEY_MODE, Prefs.BT_MODE_DEFAULT), withAbsolute)
            val absolute = if (withAbsolute) {
                readRange(el, KEY_ABSOLUTE, FALLBACK_RANGE, Prefs.AVRCP_MAX_VOLUME)
            } else {
                FALLBACK_RANGE
            }
            val software = readRange(el, KEY_SOFTWARE, FALLBACK_RANGE, Prefs.MEDIA_STEPS_MAX)
            val defaultRange = readRange(el, KEY_DEFAULT, DEFAULT_FALLBACK_RANGE, Prefs.MEDIA_STEPS_MAX)
            return DeviceConfig(mode, absolute, software, defaultRange)
        }

        /** 读取指定键的范围；缺失或非对象回落 [fallback]。[hi] 为端点 clamp 上限（同 [range]）。 */
        private fun readRange(parent: JSONObject, key: String, fallback: Range, hi: Int): Range {
            val o = parent.optJSONObject(key) ?: return fallback
            val min = intField(o, KEY_MIN, Prefs.ABS_VOLUME_MIN_DEFAULT).coerceAtLeast(0)
            val max = intField(o, KEY_MAX, hi).coerceIn(0, hi)
            val curve = Prefs.clampCurve(intField(o, KEY_CURVE, Prefs.CURVE_TYPE_DEFAULT))
            return Range(min, max, curve)
        }

        /** 整数字段：容忍数字与字符串两种写法（人工改配置常写成 `"min": "10"`）。 */
        private fun intField(o: JSONObject, key: String, def: Int): Int {
            val v = o.opt(key)
            return when {
                v is Number -> v.toInt()
                v is String -> v.trim().toIntOrNull() ?: def
                else -> def
            }
        }

        /**
         * 布尔字段：容忍 `true`/`false` 与历史的 `1`/`0`（含字符串写法），
         * 避免手工编辑或旧数据把开关读成 false。
         */
        private fun boolField(o: JSONObject, key: String, def: Boolean): Boolean {
            val v = o.opt(key)
            return when {
                v is Boolean -> v
                v is Number -> v.toInt() != 0
                v is String -> v.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
                else -> def
            }
        }

        /**
         * 归一模式：允许绝对(仅蓝牙)时 0/1/2 原样；否则把绝对(1) 归一为相对(2)（外放/有线不支持模式A），
         * 非法值一律回落默认(0)。
         */
        private fun normalizeMode(m: Int, allowAbsolute: Boolean): Int = when (m) {
            Prefs.BT_MODE_SOFTWARE -> Prefs.BT_MODE_SOFTWARE
            Prefs.BT_MODE_ABSOLUTE ->
                if (allowAbsolute) Prefs.BT_MODE_ABSOLUTE else Prefs.BT_MODE_SOFTWARE
            else -> Prefs.BT_MODE_DEFAULT
        }
    }
}

/**
 * 极简 JSON 写出器（只写本模块配置需要的 `对象 / 整数 / 布尔`，不支持转义与数组）。
 *
 * 刻意不用 `JSONObject` 写出：其 JVM 实现按 `HashMap` 迭代，键序不稳定，而紧凑串要
 * 充当「两次序列化可比对」的差异基线、格式化串要给人读，键序必须固定。
 *
 * [pretty] 为真时缩进两空格并换行，为假时输出无空白的单行串。
 */
private class JsonBuilder(private val pretty: Boolean) {

    private val sb = StringBuilder(if (pretty) 640 else 256)
    private var indent = 0

    /** 当前层是否已写过成员（决定下一个成员前是否补逗号）。 */
    private var hasMember = false

    /** 格式化模式下刚开一个对象：首个成员前要先换行，否则成员会挤在 `{` 同一行。 */
    private var freshOpen = false

    fun build(): String = sb.toString()

    /** 写一个对象成员；[key] 为 null 时写根对象。 */
    fun obj(key: String? = null, block: JsonBuilder.() -> Unit) {
        pre(key)
        sb.append('{')
        indent++
        hasMember = false
        freshOpen = pretty
        block()
        val nested = hasMember
        indent--
        if (nested) {
            sb.append(nl).append(pad)
        }
        sb.append('}')
        hasMember = true
        freshOpen = false
    }

    fun int(key: String, value: Int) {
        pre(key)
        sb.append(value)
    }

    fun bool(key: String, value: Boolean) {
        pre(key)
        sb.append(if (value) "true" else "false")
    }

    private fun pre(key: String?) {
        when {
            hasMember -> sb.append(',').append(nl)
            freshOpen -> sb.append(nl)
        }
        freshOpen = false
        hasMember = true
        sb.append(pad)
        if (key != null) {
            sb.append('"').append(key).append('"').append(if (pretty) ": " else ":")
        }
    }

    private val nl: String get() = if (pretty) "\n" else ""

    private val pad: String get() = if (pretty) "  ".repeat(indent) else ""
}
