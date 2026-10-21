package com.xiefeihong.hypervolume.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.ThemeMode
import com.xiefeihong.hypervolume.ui.screen.AboutScreen
import com.xiefeihong.hypervolume.ui.screen.ActionsSection
import com.xiefeihong.hypervolume.ui.screen.AppDialogs
import com.xiefeihong.hypervolume.ui.screen.DebugScreen
import com.xiefeihong.hypervolume.ui.screen.DeviceInfoScreen
import com.xiefeihong.hypervolume.ui.screen.LicensesScreen
import com.xiefeihong.hypervolume.ui.screen.LogsScreen
import com.xiefeihong.hypervolume.ui.screen.RangeSection
import com.xiefeihong.hypervolume.ui.screen.SettingsScreen
import com.xiefeihong.hypervolume.ui.screen.StatusSection
import com.xiefeihong.hypervolume.ui.screen.StepsSection
import com.xiefeihong.hypervolume.ui.screen.rememberPreview
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用主界面（HyperCeiler 风格：三页横滑 + 卡片流 + 全屏子页）。
 *
 * 主页（0）→ 设置（1）→ 关于（2）由 [HorizontalPager] 承载：**左划前进、右划返回**
 * （标准 pager 语义），子页顶栏出现返回箭头，系统返回键逐级退回上一页。
 * 日志 / 调试信息 / 开源许可 / 设备信息是 [AppRoute] 全屏子页，覆盖在 pager 之上。
 *
 * 预览（摘要 / 曲线图 / 映射表）只在主页内**算一次**：等宽表列数依赖实测可用宽度，
 * 故主页整列包在 [BoxWithConstraints] 中取宽度，再分发给档位卡与范围卡。
 */
@Composable
fun HyperVolumeApp(vm: HyperVolumeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 旧实现的 Toast 入口：瞬时提示（保存结果、重启中、失败原因等）。
    LaunchedEffect(vm) {
        vm.notices.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    // 回到前台仅刷新状态；设备标签只在冷启动定位一次，不随当前输出自动切走（req-5）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }

    // 语言偏好与本次 attach 生效值不一致时重建 Activity：attachBaseContext 会重新装载资源 locale。
    val activity = context as? MainActivity
    LaunchedEffect(state.language) {
        if (activity != null && activity.appliedLanguage != state.language) activity.recreate()
    }

    // 颜色模式经 ThemeController 生效（Miuix 主题可即时切换，无需重建界面）。
    val controller = remember(state.themeMode) {
        ThemeController(colorSchemeMode = state.themeMode.toColorSchemeMode())
    }

    MiuixTheme(controller = controller) {
        val pagerState = rememberPagerState { PAGE_COUNT }
        val scope = rememberCoroutineScope()
        val route = state.route
        val onMainPages = route == AppRoute.NONE

        // 返回键：子页优先退回主界面，其次 pager 退回上一页（与顶栏返回箭头同一动作）。
        BackHandler(enabled = !onMainPages) { vm.closeRoute() }
        BackHandler(enabled = onMainPages && pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        }
        val goPreviousPage = {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        }

        Scaffold(
            topBar = {
                val appBarTitle = when {
                    !onMainPages -> stringResource(route.titleRes())
                    pagerState.currentPage == PAGE_HOME -> stringResource(R.string.app_name)
                    else -> stringResource(if (pagerState.currentPage == PAGE_SETTINGS) R.string.nav_settings else R.string.nav_about)
                }
                val appBarSubtitle = if (onMainPages && pagerState.currentPage == PAGE_HOME) {
                    stringResource(R.string.app_subtitle)
                } else {
                    ""
                }
                val showBack = !onMainPages || pagerState.currentPage > 0
                TopAppBar(
                    title = appBarTitle,
                    subtitle = appBarSubtitle,
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = { if (onMainPages) goPreviousPage() else vm.closeRoute() }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.nav_back),
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (onMainPages) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            PAGE_SETTINGS -> SettingsScreen(state = state, vm = vm)
                            PAGE_ABOUT -> AboutScreen(state = state, vm = vm)
                            else -> HomeScreen(state = state, vm = vm)
                        }
                    }
                } else {
                    when (route) {
                        AppRoute.LOGS -> LogsScreen(state = state, vm = vm)
                        AppRoute.DEBUG -> DebugScreen(state = state, vm = vm)
                        AppRoute.DEVICE -> DeviceInfoScreen(state = state, vm = vm)
                        AppRoute.LICENSES -> LicensesScreen()
                    }
                }
                // 弹框必须挂在 Scaffold 内容区内：Miuix 的 OverlayDialog 不自己开窗口，而是把状态
                // 注进 Scaffold 提供的 LocalDialogStates（popupHost 默认渲染 MiuixPopupHost），
                // 写在 Scaffold 之外拿不到宿主，点了只会改状态而不会弹框。
                AppDialogs(spec = state.dialog, vm = vm)
            }
        }
    }
}

/** 主页内容：运行状态 → 媒体音量档位 → 音量范围 → 操作（使用说明已迁至关于页）。 */
@Composable
private fun HomeScreen(state: UiState, vm: HyperVolumeViewModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 卡片左右外边距 12dp + 卡内文字缩进 16dp，等宽表可用宽度需一并扣除。
        val availablePx = constraints.maxWidth - with(LocalDensity.current) { 56.dp.roundToPx() }
        val preview = rememberPreview(state.editor, availablePx, state.curveRevision)
        PageColumn {
            StatusSection(state.status)
            StepsSection(state.editor, preview, vm)
            RangeSection(state.editor, preview, vm)
            ActionsSection(state.editor, state.status, state.busy, vm)
        }
    }
}

/** 可纵向滚动的整页容器（三页与子页共用：左右不留白，卡片自带 12dp 外边距）。 */
@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
        content = content,
    )
}

/** pager 页序：主页 / 设置 / 关于。 */
private const val PAGE_HOME = 0
private const val PAGE_SETTINGS = 1
private const val PAGE_ABOUT = 2
private const val PAGE_COUNT = 3

/** 颜色偏好 → Miuix 主题模式（不使用 Monet 动态取色，保持与 HyperCeiler 一致的固定配色）。 */
private fun ThemeMode.toColorSchemeMode(): ColorSchemeMode = when (this) {
    ThemeMode.LIGHT -> ColorSchemeMode.Light
    ThemeMode.DARK -> ColorSchemeMode.Dark
    ThemeMode.SYSTEM -> ColorSchemeMode.System
}

/** 子页顶栏标题资源。 */
private fun AppRoute.titleRes(): Int = when (this) {
    AppRoute.LOGS -> R.string.log_title
    AppRoute.DEBUG -> R.string.debug_title
    AppRoute.LICENSES -> R.string.about_licenses
    AppRoute.DEVICE -> R.string.about_device_info
    AppRoute.NONE -> R.string.app_name
}
