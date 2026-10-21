package com.xiefeihong.hypervolume.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.StatusProbe
import com.xiefeihong.hypervolume.app.debugInfoLines
import com.xiefeihong.hypervolume.app.deviceInfoLines
import com.xiefeihong.hypervolume.ui.HyperVolumeViewModel
import com.xiefeihong.hypervolume.ui.PageColumn
import com.xiefeihong.hypervolume.ui.UiState
import com.xiefeihong.hypervolume.ui.component.BodyText
import com.xiefeihong.hypervolume.ui.component.HintText
import com.xiefeihong.hypervolume.ui.component.MonoText
import com.xiefeihong.hypervolume.ui.component.PrimaryButton
import com.xiefeihong.hypervolume.ui.component.SectionCard
import com.xiefeihong.hypervolume.ui.component.SectionDivider
import com.xiefeihong.hypervolume.ui.component.SecondaryButton

/**
 * 全屏子页：模块日志、调试信息、设备信息、开源许可。
 *
 * 三页共用同一排版：等宽正文装进卡片，底部一排动作按钮（复制 / 清理 / 刷新）。
 * 顶栏标题与返回由 `HyperVolumeApp` 依 [UiState.route] 统一提供。
 */

/** 模块日志页：系统框架 + 蓝牙 + LSPosed + logcat 合并结果。 */
@Composable
fun LogsScreen(state: UiState, vm: HyperVolumeViewModel) {
    val context = LocalContext.current
    val emptyHint = stringResource(R.string.log_empty)
    val copied = stringResource(R.string.toast_log_copied)
    val text = state.logs ?: (if (state.logLoading) stringResource(R.string.log_loading) else emptyHint)
    PageColumn {
        SectionCard {
            MonoText(text)
        }
        ActionRow {
            SecondaryButton(
                text = stringResource(R.string.dlg_copy),
                onClick = {
                    copyToClipboard(context, StatusProbe.logTitle(), state.logs.orEmpty())
                    vm.notify(copied)
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = stringResource(R.string.dlg_clear),
                onClick = vm::clearLogs,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = stringResource(R.string.btn_refresh),
                onClick = vm::reloadLogs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 调试信息页：版本 / 设备 / Root / LSPosed / 档位数来源 / 两段配置原文。 */
@Composable
fun DebugScreen(state: UiState, vm: HyperVolumeViewModel) {
    val context = LocalContext.current
    val copied = stringResource(R.string.toast_copied)
    val title = stringResource(R.string.debug_title)
    val text = state.debug?.let { debugInfoLines(it).joinToString("\n") }
        ?: stringResource(R.string.debug_loading)
    PageColumn {
        SectionCard {
            MonoText(text)
        }
        ActionRow {
            SecondaryButton(
                text = stringResource(R.string.debug_copy_all),
                onClick = {
                    copyToClipboard(context, title, text)
                    vm.notify(copied)
                },
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = stringResource(R.string.btn_refresh),
                onClick = vm::reloadDebug,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 设备信息页（关于页入口）：调试信息的硬件 / 系统子集。 */
@Composable
fun DeviceInfoScreen(state: UiState, vm: HyperVolumeViewModel) {
    val context = LocalContext.current
    val copied = stringResource(R.string.toast_copied)
    val title = stringResource(R.string.about_device_info)
    val text = state.debug?.let { deviceInfoLines(it).joinToString("\n") }
        ?: stringResource(R.string.debug_loading)
    PageColumn {
        SectionCard {
            MonoText(text)
        }
        ActionRow {
            SecondaryButton(
                text = stringResource(R.string.debug_copy_all),
                onClick = {
                    copyToClipboard(context, title, text)
                    vm.notify(copied)
                },
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = stringResource(R.string.btn_refresh),
                onClick = vm::reloadDebug,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 开源许可页：本项目与依赖库的许可证。 */
@Composable
fun LicensesScreen() {
    val licenses = listOf(
        stringResource(R.string.license_gpl) to stringResource(R.string.license_gpl_summary),
        stringResource(R.string.license_miuix) to stringResource(R.string.license_miuix_summary),
        stringResource(R.string.license_yuki) to stringResource(R.string.license_yuki_summary),
        stringResource(R.string.license_kava) to stringResource(R.string.license_kava_summary),
        stringResource(R.string.license_libxposed) to stringResource(R.string.license_libxposed_summary),
    )
    PageColumn {
        SectionCard(title = stringResource(R.string.about_licenses)) {
            licenses.forEachIndexed { index, (name, license) ->
                BodyText(name)
                HintText(license)
                if (index < licenses.lastIndex) SectionDivider()
            }
        }
    }
}

/** 子页底部动作行（等宽并排，主按钮在最右）。 */
@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}
