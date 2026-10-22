package com.xiefeihong.hypervolume.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.ConfigStore
import com.xiefeihong.hypervolume.app.ColumnEstimator
import com.xiefeihong.hypervolume.app.EditorState
import com.xiefeihong.hypervolume.app.NativeAxis
import com.xiefeihong.hypervolume.app.Preview
import com.xiefeihong.hypervolume.app.PreviewBuilder
import com.xiefeihong.hypervolume.app.SaveEngine
import com.xiefeihong.hypervolume.app.StatusSnapshot
import com.xiefeihong.hypervolume.app.deviceTabLabel
import com.xiefeihong.hypervolume.app.modeLabelShort
import com.xiefeihong.hypervolume.app.statusLines
import com.xiefeihong.hypervolume.core.NativeVolumeCurve
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.VolumeMode
import com.xiefeihong.hypervolume.ui.HyperVolumeViewModel
import com.xiefeihong.hypervolume.ui.component.BodyText
import com.xiefeihong.hypervolume.ui.component.ChipRow
import com.xiefeihong.hypervolume.ui.component.CurveChart
import com.xiefeihong.hypervolume.ui.component.HintText
import com.xiefeihong.hypervolume.ui.component.MonoText
import com.xiefeihong.hypervolume.ui.component.PrimaryButton
import com.xiefeihong.hypervolume.ui.component.SectionCard
import com.xiefeihong.hypervolume.ui.component.SectionDivider
import com.xiefeihong.hypervolume.ui.component.SecondaryButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlin.math.roundToInt

/**
 * 各分区界面（对应旧 `activity_main.xml` 的五张卡片）。
 *
 * 数据全部来自 [EditorState] / [StatusSnapshot] / [Preview] 快照，交互一律转成
 * ViewModel 意图；本文件不含业务判断，只有排版与文案装配。
 */

/** 运行状态卡：六行状态（Root / LSPosed / 模块配置 / 档位 / 蓝牙 / 音量模式）。 */
@Composable
fun StatusSection(snapshot: StatusSnapshot?) {
    val detecting = stringResource(R.string.status_detecting)
    val lines = snapshot?.let { statusLines(it) } ?: List(6) { detecting }
    SectionCard(title = stringResource(R.string.sec_status)) {
        lines.forEach { BodyText(it) }
    }
}

/** 媒体音量档位卡：总开关 + 档位滑条 + 音量键步进滑条。 */
@Composable
fun StepsSection(state: EditorState, preview: Preview, vm: HyperVolumeViewModel) {
    SectionCard(title = stringResource(R.string.sec_media)) {
        SwitchPreference(
            checked = state.enabled,
            onCheckedChange = vm::onToggleEnabled,
            title = stringResource(R.string.label_enable),
            summary = stringResource(R.string.label_enable_summary),
        )
        SectionDivider()
        // 关闭态仅锁定档位/步进滑条到系统默认，音量范围区仍保持可编辑（与旧实现一致）。
        SliderPreference(
            title = stringResource(R.string.label_media_steps),
            value = state.mediaSteps.toFloat(),
            onValueChange = { vm.onMediaSteps(it.roundToInt()) },
            valueText = "${state.mediaSteps}",
            valueRange = Prefs.MEDIA_STEPS_MIN.toFloat()..Prefs.MEDIA_STEPS_MAX.toFloat(),
            steps = Prefs.MEDIA_STEPS_MAX - Prefs.MEDIA_STEPS_MIN - 1,
            enabled = state.enabled,
        )
        HintText(preview.labels.mediaSteps + "\n" + stringResource(R.string.media_hint))
        SectionDivider()
        SliderPreference(
            title = stringResource(R.string.label_key_steps),
            value = state.keySteps.toFloat(),
            onValueChange = { vm.onKeySteps(it.roundToInt()) },
            valueText = "${state.keySteps}",
            valueRange = Prefs.KEY_STEP_MIN.toFloat()..Prefs.KEY_STEP_MAX.toFloat(),
            steps = Prefs.KEY_STEP_MAX - Prefs.KEY_STEP_MIN - 1,
            enabled = state.enabled,
        )
        HintText(preview.labels.keySteps + "\n" + stringResource(R.string.key_steps_hint))
    }
}

/**
 * 音量范围卡：设备选项卡（卡片外部、与卡片同级）+ 模式芯片 + 最小/最大滑条 + 曲线/口径选择 + 曲线图 + 映射预览。
 *
 * 设备选项卡按要求抬到 [Card] 外（req-1）：它切换的是**整张卡**的内容，不是卡内某个子项，
 * 因此与标题、卡片同级更符合 HyperCeiler 的层级。选项卡左划右划仍是切页（pager）手势，不冲突。
 *
 * [preview] 由上层统一构建（等宽表列数需实测可用宽度，ROM 真实曲线又是后台异步载入的），
 * 本页只消费预览数据：[Preview.curveBlock] 决定映射曲线选择器可见性，
 * [Preview.nativeSelectors] 决定默认曲线纵/横轴口径选择器可见性（与旧布局分组一致）。
 */
@Composable
fun RangeSection(state: EditorState, preview: Preview, vm: HyperVolumeViewModel) {
    val deviceTabs = ConfigStore.DEVICE_BY_TAB_ORDER.map { deviceTabLabel(it) }
    val tabIndex = deviceTabs.indexOf(deviceTabLabel(state.editingDevice)).coerceAtLeast(0)
    // 模式芯片 = 可选模式 id + 其标签：绝对音量（AVRCP）为蓝牙专属，有线耳机/外放不支持，
    // 因此**不列出该选项**（旧行为是列出但置灰）；历史配置里的非法绝对模式已由 EditorState 归一。
    val allowedModes = buildList {
        if (state.absoluteAllowed) add(Prefs.BT_MODE_ABSOLUTE)
        add(Prefs.BT_MODE_SOFTWARE)
        add(Prefs.BT_MODE_DEFAULT)
    }
    val modeOptions = allowedModes.map { modeLabelShort(it) }
    val currentMode = when {
        state.showingDefault -> Prefs.BT_MODE_DEFAULT
        state.absoluteEditing -> Prefs.BT_MODE_ABSOLUTE
        else -> Prefs.BT_MODE_SOFTWARE
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp)) {
        SmallTitle(text = stringResource(R.string.sec_range))
        TabRow(
            tabs = deviceTabs,
            selectedTabIndex = tabIndex,
            onTabSelected = vm::onSelectDeviceTab,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        Card {
            ChipRow(
                options = modeOptions,
                selectedIndex = allowedModes.indexOf(currentMode).coerceAtLeast(0),
                onSelect = { index ->
                    when (allowedModes.getOrElse(index) { Prefs.BT_MODE_DEFAULT }) {
                        Prefs.BT_MODE_ABSOLUTE -> vm.onSelectMode(VolumeMode.ABSOLUTE)
                        Prefs.BT_MODE_SOFTWARE -> vm.onSelectMode(VolumeMode.SOFTWARE)
                        else -> vm.onSelectDefault()
                    }
                },
            )
            MonoText(preview.summary)
            SectionDivider()
            SliderPreference(
                title = preview.minLabel,
                value = state.rangeMin.toFloat(),
                onValueChange = { vm.onRangeMin(it.roundToInt()) },
                valueText = "${state.rangeMin}",
                valueRange = 0f..state.rangeValueMax.toFloat(),
                steps = (state.rangeValueMax - 1).coerceAtLeast(0),
            )
            SliderPreference(
                title = preview.maxLabel,
                value = state.rangeMax.toFloat(),
                onValueChange = { vm.onRangeMax(it.roundToInt()) },
                valueText = "${state.rangeMax}",
                valueRange = 0f..state.rangeValueMax.toFloat(),
                steps = (state.rangeValueMax - 1).coerceAtLeast(0),
            )
            if (preview.curveBlock) {
                CurveSelector(state = state, vm = vm)
            }
            if (preview.nativeSelectors) {
                NativeAxisSelectors(state = state, vm = vm)
            }
            SectionDivider()
            CurveChart(spec = preview.chart, modifier = Modifier.padding(horizontal = 16.dp))
            MonoText(preview.mappingTable)
            HintText(stringResource(R.string.range_hint))
        }
    }
}

/** 操作卡：未保存/未生效提示 + 保存、重启蓝牙、重启系统框架。 */
@Composable
fun ActionsSection(state: EditorState, snapshot: StatusSnapshot?, busy: Boolean, vm: HyperVolumeViewModel) {
    val pending = when {
        state.hasUnsavedChanges -> SaveEngine.Messages.pendingUnsaved
        else -> snapshot?.pendingText ?: stringResource(R.string.status_detecting)
    }
    SectionCard(title = stringResource(R.string.sec_actions)) {
        BodyText(pending)
        SectionDivider()
        PrimaryButton(
            text = stringResource(R.string.btn_save),
            onClick = vm::onSave,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SecondaryButton(
            text = stringResource(R.string.btn_restart_bt),
            onClick = vm::onSaveAndRestartBluetooth,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SecondaryButton(
            text = stringResource(R.string.btn_apply_restart),
            onClick = vm::onAskRestartSystem,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HintText(stringResource(R.string.ops_hint))
    }
}

/** 使用说明卡。 */
@Composable
fun GuideSection() {
    SectionCard(title = stringResource(R.string.sec_guide)) {
        HintText(stringResource(R.string.guide_text))
    }
}

// ==================== 内部 ====================

/** 映射曲线选择（仅 A/B 模式可见）。 */
@Composable
private fun CurveSelector(state: EditorState, vm: HyperVolumeViewModel) {
    OverlaySpinnerPreference(
        items = listOf(
            DropdownItem(stringResource(R.string.curve_log)),
            DropdownItem(stringResource(R.string.curve_linear)),
            DropdownItem(stringResource(R.string.curve_sqrt)),
        ),
        selectedIndex = Prefs.clampCurve(state.curve),
        title = stringResource(R.string.sec_curve),
        onSelectedIndexChange = vm::onCurve,
    )
    HintText(stringResource(R.string.curve_hint))
}

/** 默认（系统原生）曲线的纵轴/横轴口径选择（仅「默认」模式可见）。 */
@Composable
private fun NativeAxisSelectors(state: EditorState, vm: HyperVolumeViewModel) {
    OverlaySpinnerPreference(
        items = listOf(
            DropdownItem(stringResource(R.string.axis_perceptual)),
            DropdownItem(stringResource(R.string.axis_db_linear)),
        ),
        selectedIndex = if (state.nativeMode == NativeVolumeCurve.Mode.DB_LINEAR) 1 else 0,
        title = stringResource(R.string.axis_y_title),
        onSelectedIndexChange = vm::onNativeModeIndex,
    )
    OverlaySpinnerPreference(
        items = listOf(
            DropdownItem(stringResource(R.string.axis_steps)),
            DropdownItem(stringResource(R.string.axis_percent)),
        ),
        selectedIndex = if (state.nativeAxis == NativeAxis.PERCENT) 1 else 0,
        title = stringResource(R.string.axis_x_title),
        onSelectedIndexChange = vm::onNativeAxisIndex,
    )
}

/**
 * 按当前编辑态构建预览（纯计算）。
 *
 * [availablePx] 为等宽表可用像素宽度，用于换算每行可容纳的列数（等价旧
 * `measureCellColumns`）；测不出宽度时返回 0，交回自然换行。[curveRevision] 自增后
 * 强制重算（后台读完 ROM 真实曲线时界面需要重绘默认曲线）。
 */
@Composable
internal fun rememberPreview(state: EditorState, availablePx: Int, curveRevision: Int): Preview {
    val deviceLabel = deviceTabLabel(state.editingDevice)
    val estimator = rememberColumnEstimator(availablePx)
    return remember(state, deviceLabel, estimator, curveRevision) {
        PreviewBuilder.build(state, deviceLabel, estimator)
    }
}

/** 等宽字体实测单元格宽度 → 每行可容纳列数的估算器（旧 `ConfigEditor.measureCellColumns`）。 */
@Composable
internal fun rememberColumnEstimator(availablePx: Int): ColumnEstimator {
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val density = LocalDensity.current
    return remember(measurer, style, availablePx, density) {
        ColumnEstimator { cell ->
            if (availablePx <= 0) {
                0
            } else {
                val width = measurer.measure(text = cell, style = style, density = density).size.width
                if (width <= 0) 0 else availablePx / width
            }
        }
    }
}
