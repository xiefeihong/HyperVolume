package com.xiefeihong.hypervolume.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Compose 宿主（旧 `MainActivity` 只负责装配控件与协作者，这里只剩两件事）：
 * 全屏边衬 + 把 [HyperVolumeViewModel] 交给 [HyperVolumeApp] 渲染。
 *
 * 状态刷新、设备定位、保存/重启等职责全部在 ViewModel 内（旧 `onResume` 的
 * `refreshStatus()` 由 `LifecycleEventEffect(ON_RESUME)` 等价实现）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏/导航栏透明（themes.xml），边衬由 Miuix Scaffold 的 window insets 统一处理。
        enableEdgeToEdge()
        setContent {
            HyperVolumeApp(vm = viewModel())
        }
    }
}
