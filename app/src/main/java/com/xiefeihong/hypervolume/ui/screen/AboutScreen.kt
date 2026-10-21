package com.xiefeihong.hypervolume.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.UpdateChecker
import com.xiefeihong.hypervolume.ui.AppRoute
import com.xiefeihong.hypervolume.ui.HyperVolumeViewModel
import com.xiefeihong.hypervolume.ui.PageColumn
import com.xiefeihong.hypervolume.ui.UiState
import com.xiefeihong.hypervolume.ui.component.BodyText
import com.xiefeihong.hypervolume.ui.component.HintText
import com.xiefeihong.hypervolume.ui.component.SectionCard
import com.xiefeihong.hypervolume.ui.component.SectionDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 关于页（参考 HyperCeiler）：版本与检查更新、项目链接、开源许可、设备信息、致谢、使用说明。
 *
 * 使用说明自旧主页迁入（req-4），直接复用主页的 [GuideSection] 排版，文案仍是 `guide_text`。
 */
@Composable
fun AboutScreen(state: UiState, vm: HyperVolumeViewModel) {
    val (version, variant) = rememberBuildInfo()
    val openUrl = rememberUrlOpener(vm)
    PageColumn {
        SectionCard(title = stringResource(R.string.about_version_card)) {
            HintText(stringResource(R.string.module_description))
            BodyText(stringResource(R.string.about_current_version, version))
            BodyText(stringResource(R.string.about_build_variant, variant))
            SectionDivider()
            ArrowPreference(
                title = stringResource(R.string.about_check_update),
                summary = stringResource(R.string.about_check_update_summary),
                startAction = { Icon(imageVector = MiuixIcons.Update, contentDescription = null) },
                enabled = !state.busy,
                onClick = vm::checkUpdate,
            )
        }
        SectionCard {
            ArrowPreference(
                title = stringResource(R.string.about_github),
                summary = stringResource(R.string.about_github_summary),
                startAction = { Icon(imageVector = MiuixIcons.Link, contentDescription = null) },
                onClick = { openUrl(UpdateChecker.REPO_URL) },
            )
            SectionDivider()
            ArrowPreference(
                title = stringResource(R.string.about_licenses),
                summary = stringResource(R.string.about_licenses_summary),
                startAction = { Icon(imageVector = MiuixIcons.Info, contentDescription = null) },
                onClick = { vm.openRoute(AppRoute.LICENSES) },
            )
            SectionDivider()
            ArrowPreference(
                title = stringResource(R.string.about_device_info),
                summary = stringResource(R.string.about_device_info_summary),
                startAction = { Icon(imageVector = MiuixIcons.SearchDevice, contentDescription = null) },
                onClick = { vm.openRoute(AppRoute.DEVICE) },
            )
        }
        SectionCard(title = stringResource(R.string.about_credits)) {
            HintText(stringResource(R.string.about_credits_summary))
            BodyText(stringResource(R.string.about_credits_body))
        }
        GuideSection()
    }
}
