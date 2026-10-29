package com.xiefeihong.hypervolume.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.BlockedChoice
import com.xiefeihong.hypervolume.app.SaveEngine
import com.xiefeihong.hypervolume.ui.DialogSpec
import com.xiefeihong.hypervolume.ui.HyperVolumeViewModel
import com.xiefeihong.hypervolume.ui.component.DialogButtons
import com.xiefeihong.hypervolume.ui.component.PrimaryButton
import com.xiefeihong.hypervolume.ui.component.SecondaryButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 全部弹框（移植自旧 `AlertDialog` 调用点）。
 *
 * 统一走 Miuix [OverlayDialog]：`title` + `summary` 承载标题与正文——文案全部来自 app 层的
 * [SaveEngine.Messages]，界面只负责装配按钮与动作，保持与旧实现逐条对应。
 * 模块日志不再占弹框，已改为「设置 → 故障排除」里的全屏日志页（req-3/5）。
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
            // 旧实现的四个处理入口，纵向排布（推荐项用主按钮）。
            // 间距与 [DialogButtons] 对齐：外层留 16dp/12dp，按钮之间统一 8dp——
            // 旧写法把 padding 逐个加在按钮上（首个只给了 horizontal），导致第一、二个之间只有 4dp。
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(
                    text = SaveEngine.Messages.resolveEnable,
                    onClick = { vm.onBlockedChoice(BlockedChoice.ENABLE_STEPS) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = SaveEngine.Messages.resolveDefaultMode,
                    onClick = { vm.onBlockedChoice(BlockedChoice.DEFAULT_MODE) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = SaveEngine.Messages.resolveDefaultSteps,
                    onClick = { vm.onBlockedChoice(BlockedChoice.DEFAULT_STEPS) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = SaveEngine.Messages.cancel,
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
    }
}
