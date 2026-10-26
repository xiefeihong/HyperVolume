package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.VolumeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 生效配置原文 → 模型的解析口径（[ConfigStore.parseEffective]）。
 *
 * `ContentResolver` 本身要 Android 运行时，不进 JVM 单测；这里锁定交给解析前的脏值归一：
 * 读不到 / 系统侧压根没有值 / 内容畸形都返回 `null`，由界面回落默认配置，绝不半份装载。
 */
class EffectiveConfigParseTest {

    private fun device(mode: Int, abs: Range, soft: Range, default: Range): DeviceConfig =
        DeviceConfig(mode, abs, soft, default)

    /** 真配置序列化出的格式化串（多行、缩进两空格）。 */
    private val formatted = VolumeConfig(
        true, 40, 15,
        device(Prefs.BT_MODE_DEFAULT, Range(0, 100, 0), Range(0, 100, 0), Range(0, 100, 0)),
        device(Prefs.BT_MODE_SOFTWARE, Range(0, 100, 0), Range(1, 40, 1), Range(0, 100, 0)),
        device(Prefs.BT_MODE_ABSOLUTE, Range(10, 127, 2), Range(0, 100, 0), Range(0, 100, 0)),
    ).toJson()

    @Test
    fun formattedAndSingleLineTextsParseToSameModel() {
        val model = ConfigStore.parseEffective(formatted)
        assertNotNull(model)
        // 排版不参与语义：历史存量的紧凑单行串解析出等价模型
        val singleLine = formatted.replace("\n", "").replace("  ", "").replace(", ", ",").replace(": ", ":")
        assertEquals(model, ConfigStore.parseEffective(singleLine))
        // 首尾空白（`settings get` 回显常带换行）不影响
        assertEquals(model, ConfigStore.parseEffective("\n$formatted\n"))
    }

    @Test
    fun missingOrDirtyValuesYieldNull() {
        assertNull(ConfigStore.parseEffective(""))
        assertNull(ConfigStore.parseEffective("   "))
        // `settings get global <key>` 在键不存在时回显字面 null
        assertNull(ConfigStore.parseEffective("null"))
        assertNull(ConfigStore.parseEffective("{"))
        // 缺本模块键名（enabled/mediaSteps/keySteps/devices 一个都没有）= 不是本模块的配置
        assertNull(ConfigStore.parseEffective("""{"module":true}"""))
    }
}
