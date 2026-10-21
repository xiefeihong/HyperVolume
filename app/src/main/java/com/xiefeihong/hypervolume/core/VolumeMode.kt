package com.xiefeihong.hypervolume.core

/**
 * 音量模式的统一抽象（「枚举即策略」），App 与 Xposed Hook 共用。
 *
 * 仅描述「绝对音量(A) / 相对音量(B) / 默认直通」三种曲线语义本身：该模式是否在
 * system_server 软件衰减、是否在蓝牙进程走 AVRCP。具体「某设备用哪个模式、范围是多少」
 * 由 [VolumeConfig]（`modeFor(d)` / `activeRangeFor(d)`）与 [DeviceConfig] 持有，
 * 本枚举不携带全局 SharedPreferences 键或全局解析入口。
 *
 * 与 [Prefs]/[Avrcp]/[VolumeConfig]/[OutputDevice] 一样同时被 App 进程与 Hook 端
 * （system_server / 蓝牙进程）使用，禁止引用任何 Xposed API 类。
 */
enum class VolumeMode(

    /** 是否天然在 system_server 通过改写系统音量档位做软件衰减（模式B）。 */
    val attenuatesInSystemServer: Boolean,

    /** 供 App：枚举写回模式 id。 */
    val modeId: Int,
) {

    /** 模式A：蓝牙 AVRCP 绝对音量，档位映射为 0~127 直接发送给耳机（蓝牙进程）。 */
    ABSOLUTE(false, Prefs.BT_MODE_ABSOLUTE),

    /** 模式B：停用绝对音量，由手机端在 system_server 软件衰减音频。 */
    SOFTWARE(true, Prefs.BT_MODE_SOFTWARE),

    /**
     * 默认模式：不改档位数、不加自定义曲线，仅在 system_server 把系统档位在所选
     * `[min,max]` 间线性等分重映射（见 AudioHooks 的 defaultMode 分支，用
     * [Avrcp.defaultRangeToIndex]；满量程时恒等于直通）。是否生效仍先经
     * [VolumeConfig.remapActive] 与该设备 [DeviceConfig.isDefault] 判定。
     */
    DEFAULT(false, Prefs.BT_MODE_DEFAULT),
}
