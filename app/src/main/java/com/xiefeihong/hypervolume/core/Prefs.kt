package com.xiefeihong.hypervolume.core

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 配置常量与读写工具。
 *
 * 与旧实现一致：本类同时被 App 进程与 Hook 端（system_server 进程、蓝牙进程）使用，
 * 因此不得引用任何 Xposed / YukiHook API 类。
 */
object Prefs {

    /** SharedPreferences 文件名，必须与 Hook 端读取的文件名一致。 */
    const val PREFS_NAME = "settings"

    /** Settings.Global 备用配置键：Hook 端优先从这里读取（system_server 一定能读到）。 */
    const val GLOBAL_KEY = "volume_steps_hook_config"

    /** 配置镜像文件：App 以 root 同步写入，Hook 端在 Settings 读取失败时直读该文件兜底。 */
    const val MIRROR_CONFIG_FILE = "/data/system/hypervolume_config"

    /**
     * App 侧唯一的配置持久化键：整份 [VolumeConfig] 以其 `toXml()` 存此一键，
     * 读时 [VolumeConfig.fromXml]。取代此前散落的每模式标量键（对象图取代键位映射）。
     */
    const val KEY_CONFIG_XML = "config_xml"

    /** 首次运行时捕获的系统原生媒体档位数（模块未覆盖时的 getStreamMaxVolume，属环境事实、不入配置 XML）。 */
    const val KEY_SYSTEM_DEFAULT_STEPS = "system_default_steps"

    /**
     * 「未获取系统默认档位」警告是否仍需弹出：用户点「使用档位默认值」后置 true 即不再弹。
     * 与 [KEY_SYSTEM_DEFAULT_STEPS] 解耦——后者只在真正从系统服务探到档位时才固化，
     * 本标志仅记录「用户已知晓并选择继续」，不代表已拿到真实默认档位。
     */
    const val KEY_SYSTEM_DEFAULT_ACK = "system_default_ack"

    /**
     * 蓝牙音量控制模式 A：保持绝对音量（默认）。
     * 手机档位经低音量增强曲线映射为 0~127 的绝对音量发送给耳机。
     */
    const val BT_MODE_ABSOLUTE = 1

    /**
     * 蓝牙音量控制模式 B：停用绝对音量。
     * Hook 蓝牙栈使系统以手机端软件衰减控制音量，耳机固定于自身硬件音量。
     */
    const val BT_MODE_SOFTWARE = 2

    /**
     * 默认（系统直通）模式：与启用开关合并到同一模式字段（btMode）。
     * 选中时模块不改写系统音量逻辑（不改档位数、不重映射），等同未启用。
     */
    const val BT_MODE_DEFAULT = 0

    /**
     * 映射曲线类型（Mode A/B 各自独立设置）：step→音量范围的中间分布。
     * 三者均保证 step=1 精确命中最小、maxSteps 精确命中最大。
     */
    const val CURVE_LOG = 0
    const val CURVE_LINEAR = 1
    const val CURVE_SQRT = 2

    /** 曲线类型默认值（默认对数曲线）。 */
    const val CURVE_TYPE_DEFAULT = CURVE_LOG

    /**
     * 蓝牙 AVRCP 绝对音量上限。
     * 若媒体档位数超过该值，相邻档位会映射到相同的 AVRCP 音量（听感相同），
     * 换算公式见 AOSP 蓝牙模块 AvrcpVolumeManager#systemToAvrcpVolume：
     * absVolume = round(档位 * 127 / 最大档位)。
     */
    const val AVRCP_MAX_VOLUME = 127

    /** 模式A 下绝对音量范围（0~127）的默认上下限。 */
    const val ABS_VOLUME_MIN_DEFAULT = 0
    const val ABS_VOLUME_MAX_DEFAULT = AVRCP_MAX_VOLUME

    /** 媒体音量级数的可选范围（10~100）。 */
    const val MEDIA_STEPS_MIN = 10
    const val MEDIA_STEPS_MAX = 100

    /** 兜底默认媒体档位数（仅在无法探测系统原生档位时使用）。 */
    const val MEDIA_STEPS_DEFAULT = 15

    /**
     * 音量键步进（跨完整音量条需要的按键段数）的可选范围（10~29，默认 15）。
     * 每按一次音量键跳 [keyDelta] 个级；段数越大每次跳得越少（越细）。
     */
    const val KEY_STEP_MIN = 10
    const val KEY_STEP_MAX = 29
    const val KEY_STEP_DEFAULT = 15

    /** 媒体流索引（android.media.AudioSystem.STREAM_MUSIC 的稳定取值）。 */
    const val STREAM_MUSIC_INDEX = 3

    /** App 侧 SharedPreferences 句柄。 */
    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 把媒体档位数限制在可选范围内。 */
    fun clampMediaSteps(steps: Int): Int = steps.coerceIn(MEDIA_STEPS_MIN, MEDIA_STEPS_MAX)

    /** 把音量键步进（按键段数）限制在可选范围 10~29 内。 */
    fun clampKeySteps(steps: Int): Int = steps.coerceIn(KEY_STEP_MIN, KEY_STEP_MAX)

    /** 把系统档位（模式B/默认的映射最小/最大目标档）限制在 `0..maxSteps`。 */
    fun clampSystemStep(v: Int, maxSteps: Int): Int {
        if (maxSteps <= 0) {
            return 0
        }
        return v.coerceIn(0, maxSteps)
    }

    /**
     * 音量键每按一次跳过的级数：`clamp(round(levels / keySteps), 1, levels)`。
     * App 预览与 system_server Hook 共用此公式，避免算法漂移。
     *
     * @param levels 音量级数（[clampMediaSteps] 后的 mediaSteps）
     * @param keySteps 按键段数（[clampKeySteps] 后）
     */
    fun keyDelta(levels: Int, keySteps: Int): Int {
        if (keySteps <= 0) {
            return levels.coerceAtLeast(1)
        }
        return levels.toFloat().div(keySteps).roundToInt().coerceIn(1, levels)
    }

    /**
     * 第 [seg] 段对应的网格绝对级：`clamp(round(seg*levels/keySteps), 0, levels)`。
     * 使滑块显示百分比 `round(level*100/levels)` 尽量落在 `seg*100/keySteps` 的整数倍，
     * 消除「固定 delta 累加」从任意当前位置产生的漂移。App 预览与 Hook 共用。
     */
    fun keyStepLevel(seg: Int, levels: Int, keySteps: Int): Int {
        if (levels <= 0) {
            return 0
        }
        if (keySteps <= 0) {
            return seg.coerceIn(0, levels)
        }
        val lvl = (seg.toDouble() * levels / keySteps).roundToInt()
        return lvl.coerceIn(0, levels)
    }

    /**
     * 音量键「升高一次」吸附目标级：取严格大于 [current] 的最小网格级；到顶则返回 levels。
     * 段数≥级数（网格间距&lt;1）时退化为逐级 +1。
     */
    fun nextKeyStepUp(current: Int, levels: Int, keySteps: Int): Int {
        if (current >= levels) {
            return levels
        }
        if (keySteps >= levels) {
            return (current + 1).coerceAtMost(levels)
        }
        var seg = floor(current.toDouble() * keySteps / levels).toInt() + 1
        var lvl = keyStepLevel(seg, levels, keySteps)
        while (seg < keySteps && lvl <= current) { // 防 round 碰撞：确保严格增大
            seg++
            lvl = keyStepLevel(seg, levels, keySteps)
        }
        return lvl.coerceIn(current, levels)
    }

    /**
     * 音量键「降低一次」吸附目标级：取严格小于 [current] 的最大网格级；到底则返回 0。
     * 段数≥级数时退化为逐级 -1。
     */
    fun nextKeyStepDown(current: Int, levels: Int, keySteps: Int): Int {
        if (current <= 0) {
            return 0
        }
        if (keySteps >= levels) {
            return (current - 1).coerceAtLeast(0)
        }
        var seg = ceil(current.toDouble() * keySteps / levels).toInt() - 1
        var lvl = keyStepLevel(seg.coerceAtLeast(0), levels, keySteps)
        while (seg > 0 && lvl >= current) { // 防 round 碰撞：确保严格减小
            seg--
            lvl = keyStepLevel(seg, levels, keySteps)
        }
        return lvl.coerceIn(0, current)
    }

    /** 绝对音量值限制在 0~127。 */
    fun clampAbs(v: Int): Int = v.coerceIn(0, AVRCP_MAX_VOLUME)

    /** 曲线类型限制在 0~2。 */
    fun clampCurve(v: Int): Int = v.coerceIn(0, CURVE_SQRT)
}
