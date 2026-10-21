package com.xiefeihong.hypervolume.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.ChartSpec
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.core.Avrcp
import com.xiefeihong.hypervolume.core.Prefs
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 音量映射曲线图（移植自旧 `CurveChartView`，改为 Compose `Canvas` + 绝对定位轴标签）。
 *
 * 横轴＝音量级数档位（0~maxSteps，默认·百分比模式可窗口化到 `[xMin,xMax]`），纵轴＝该档位经
 * 所选曲线映射后的输出值：模式A 为 AVRCP 绝对音量（0~127），模式B/耳机模式为换算后的系统档位；
 * 「默认（系统原生）」则按 [com.xiefeihong.hypervolume.core.NativeVolumeCurve] 的真实增益渲染。
 * 曲线与预览文本、实际下发共用 [Avrcp] 同一映射函数，叠加圆点是音量键逐次按键落点，直观呈现
 * 「按键段数」把连续级数量化成整数倍停点。
 *
 * 两条数据路径（A/B 与默认）在 [toPlotModel] 各自打包成统一 [PlotModel]，绘制只认单一模型
 * ——与旧实现消除 `drawNative`/`onDraw` 两套重复逻辑的做法一致。
 */
@Composable
fun CurveChart(spec: ChartSpec, modifier: Modifier = Modifier, chartHeight: Dp = 240.dp) {
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val accent = MiuixTheme.colorScheme.primary
    val model = remember(spec) { toPlotModel(spec) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // dB 纵轴需容纳“-100dB”等较宽标签，适当加宽左边距（与旧 onDraw 同值）。
        val padL = if (model.yLabelBottom != null) 42.dp else 34.dp
        val padR = 8.dp
        val padT = 18.dp
        val padB = 20.dp
        val labelWidth = padL - 5.dp
        val labelHeight = 14.dp
        val left = with(density) { padL.toPx() }
        val right = widthPx - with(density) { padR.toPx() }
        val top = with(density) { padT.toPx() }
        val bottom = heightPx - with(density) { padB.toPx() }
        val spanX = (model.xMax - model.xMin).toFloat()
        val drawable = right > left && bottom > top && spanX > 0f && model.samples.size >= 2

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (drawable) {
                val grid = labelColor.copy(alpha = 0.15f)
                val axis = labelColor.copy(alpha = 0.55f)
                // 纵向网格：落点驱动时逐点一条，否则按绘图宽度三等分
                if (model.dotsDriveGrid) {
                    for (dx in model.dots) {
                        if (dx in model.xMin..model.xMax) {
                            val gx = mapX(dx.toFloat(), model, left, right, spanX)
                            drawLine(grid, Offset(gx, top), Offset(gx, bottom), 1.dp.toPx())
                        }
                    }
                } else {
                    for (p in 0..2) {
                        val gx = left + (right - left) * p / 2f
                        drawLine(grid, Offset(gx, top), Offset(gx, bottom), 1.dp.toPx())
                    }
                }
                // 横向网格：0 / 50% / 100%
                for (p in 0..2) {
                    val gy = bottom - (bottom - top) * (p / 2f)
                    drawLine(grid, Offset(left, gy), Offset(right, gy), 1.dp.toPx())
                }
                // 坐标轴框
                drawRect(
                    color = axis,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                // 曲线：逐横轴单位连线
                val path = Path()
                path.moveTo(
                    mapX(model.xMin.toFloat(), model, left, right, spanX),
                    mapY(sampleAt(model, model.xMin.toFloat()), top, bottom, model.yMax),
                )
                for (x in model.xMin + 1..model.xMax) {
                    path.lineTo(
                        mapX(x.toFloat(), model, left, right, spanX),
                        mapY(sampleAt(model, x.toFloat()), top, bottom, model.yMax),
                    )
                }
                drawPath(
                    path = path,
                    color = accent,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
                // 落点圆点（仅落在窗口内）
                val radius = 3.dp.toPx()
                for (dx in model.dots) {
                    if (dx in model.xMin..model.xMax) {
                        drawCircle(
                            color = accent,
                            radius = radius,
                            center = Offset(
                                mapX(dx.toFloat(), model, left, right, spanX),
                                mapY(sampleAt(model, dx.toFloat()), top, bottom, model.yMax),
                            ),
                        )
                    }
                }
            }
        }

        if (drawable) {
            // 图注（左上，落在纵轴上方预留区内）
            Text(
                text = model.caption,
                color = labelColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = padL, top = 1.dp),
            )
            // 纵轴标签：右对齐贴纵轴左侧（底端仅在 dB 口径显示）
            model.yLabelBottom?.let {
                AxisLabel(it, bottom, labelWidth, labelHeight, labelColor)
            }
            AxisLabel(model.yLabelMid, bottom - (bottom - top) * 0.5f, labelWidth, labelHeight, labelColor)
            AxisLabel(model.yLabelTop, top, labelWidth, labelHeight, labelColor)
            // 横轴标签：端点反映窗口，百分比口径带 % 后缀
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, (bottom + 3.dp.toPx()).roundToInt()) }
                    .fillMaxWidth()
                    .padding(start = padL, end = padR),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (model.xIsPercent) "${model.xMin}%" else model.xMin.toString(),
                    color = labelColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (model.xIsPercent) "${model.xMax}%" else model.xMax.toString(),
                    color = labelColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 纵轴单条标签：右对齐贴在纵轴左侧、垂直居中于 [centerY]（宽度 [width] 落在左边距内）。 */
@Composable
private fun AxisLabel(
    text: String,
    centerY: Float,
    width: Dp,
    height: Dp,
    color: Color,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(0, (centerY - with(density) { height.toPx() } / 2f).roundToInt()) }
            .width(width)
            .height(height),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text = text, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * 统一渲染模型：两条数据路径（A/B 映射、默认原生）各自打包成一份，绘制只认它。
 * 字段语义与旧 `CurveChartView.PlotModel` 逐一对齐，保证视觉输出不变。
 */
private class PlotModel(
    /** 横轴左端（含），常规为 0；默认·百分比模式可为窗口起点。 */
    val xMin: Int,
    /** 横轴右端（含），恒大于 [xMin]。 */
    val xMax: Int,
    /** 横轴标签是否带 `%` 后缀（默认·百分比模式）。 */
    val xIsPercent: Boolean,
    /** 逐单位纵轴原始值：`samples[i]`＝横轴绝对值 `xMin+i` 的纵轴值。 */
    val samples: FloatArray,
    /** 纵轴满量程：A/B＝127(AVRCP) 或档位数；默认＝100（归一增益百分比）。 */
    val yMax: Float,
    /** 底端纵轴标签（仅 dB 口径非空，此时左边距加宽容纳宽标签）。 */
    val yLabelBottom: String?,
    /** 中间（50% 高度）纵轴标签。 */
    val yLabelMid: String,
    /** 顶端（100% 高度）纵轴标签。 */
    val yLabelTop: String,
    /** 落点圆点的横轴绝对坐标（无可落点时为空数组）。 */
    val dots: IntArray,
    /** 纵向网格是否按 [dots] 逐点绘制（false 则按绘图宽度三等分）。 */
    val dotsDriveGrid: Boolean,
    /** 图注（含前缀，直接绘制）。 */
    val caption: String,
)

/** [ChartSpec.Mapped] → PlotModel：逐档位采样 [Avrcp] 映射函数，圆点为音量键逐次按键落点。 */
private fun toPlotModel(spec: ChartSpec.Mapped): PlotModel {
    val steps = max(1, spec.maxSteps)
    val segs = Prefs.clampKeySteps(spec.keySteps)
    val yMax = if (spec.useSystemIndex) steps else Prefs.AVRCP_MAX_VOLUME
    val samples = FloatArray(steps + 1) { s ->
        if (spec.useSystemIndex) {
            Avrcp.curveToSystemIndex(s, steps, spec.minAbs, spec.maxAbs, spec.curveType)
        } else {
            Avrcp.curveToAbsoluteVolume(s, steps, spec.minAbs, spec.maxAbs, spec.curveType)
        }.toFloat()
    }
    val dots = IntArray(segs + 1) { i -> if (i == 0) 0 else Prefs.keyStepLevel(i, steps, segs) }
    return PlotModel(
        xMin = 0, xMax = steps, xIsPercent = false, samples = samples, yMax = yMax.toFloat(),
        yLabelBottom = null, yLabelMid = (yMax / 2).toString(), yLabelTop = yMax.toString(),
        dots = dots, dotsDriveGrid = true,
        caption = AppText.str(
            R.string.chart_mapped_caption,
            Avrcp.curveLabel(spec.curveType),
            AppText.str(if (spec.useSystemIndex) R.string.chart_axis_sys else R.string.chart_axis_avrcp),
            spec.minAbs,
            spec.maxAbs,
        ),
    )
}

/** [ChartSpec.Native] → PlotModel：按 `gainByX` 逐单位连成真实默认曲线，横轴可窗口化。 */
private fun toPlotModel(spec: ChartSpec.Native): PlotModel {
    val xMin = max(0, spec.xMin)
    val xMax = max(xMin + 1, spec.xMax)
    val samples = FloatArray(xMax - xMin + 1) { gainAt(spec.gainByX, xMin + it) }
    val dots = spec.dots
    return PlotModel(
        xMin = xMin, xMax = xMax, xIsPercent = spec.percentAxis, samples = samples, yMax = 100f,
        yLabelBottom = if (spec.showDbAxis) fmtDb(spec.dbFloor) else null,
        yLabelMid = if (spec.showDbAxis) fmtDb((spec.dbFloor + spec.dbCeil) / 2f) else "50%",
        yLabelTop = if (spec.showDbAxis) fmtDb(spec.dbCeil) else "100%",
        dots = dots,
        // 档位模式：纵向网格按按键落点；百分比模式：无落点语义，三等分
        dotsDriveGrid = !spec.percentAxis && dots.isNotEmpty(),
        caption = AppText.str(R.string.chart_native_caption, spec.caption),
    )
}

private fun toPlotModel(spec: ChartSpec): PlotModel = when (spec) {
    is ChartSpec.Mapped -> toPlotModel(spec)
    is ChartSpec.Native -> toPlotModel(spec)
}

/** 取默认曲线第 [level] 档的归一化增益百分比（越界钳到数组末端）。 */
private fun gainAt(gainByX: FloatArray, level: Int): Float =
    if (gainByX.isEmpty()) 0f else gainByX[level.coerceIn(0, gainByX.lastIndex)]

/** 按绝对横轴值取模型样本（越界钳到样本数组两端）。 */
private fun sampleAt(model: PlotModel, x: Float): Float =
    model.samples[(x - model.xMin).roundToInt().coerceIn(0, model.samples.lastIndex)]

/** 横轴绝对值 → 画布 x：按窗口 `[xMin, xMax]` 线性映射。 */
private fun mapX(x: Float, model: PlotModel, left: Float, right: Float, spanX: Float): Float =
    left + (right - left) * ((x - model.xMin) / spanX)

/** 纵轴原始值 → 画布 y：按 `yMax` 满量程钳制后线性映射（顶=满量程、底=0）。 */
private fun mapY(value: Float, top: Float, bottom: Float, yMax: Float): Float =
    bottom - (bottom - top) * (max(0f, min(yMax, value)) / yMax)

/** dB 值轴标签（四舍五入取整、带 dB 单位），如 `-48dB`、`0dB`。 */
private fun fmtDb(db: Float): String = String.format(Locale.US, "%ddB", db.roundToInt())
