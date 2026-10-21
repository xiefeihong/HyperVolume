package com.xiefeihong.hypervolume.core

import android.util.Log
import kotlin.math.pow

/**
 * 「默认（系统原生）」音量曲线。
 *
 * Android 13+/AIDL Audio HAL 下，framework 层（`dumpsys audio` / AudioService）不再持有逐档
 * index→dB 曲线，增益映射下沉到厂商 audio policy。这里通过 root 读取三处配置，按「更专属优先」
 * 还原媒体流在各输出设备类别下的真实默认曲线：
 *  1. 主源 `/vendor/etc/audio_policy_engine_stream_volumes.xml`——旧式音量引擎表，按
 *     `<volumeGroup><name>music</name>` 给出各 `deviceCategory`（含厂商专属的
 *     `DEVICE_CATEGORY_A2DP`）显式 dB 点表，最贴近本机调音；
 *  2. 次源 `/vendor/etc/audio_policy_volumes.xml`——新式 audio policy（媒体流多走 `ref=` 引用），
 *     配合 `/vendor/etc/default_volume_tables.xml` 解析被引用点表；
 *  3. 回退：硬编码 AOSP 参考曲线（读不到任何 ROM 文件时）。
 *
 * 点表 x＝跨档位区间的百分比（0~100），y＝增益（millibel，dB×100，满量程 0）。归一化到 0~100%
 * 作展示：以本曲线最负增益为底、0 dB 为顶线性归一（近似硬件增益阶梯，非最终主观响度）。
 * [load] 只应在后台线程调用（涉及 root 读文件）。
 */
object NativeVolumeCurve {

    /** 输出设备类别：分别给出主源(引擎表)/次源(策略表)的 deviceCategory token。 */
    enum class Device(
        internal val engineToken: String,
        internal val policyToken: String,
        internal val aospRefName: String,
    ) {
        SPEAKER("DEVICE_CATEGORY_SPEAKER", "DEVICE_CATEGORY_SPEAKER", "DEFAULT_DEVICE_CATEGORY_SPEAKER_VOLUME_CURVE"),
        WIRED("DEVICE_CATEGORY_HEADSET", "DEVICE_CATEGORY_HEADSET", "DEFAULT_MEDIA_VOLUME_CURVE"),

        // 蓝牙：引擎表有专属 A2DP；新策略表只有 EXT_MEDIA
        BT("DEVICE_CATEGORY_A2DP", "DEVICE_CATEGORY_EXT_MEDIA", "DEFAULT_MEDIA_VOLUME_CURVE"),
    }

    /** 曲线来源。 */
    enum class Source { ENGINE, POLICY, AOSP }

    /**
     * 纵轴换算口径（同一 dB 阶梯，三种映射，决定曲线弯法）：
     *  - [DB_LINEAR]：dB 在 [floor,ceil] 线性 → 忠实硬件增益阶梯；
     *  - [AMPLITUDE]：振幅比 `10^(dB/20)` 归一 → 反映信号幅度；
     *  - [PERCEPTUAL]：振幅^0.6（Stevens 幂律，近似等响）归一 → 贴近主观响度。
     */
    enum class Mode { DB_LINEAR, AMPLITUDE, PERCEPTUAL }

    /** 某设备在给定档位数下的归一化增益曲线。 */
    class Curve internal constructor(
        val device: Device,
        /** true＝解析自本机 ROM 配置（引擎/策略表）；false＝AOSP 参考回退。 */
        val fromRom: Boolean,
        val source: Source,
        val mode: Mode,
        /** 长度 maxSteps+1，gainPercent[i]＝第 i 档的归一化增益 0~100。 */
        val gainPercent: FloatArray,
        /** 长度 maxSteps+1，gainDb[i]＝第 i 档的硬件增益 dB（millibel/100，负值）。 */
        val gainDb: FloatArray,
        val maxSteps: Int,
    )

    /**
     * 「默认」XML 锚点曲线：直接以 XML 点表的档位百分比（0~100）为横轴，供「横轴=百分比」预览。
     */
    class Anchors internal constructor(
        /** XML 点表的档位百分比（0~100），映射表左列即取此值。 */
        val percent: IntArray,
        /** 各锚点的归一化增益 0~100（按所选 [Mode]）。 */
        val gainPercentAtAnchor: FloatArray,
        /** 各锚点的硬件增益 dB（millibel/100）。 */
        val gainDbAtAnchor: FloatArray,
        /** 长度 101：整数百分比 0~100 处的归一化增益（画平滑曲线用）。 */
        val gainByPercent: FloatArray,
        /** 长度 101：整数百分比 0~100 处的硬件增益 dB（millibel/100，负值），供百分比表取窗口端点。 */
        val gainDbByPercent: FloatArray,
        val source: Source,
        val mode: Mode,
    )

    /** AOSP 参考点表（三源全失败时回退），来自本机 dump 的标准 AOSP 媒体曲线。 */
    private val AOSP_POINTS = arrayOf(
        // SPEAKER
        arrayOf(
            intArrayOf(0, -9600), intArrayOf(1, -5800), intArrayOf(20, -4000),
            intArrayOf(60, -1700), intArrayOf(100, 0),
        ),
        // WIRED（DEFAULT_MEDIA_VOLUME_CURVE）
        arrayOf(
            intArrayOf(1, -5800), intArrayOf(20, -4000), intArrayOf(60, -1700), intArrayOf(100, 0),
        ),
        // BT（EXT_MEDIA → DEFAULT_MEDIA_VOLUME_CURVE）
        arrayOf(
            intArrayOf(1, -5800), intArrayOf(20, -4000), intArrayOf(60, -1700), intArrayOf(100, 0),
        ),
    )

    private const val FILE_ENGINE = "/vendor/etc/audio_policy_engine_stream_volumes.xml"
    private const val FILE_POLICY = "/vendor/etc/audio_policy_volumes.xml"
    private const val FILE_TABLES = "/vendor/etc/default_volume_tables.xml"

    /** 共享/App 侧数据类不得引 Hook 框架类（App 进程会因 compileOnly libxposed 加载失败），故用框架级 Log。 */
    private const val LOG_TAG = "HyperVolume"

    @Volatile
    private var loaded = false

    // device.ordinal → 解析出的点表（null＝该设备未取到，回退 AOSP）
    private var parsed: Array<Array<IntArray>?>? = null
    private var parsedSource: Array<Source?>? = null

    // 引擎表 music 组的 <indexMax>（ROM 声明的媒体原生档位数）；0＝未解析到。
    private var musicIndexMax = 0

    /**
     * 曲线构建缓存：App 内拖动 min/max 时 steps/mode 不变，重复 updatePreview 命中缓存，
     * 省去逐次 maxSteps+1（或 101 点）插值。[Curve]/[Anchors] 均为不可变值对象且调用方只读不写，
     * 可安全共享。[load] 完成后清空，确保缓存只基于最终点表（避免 load 前误缓存 AOSP 回退结果）。
     */
    private val curveCache = java.util.concurrent.ConcurrentHashMap<String, Curve>()
    private val anchorsCache = java.util.concurrent.ConcurrentHashMap<String, Anchors>()

    /** 引擎表 music 组声明的媒体档位数（`<indexMax>`）；未加载/未解析到返回 0。 */
    fun nativeMusicIndexMax(): Int = if (loaded) musicIndexMax else 0

    /** 读文件、解析（含 root 调用）。只在后台线程调用一次；解析失败静默回退 AOSP。 */
    @Synchronized
    fun load() {
        if (loaded) {
            return
        }

        val refMap = HashMap<String, Array<IntArray>>() // reference name → points（default_volume_tables）
        val engineMusic = HashMap<String, Array<IntArray>>() // 引擎表 music 组 deviceCategory → points
        val policyMusic = HashMap<String, Array<IntArray>>() // 策略表 MUSIC profile deviceCategory → points

        read(FILE_TABLES)?.let { parseReferences(it, refMap) }
        read(FILE_ENGINE)?.let { parseEngineMusic(it, refMap, engineMusic) }
        read(FILE_POLICY)?.let { parseMusicVolumes(it, refMap, policyMusic) }

        val n = Device.entries.size
        val points = arrayOfNulls<Array<IntArray>>(n)
        val sources = arrayOfNulls<Source>(n)
        for (d in Device.entries) {
            val engine = engineMusic[d.engineToken]
            if (engine != null) {
                points[d.ordinal] = engine
                sources[d.ordinal] = Source.ENGINE
                continue
            }
            val policy = policyMusic[d.policyToken]
            if (policy != null) {
                points[d.ordinal] = policy
                sources[d.ordinal] = Source.POLICY
            } else {
                sources[d.ordinal] = Source.AOSP
            }
        }
        parsed = points
        parsedSource = sources
        loaded = true
        // 点表已最终确定：清空此前可能基于 AOSP 回退填充的曲线/锚点缓存。
        curveCache.clear()
        anchorsCache.clear()
    }

    /** 归一化增益曲线（0~100），按档位数 [steps] 输出。 */
    fun curveFor(device: Device, steps: Int, mode: Mode): Curve {
        val key = "${device.name}|$steps|${mode.name}"
        curveCache[key]?.let { return it }
        val maxSteps = steps.coerceAtLeast(1)
        val points = resolvePoints(device)
        val floor = floorDb(points) // 最负 dB（底 → 0%）
        val ceil = ceilDb(points) // 最接近 0 的 dB（顶 → 100%）
        val gain = FloatArray(maxSteps + 1)
        val gainDb = FloatArray(maxSteps + 1)
        for (i in 0..maxSteps) {
            val percent = percentAcross(i, maxSteps)
            val db = interpolateDb(points, percent)
            gainDb[i] = db / 100f // millibel → dB
            gain[i] = transform(db, floor, ceil, mode)
        }
        val src = sourceOf(device)
        val curve = Curve(device, src != Source.AOSP, src, mode, gain, gainDb, maxSteps)
        curveCache[key] = curve
        return curve
    }

    /** 取某设备 XML 点表的档位百分比锚点曲线（横轴=百分比）；带 [anchorsCache] 缓存。 */
    fun anchorsFor(device: Device, mode: Mode): Anchors {
        val key = "${device.name}|${mode.name}"
        anchorsCache[key]?.let { return it }
        val points = resolvePoints(device)
        val floor = floorDb(points)
        val ceil = ceilDb(points)
        val n = points.size
        val pct = IntArray(n)
        val gain = FloatArray(n)
        val gdb = FloatArray(n)
        for (i in 0 until n) {
            pct[i] = points[i][0]
            val db = points[i][1].toFloat()
            gdb[i] = db / 100f
            gain[i] = transform(db, floor, ceil, mode)
        }
        val byPct = FloatArray(101)
        val dbByPct = FloatArray(101)
        for (p in 0..100) {
            val db = interpolateDb(points, p.toFloat())
            byPct[p] = transform(db, floor, ceil, mode)
            dbByPct[p] = db / 100f
        }
        val anchors = Anchors(pct, gain, gdb, byPct, dbByPct, sourceOf(device), mode)
        anchorsCache[key] = anchors
        return anchors
    }

    // --- 归一化 / 采样 ---

    /** 索引 → 跨区间百分比（index=0→0，index=maxSteps→100）。 */
    private fun percentAcross(index: Int, maxSteps: Int): Float = 100f * index / maxSteps

    /** 按所选口径把 dB（毫贝）映射到 0~100%，端点分别锚定 floor/ceil（保证 0%~100%）。 */
    private fun transform(dbMB: Float, floorMB: Float, ceilMB: Float, mode: Mode): Float {
        if (ceilMB <= floorMB) {
            return 100f
        }
        return when (mode) {
            Mode.AMPLITUDE -> {
                val a = amp(dbMB.toDouble())
                val af = amp(floorMB.toDouble())
                val ac = amp(ceilMB.toDouble())
                ((a - af) / (ac - af) * 100.0).toFloat().coerceIn(0f, 100f)
            }
            Mode.PERCEPTUAL -> {
                val p = amp(dbMB.toDouble()).pow(0.6)
                val pf = amp(floorMB.toDouble()).pow(0.6)
                val pc = amp(ceilMB.toDouble()).pow(0.6)
                ((p - pf) / (pc - pf) * 100.0).toFloat().coerceIn(0f, 100f)
            }
            Mode.DB_LINEAR -> ((dbMB - floorMB) / (ceilMB - floorMB) * 100f).coerceIn(0f, 100f)
        }
    }

    /** dB（毫贝）→ 振幅比：`10^(dB/20)`，dB＝millibel/100，故指数＝millibel/2000。 */
    private fun amp(dbMillibel: Double): Double = 10.0.pow(dbMillibel / 2000.0)

    /** 本曲线点表的最负 dB（底）。 */
    private fun floorDb(points: Array<IntArray>): Float {
        if (points.isEmpty()) {
            return -9600f
        }
        var floor = points[0][1].toFloat()
        for (pt in points) {
            floor = minOf(floor, pt[1].toFloat())
        }
        return floor
    }

    /** 本曲线点表的最接近 0 的 dB（顶）。 */
    private fun ceilDb(points: Array<IntArray>): Float {
        if (points.isEmpty()) {
            return 0f
        }
        var ceil = points[0][1].toFloat()
        for (pt in points) {
            ceil = maxOf(ceil, pt[1].toFloat())
        }
        return ceil
    }

    /** 索引 0 恒为底（floor），其余按点表 dB 插值后归一，保证单调递增的相对增益。 */
    private fun interpolateDb(points: Array<IntArray>, percent: Float): Float {
        if (points.isEmpty()) {
            return -9600f
        }
        if (percent <= points[0][0]) {
            return points[0][1].toFloat()
        }
        for (i in 1 until points.size) {
            if (percent <= points[i][0]) {
                val p0 = points[i - 1][0].toFloat()
                val d0 = points[i - 1][1].toFloat()
                val p1 = points[i][0].toFloat()
                val d1 = points[i][1].toFloat()
                if (p1 == p0) {
                    return d1
                }
                val t = (percent - p0) / (p1 - p0)
                return d0 + (d1 - d0) * t
            }
        }
        return points[points.size - 1][1].toFloat()
    }

    private fun resolvePoints(device: Device): Array<IntArray> {
        val p = parsed
        if (loaded && p != null) {
            p[device.ordinal]?.let { return it }
        }
        return AOSP_POINTS[device.ordinal]
    }

    private fun sourceOf(device: Device): Source {
        val s = parsedSource
        if (loaded && s != null) {
            return s[device.ordinal] ?: Source.AOSP
        }
        return Source.AOSP
    }

    // --- 文件读取 / XML 解析 ---

    private fun read(path: String): String? = try {
        val r = Shell.su("cat $path")
        if (r.isSuccess) r.output else null
    } catch (t: Exception) {
        Log.w(LOG_TAG, "NativeVolumeCurve.read failed: path=$path", t)
        null
    }

    /** 解析 default_volume_tables.xml 里的 `<reference name>` 点表。 */
    private fun parseReferences(xml: String, out: MutableMap<String, Array<IntArray>>) {
        val matcher = Regex("<reference[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</reference>", setOf(RegexOption.DOT_MATCHES_ALL))
        for (m in matcher.findAll(xml)) {
            out[m.groupValues[1]] = parsePoints(m.groupValues[2])
        }
    }

    /** 引擎表：定位 `<volumeGroup><name>music</name>`，抽取其各 deviceCategory 点表。 */
    private fun parseEngineMusic(
        xml: String, refMap: Map<String, Array<IntArray>>, out: MutableMap<String, Array<IntArray>>,
    ) {
        val groups = Regex("<volumeGroup>(.*?)</volumeGroup>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml)
        for (g in groups) {
            val block = g.groupValues[1]
            val name = Regex("<name>\\s*([^<]+?)\\s*</name>").find(block)?.groupValues?.get(1)?.trim()
            if (name != "music") {
                continue
            }
            Regex("<indexMax>\\s*(\\d+)\\s*</indexMax>").find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { musicIndexMax = it }
            extractVolumes(block, refMap, out)
            return
        }
    }

    /** 策略表：定位 `<profile ... group="MUSIC" ...>`，抽取各 deviceCategory 点表。 */
    private fun parseMusicVolumes(
        xml: String, refMap: Map<String, Array<IntArray>>, out: MutableMap<String, Array<IntArray>>,
    ) {
        val matcher = Regex("<profile[^>]*group=\"MUSIC\"[^>]*>(.*?)</profile>", setOf(RegexOption.DOT_MATCHES_ALL))
        for (p in matcher.findAll(xml)) {
            extractVolumes(p.groupValues[1], refMap, out)
        }
    }

    /** 从一段 XML 抽取所有 `<volume deviceCategory>` 点表（自闭合则跟随 ref）。 */
    private fun extractVolumes(
        section: String, refMap: Map<String, Array<IntArray>>, out: MutableMap<String, Array<IntArray>>,
    ) {
        val matcher = Regex("<volume\\s+deviceCategory=\"([^\"]+)\"([^>]*?)(/>|>(.*?)</volume>)", setOf(RegexOption.DOT_MATCHES_ALL))
        for (m in matcher.findAll(section)) {
            val cat = m.groupValues[1]
            val attrs = m.groupValues[2] + m.groupValues[3]
            val body = m.groupValues[4]
            val refName = Regex("ref=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
            if (refName != null) {
                refMap[refName]?.let { if (it.isNotEmpty()) out[cat] = it }
            } else if (body.isNotEmpty()) {
                val pts = parsePoints(body)
                if (pts.isNotEmpty()) {
                    out[cat] = pts
                }
            }
        }
    }

    /** 从含若干 `<point>百分比,毫贝</point>` 的片段解析为点表（按 x 升序）。 */
    private fun parsePoints(xml: String): Array<IntArray> {
        val pts = ArrayList<IntArray>()
        for (m in Regex("<point>\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*</point>").findAll(xml)) {
            val x = m.groupValues[1].toIntOrNull() ?: continue
            val y = m.groupValues[2].toIntOrNull() ?: continue
            pts.add(intArrayOf(x, y))
        }
        pts.sortBy { it[0] }
        return pts.toTypedArray()
    }
}
