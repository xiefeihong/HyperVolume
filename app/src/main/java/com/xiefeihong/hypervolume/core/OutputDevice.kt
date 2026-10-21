package com.xiefeihong.hypervolume.core

/**
 * 输出设备类别（App 与 Hook 端共用，纯 `DEVICE_OUT` int 位掩码判定，
 * 禁止引用任何 Android / Xposed API）。
 *
 * 序数与 UI 设备标签、[NativeVolumeCurve.Device] 当前一致（SPEAKER/WIRED/BT = 0/1/2），
 * 但跨维度映射统一走显式 `when`，不依赖 `values()[ordinal]` 的顺序巧合。
 */
enum class OutputDevice {

    /** 内置扬声器 / 受话器。 */
    SPEAKER,

    /** 有线耳机 / USB / 底座 / 线路输出。 */
    WIRED,

    /** 蓝牙（SCO / A2DP / BLE）。 */
    BT;

    /** 仅蓝牙支持「绝对音量（模式A）」；外放/有线只能相对音量或默认直通。 */
    fun supportsAbsolute(): Boolean = this == BT

    companion object {

        /** 蓝牙输出位：SCO 0x10/0x20/0x40、A2DP 0x80/0x100/0x200、BLE 0x800000/0x1000000/0x2000000。 */
        private const val BT_MASK = 0x70 or 0x380 or 0x3800000

        /** 有线耳机/USB/底座/线路输出位（不含内置扬声器 0x2 / 受话器 0x1）。 */
        private const val WIRED_MASK = 0x4 or 0x8 or 0x800 or 0x1000 or 0x2000 or 0x4000 or 0x20000

        /** 内置扬声器 0x2 / 受话器 0x1。 */
        private const val SPEAKER_MASK = 0x1 or 0x2

        /**
         * 由 AudioSystem `DEVICE_OUT` 位掩码判定设备类别。蓝牙优先；未识别返回 `null`
         * （调用方保守放行，避免误伤未知设备）。
         */
        @JvmStatic
        fun fromOutMask(device: Int): OutputDevice? = when {
            device and BT_MASK != 0 -> BT
            device and WIRED_MASK != 0 -> WIRED
            device and SPEAKER_MASK != 0 -> SPEAKER
            else -> null
        }
    }
}
