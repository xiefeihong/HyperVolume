package com.xiefeihong.hypervolume.core

/**
 * 单个输出设备的配置：生效模式 + 模式A/B/默认 各自独立的音量范围。不可变值对象。
 *
 * 模式A（绝对音量，仅蓝牙）、模式B（软件衰减）与默认（可调最小/最大）各保存一套
 * [Range]，互不同步；外放/有线不使用 [absolute]（其 [mode] 只会是默认(0)/相对(2)）。
 *
 * 与 [Prefs]/[VolumeMode]/[VolumeConfig] 一样同时被 App 进程与 Hook 端
 * （system_server / 蓝牙进程）使用，禁止引用任何 Android / Xposed API。
 */
data class DeviceConfig(

    /**
     * 该设备生效模式 id：[Prefs.BT_MODE_DEFAULT](0·系统直通) /
     * [Prefs.BT_MODE_ABSOLUTE](1·绝对音量, 仅蓝牙) / [Prefs.BT_MODE_SOFTWARE](2·相对音量)。
     */
    val mode: Int,

    /** 模式A（绝对音量）范围（AVRCP 0~127）；仅蓝牙使用。 */
    val absolute: Range?,

    /** 模式B（相对音量 / 软件衰减）范围（系统档位单位）。 */
    val software: Range?,

    /** 默认模式可调范围（系统档位单位，仅用 min/max，curve 忽略）；与模式B 独立。 */
    val defaultRange: Range?,
) {

    /** 是否处于默认（系统直通）模式。 */
    val isDefault: Boolean get() = mode == Prefs.BT_MODE_DEFAULT

    /**
     * 该设备所选模式对应的 A/B 范围；默认模式返回 [defaultRange]（其范围与模式B 独立持有）。
     */
    fun activeRange(): Range? = when (mode) {
        Prefs.BT_MODE_SOFTWARE -> software
        Prefs.BT_MODE_ABSOLUTE -> absolute
        else -> defaultRange
    }

    /** 返回一个仅模式不同的副本（范围保持不变），供界面按设备写回模式选择。 */
    fun withMode(newMode: Int): DeviceConfig =
        DeviceConfig(newMode, absolute, software, defaultRange)
}
