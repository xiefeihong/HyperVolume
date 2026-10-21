package com.xiefeihong.hypervolume.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.ui.HyperVolumeViewModel

/**
 * 各界面共用的轻量辅助（剪贴板、外链、版本信息）。
 *
 * 只包装系统 API，不含业务判断；失败一律经 ViewModel 的 [HyperVolumeViewModel.notify] 提示。
 */

/** 复制文本到系统剪贴板（日志、调试信息、映射表共用）。 */
internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 用外部浏览器打开链接；无可用应用时提示（不崩溃）。 */
@Composable
internal fun rememberUrlOpener(vm: HyperVolumeViewModel): (String) -> Unit {
    val context = LocalContext.current
    return remember(context, vm) {
        { url ->
            val opened = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            // 带实参的整句走 AppText（与界面同 locale），避开手拼 formatArgs
            if (!opened) vm.notify(AppText.str(R.string.about_open_fail, url))
        }
    }
}

/** 版本卡展示的 `versionName` 与构建类型（与调试信息同一判定口径：FLAG_DEBUGGABLE）。 */
@Composable
internal fun rememberBuildInfo(): Pair<String, String> {
    val context = LocalContext.current
    return remember(context) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val variant =
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) "debug" else "release"
        version to variant
    }
}
