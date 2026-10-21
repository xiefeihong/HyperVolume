package com.xiefeihong.hypervolume.core

import android.content.Context
import android.net.Uri
import android.provider.Settings

/**
 * `android.provider.Settings.Global` 的薄封装（隔离 import，便于阅读）。
 *
 * 供配置读取通道1（Settings.Global，各进程均可访问）使用。冷启动早期 SettingsProvider
 * 未就绪时本调用可能抛错，由 Hook 端的 readConfig 捕获后回退镜像文件通道。
 */
internal object SettingsGlobal {

    fun getString(context: Context, key: String): String? =
        Settings.Global.getString(context.contentResolver, key)

    /** 该 Global 键对应的通知 Uri，供 `registerContentObserver` 监听配置变更实时失效缓存。 */
    fun getUriFor(key: String): Uri = Settings.Global.getUriFor(key)
}
