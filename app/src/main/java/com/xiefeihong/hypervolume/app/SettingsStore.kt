package com.xiefeihong.hypervolume.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.xiefeihong.hypervolume.core.Prefs
import java.util.Locale

/**
 * 界面偏好（语言 / 颜色 / 设置页标签位）的读写。
 *
 * 与业务配置同库不同键：业务键由 [ConfigStore] 负责，本类只碰 `ui_*` 三个键，
 * 备份时两者一并导出（见 [BackupEngine]）。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = Prefs.get(context.applicationContext)

    /** 界面语言；`SYSTEM` 表示跟随系统。 */
    var language: AppLanguage
        get() = AppLanguage.fromTag(prefs.getString(Prefs.KEY_LANGUAGE, AppLanguage.SYSTEM.tag))
        set(value) {
            prefs.edit().putString(Prefs.KEY_LANGUAGE, value.tag).apply()
        }

    /** 颜色模式（跟随系统 / 浅色 / 深色）。 */
    var themeMode: ThemeMode
        get() = ThemeMode.fromStored(prefs.getInt(Prefs.KEY_THEME_MODE, ThemeMode.SYSTEM.stored))
        set(value) {
            prefs.edit().putInt(Prefs.KEY_THEME_MODE, value.stored).apply()
        }

    /** 本模块写入的全部本地偏好（含业务配置），供备份导出。 */
    fun allPreferences(): Map<String, *> = prefs.all

    /** 清空并整体回落本地偏好（导入备份用；类型由 [PreferenceCodec] 标记还原）。 */
    fun restorePreferences(values: Map<String, Any?>) {
        val editor = prefs.edit()
        prefs.all.keys.forEach { editor.remove(it) }
        PreferenceCodec.putAll(editor, values)
        editor.apply()
    }

    companion object {

        /**
         * 早期读取语言偏好（此时 [SettingsStore] 还不可用）：
         * `MainActivity.attachBaseContext` 需在资源装载前决定 locale，只能直接读 SharedPreferences。
         */
        fun readLanguage(context: Context): AppLanguage =
            AppLanguage.fromTag(
                Prefs.get(context.applicationContext ?: context)
                    .getString(Prefs.KEY_LANGUAGE, AppLanguage.SYSTEM.tag),
            )
    }
}

/** 界面语言选项（tag 落盘，改名不影响已存值）。 */
enum class AppLanguage(val tag: String) {
    SYSTEM("system"),
    CHINESE("zh"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/** 颜色（主题）选项：`stored` 值与历史落盘值对齐，不依赖枚举序数。 */
enum class ThemeMode(val stored: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    ;

    companion object {
        fun fromStored(value: Int): ThemeMode = entries.firstOrNull { it.stored == value } ?: SYSTEM
    }
}

/** 生效语言解析出的 [Locale]；`null` 表示跟随系统（不做任何覆盖）。 */
fun AppLanguage.toLocale(): Locale? = when (this) {
    AppLanguage.SYSTEM -> null
    AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.ENGLISH -> Locale.ENGLISH
}

/**
 * 按 [language] 覆盖 locale 后的新 Context（`SYSTEM` 原样返回，不改系统语言）。
 *
 * 注意：`createConfigurationContext` 的覆盖**不会**透过 `getApplicationContext()` 传递，
 * 所以资源接缝要直接在 application context 上再包一层，不能取包裹后 context 的 application。
 */
fun Context.localizedFor(language: AppLanguage): Context {
    val locale = language.toLocale() ?: return this
    val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(configuration)
}
