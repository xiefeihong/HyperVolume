package com.xiefeihong.hypervolume.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.ui.AppRoute
import com.xiefeihong.hypervolume.ui.HyperVolumeViewModel
import com.xiefeihong.hypervolume.ui.PageColumn
import com.xiefeihong.hypervolume.ui.UiState
import com.xiefeihong.hypervolume.ui.component.BodyText
import com.xiefeihong.hypervolume.ui.component.HintText
import com.xiefeihong.hypervolume.ui.component.SectionCard
import com.xiefeihong.hypervolume.ui.component.SectionDivider
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

/**
 * 设置页：通用 / 备份与恢复 / 故障排除 三标签（参考 HyperCeiler 的设置页分区）。
 *
 * 标签位由 [UiState.settingsTab] 持有并落盘，重开 App 回到上次的标签。
 */
@Composable
fun SettingsScreen(state: UiState, vm: HyperVolumeViewModel) {
    val tabs = listOf(
        stringResource(R.string.tab_general),
        stringResource(R.string.tab_backup),
        stringResource(R.string.tab_troubleshoot),
    )
    PageColumn {
        TabRow(
            tabs = tabs,
            selectedTabIndex = state.settingsTab.coerceIn(0, tabs.lastIndex),
            onTabSelected = vm::onSettingsTab,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            // 默认上限 98dp 会把英文标签截成 "Backup & r…"，放宽后三个标签均分整行
            maxWidth = 200.dp,
            itemSpacing = 6.dp,
        )
        when (state.settingsTab) {
            TAB_BACKUP -> BackupSection(vm)
            TAB_TROUBLESHOOT -> TroubleshootSection(vm)
            else -> GeneralSection(state = state, vm = vm)
        }
    }
}

/** 通用：语言与颜色（语言切换后由界面重建 Activity 生效）。 */
@Composable
private fun GeneralSection(state: UiState, vm: HyperVolumeViewModel) {
    // 语言切换后文案会变，不包 remember（remember 内也不能调 stringResource）
    val languageItems = listOf(
        DropdownItem(stringResource(R.string.lang_system)),
        DropdownItem(stringResource(R.string.lang_zh)),
        DropdownItem(stringResource(R.string.lang_en)),
    )
    val themeItems = listOf(
        DropdownItem(stringResource(R.string.theme_system)),
        DropdownItem(stringResource(R.string.theme_light)),
        DropdownItem(stringResource(R.string.theme_dark)),
    )
    SectionCard(title = stringResource(R.string.tab_general)) {
        OverlaySpinnerPreference(
            items = languageItems,
            selectedIndex = state.language.ordinal,
            title = stringResource(R.string.pref_language),
            startAction = { Icon(imageVector = MiuixIcons.Translate, contentDescription = null) },
            onSelectedIndexChange = vm::onLanguage,
        )
        SectionDivider()
        OverlaySpinnerPreference(
            items = themeItems,
            selectedIndex = state.themeMode.ordinal,
            title = stringResource(R.string.pref_theme),
            startAction = { Icon(imageVector = MiuixIcons.Theme, contentDescription = null) },
            onSelectedIndexChange = vm::onThemeMode,
        )
        HintText(stringResource(R.string.pref_language_summary))
    }
}

/** 备份与恢复：SAF 文档选择器导出/导入 JSON 存档。 */
@Composable
private fun BackupSection(vm: HyperVolumeViewModel) {
    val defaultName = stringResource(R.string.backup_file_name)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_JSON),
    ) { uri -> uri?.let(vm::exportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importBackup) }

    SectionCard(title = stringResource(R.string.tab_backup)) {
        ArrowPreference(
            title = stringResource(R.string.backup_export),
            summary = stringResource(R.string.backup_export_summary),
            startAction = { Icon(imageVector = MiuixIcons.Backup, contentDescription = null) },
            onClick = { exportLauncher.launch(defaultName) },
        )
        SectionDivider()
        ArrowPreference(
            title = stringResource(R.string.backup_import),
            summary = stringResource(R.string.backup_import_summary),
            startAction = { Icon(imageVector = MiuixIcons.Import, contentDescription = null) },
            onClick = { importLauncher.launch(arrayOf(MIME_JSON, "text/plain", "*/*")) },
        )
        SectionDivider()
        BodyText(stringResource(R.string.backup_scope_title))
        HintText(stringResource(R.string.backup_scope_body))
        HintText(stringResource(R.string.backup_intro))
    }
}

/** 故障排除：调试信息 + 模块日志（均为全屏子页）+ 手动重新检测运行状态。 */
@Composable
private fun TroubleshootSection(vm: HyperVolumeViewModel) {
    SectionCard(title = stringResource(R.string.tab_troubleshoot)) {
        ArrowPreference(
            title = stringResource(R.string.debug_info),
            summary = stringResource(R.string.debug_info_summary),
            startAction = { Icon(imageVector = MiuixIcons.Report, contentDescription = null) },
            onClick = { vm.openRoute(AppRoute.DEBUG) },
        )
        SectionDivider()
        ArrowPreference(
            title = stringResource(R.string.btn_logs),
            summary = stringResource(R.string.log_page_summary),
            startAction = { Icon(imageVector = MiuixIcons.Settings, contentDescription = null) },
            onClick = { vm.openRoute(AppRoute.LOGS) },
        )
        SectionDivider()
        ArrowPreference(
            title = stringResource(R.string.btn_refresh),
            summary = stringResource(R.string.refresh_summary),
            startAction = { Icon(imageVector = MiuixIcons.Refresh, contentDescription = null) },
            onClick = vm::refreshStatus,
        )
    }
}

private const val TAB_BACKUP = 1
private const val TAB_TROUBLESHOOT = 2
private const val MIME_JSON = "application/json"
