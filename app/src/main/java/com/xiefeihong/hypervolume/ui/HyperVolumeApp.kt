package com.xiefeihong.hypervolume.ui

import android.widget.Toast
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.ui.screen.ActionsSection
import com.xiefeihong.hypervolume.ui.screen.AppDialogs
import com.xiefeihong.hypervolume.ui.screen.GuideSection
import com.xiefeihong.hypervolume.ui.screen.RangeSection
import com.xiefeihong.hypervolume.ui.screen.StatusSection
import com.xiefeihong.hypervolume.ui.screen.StepsSection
import com.xiefeihong.hypervolume.ui.screen.rememberPreview
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用主界面（HyperCeiler 风格：顶栏 + 卡片流 + 底部弹框）。
 *
 * 分区顺序与旧 `activity_main.xml` 一致：运行状态 → 媒体音量档位 → 音量范围 → 操作 → 使用说明。
 * 预览（摘要 / 曲线图 / 映射表）在这里**只算一次**：等宽表列数依赖实测可用宽度，故整页包在
 * [BoxWithConstraints] 中取宽度，再分发给档位卡与范围卡。
 */
@Composable
fun HyperVolumeApp(vm: HyperVolumeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current

    // 旧实现的 Toast 入口：瞬时提示（保存结果、重启中、失败原因等）。
    LaunchedEffect(vm) {
        vm.notices.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    // 回到前台仅刷新状态；设备标签只在冷启动定位一次，不随当前输出自动切走（req-5）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }

    MiuixTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.app_subtitle),
                    actions = {
                        IconButton(onClick = vm::refreshStatus) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.btn_refresh),
                            )
                        }
                        IconButton(onClick = vm::showLogs) {
                            Icon(
                                imageVector = MiuixIcons.Notes,
                                contentDescription = stringResource(R.string.btn_logs),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // 卡片左右外边距 12dp + 卡内文字缩进 16dp，等宽表可用宽度需一并扣除。
                val availablePx = constraints.maxWidth - with(density) { 56.dp.roundToPx() }
                val preview = rememberPreview(state.editor, availablePx, state.curveRevision)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 28.dp),
                ) {
                    StatusSection(state.status)
                    StepsSection(state.editor, preview, vm)
                    RangeSection(state.editor, preview, vm)
                    ActionsSection(state.editor, state.status, state.busy, vm)
                    GuideSection()
                }
                // 弹框必须挂在 Scaffold 内容区内：Miuix 的 OverlayDialog 不自己开窗口，而是把状态
                // 注进 Scaffold 提供的 LocalDialogStates（popupHost 默认渲染 MiuixPopupHost），
                // 写在 Scaffold 之外拿不到宿主，点了只会改状态而不会弹框。
                AppDialogs(spec = state.dialog, vm = vm)
            }
        }
    }
}
