package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.core.Avrcp
import com.xiefeihong.hypervolume.core.NativeVolumeCurve
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 预览数据构建（移植自旧 `ConfigEditor.updatePreview` 的**计算**部分）。
 *
 * 旧实现在这里直接刷 TextView / SeekBar / CurveChartView；Compose 侧只需要数据：
 * 文案、映射表文本、曲线图参数。等宽单元换行列数依赖实测像素宽度，因此改由
 * UI 通过 [ColumnEstimator] 注入（等价旧的 `measureCellColumns`），本对象保持纯函数、
 * 可在 JVM 单测中直接断言输出文本。
 */
object PreviewBuilder {

    /**
     * dB 模式映射表右列用 `%6.1f` 格式；[computeTableColumns] 靠 valueMax 的位数估算列宽，
     * 传此 6 位哨兵值使估宽贴近 dB 列实际宽度（其数值不参与 dB 表渲染）。
     */
    const val DB_COLUMN_WIDTH_SENTINEL = 999999

    /**
     * 由编辑态快照构建预览。
     *
     * @param deviceLabel 当前设备的界面名称（「蓝牙」/「有线耳机」/「外放」），用于默认曲线图注。
     * @param estimator 依代表单元格文本估算每行可容纳的列数（<=0 表示交回自然换行）。
     */
    fun build(state: EditorState, deviceLabel: String, estimator: ColumnEstimator): Preview {
        val mediaSteps = Prefs.clampMediaSteps(state.mediaSteps)
        val keySteps = Prefs.clampKeySteps(state.keySteps)
        val segs = Prefs.clampKeySteps(keySteps)
        val labels = PreviewLabels(
            mediaSteps = AppText.str(R.string.pv_label_media_steps, mediaSteps),
            keySteps = AppText.str(R.string.pv_label_key_steps, keySteps, Prefs.keyDelta(mediaSteps, keySteps)),
        )
        return if (state.showingDefault) {
            buildDefaultPreview(state, deviceLabel, estimator, mediaSteps, keySteps, segs, labels)
        } else {
            buildModePreview(state, estimator, mediaSteps, keySteps, segs, labels)
        }
    }

    // ==================== 默认（系统原生曲线） ====================

    private fun buildDefaultPreview(
        state: EditorState, deviceLabel: String, estimator: ColumnEstimator,
        mediaSteps: Int, keySteps: Int, segs: Int, labels: PreviewLabels,
    ): Preview {
        val device = toNativeDevice(state.editingDevice)
        val showDb = state.nativeMode == NativeVolumeCurve.Mode.DB_LINEAR
        val minStep = Prefs.clampSystemStep(state.rangeMin, mediaSteps)
        val maxStep = Prefs.clampSystemStep(state.rangeMax, mediaSteps)
        val curve = NativeVolumeCurve.curveFor(device, mediaSteps, state.nativeMode)
        val minLabel = AppText.str(R.string.pv_min_label, minStep, (minStep * 100.0 / mediaSteps).roundToInt())
        val maxLabel = AppText.str(R.string.pv_max_label, maxStep, (maxStep * 100.0 / mediaSteps).roundToInt())
        val summary = Avrcp.buildDefaultPreview(mediaSteps, minStep, maxStep, keySteps, curve.gainDb)

        if (state.nativeAxis == NativeAxis.PERCENT) {
            val anchors = NativeVolumeCurve.anchorsFor(device, state.nativeMode)
            var minPct = (minStep * 100.0 / mediaSteps).roundToInt()
            var maxPct = (maxStep * 100.0 / mediaSteps).roundToInt()
            if (maxPct < minPct) {
                val tmp = minPct
                minPct = maxPct
                maxPct = tmp
            }
            // 仅取窗口 [minPct,maxPct] 内的点位：窗口内 XML 锚点 + 两端点（保证覆盖窗口）。
            val points = (anchors.percent.toList().filter { it in minPct..maxPct } + listOf(minPct, maxPct))
                .distinct().sorted()
            val windowPct = points.toIntArray()
            val windowGain = FloatArray(points.size) { anchors.gainByPercent[points[it]] }
            val windowDb = FloatArray(points.size) { anchors.gainDbByPercent[points[it]] }
            val caption = AppText.str(
                R.string.caption_native_percent,
                deviceLabel,
                sourceLabel(anchors.source),
                modeLabel(anchors.mode),
                minPct,
                maxPct,
            )
            // 曲线图：横轴窗口化到 [minPct,maxPct]（从设定最小值开始），仅取窗内原生增益。
            // 纵轴：dB 口径时以整条曲线 floor/ceil dB（百分 0/100 处）标注。
            val chart = ChartSpec.Native(
                xMin = minPct, xMax = maxPct,
                gainByX = anchors.gainByPercent, dots = anchors.percent,
                percentAxis = true, showDbAxis = showDb,
                dbFloor = anchors.gainDbByPercent.first(), dbCeil = anchors.gainDbByPercent.last(),
                caption = caption,
            )
            val table = Avrcp.buildNativePercentTable(
                windowPct, windowGain, windowDb, showDb,
                computePercentTableColumns(windowPct.last(), showDb, estimator),
            )
            return Preview(labels, minLabel, maxLabel, summary, table, chart, nativeSelectors = true, curveBlock = false)
        }

        // 默认：与 A/B 同范式，按当前 [min,max] 把窗内原生响度曲线重算（目标档位线性等分）。
        val effGain = FloatArray(mediaSteps + 1)
        val effDb = FloatArray(mediaSteps + 1)
        for (i in 0..mediaSteps) {
            val t = Avrcp.defaultRangeToIndex(i, mediaSteps, minStep, maxStep)
            effGain[i] = curve.gainPercent[t]
            effDb[i] = curve.gainDb[t]
        }
        val caption = AppText.str(
            R.string.caption_native_steps,
            deviceLabel,
            sourceLabel(curve.source),
            modeLabel(curve.mode),
            minStep,
            maxStep,
        )
        val chart = ChartSpec.Native(
            xMin = 0, xMax = mediaSteps,
            gainByX = effGain, dots = buildPressLevels(mediaSteps, keySteps),
            percentAxis = false, showDbAxis = showDb,
            dbFloor = curve.gainDb.first(), dbCeil = curve.gainDb[mediaSteps],
            caption = caption,
        )
        val table = Avrcp.buildNativeStepsTable(
            mediaSteps, effGain, effDb, showDb, keySteps,
            computeTableColumns(segs, if (showDb) DB_COLUMN_WIDTH_SENTINEL else 100, !showDb, estimator),
        )
        return Preview(labels, minLabel, maxLabel, summary, table, chart, nativeSelectors = true, curveBlock = false)
    }

    // ==================== 模式A / 模式B ====================

    private fun buildModePreview(
        state: EditorState, estimator: ColumnEstimator,
        mediaSteps: Int, keySteps: Int, segs: Int, labels: PreviewLabels,
    ): Preview {
        val minAbs = min(state.rangeMin, state.rangeMax)
        val maxAbs = max(state.rangeMin, state.rangeMax)
        val useSystemIndex = state.editingMode.attenuatesInSystemServer
        val valueMax = if (useSystemIndex) mediaSteps else Prefs.AVRCP_MAX_VOLUME
        val minLabel = AppText.str(R.string.pv_min_label, minAbs, (minAbs * 100.0 / valueMax).roundToInt())
        val maxLabel = AppText.str(R.string.pv_max_label, maxAbs, (maxAbs * 100.0 / valueMax).roundToInt())
        // 关闭「启用档位修改」时档位/步进滑条锁到系统默认，故按当前快照的档位/步进 +
        // A/B 的最小/最大/曲线渲染预览；音量范围编辑区仍保持可用。
        val summary = Avrcp.buildPreview(
            mediaSteps, state.editingMode.modeId, minAbs, maxAbs, state.curve, keySteps,
        )
        val chart = ChartSpec.Mapped(
            maxSteps = mediaSteps, minAbs = minAbs, maxAbs = maxAbs, curveType = state.curve,
            useSystemIndex = useSystemIndex, keySteps = keySteps,
        )
        val table = Avrcp.buildMappingTable(
            mediaSteps, useSystemIndex, minAbs, maxAbs, state.curve, keySteps,
            computeTableColumns(segs, valueMax, false, estimator),
        )
        return Preview(labels, minLabel, maxLabel, summary, table, chart, nativeSelectors = false, curveBlock = true)
    }

    // ==================== 辅助 ====================

    /** 构造音量键逐次按下的落点数组（含 0 档）：`[0, keyStepLevel(1..segs)]`，供默认曲线图圆点。 */
    private fun buildPressLevels(maxSteps: Int, keySteps: Int): IntArray {
        val segments = Prefs.clampKeySteps(keySteps)
        val levels = IntArray(segments + 1)
        levels[0] = 0
        for (i in 1..segments) {
            levels[i] = Prefs.keyStepLevel(i, maxSteps, keySteps)
        }
        return levels
    }

    /** 依单元格宽度与可用宽度估算映射表每行可容纳的单元个数（<=0 表示交回自然换行）。 */
    private fun computeTableColumns(
        segs: Int, valueMax: Int, percent: Boolean, estimator: ColumnEstimator,
    ): Int {
        if (segs <= 0) {
            return 0
        }
        val pressWidth = max(2, segs.toString().length)
        val valWidth = max(2, valueMax.toString().length)
        val cell = String.format(
            Locale.US, "%${pressWidth}d→%${valWidth}d${if (percent) "%%" else ""}  ", segs, valueMax,
        )
        return estimator.columns(cell)
    }

    /**
     * 百分比映射表专用列宽估算：左列为档位百分比（带 `%` 后缀），右列 dB（`%6.1f`）
     * 或百分比（`%3d%%`）。与 [Avrcp.buildNativePercentTable] 的实际单元格格式严格对齐，
     * 避免宽度低估导致每行最后一个数据换行。
     */
    private fun computePercentTableColumns(
        maxAnchorPct: Int, showDb: Boolean, estimator: ColumnEstimator,
    ): Int {
        val pctWidth = max(2, maxAnchorPct.toString().length)
        val cell = if (showDb) {
            String.format(Locale.US, "%${pctWidth}d%%→%6.1f  ", maxAnchorPct, -1.0)
        } else {
            String.format(Locale.US, "%${pctWidth}d%%→%3d%%  ", maxAnchorPct, 100)
        }
        return estimator.columns(cell)
    }

    /**
     * [OutputDevice]（UI/配置维度）→ [NativeVolumeCurve.Device]（原生曲线维度）显式映射。
     * 两个枚举当前恰好同序（SPEAKER/WIRED/BT），但刻意用显式 `when` 而非 `values()[ordinal]`
     * 解耦，避免任一枚举调序时静默错位。
     */
    private fun toNativeDevice(device: OutputDevice): NativeVolumeCurve.Device = when (device) {
        OutputDevice.WIRED -> NativeVolumeCurve.Device.WIRED
        OutputDevice.BT -> NativeVolumeCurve.Device.BT
        OutputDevice.SPEAKER -> NativeVolumeCurve.Device.SPEAKER
    }

    /** 曲线数据来源（引擎表/策略表/AOSP）的显示文案。 */
    private fun sourceLabel(source: NativeVolumeCurve.Source): String = AppText.str(
        when (source) {
            NativeVolumeCurve.Source.ENGINE -> R.string.source_engine
            NativeVolumeCurve.Source.POLICY -> R.string.source_policy
            NativeVolumeCurve.Source.AOSP -> R.string.source_aosp
        },
    )

    /** 纵轴换算口径（dB线性/振幅/感知）的显示文案。 */
    private fun modeLabel(mode: NativeVolumeCurve.Mode): String = AppText.str(
        when (mode) {
            NativeVolumeCurve.Mode.AMPLITUDE -> R.string.axis_amplitude
            NativeVolumeCurve.Mode.PERCEPTUAL -> R.string.axis_perceptual
            NativeVolumeCurve.Mode.DB_LINEAR -> R.string.axis_db_linear
        },
    )
}

/** 依代表单元格文本估算「每行可容纳几个单元」；由 UI 用实测宽度实现（<=0 表示自然换行）。 */
fun interface ColumnEstimator {

    /** 返回一行可容纳的单元数；无法测量时返回 0（交回自然换行）。 */
    fun columns(cell: String): Int
}

/** 档位/步进两行滑条说明文案。 */
data class PreviewLabels(val mediaSteps: String, val keySteps: String)

/** 曲线图绘制参数：A/B 模式逐档采样映射函数，默认模式直接画原生增益曲线。 */
sealed interface ChartSpec {

    /** 绝对/相对模式：逐档位采样 [Avrcp] 映射函数，与预览文本、实际下发完全一致。 */
    data class Mapped(
        val maxSteps: Int,
        val minAbs: Int,
        val maxAbs: Int,
        val curveType: Int,
        val useSystemIndex: Boolean,
        val keySteps: Int,
    ) : ChartSpec

    /** 「默认（系统原生）」专用：按 [gainByX] 逐单位连成真实默认曲线，横轴可窗口化到 `[xMin,xMax]`。 */
    class Native(
        /** 横轴左端值（窗口起点：百分比模式可为设定最小值对应百分比；常规为 0）。 */
        val xMin: Int,
        /** 横轴右端值（档位模式=当前档位数，百分比模式=窗口上限）。 */
        val xMax: Int,
        /** 按绝对横轴索引的归一化增益（0~100%，长度至少 `xMax+1`，仅取 `[xMin,xMax]` 段）。 */
        val gainByX: FloatArray,
        /** 落点圆点横坐标（绝对值；超出 `[xMin,xMax]` 的被裁剪）。 */
        val dots: IntArray,
        /** 横轴是否取百分比（影响轴标签）。 */
        val percentAxis: Boolean,
        /** 纵轴是否按 dB 标注（仅「dB 线性」口径为 true，否则显示 0~100%）。 */
        val showDbAxis: Boolean,
        /** 纵轴底端（0%）对应 dB 值。 */
        val dbFloor: Float,
        /** 纵轴顶端（100%）对应 dB 值。 */
        val dbCeil: Float,
        /** 图注（设备 · 数据来源 · 纵轴口径 · 范围）。 */
        val caption: String,
    ) : ChartSpec {

        override fun equals(other: Any?): Boolean = this === other ||
            (other is Native && other.xMin == xMin && other.xMax == xMax &&
                other.percentAxis == percentAxis && other.showDbAxis == showDbAxis &&
                other.dbFloor == dbFloor && other.dbCeil == dbCeil && other.caption == caption &&
                other.gainByX.contentEquals(gainByX) && other.dots.contentEquals(dots))

        override fun hashCode(): Int {
            var result = xMin
            result = 31 * result + xMax
            result = 31 * result + percentAxis.hashCode()
            result = 31 * result + showDbAxis.hashCode()
            result = 31 * result + dbFloor.hashCode()
            result = 31 * result + dbCeil.hashCode()
            result = 31 * result + caption.hashCode()
            result = 31 * result + gainByX.contentHashCode()
            result = 31 * result + dots.contentHashCode()
            return result
        }
    }
}

/** 一次预览渲染所需的全部展示数据。 */
data class Preview(
    val labels: PreviewLabels,
    val minLabel: String,
    val maxLabel: String,
    val summary: String,
    val mappingTable: String,
    val chart: ChartSpec,
    /** 是否显示默认曲线的纵/横轴口径选择器（仅「默认」模式可见）。 */
    val nativeSelectors: Boolean,
    /** 是否显示映射曲线选择区（「默认」模式无自定义曲线，隐藏）。 */
    val curveBlock: Boolean,
)
