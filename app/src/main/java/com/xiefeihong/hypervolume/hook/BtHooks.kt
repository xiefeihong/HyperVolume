package com.xiefeihong.hypervolume.hook

import com.highcapable.yukihookapi.hook.param.HookChain
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.xiefeihong.hypervolume.core.Avrcp
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.VolumeMode

/**
 * 蓝牙进程（com.android.bluetooth）侧 Hook：音量控制模式。
 *
 *
 * Hook `com.android.bluetooth.avrcp.AvrcpVolumeManager` 与
 * `com.android.bluetooth.avrcp.AvrcpNativeInterface`：
 *  - 模式A（保持绝对音量，默认）：`systemToAvrcpVolume()` 使用低音量增强曲线
 *    （sqrt 映射），与 UI 预览保持一致；`avrcpToSystemVolume()`（耳机音量键回调）
 *    使用曲线反函数保持双向一致；
 *  - 默认模式但收窄了音量范围：`AvrcpNativeInterface.sendVolumeChanged` 是手机→耳机
 *    绝对音量的真正 JNI 出口（本 mainline APEX 上 `systemToAvrcpVolume` 不在热路径、
 *    疑被 ART 内联），在此按 `defaultRange` 把 AVRCP 绝对音量抬升，使最小音量生效、
 *    消除首档无声（保留绝对音量，不强制软件衰减）；
 *  - 模式B（停用绝对音量）：`deviceConnected` 强制上报不支持 + `sendVolumeChanged`
 *    阻断 AVRCP 发送。耳机固定于自身硬件音量，手机端通过 AudioService 软件衰减控制音量。
 *
 *
 * 切换模式（A↔B）后需重启蓝牙使 `deviceConnected` 重新触发；默认收窄范围经
 * `AvrcpNativeInterface` 每次实时读配置生效，无需重连。
 */
object BtHooks {

    private const val BT_VOLUME_MANAGER_CLASS = "com.android.bluetooth.avrcp.AvrcpVolumeManager"
    private const val BT_NATIVE_INTERFACE_CLASS = "com.android.bluetooth.avrcp.AvrcpNativeInterface"
    private const val FIELD_DEVICE_MAX_VOLUME = "sDeviceMaxVolume"

    /** 日志节流：避免音量调节时高频刷屏。 */
    private var sLastLoggedCurveValue = -1

    private var sModeBLogged = false

    /** 在蓝牙进程加载 AvrcpVolumeManager 相关 Hook。 */
    fun install(param: PackageParam) {
        try {
            XposedKit.init(false)
            val classLoader = param.hostClassLoader
                ?: throw IllegalStateException("bluetooth class loader unavailable")
            val volumeManager = classLoader.loadClass(BT_VOLUME_MANAGER_CLASS)

            // 模式A：手机档位 → AVRCP 绝对音量（sqrt 低音量增强曲线）
            val toAvrcp = param.hookAllNamed(volumeManager, "systemToAvrcpVolume") { chain ->
                systemToAvrcp(volumeManager, chain)
            }
            XposedKit.log("systemToAvrcpVolume hooked: $toAvrcp")

            // 模式A：耳机音量键回调（AVRCP → 手机档位）使用曲线反函数，保持映射一致
            val toSystem = param.hookAllNamed(volumeManager, "avrcpToSystemVolume") { chain ->
                avrcpToSystem(volumeManager, chain)
            }
            XposedKit.log("avrcpToSystemVolume hooked: $toSystem")

            // 模式B：设备连接上报改为"不支持绝对音量"（使 AudioService 走软件衰减路径）
            val connected = param.hookAllNamed(volumeManager, "deviceConnected") { chain ->
                deviceConnected(chain)
            }
            XposedKit.log("deviceConnected hooked: $connected")

            // 模式B：屏蔽发往耳机的 AVRCP 音量（避免双重衰减，耳机固定自身音量）
            val sendChanged = param.hookAllNamed(volumeManager, "sendVolumeChanged") { chain ->
                sendVolumeChanged(chain)
            }
            XposedKit.log("sendVolumeChanged hooked: $sendChanged")

            XposedKit.log("$BT_VOLUME_MANAGER_CLASS hooks installed")

            // 默认模式（保持绝对音量）但收窄音量范围：在真正的 JNI 出口 AvrcpNativeInterface.sendVolumeChanged
            // 抬升发往耳机的 AVRCP 绝对音量（本 ROM 上 systemToAvrcpVolume 不在热路径）。
            val nativeInterface = classLoader.loadClass(BT_NATIVE_INTERFACE_CLASS)
            val nativeSend = param.hookAllNamed(nativeInterface, "sendVolumeChanged") { chain ->
                avrcpNativeSend(volumeManager, chain)
            }
            XposedKit.log("AvrcpNativeInterface.sendVolumeChanged hooked: $nativeSend")
        } catch (t: Throwable) {
            XposedKit.logError("bluetooth hooks install failed: $t")
        }
    }

    /** 读取音量管理器记录的系统最大档位（sDeviceMaxVolume）；失败记录并返回 -1。 */
    private fun readDeviceMaxVolume(volumeManager: Class<*>): Int = try {
        XposedKit.getStaticIntField(volumeManager, FIELD_DEVICE_MAX_VOLUME)
    } catch (t: Throwable) {
        XposedKit.logErrorOnce("read-device-max", "readDeviceMaxVolume failed: $t")
        -1
    }

    /**
     * 模式A：系统档位 → AVRCP 绝对音量。
     * 使用 [Avrcp.curveToAbsoluteVolume] 可选曲线映射，与预览完全一致。
     */
    private fun systemToAvrcp(volumeManager: Class<*>, chain: HookChain): Any? {
        XposedKit.logOnce("hook-bt-toavrcp", "HOOK fired: systemToAvrcpVolume (modeA)")
        val result = chain.proceed()
        try {
            val config = XposedKit.readConfig(XposedKit.bluetoothContext())
            if (config == null || !config.enabled) {
                return result // 未启用：走系统原始换算
            }
            val mode: VolumeMode? = config.bluetoothAvrcpMode()
            if (mode == null) {
                return result // 非模式A（含未启用/模式B/默认）走系统原始换算
            }
            val maxSteps = readDeviceMaxVolume(volumeManager)
            if (maxSteps <= 0) {
                return result
            }
            val step = chain.arg(0) as? Int ?: return result
            val original = result as? Int ?: return result
            val range = config.bluetooth.absolute ?: return result
            val minA = range.min
            val maxA = range.max
            val curveType = range.curve
            // 无范围限制时保持系统原始线性换算
            if (minA == 0 && maxA >= Prefs.AVRCP_MAX_VOLUME) {
                return result
            }
            // 按所选曲线映射（与 Avrcp.curveToAbsoluteVolume 一致）
            val curved = Avrcp.curveToAbsoluteVolume(step, maxSteps, minA, maxA, curveType)
            if (curved != original) {
                if (curved != sLastLoggedCurveValue) {
                    sLastLoggedCurveValue = curved
                    XposedKit.log(
                        "modeA: step $step/$maxSteps avrcp $original -> $curved" +
                            " (range=$minA~$maxA curve=" + Avrcp.curveKey(curveType) + ")",
                    )
                }
                return curved
            }
        } catch (t: Throwable) {
            XposedKit.logErrorOnce("modeA-toavrcp-err", "modeA systemToAvrcp hook failed: $t")
        }
        return result
    }

    /**
     * 模式A：耳机 AVRCP 音量 → 系统档位。
     * 使用 [Avrcp.curveToSystemStep] 反函数，与正向所选曲线保持一致。
     */
    private fun avrcpToSystem(volumeManager: Class<*>, chain: HookChain): Any? {
        XposedKit.logOnce("hook-bt-tosystem", "HOOK fired: avrcpToSystemVolume (modeA)")
        val result = chain.proceed()
        try {
            val config = XposedKit.readConfig(XposedKit.bluetoothContext())
            if (config == null) {
                return result // 配置未就绪：走系统原始换算
            }
            val mode: VolumeMode? = config.bluetoothAvrcpMode()
            if (mode == null) {
                return result // 非模式A（含未启用/模式B）无需反算
            }
            val maxSteps = readDeviceMaxVolume(volumeManager)
            if (maxSteps <= 0) {
                return result
            }
            val avrcp = chain.arg(0) as? Int ?: return result
            val range = config.bluetooth.absolute ?: return result
            val minA = range.min
            val maxA = range.max
            val curveType = range.curve
            if (minA == 0 && maxA >= Prefs.AVRCP_MAX_VOLUME) {
                return result // 无范围限制时无需反算
            }
            // 曲线反函数（按所选类型）
            val step = Avrcp.curveToSystemStep(avrcp, maxSteps, minA, maxA, curveType)
            return step.coerceIn(0, maxSteps)
        } catch (t: Throwable) {
            XposedKit.logErrorOnce("modeA-tosystem-err", "modeA avrcpToSystem hook failed: $t")
        }
        return result
    }

    /** 模式B：设备连接时上报"不支持绝对音量"，使 AudioService 走本地软件衰减路径。 */
    private fun deviceConnected(chain: HookChain): Any? {
        XposedKit.logOnce("hook-bt-devconn", "HOOK fired: deviceConnected (modeB)")
        val args = chain.args
        if (args.size >= 2 && args[1] == java.lang.Boolean.TRUE) {
            val config = XposedKit.readConfig(XposedKit.bluetoothContext())
            if (config != null && config.suppressBtAbsoluteVolume()) {
                if (!sModeBLogged) {
                    sModeBLogged = true
                    XposedKit.log("modeB: force absoluteVolume=false")
                }
                val newArgs = args.toTypedArray()
                newArgs[1] = java.lang.Boolean.FALSE
                return chain.proceed(newArgs)
            }
        }
        return chain.proceed()
    }

    /** 模式B：屏蔽发往耳机的 AVRCP 音量命令（耳机固定自身硬件音量）。 */
    private fun sendVolumeChanged(chain: HookChain): Any? {
        XposedKit.logOnce("hook-bt-sendvol", "HOOK fired: sendVolumeChanged (modeB)")
        val config = XposedKit.readConfig(XposedKit.bluetoothContext())
        if (config != null && config.suppressBtAbsoluteVolume()) {
            return null // 模式B：阻止发送 AVRCP
        }
        return chain.proceed()
    }

    /**
     * 默认模式（保持绝对音量）但收窄了音量范围：在真正的 JNI 出口
     * `AvrcpNativeInterface.sendVolumeChanged` 把发往耳机的 AVRCP 绝对音量按
     * `defaultRange` 抬升。入参 `volume` 是 AOSP 线性 `round(index*127/max)` 的结果，
     * 先反推系统档位 `index`，再经 [Avrcp.defaultRangeToIndex] 线性映射、回算 AVRCP，写回该 int 参数。
     */
    private fun avrcpNativeSend(volumeManager: Class<*>, chain: HookChain): Any? {
        try {
            val config = XposedKit.readConfig(XposedKit.bluetoothContext())
            if (config == null || !config.enabled ||
                config.modeFor(OutputDevice.BT) != Prefs.BT_MODE_DEFAULT
            ) {
                return chain.proceed() // 非默认模式不在此处理（模式A/B 各自已有路径）
            }
            // 满量程直通 = null
            val dr = config.activeRangeFor(OutputDevice.BT) ?: return chain.proceed()
            val maxSteps = readDeviceMaxVolume(volumeManager)
            if (maxSteps <= 0) {
                return chain.proceed()
            }
            val args = chain.args
            var volIdx = -1
            for (i in args.indices.reversed()) {
                if (args[i] is Int) {
                    volIdx = i
                    break
                }
            }
            if (volIdx < 0) {
                return chain.proceed()
            }
            val vol = args[volIdx] as Int
            // 反推系统档位：AOSP abs = round(index*127/maxSteps) → index = round(vol*maxSteps/127)
            val index = Math.round(vol * maxSteps.toDouble() / Prefs.AVRCP_MAX_VOLUME)
                .toInt()
                .coerceIn(0, maxSteps)
            val mapped = Avrcp.defaultRangeToIndex(index, maxSteps, dr.min, dr.max)
            val newVol = Math.round(mapped * Prefs.AVRCP_MAX_VOLUME.toDouble() / maxSteps)
                .toInt()
                .coerceIn(0, Prefs.AVRCP_MAX_VOLUME)
            if (newVol != vol) {
                if (newVol != sLastLoggedCurveValue) {
                    sLastLoggedCurveValue = newVol
                    XposedKit.log(
                        "defaultAvrcp(BT): vol $vol (index~$index) -> $newVol" +
                            " (range=${dr.min}~${dr.max})",
                    )
                }
                val newArgs = args.toTypedArray()
                newArgs[volIdx] = newVol
                return chain.proceed(newArgs)
            }
        } catch (t: Throwable) {
            XposedKit.logErrorOnce("default-send-err", "defaultAvrcp native-send hook failed: $t")
        }
        return chain.proceed()
    }
}
