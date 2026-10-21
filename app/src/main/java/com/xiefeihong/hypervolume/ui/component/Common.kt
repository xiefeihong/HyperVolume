package com.xiefeihong.hypervolume.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 界面通用组件（HyperCeiler 风格的分区卡片 + 芯片选择行）。
 *
 * 全部薄封装：只做「Miuix 基础件 + 间距/配色」的组合，不含任何业务判断，
 * 业务态一律由上层从 [com.xiefeihong.hypervolume.app.EditorState] 快照传入。
 */

/** 分区容器：可选 [title] 走 Miuix `SmallTitle`，内容包进圆角 [Card]（HyperCeiler 卡片流式排版）。 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp)) {
        if (title != null) {
            SmallTitle(text = title)
        }
        Card {
            content()
        }
    }
}

/** 卡片内的说明文字（次要色、小字号），等价旧布局的 `*_hint` TextView。 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
    )
}

/** 普通正文行（状态区、标签等）。 */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 13.sp,
    )
}

/** 等宽正文：预览摘要与映射表（列对齐由 [com.xiefeihong.hypervolume.app.PreviewBuilder] 算好）。 */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/** 卡片内分隔线（左右留出与内容一致的缩进）。 */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** 单选芯片（模式/曲线选择行）；选中态用主题主色底，不可用态整体降透明度。 */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    Surface(
        onClick = onClick,
        // Miuix Surface 的 enabled = false 只吞掉点击、不会置灰：外放/有线耳机上
        // 「绝对音量」不可选时需要自己降低透明度，否则看不出被禁用（旧布局由 RadioButton 置灰）。
        modifier = modifier.alpha(if (enabled) 1f else 0.38f),
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) colors.primaryVariant else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp,
        )
    }
}

/** 芯片行：等间距左对齐，整行可横向换行由调用方决定（各芯片互斥，由 [selected] 高亮）。 */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabledAt: (Int) -> Boolean = { true },
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            Chip(
                text = label,
                selected = index == selectedIndex,
                enabled = enabledAt(index),
                onClick = { onSelect(index) },
            )
        }
    }
}

/** 弹框底部按钮行（右对齐，主按钮在最右）。 */
@Composable
fun DialogButtons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** 主要按钮（保存 / 立即重启等）：主色底，与次要按钮区分层级。 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        // Button 默认 colors 是次要灰底，与 SecondaryButton 看不出主次，这里显式取主色配色。
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button)
    }
}

/** 次要按钮（取消 / 稍后手动等）。 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
}
