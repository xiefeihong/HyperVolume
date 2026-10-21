package com.xiefeihong.hypervolume.core

/**
 * 单个音量模式的范围（最小~最大 + 映射曲线类型）。不可变值对象。
 *
 * 供 [DeviceConfig]（每设备三套 absolute/software/defaultRange）、[VolumeConfig]
 * （`activeRangeFor(d)`）与 App 侧读写共同使用，禁止引用任何 Android / Xposed API。
 *
 * 字段含义：[min]/[max] 为该模式映射后的音量下限/上限（模式A 为 AVRCP 绝对音量 0~127；
 * 模式B 为手机端软件衰减的目标档位），[curve] 为 [Prefs.CURVE_LOG] /
 * [Prefs.CURVE_LINEAR] / [Prefs.CURVE_SQRT]。
 */
data class Range(
    val min: Int,
    val max: Int,
    val curve: Int,
)
