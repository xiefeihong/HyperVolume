package com.xiefeihong.hypervolume.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiefeihong.hypervolume.app.AppLanguage
import com.xiefeihong.hypervolume.app.SettingsStore
import com.xiefeihong.hypervolume.app.localizedFor
import com.xiefeihong.hypervolume.core.AppText

/**
 * Compose 宿主（旧 `MainActivity` 只负责装配控件与协作者，这里只剩三件事）：
 * 全屏边衬 + 按语言偏好装载资源 + 把 [HyperVolumeViewModel] 交给 [HyperVolumeApp] 渲染。
 *
 * 状态刷新、设备定位、保存/重启等职责全部在 ViewModel 内（旧 `onResume` 的
 * `refreshStatus()` 由 `LifecycleEventEffect(ON_RESUME)` 等价实现）。
 *
 * 语言用 `createConfigurationContext` 覆盖本 App 的 `Configuration`（不改系统语言、
 * 不需 AppCompatDelegate）；[AppText] 同步装上同一 locale 的 context，使 app/core 层
 * 动态文案与 Compose 侧 `stringResource` 取到同一种语言。
 */
class MainActivity : ComponentActivity() {

    /**
     * 本次 attach 生效的语言（`AppLanguage.SYSTEM` 时跟随系统）。
     *
     * 界面据此判断「偏好已变、需要 recreate」——`recreate()` 会重新走 [attachBaseContext]。
     */
    var appliedLanguage: AppLanguage = AppLanguage.SYSTEM
        private set

    override fun attachBaseContext(newBase: Context) {
        val language = SettingsStore.readLanguage(newBase)
        appliedLanguage = language
        super.attachBaseContext(newBase.localizedFor(language))
        // 动态文案（预览整句 / 状态行 / 保存清单）走资源取值，必须与界面同 locale。
        // locale 覆盖不透传 application context，所以在 application context 上单独再包一层，
        // 既不拿 Activity 交给单例（避免泄漏），也不会退成系统语言。
        AppText.install((applicationContext ?: this).localizedFor(language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏/导航栏透明（themes.xml），边衬由 Miuix Scaffold 的 window insets 统一处理。
        enableEdgeToEdge()
        setContent {
            HyperVolumeApp(vm = viewModel())
        }
    }
}
