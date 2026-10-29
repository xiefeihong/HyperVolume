package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.TextBackedTest
import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.VolumeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存改动清单（[describeChanges]）与对话框文案的 JVM 单元测试：
 * 逐条对应旧 `SaveFlow.describeChanges` 的 `strings.xml` 格式串。
 */
class SavePlanTest : TextBackedTest() {

    private val fullAbs = Range(0, Prefs.AVRCP_MAX_VOLUME, Prefs.CURVE_TYPE_DEFAULT)
    private val fullSoft = Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_LINEAR)
    private val fullDefault = Range(0, Prefs.MEDIA_STEPS_MAX, Prefs.CURVE_LINEAR)

    private fun passthroughDevice(): DeviceConfig =
        DeviceConfig(Prefs.BT_MODE_DEFAULT, fullAbs, fullSoft, fullDefault)

    private fun baseConfig(): VolumeConfig = VolumeConfig(
        false, 15, 15, passthroughDevice(), passthroughDevice(), passthroughDevice(),
    )

    @Test
    fun nullBaseline_hasNoItemListedChanges() {
        // 首次保存无基线可比：返回空列表 → 界面用「首次保存」文案
        assertTrue(describeChanges(null, baseConfig()).isEmpty())
    }

    @Test
    fun identicalConfig_producesNoChanges() {
        val c = baseConfig()
        assertTrue(describeChanges(c, c).isEmpty())
    }

    @Test
    fun readBackVerification_ignoresLayoutButNotContent() {
        val pending = baseConfig().withEnabled(true)
        // 回读校验是语义比较：格式化串、旧的单行紧凑串都算写入成功
        assertTrue(readBackMatches(pending.toJson(), pending))
        val compact = pending.toJson().replace("\n", "").replace(", ", ",").replace(": ", ":")
        assertTrue(readBackMatches(compact, pending))
        // 内容不同 / 读不回东西（空串、畸形）仍判为失败
        assertFalse(readBackMatches(pending.withEnabled(false).toJson(), pending))
        assertFalse(readBackMatches("", pending))
        assertFalse(readBackMatches("null", pending))
    }

    @Test
    fun readBackVerification_judgesPersistedContentNotObjectGraph() {
        // 回归锁形：外放 / 有线的模式A 范围不写进 JSON，内存里带着值也不会落盘。
        // 校验必须比「归一后的落盘内容」而不是模型对象——按对象比，这类字段会让写入成功却判失败。
        val pending = baseConfig().withEnabled(true)
        val persisted = requireNotNull(VolumeConfig.fromJson(pending.toJson()))
        assertNotEquals(pending, persisted)
        assertTrue(readBackMatches(pending.toJson(), pending))
    }

    @Test
    fun globalChanges_areListedInOrder() {
        val base = baseConfig()
        val pending = base.withEnabled(true).withSteps(100, 20)
            .withDevice(OutputDevice.BT, base.bluetooth.withMode(Prefs.BT_MODE_ABSOLUTE))
        assertEquals(
            listOf(
                "启用档位修改：关闭 → 开启",
                "档位：15 → 100",
                "音量键步进：15 → 20",
                "蓝牙 模式：默认 → 绝对音量",
            ),
            describeChanges(base, pending),
        )
    }

    @Test
    fun moduleSwitchChange_isListedFirst() {
        // 总闸改动排在改动清单最前，与界面阅读顺序一致
        val base = baseConfig()
        assertEquals(listOf("启用模块：开启 → 关闭"), describeChanges(base, base.withModuleEnabled(false)))
    }

    @Test
    fun rangeChange_isListedPerDeviceAndMode() {
        val base = baseConfig()
        val pending = base.withDevice(
            OutputDevice.SPEAKER,
            base.speaker.copy(defaultRange = Range(5, 80, Prefs.CURVE_LINEAR)),
        )
        assertEquals(listOf("外放·默认：0~100/线性 → 5~80/线性"), describeChanges(base, pending))
    }

    @Test
    fun absoluteRangeChange_usesBluetoothAbsoluteLabel() {
        val base = baseConfig()
        val pending = base.withDevice(
            OutputDevice.BT,
            base.bluetooth.copy(absolute = Range(8, 120, Prefs.CURVE_SQRT)),
        )
        assertEquals(listOf("蓝牙·绝对音量：0~127/对数 → 8~120/平方根"), describeChanges(base, pending))
    }

    @Test
    fun modeAndCurveLabels_useShortNames() {
        assertEquals("绝对音量", modeLabelShort(Prefs.BT_MODE_ABSOLUTE))
        assertEquals("相对音量", modeLabelShort(Prefs.BT_MODE_SOFTWARE))
        assertEquals("默认", modeLabelShort(Prefs.BT_MODE_DEFAULT))
        assertEquals("蓝牙", deviceTabLabel(OutputDevice.BT))
        assertEquals("有线耳机", deviceTabLabel(OutputDevice.WIRED))
        assertEquals("外放", deviceTabLabel(OutputDevice.SPEAKER))
    }

    @Test
    fun bootScriptShouldRewrite_onlyOnParameterChange() {
        // 幂等判据：未记录保守必写；参数一致跳过；active 或 steps 任一变化即重写。
        assertTrue(bootScriptShouldRewrite(true, 100, null))
        assertFalse(bootScriptShouldRewrite(true, 100, BootScriptRecord(true, 100)))
        assertTrue(bootScriptShouldRewrite(false, 100, BootScriptRecord(true, 100)))
        assertTrue(bootScriptShouldRewrite(true, 90, BootScriptRecord(true, 100)))
    }

    @Test
    fun blockedMessage_embedsNonDefaultMode() {
        val text = SaveEngine.Messages.saveNeedSystemDefault(modeLabelShort(Prefs.BT_MODE_SOFTWARE))
        assertTrue(text.contains("处于相对音量模式（非默认）"))
        assertTrue(text.contains("请选择处理方式："))
    }

    @Test
    fun restartWhat_coversThreeCombinations() {
        assertEquals("蓝牙与系统框架", SaveEngine.Messages.restartWhat(true, true))
        assertEquals("蓝牙", SaveEngine.Messages.restartWhat(true, false))
        assertEquals("系统框架", SaveEngine.Messages.restartWhat(false, true))
        assertTrue(SaveEngine.Messages.restartNowMsg(false, true).contains("需重启系统框架才能生效"))
    }

    @Test
    fun perfText_showsSkipWithMatchingValues() {
        // 现值与目标一致 + 基线命中：两处都 skip，同时把比对依据写出来
        assertEquals(
            "av=skip(cur=1 want=1) boot=skip(rec=on/100 want=on/100)",
            perfDecisionText(
                avCurrent = "1", avTarget = "1", avWillWrite = false,
                recorded = BootScriptRecord(true, 100), active = true, steps = 100, bootWillWrite = false,
            ),
        )
    }

    @Test
    fun perfText_marksMissingCurrentAndBaseline() {
        // 现值读不到（null）+ 基线缺失（首次保存）：两者都必须写，且原因可见
        assertEquals(
            "av=write(cur=null want=1) boot=write(rec=- want=on/100)",
            perfDecisionText(
                avCurrent = null, avTarget = "1", avWillWrite = true,
                recorded = null, active = true, steps = 100, bootWillWrite = true,
            ),
        )
    }
}
