package com.xiefeihong.hypervolume

import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.factory.configure
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule
import com.highcapable.yukihookapi.hook.xposed.bridge.event.registerFrameworkEvents
import com.highcapable.yukihookapi.hook.xposed.bridge.event.v101.onModuleLoaded
import com.xiefeihong.hypervolume.hook.AudioHooks
import com.xiefeihong.hypervolume.hook.BtHooks
import com.xiefeihong.hypervolume.hook.XposedKit

/**
 * 模块框架入口（YukiHook + libxposed API 102）。
 *
 * 与旧实现（手写 `XposedModule` + `META-INF/xposed` 配置文件）不同：入口类与
 * `java_init.list`、`module.prop`、`scope.list` 全部由 `yukihook-compiler`
 * 在编译期依据下面的注解生成，源码目录中不得再手动声明这些文件。
 *
 * 生命周期分发（由 YukiHook 的 libxposed runtime 映射到 [encase] 的作用域回调）：
 *  - [loadSystem]：在 `onSystemServerStarting` 派发——system_server 启动关键服务之前，
 *    AudioService 尚未构造，正是改写档位数的完整时机（旧实现的同名事件语义）；
 *  - [loadApp] 蓝牙：在 `onPackageReady` 派发，Avrcp 音量管理器此时尚未工作，Hook 仍然有效。
 */
@YukiHookLibXposedEntry(
    minApiVersion = 102,
    targetApiVersion = 102,
    scope = ["android", "system", "com.android.bluetooth"],
    staticScope = true
)
object HookEntry : YukiHookXposedModule {

    override fun onInit() {
        configure {
            logging {
                tag = "HyperVolume"
                enabled = true
                recording = false
            }
            debug = false
            dataChannel = false
        }
        // 框架事件：记录模块加载与框架信息，并标记当前进程类型（决定日志文件位置与
        // Context 解析方式），语义等同旧实现的 onModuleLoaded。
        registerFrameworkEvents {
            onModuleLoaded { param ->
                XposedKit.init(param.isSystemServer)
                XposedKit.log(
                    "module loaded in ${param.processName}" +
                        " (framework $frameworkName $frameworkVersion, api $apiVersion)",
                )
            }
        }
    }

    override fun onHook() = encase {
        // 系统框架侧：档位保险 + 软件衰减 + 音量键步进 + 模式B 压制
        loadSystem { AudioHooks.install(this) }
        // 蓝牙侧：AVRCP 绝对音量曲线与模式B 阻断
        loadApp(XposedKit.BT_PACKAGE) { BtHooks.install(this) }
    }
}
