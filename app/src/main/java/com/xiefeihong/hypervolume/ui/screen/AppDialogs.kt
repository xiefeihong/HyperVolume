package com.xiefeihong.hypervolume.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.BlockedChoice
import com.xiefeihong.hypervolume.app.SaveEngine
import com.xiefeihong.hypervolume.app.StatusProbe
import com.xiefeihong.hypervolume.ui.DialogSpec
import com.xiefeihong.hypervolume.ui.HyperVolumeViewModel
import com.xiefeihong.hypervolume.ui.component.DialogButtons
import com.xiefeihong.hypervolume.ui.component.MonoText
import com.xiefeihong.hypervolume.ui.component.PrimaryButton
import com.xiefeihong.hypervolume.ui.component.SecondaryButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 全部弹框（移植自旧 `AlertDialog` 调用点）。
 *
 * 统一走 Miuix [OverlayDialog]：`title` + `summary` 承载标题与正文——文案全部来自 app 层的
 * [SaveEngine.Messages] / [StatusProbe]，界面只负责装配按钮与动作，保持与旧实现逐条对应。
 */
@Composable
fun AppDialogs(spec: DialogSpec, vm: HyperVolumeViewModel) {
    val dismiss = { vm.dismissDialog() }
    when (spec) {
        DialogSpec.None -> Unit

        is DialogSpec.SaveConfirm -> OverlayDialog(
            show = true,
            title = SaveEngine.Messages.saveTitle,
            summary = spec.plan.message,
            onDismissRequest = dismiss,
        ) {
            DialogButtons {
                SecondaryButton(text = SaveEngine.Messages.cancel, onClick = dismiss)
                PrimaryButton(text = SaveEngine.Messages.saveConfirm, onClick = vm::onConfirmSave)
            }
        }

        is DialogSpec.Blocked -> OverlayDialog(
            show = true,
            title = SaveEngine.Messages.saveNeedSystemDefaultTitle,
            summary = spec.plan.message,
            onDismissRequest = dismiss,
        ) {
            // 旧实现的三个处理入口，纵向排布（推荐项用主按钮）
            PrimaryButton(
                text = SaveEngine.Messages.resolveEnable,
                onClick = { vm.onBlockedChoice(BlockedChoice.ENABLE_STEPS) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            SecondaryButton(
                text = SaveEngine.Messages.resolveDefaultMode,
                onClick = { vm.onBlockedChoice(BlockedChoice.DEFAULT_MODE) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SecondaryButton(
                text = SaveEngine.Messages.resolveDefaultSteps,
                onClick = { vm.onBlockedChoice(BlockedChoice.DEFAULT_STEPS) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SecondaryButton(
                text = SaveEngine.Messages.cancel,
                onClick = dismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        is DialogSpec.Restart -> OverlayDialog(
            show = true,
            title = SaveEngine.Messages.saveDoneTitle,
            summary = SaveEngine.Messages.restartNowMsg(spec.restartBt, spec.restartSystem),
            onDismissRequest = dismiss,
        ) {
            DialogButtons {
                SecondaryButton(text = SaveEngine.Messages.restartLater, onClick = dismiss)
                PrimaryButton(text = SaveEngine.Messages.restartNow, onClick = vm::onRestartNow)
            }
        }

        is DialogSpec.ClearData -> OverlayDialog(
            show = true,
            title = SaveEngine.Messages.clearDataTitle,
            summary = SaveEngine.Messages.clearDataMsg,
            onDismissRequest = dismiss,
        ) {
            DialogButtons {
                SecondaryButton(text = SaveEngine.Messages.cancel, onClick = dismiss)
                PrimaryButton(text = SaveEngine.Messages.clearDataConfirm, onClick = vm::onClearData)
            }
        }

        DialogSpec.RestartSystemConfirm -> OverlayDialog(
            show = true,
            title = SaveEngine.Messages.restartSystemTitle,
            summary = SaveEngine.Messages.restartSystemMsg,
            onDismissRequest = dismiss,
        ) {
            DialogButtons {
                SecondaryButton(text = SaveEngine.Messages.cancel, onClick = dismiss)
                PrimaryButton(
                    text = SaveEngine.Messages.restartSystemConfirm,
                    onClick = vm::onSaveAndRestartSystem,
                )
            }
        }

        is DialogSpec.Notice -> OverlayDialog(
            show = true,
            title = spec.title,
            summary = spec.text,
            onDismissRequest = dismiss,
        ) {
            DialogButtons {
                PrimaryButton(text = stringResource(R.string.dlg_close), onClick = dismiss)
            }
        }

        is DialogSpec.Logs -> LogsDialog(text = spec.text, vm = vm, dismiss = dismiss)
    }
}

/** 模块日志框：等宽可滚动正文 + 复制 / 清理 / 关闭。 */
@Composable
private fun LogsDialog(text: String, vm: HyperVolumeViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.toast_log_copied)
    OverlayDialog(
        show = true,
        title = StatusProbe.LOG_TITLE,
        onDismissRequest = dismiss,
    ) {
        MonoText(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
        )
        DialogButtons {
            SecondaryButton(
                text = stringResource(R.string.dlg_copy),
                onClick = {
                    copyLog(context, text)
                    vm.notify(copied)
                },
            )
            SecondaryButton(text = stringResource(R.string.dlg_clear), onClick = vm::clearLogs)
            PrimaryButton(text = stringResource(R.string.dlg_close), onClick = dismiss)
        }
    }
}

/** 复制日志到系统剪贴板。 */
private fun copyLog(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(StatusProbe.LOG_TITLE, text))
}
