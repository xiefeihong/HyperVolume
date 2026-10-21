package com.xiefeihong.hypervolume.core

import android.util.Log
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * 音量配置的不可变值对象（纯逻辑，禁止引用任何 Xposed API）。
 *
 * 按「外放 / 有线 / 蓝牙」三设备各自持有一份 [DeviceConfig]（生效模式 + 模式A/B 范围），
 * 加全局的启用开关、档位数、音量键步进。整体以**自描述 XML** 序列化，供 App 单键持久化
 * 与跨进程（Settings.Global / 镜像文件 / boot 脚本）传递。
 *
 * **不兼容任何历史格式**：位置式 `;` 串已彻底删除，全链路只认 XML。[fromXml] 解析失败或
 * 字段缺失一律回落到默认值。
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

    // ==================== XML 序列化 ====================

    /**
     * 序列化为紧凑单行 XML。每设备始终写模式B 范围 `<b>` 与默认范围 `<d>`；
     * 仅蓝牙额外写模式A 范围 `<a>`。用于 SharedPreferences 暂存与差异比对（稳定、无换行）。
     */
    fun toXml(): String = buildXml(false)

    /**
     * 序列化为缩进多行的可读 XML，内容与 [toXml] 解析等价，仅排版不同。供写入
     * `Settings.Global` 与镜像文件，便于人工 `cat`/`settings get` 直接查看。
     * 全程只用双引号、不含单引号，可安全嵌入 `settings put global '<v>'` 与 `echo '<v>'`
     * （POSIX 单引号原样保留其中的换行）。
     */
    fun toPrettyXml(): String = buildXml(true)

    private fun buildXml(pretty: Boolean): String {
        val nl = if (pretty) "\n" else ""
        val i1 = if (pretty) "  " else ""
        val i2 = if (pretty) "    " else ""
        val sb = StringBuilder(if (pretty) 512 else 256)
        sb.append('<').append(TAG_ROOT)
            .append(" enabled=\"").append(if (enabled) 1 else 0)
            .append("\" mediaSteps=\"").append(Prefs.clampMediaSteps(mediaSteps))
            .append("\" keySteps=\"").append(Prefs.clampKeySteps(keySteps))
            .append(if (pretty) "\">\n" else "\">")
        appendDevice(sb, "speaker", speaker, false, i1, i2, nl)
        appendDevice(sb, "wired", wired, false, i1, i2, nl)
        appendDevice(sb, "bt", bluetooth, true, i1, i2, nl)
        sb.append("</").append(TAG_ROOT).append('>')
        return sb.toString()
    }

    private fun deviceDesc(c: DeviceConfig): String {
        val m = when (c.mode) {
            Prefs.BT_MODE_SOFTWARE -> "B"
            Prefs.BT_MODE_ABSOLUTE -> "A"
            else -> "默认"
        }
        val r = if (c.mode == Prefs.BT_MODE_DEFAULT) c.defaultRange else c.activeRange()
        return if (r == null) m else "$m[${r.min}~${r.max}/${r.curve}]"
    }

    companion object {

        private const val TAG_ROOT = "config"
        private const val TAG_DEVICE = "device"

        /** 缺字段时的兜底范围（App 正常写入总会给出完整三元组，此处仅防御畸形 XML）。 */
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

        // ==================== XML 写入辅助（供 buildXml） ====================

        private fun appendDevice(
            sb: StringBuilder, name: String, c: DeviceConfig, withAbsolute: Boolean,
            i1: String, i2: String, nl: String,
        ) {
            sb.append(i1).append('<').append(TAG_DEVICE)
                .append(" name=\"").append(name)
                .append("\" mode=\"").append(c.mode).append("\">").append(nl)
            if (withAbsolute) {
                appendRange(sb, 'a', c.absolute, i2, nl, true)
            }
            appendRange(sb, 'b', c.software, i2, nl, false)
            appendRange(sb, 'd', c.defaultRange, i2, nl, false)
            sb.append(i1).append("</").append(TAG_DEVICE).append('>').append(nl)
        }

        /**
         * 写单个范围元素。[absolute] 决定端点 clamp 口径：true＝AVRCP（0~[Prefs.AVRCP_MAX_VOLUME]），
         * false＝系统档位（0~[Prefs.MEDIA_STEPS_MAX]）。两者现值域不重叠（当前上限 100<127）故等价，
         * 按口径区分避免未来放宽档位数上限时静默截断。
         */
        private fun appendRange(
            sb: StringBuilder, tag: Char, r: Range?, i2: String, nl: String, absolute: Boolean,
        ) {
            val safe = r ?: FALLBACK_RANGE
            val hi = if (absolute) Prefs.AVRCP_MAX_VOLUME else Prefs.MEDIA_STEPS_MAX
            val min = safe.min.coerceAtLeast(0)
            val max = safe.max.coerceIn(0, hi)
            sb.append(i2).append('<').append(tag)
                .append(" min=\"").append(min)
                .append("\" max=\"").append(max)
                .append("\" curve=\"").append(Prefs.clampCurve(safe.curve))
                .append("\"/>").append(nl)
        }

        // ==================== XML 反序列化 ====================

        /**
         * 解析配置 XML。字段缺失按默认值、未知标签忽略；[xml] 为 null/空白、解析异常或根标签
         * 非 `config` 时返回 `null`（调用方回落默认配置）。
         */
        @JvmStatic
        fun fromXml(xml: String?): VolumeConfig? {
            if (xml == null) {
                return null
            }
            val trimmed = xml.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            return try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = false
                // XXE 加固：输入源自 Settings.Global / 镜像文件（非完全可信），禁用 DTD 与外部实体，
                // 避免外部实体解析或实体膨胀（billion laughs）拖垮 system_server / 蓝牙进程的 hook 回调。
                // 每项单独 try-catch：内置 parser 若不支持该 feature 则跳过，不影响解析主流程。
                safeFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
                safeFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
                safeFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
                val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(trimmed)))
                val root = doc.documentElement ?: return null
                if (root.tagName != TAG_ROOT) {
                    return null
                }
                val enabled = intAttr(root, "enabled", 0) != 0
                val mediaSteps = Prefs.clampMediaSteps(
                    intAttr(root, "mediaSteps", Prefs.MEDIA_STEPS_DEFAULT),
                )
                val keySteps = Prefs.clampKeySteps(
                    intAttr(root, "keySteps", Prefs.KEY_STEP_DEFAULT),
                )
                VolumeConfig(
                    enabled, mediaSteps, keySteps,
                    readDevice(root, "speaker", false),
                    readDevice(root, "wired", false),
                    readDevice(root, "bt", true),
                )
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "VolumeConfig.fromXml parse failed", t)
                null
            }
        }

        private fun safeFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
            try {
                factory.setFeature(name, value)
            } catch (ignored: Throwable) {
            }
        }

        /** 读取指定 name 的设备元素；缺失时给出默认（直通 + 兜底范围）。 */
        private fun readDevice(root: Element, name: String, withAbsolute: Boolean): DeviceConfig {
            val el = findDevice(root, name) ?: return DeviceConfig(
                Prefs.BT_MODE_DEFAULT, FALLBACK_RANGE, FALLBACK_RANGE, DEFAULT_FALLBACK_RANGE,
            )
            val rawMode = intAttr(el, "mode", Prefs.BT_MODE_DEFAULT)
            val mode = normalizeMode(rawMode, withAbsolute)
            val absolute = if (withAbsolute) readRange(el, "a", true) else FALLBACK_RANGE
            val software = readRange(el, "b", false)
            val defaultRange = readRange(el, "d", DEFAULT_FALLBACK_RANGE, false)
            return DeviceConfig(mode, absolute, software, defaultRange)
        }

        private fun findDevice(root: Element, name: String): Element? {
            val nodes = root.getElementsByTagName(TAG_DEVICE)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node is Element && node.getAttribute("name") == name) {
                    return node
                }
            }
            return null
        }

        /**
         * 读取指定标签的范围；缺失回落 [FALLBACK_RANGE]。[absolute] 决定 clamp 口径：
         * true＝AVRCP（0~[Prefs.AVRCP_MAX_VOLUME]），false＝系统档位（0~[Prefs.MEDIA_STEPS_MAX]）。
         */
        private fun readRange(parent: Element, tag: String, absolute: Boolean): Range =
            readRange(parent, tag, FALLBACK_RANGE, absolute)

        /** 读取指定标签的范围；缺失回落 [fallback]。[absolute] 同上。 */
        private fun readRange(parent: Element, tag: String, fallback: Range, absolute: Boolean): Range {
            val hi = if (absolute) Prefs.AVRCP_MAX_VOLUME else Prefs.MEDIA_STEPS_MAX
            val list = parent.getElementsByTagName(tag)
            for (i in 0 until list.length) {
                val el = list.item(i) as? Element ?: continue
                val min = intAttr(el, "min", Prefs.ABS_VOLUME_MIN_DEFAULT).coerceAtLeast(0)
                val max = intAttr(el, "max", hi).coerceIn(0, hi)
                val curve = Prefs.clampCurve(intAttr(el, "curve", Prefs.CURVE_TYPE_DEFAULT))
                return Range(min, max, curve)
            }
            return fallback
        }

        private fun intAttr(el: Element, name: String, def: Int): Int {
            val v = el.getAttribute(name)
            if (v.isNullOrEmpty()) {
                return def
            }
            return v.trim().toIntOrNull() ?: def
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
