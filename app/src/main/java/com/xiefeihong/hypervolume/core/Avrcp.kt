package com.xiefeihong.hypervolume.core

import com.xiefeihong.hypervolume.R
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 蓝牙 AVRCP 绝对音量映射计算与预览文本。
 *
 * AOSP 蓝牙模块（packages/modules/Bluetooth，com.android.bluetooth.avrcp.AvrcpVolumeManager）
 * 的默认换算为线性公式：`absVolume = round(档位 * 127 / 系统最大档位)`。该公式第 1 档
 * AVRCP 值过低（部分耳机无声），且低音量区相邻档位差值小，易被耳机内部档位量化到同一格。
 *
 * 因此模式A 改用端点为 [minAbs, maxAbs] 的可选曲线（由 curveType 选择，预览与实际发送共用同一
 * 函数）：设 `e=(档位-1)/(最大档位-1)`，`lo = minAbs`（minAbs=0 时取 0.15*maxAbs 的自适应下限）：
 *
 * ```
 * 对数（默认）：absVolume = lo * (maxAbs / lo)^e
 * 线性：absVolume = lo + (maxAbs - lo) * e
 * 平方根：absVolume = lo + (maxAbs - lo) * sqrt(e)
 * ```
 *
 * 三者均保证：档位 1 精确命中 minAbs（minAbs=0 时按 [MIN_VOLUME_FLOOR_RATIO] 取自适应可闻下限，
 * 避免第 1 档接近无声）；最大档位精确命中 maxAbs；档位 0 恒为静音。预览与实际发送（BtHooks 的
 * systemToAvrcp 拦截）调用同一函数，天然一致。模式B 与 AVRCP 无关，直接在同一曲线族下把档位
 * 映射为系统音量档位（[curveToSystemIndex]）。
 */
object Avrcp {

    /**
     * 最小音量下限比例：当用户将「最小音量」设为 0 时，第 1 档自动取 [maxAbs] 的此比例
     * 作为可闻下限（自适应耳机量程），而非退化为接近无声的 1。可据实机听感微调此常量。
     */
    const val MIN_VOLUME_FLOOR_RATIO = 0.06

    /**
     * 默认模式低音量区可分辨下限：第 1→2 档的原生 dB 差值小于此值时，相邻档位听感几乎相同，
     * 预览给出 ⚠ 提示（约为人耳可辨的最小响度差，可按实机微调）。
     */
    const val DEFAULT_DB_GAP_MIN = 1.0f

    /** 模式A（AVRCP 0~127）第 1 档可闻下限阈值：AVRCP 值 ≤ 此值提示低音量可能无声。 */
    private const val ABS_LOW_FLOOR = 5

    /** 模式A 低音量区第 1→2 档最小间距（AVRCP 单位）：小于此值提示耳机粒度下听感趋同。 */
    private const val ABS_SPACING_MIN = 8

    /** 模式B/默认（百分比口径）第 1 档可闻下限阈值：归一百分比 ≤ 此值提示低音量可能无声。 */
    private const val LOW_FLOOR_PCT = 5

    /** 规范化绝对音量范围：值域 0~127，且 最小值 <= 最大值。返回 `[lo, hi]`。 */
    private fun normalizedRange(minAbs: Int, maxAbs: Int): IntArray {
        val lo = min(minAbs, maxAbs).coerceIn(0, Prefs.AVRCP_MAX_VOLUME)
        val hi = max(minAbs, maxAbs).coerceIn(0, Prefs.AVRCP_MAX_VOLUME)
        return intArrayOf(lo, hi)
    }

    /**
     * 对数曲线使用的区间：`[lo, hi]`，`hi = 归一上限`。
     * 下限 `lo`：minAbs≥1 时精确=minAbs；minAbs=0 时按 [MIN_VOLUME_FLOOR_RATIO]
     * 取 hi 的比例作为自适应可闻下限（避免第 1 档接近无声），并保证 lo≤hi、lo≥1。
     */
    private fun logRange(minAbs: Int, maxAbs: Int): IntArray {
        val range = normalizedRange(minAbs, maxAbs)
        val hi = range[1]
        val lo = if (range[0] > 0) range[0] else max(1, (MIN_VOLUME_FLOOR_RATIO * hi).roundToInt())
        return intArrayOf(min(lo, hi), hi)
    }

    /** 曲线类型名称（预览与界面文案用，随语言切换）。 */
    fun curveLabel(curveType: Int): String = when (curveType) {
        Prefs.CURVE_LINEAR -> AppText.str(R.string.curve_short_linear)
        Prefs.CURVE_SQRT -> AppText.str(R.string.curve_short_sqrt)
        else -> AppText.str(R.string.curve_short_log)
    }

    /** 曲线类型的稳定机器名：Hook 进程（蓝牙 / system_server）日志用，与界面语言无关。 */
    fun curveKey(curveType: Int): String = when (curveType) {
        Prefs.CURVE_LINEAR -> "linear"
        Prefs.CURVE_SQRT -> "sqrt"
        else -> "log"
    }

    /**
     * 正向曲线：step → [lo,hi] 区间内的音量值。三曲线共用 logRange 的 lo/hi（min=0 时含自适应下限）。
     * `e=(step-1)/(maxSteps-1)`：线性 `lo+(hi-lo)·e`；平方根 `lo+(hi-lo)·√e`；对数 `lo·(hi/lo)^e`。
     */
    private fun applyCurve(step: Int, maxSteps: Int, lo: Int, hi: Int, curveType: Int): Int {
        // 退化（maxSteps<=1 或 min>=max）：直接输出上限
        if (maxSteps <= 1 || hi <= lo) {
            return hi.coerceIn(0, Prefs.AVRCP_MAX_VOLUME)
        }
        val e = ((step - 1).toDouble() / (maxSteps - 1)).coerceIn(0.0, 1.0)
        val dLo = lo.toDouble()
        val dHi = hi.toDouble()
        val value = when (curveType) {
            Prefs.CURVE_LINEAR -> dLo + (dHi - dLo) * e
            Prefs.CURVE_SQRT -> dLo + (dHi - dLo) * sqrt(e)
            else -> dLo * (dHi / dLo).pow(e)
        }
        return value.roundToInt().coerceIn(0, Prefs.AVRCP_MAX_VOLUME)
    }

    /** 反向曲线：音量值 → step（[applyCurve] 的精确反函数）。 */
    private fun invertCurve(absVolume: Int, maxSteps: Int, lo: Int, hi: Int, curveType: Int): Int {
        if (maxSteps <= 1 || hi <= lo) {
            return maxSteps
        }
        val dLo = lo.toDouble()
        val dHi = hi.toDouble()
        val e = when (curveType) {
            Prefs.CURVE_LINEAR -> (absVolume - lo) / (dHi - dLo)
            Prefs.CURVE_SQRT -> {
                val t = (absVolume - lo) / (dHi - dLo)
                t * t
            }
            else -> ln(absVolume.toDouble() / dLo) / ln(dHi / dLo)
        }.coerceIn(0.0, 1.0)
        val step = (1 + (maxSteps - 1) * e).roundToInt()
        return step.coerceIn(0, maxSteps)
    }

    /**
     * 模式A 映射：手机档位 → AVRCP 绝对音量（按 curveType 选择分布）。
     *
     * 档位 0 保持静音；step=1 精确命中 minAbs（minAbs=0 时取自适应下限），
     * step=maxSteps 精确命中 maxAbs。
     */
    @JvmStatic
    fun curveToAbsoluteVolume(step: Int, maxSteps: Int, minAbs: Int, maxAbs: Int, curveType: Int): Int {
        if (maxSteps <= 0 || step <= 0) {
            return 0
        }
        val range = logRange(minAbs, maxAbs)
        return applyCurve(step, maxSteps, range[0], range[1], curveType)
    }

    /** 曲线反函数：AVRCP 绝对音量 → 手机档位（耳机音量键回调时保持映射一致）。 */
    @JvmStatic
    fun curveToSystemStep(absVolume: Int, maxSteps: Int, minAbs: Int, maxAbs: Int, curveType: Int): Int {
        if (maxSteps <= 0 || absVolume <= 0) {
            return 0
        }
        val range = logRange(minAbs, maxAbs)
        return invertCurve(absVolume, maxSteps, range[0], range[1], curveType)
    }

    /**
     * 模式B 映射（系统档位直映射，与 AVRCP 无关）：与模式A 同曲线族，但 min/max 以
     * **系统档位单位 0~maxSteps** 为经，一次 [applyCurve] 直接得到目标系统档位，
     * 不再绕道 0~127（消除双重取整、精度更好）。min=0 时按同一自适应下限（`minStep` 空间）。
     */
    @JvmStatic
    fun curveToSystemIndex(step: Int, maxSteps: Int, minStep: Int, maxStep: Int, curveType: Int): Int {
        if (maxSteps <= 0 || step <= 0) {
            return 0
        }
        val range = clampSwapRange(maxSteps, minStep, maxStep)
        var lo = range[0]
        val hi = range[1]
        if (lo <= 0) {
            lo = max(1, (MIN_VOLUME_FLOOR_RATIO * hi).roundToInt())
        }
        if (lo > hi) {
            lo = hi
        }
        return applyCurve(step, maxSteps, lo, hi, curveType).coerceIn(0, maxSteps)
    }

    /** clamp+swap：把 [minStep,maxStep] 归一到 [0,maxSteps] 且 lo<=hi（curveToSystemIndex 与 defaultRangeToIndex 共用）。 */
    private fun clampSwapRange(maxSteps: Int, minStep: Int, maxStep: Int): IntArray {
        val lo = min(minStep, maxStep).coerceIn(0, maxSteps)
        val hi = max(minStep, maxStep).coerceIn(0, maxSteps)
        return intArrayOf(lo, hi)
    }

    /**
     * 默认模式映射（无曲线）：把用户档位的目标系统档位在 `[minStep,maxStep]` 间线性等分
     * 选取：`target = round(min + (max-min) * step / maxSteps)`。HAL 仍对目标档位施加其
     * 原生 dB，故中间听感保持原生曲线形状、仅两端被锚到 min/max。满量程
     * （`min=0 且 max=maxSteps`）时 `target=step`，恒等于直通。App 预览与 Hook 共用。
     */
    @JvmStatic
    fun defaultRangeToIndex(step: Int, maxSteps: Int, minStep: Int, maxStep: Int): Int {
        if (maxSteps <= 0 || step <= 0) {
            return 0
        }
        val range = clampSwapRange(maxSteps, minStep, maxStep)
        val value = (range[0] + (range[1] - range[0]) * step.toDouble() / maxSteps).roundToInt()
        return value.coerceIn(0, maxSteps)
    }

    /**
     * 以音量键步进为主，统计「相邻两次按键落到相同输出值」的次数：第 `i` 次按键到达档位
     * `keyStepLevel(i, maxSteps, keySteps)`（吸附到 `round(i*maxSteps/keySteps)` 网格），
     * 经 [levelToOutput] 映射为输出值后逐比较相邻按键。三种模式共用：模式A 传 AVRCP 映射、
     * 模式B 传系统档位直映射、默认传 [defaultRangeToIndex]。因按键序列只是全部档位的一个子集，
     * 重复对数明显少于「逐档统计」。
     */
    fun countDuplicateAtKeySteps(maxSteps: Int, keySteps: Int, levelToOutput: (Int) -> Int): Int {
        if (maxSteps <= 0) {
            return 0
        }
        val segs = Prefs.clampKeySteps(keySteps)
        var duplicates = 0
        var prevValue = -1
        for (press in 1..segs) {
            val level = Prefs.keyStepLevel(press, maxSteps, keySteps)
            val value = levelToOutput(level)
            if (press > 1 && value == prevValue) {
                duplicates++
            }
            prevValue = value
        }
        return duplicates
    }

    /** 预览「✓/⚠ 各次按键是否重复」判重块：整句按单位分条资源化（AVRCP 口径 / 系统档位口径）。 */
    private fun appendKeyStepDupHint(sb: StringBuilder, dups: Int, avrcpUnit: Boolean) {
        if (dups == 0) {
            sb.append(AppText.str(if (avrcpUnit) R.string.hint_dup_ok_avrcp else R.string.hint_dup_ok_sys))
        } else {
            sb.append(AppText.str(if (avrcpUnit) R.string.hint_dup_many_avrcp else R.string.hint_dup_many_sys, dups))
        }
    }

    /** 预览「第 1 档 → …」行 + 可闻下限提示：[stepLine] 由调用方整句给出（自带结尾换行）。 */
    private fun appendAudibleHint(sb: StringBuilder, stepLine: String, percent: Int, thresholdPct: Int) {
        sb.append(stepLine)
        sb.append(AppText.str(if (percent <= thresholdPct) R.string.hint_low_warn else R.string.hint_low_ok))
    }

    /**
     * 生成蓝牙音量预览文本（供界面直接显示）。
     *
     * @param maxSteps 生效后的媒体档位数
     * @param btMode [Prefs.BT_MODE_ABSOLUTE] 或 [Prefs.BT_MODE_SOFTWARE]
     * @param minAbs 最小音量（当前模式）
     * @param maxAbs 最大音量（当前模式）
     * @param curveType 曲线类型（[Prefs.CURVE_LOG]/[Prefs.CURVE_LINEAR]/[Prefs.CURVE_SQRT]）
     * @param keySteps 音量键步进（按键段数）：判重改以按键序列为准
     */
    @JvmStatic
    fun buildPreview(maxSteps: Int, btMode: Int, minAbs: Int, maxAbs: Int, curveType: Int, keySteps: Int): String {
        if (maxSteps <= 0) {
            return AppText.str(R.string.pv_no_data)
        }
        return if (btMode == Prefs.BT_MODE_SOFTWARE) {
            buildSoftwarePreview(maxSteps, minAbs, maxAbs, curveType, keySteps)
        } else {
            buildAbsolutePreview(maxSteps, minAbs, maxAbs, curveType, keySteps)
        }
    }

    /** 模式A：所选曲线分布 + 音量范围。 */
    private fun buildAbsolutePreview(
        maxSteps: Int, minAbs: Int, maxAbs: Int, curveType: Int, keySteps: Int,
    ): String {
        val range = normalizedRange(minAbs, maxAbs)
        val sb = StringBuilder()

        val duplicates = countDuplicateAtKeySteps(maxSteps, keySteps) { level ->
            curveToAbsoluteVolume(level, maxSteps, range[0], range[1], curveType)
        }
        val lowest = curveToAbsoluteVolume(1, maxSteps, range[0], range[1], curveType)
        val lowestPercent = (lowest * 100.0 / Prefs.AVRCP_MAX_VOLUME).roundToInt()
        val spacing = if (maxSteps >= 2) {
            curveToAbsoluteVolume(2, maxSteps, range[0], range[1], curveType) - lowest
        } else {
            0
        }

        sb.append(AppText.str(R.string.pv_abs_header, curveLabel(curveType)))
        sb.append(AppText.str(R.string.pv_abs_range, maxSteps, range[0], range[1]))
        appendKeyStepDupHint(sb, duplicates, avrcpUnit = true)
        sb.append(AppText.str(R.string.pv_abs_first, lowest, lowestPercent))
        sb.append(AppText.str(if (lowest <= ABS_LOW_FLOOR) R.string.hint_low_warn else R.string.hint_low_ok))
        if (maxSteps >= 2) {
            sb.append(AppText.str(R.string.pv_abs_spacing, spacing))
            sb.append(AppText.str(if (spacing < ABS_SPACING_MIN) R.string.pv_abs_spacing_small else R.string.pv_abs_spacing_ok))
        }
        sb.append(AppText.str(R.string.pv_abs_tip))
        return sb.toString()
    }

    /** 模式B：手机端软件衰减（系统档位直映射）。 */
    private fun buildSoftwarePreview(
        maxSteps: Int, minStep: Int, maxStep: Int, curveType: Int, keySteps: Int,
    ): String {
        val lowest = curveToSystemIndex(1, maxSteps, minStep, maxStep, curveType)
        val lowestPercent = (lowest * 100.0 / maxSteps).roundToInt()

        val sb = StringBuilder()
        sb.append(AppText.str(R.string.pv_soft_header, curveLabel(curveType)))
        sb.append(AppText.str(R.string.pv_soft_range, lowest, maxStep, maxSteps))

        val dups = countDuplicateAtKeySteps(maxSteps, keySteps) { level ->
            curveToSystemIndex(level, maxSteps, minStep, maxStep, curveType)
        }
        appendKeyStepDupHint(sb, dups, avrcpUnit = false)
        appendAudibleHint(
            sb,
            AppText.str(R.string.pv_first_sys_step, lowest, maxSteps, lowestPercent),
            lowestPercent, LOW_FLOOR_PCT,
        )
        sb.append(AppText.str(R.string.pv_soft_tip))
        return sb.toString()
    }

    /**
     * 默认模式：把系统档位在 `[minStep,maxStep]` 间线性等分重映射（无自定义曲线），两端锚到
     * min/max、中间保持 ROM 原生响度曲线（满量程时恒等于直通）。与模式A/B 同构的多段式（模式行 /
     * 范围行 / ✓⚠ 判重 / 第 1 档可闻下限 / 提示）；档位数与百分比均为变量。
     *
     * @param maxSteps 当前生效媒体档位数
     * @param minStep 当前窗口最小系统档位
     * @param maxStep 当前窗口最大系统档位
     * @param keySteps 音量键步进（按键段数）
     * @param nativeGainDb 该设备原生逐档 dB（长度 maxSteps+1，供第 1→2 档 dB 差值判定；可为 null）
     */
    @JvmStatic
    fun buildDefaultPreview(
        maxSteps: Int, minStep: Int, maxStep: Int, keySteps: Int, nativeGainDb: FloatArray?,
    ): String {
        if (maxSteps <= 0) {
            return AppText.str(R.string.pv_no_data)
        }
        val lo = min(minStep, maxStep)
        val hi = max(minStep, maxStep)
        val loPct = (lo * 100.0 / maxSteps).roundToInt()
        val hiPct = (hi * 100.0 / maxSteps).roundToInt()
        val dups = countDuplicateAtKeySteps(maxSteps, keySteps) { level ->
            defaultRangeToIndex(level, maxSteps, minStep, maxStep)
        }
        val lowest = defaultRangeToIndex(1, maxSteps, lo, hi)
        val lowestPercent = (lowest * 100.0 / maxSteps).roundToInt()

        val sb = StringBuilder()
        sb.append(AppText.str(R.string.pv_default_header))
        sb.append(AppText.str(R.string.pv_default_range, maxSteps, maxSteps, lo, hi, loPct, hiPct))
        appendKeyStepDupHint(sb, dups, avrcpUnit = false)
        appendAudibleHint(
            sb,
            AppText.str(R.string.pv_first_sys_step, lowest, maxSteps, lowestPercent),
            lowestPercent, LOW_FLOOR_PCT,
        )
        if (maxSteps >= 2 && nativeGainDb != null && nativeGainDb.size > 2) {
            val idx2 = defaultRangeToIndex(2, maxSteps, lo, hi)
            val dbGap = nativeGainDb[min(idx2, nativeGainDb.size - 1)] -
                nativeGainDb[min(lowest, nativeGainDb.size - 1)]
            sb.append(AppText.str(R.string.pv_default_spacing, String.format(Locale.US, "%.1f", dbGap)))
            sb.append(AppText.str(if (dbGap < DEFAULT_DB_GAP_MIN) R.string.pv_default_spacing_small else R.string.pv_default_spacing_ok))
        }
        sb.append(AppText.str(R.string.pv_default_tip))
        return sb.toString()
    }

    /**
     * 生成「按键次数 → 音量」映射表（等宽对齐、自然换行）：左列为音量键按下第几次（1~按键段数），
     * 右列为该次按键到达档位 `keyStepLevel(i, ...)`（吸附到 round(i*maxSteps/keySteps)）对应的输出值。
     * 三种模式共用此方法：`useSystemIndex=true`（模式B/耳机，system_server 软件衰减）右列为
     * 系统实际档位（0~maxSteps）；`false`（模式A，蓝牙 AVRCP）右列为 0~127 绝对音量。
     */
    @JvmStatic
    fun buildMappingTable(
        maxSteps: Int, useSystemIndex: Boolean, minAbs: Int, maxAbs: Int,
        curveType: Int, keySteps: Int, perLine: Int,
    ): String {
        if (maxSteps <= 0) {
            return ""
        }
        val segs = Prefs.clampKeySteps(keySteps)
        val column = if (useSystemIndex) {
            AppText.str(R.string.table_col_sys, maxSteps)
        } else {
            AppText.str(R.string.table_col_avrcp)
        }
        val valueMax = if (useSystemIndex) maxSteps else Prefs.AVRCP_MAX_VOLUME
        val cellFmt = "%" + max(2, segs.toString().length) + "d→%" +
            max(2, valueMax.toString().length) + "d"
        return renderKeyPressTable(AppText.str(R.string.table_press_title, column, segs), maxSteps, keySteps, perLine) { press, level ->
            val value = if (useSystemIndex) {
                curveToSystemIndex(level, maxSteps, minAbs, maxAbs, curveType)
            } else {
                curveToAbsoluteVolume(level, maxSteps, minAbs, maxAbs, curveType)
            }
            String.format(Locale.US, cellFmt, press, value)
        }
    }

    /**
     * 默认（系统原生）曲线映射表，横轴=档位：左列音量键按下第几次（1~按键段数），
     * 右列该次按键到达档位 `keyStepLevel` 的默认增益。[showDb] true 取硬件增益 dB，
     * false 取按 dB 归一的相对增益 0~100%。
     *
     * @param steps 当前档位数（横轴 1~steps，与模式A/B 一致取当前档位）
     * @param gainPct 长度 steps+1 的归一化增益（按档位索引）
     * @param gainDb 长度 steps+1 的硬件增益 dB（可为 null）
     * @param showDb true＝右列取 dB；false＝右列取百分比
     * @param keySteps 音量键步进（按键段数）
     * @param perLine 每行条目数（<=0 交回自然换行）
     */
    @JvmStatic
    fun buildNativeStepsTable(
        steps: Int, gainPct: FloatArray?, gainDb: FloatArray?,
        showDb: Boolean, keySteps: Int, perLine: Int,
    ): String {
        if (steps <= 0 || gainPct == null || gainPct.size < steps + 1) {
            return ""
        }
        val segs = Prefs.clampKeySteps(keySteps)
        val title = if (showDb) {
            AppText.str(R.string.table_native_db_title, segs)
        } else {
            AppText.str(R.string.table_native_pct_title, segs)
        }
        val cellFmt = "%" + max(2, segs.toString().length) + "d→" + if (showDb) "%6.1f" else "%3d%%"
        return renderKeyPressTable(title, steps, keySteps, perLine) { press, level ->
            if (showDb) {
                val db = if (gainDb != null && level < gainDb.size) gainDb[level] else 0f
                String.format(Locale.US, cellFmt, press, db)
            } else {
                val out = gainPct[level].coerceIn(0f, 100f).roundToInt()
                String.format(Locale.US, cellFmt, press, out)
            }
        }
    }

    /**
     * 默认（系统原生）曲线映射表，横轴=百分比：左列 XML 点表的档位百分比（[anchorPct]），
     * 右列该锚点增益（[showDb] 取 dB，否则取 0~100% 相对增益）。
     */
    @JvmStatic
    fun buildNativePercentTable(
        anchorPct: IntArray?, gainPct: FloatArray?, gainDb: FloatArray?,
        showDb: Boolean, perLine: Int,
    ): String {
        if (anchorPct == null || anchorPct.isEmpty()) {
            return ""
        }
        val pctWidth = max(2, anchorPct[anchorPct.size - 1].toString().length)
        val sb = StringBuilder()
        sb.append(
            if (showDb) {
                AppText.str(R.string.table_percent_db_title, anchorPct.size)
            } else {
                AppText.str(R.string.table_percent_pct_title, anchorPct.size)
            },
        )
        val cellFmt = "%${pctWidth}d%%→" + if (showDb) "%6.1f  " else "%3d%%  "
        for (i in anchorPct.indices) {
            if (showDb) {
                val db = if (gainDb != null && i < gainDb.size) gainDb[i] else 0f
                sb.append(String.format(Locale.US, cellFmt, anchorPct[i], db))
            } else {
                val out = if (gainPct != null && i < gainPct.size) {
                    gainPct[i].coerceIn(0f, 100f).roundToInt()
                } else {
                    0
                }
                sb.append(String.format(Locale.US, cellFmt, anchorPct[i], out))
            }
            if (i < anchorPct.size - 1 && perLine > 0 && (i + 1) % perLine == 0) {
                sb.append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * 「按键次数 → 输出值」映射表骨架：三种模式（A/B 传 int 输出值、默认传增益百分比/dB）共用。
     * 逐次按键取到达档位 `keyStepLevel(press, maxSteps, keySteps)`，交 [cell] 格式化为单元格；
     * 单元格间以两空格分隔、每满 [perLine] 个换行（`perLine<=0` 则交给文本组件自然换行）。
     */
    private fun renderKeyPressTable(
        title: String, maxSteps: Int, keySteps: Int, perLine: Int, cell: (press: Int, level: Int) -> String,
    ): String {
        val segs = Prefs.clampKeySteps(keySteps)
        val sb = StringBuilder(title)
        for (press in 1..segs) {
            val level = Prefs.keyStepLevel(press, maxSteps, keySteps)
            sb.append(cell(press, level))
            if (press < segs) {
                if (perLine > 0 && press % perLine == 0) {
                    sb.append('\n')
                } else {
                    sb.append("  ")
                }
            }
        }
        sb.append('\n')
        return sb.toString()
    }
}
