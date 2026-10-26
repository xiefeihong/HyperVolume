package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VolumeConfig] 的 JSON 序列化/解析往返、畸形输入回落、构造期钳制的 JVM 单元测试。
 *
 * 格式化串是全链路唯一的写出形态（`Settings.Global` / 镜像 / 备份同源），
 * 故其键序与字面形式一并锁死；内容一致性靠语义比较，排版不参与。
 */
class VolumeConfigTest {

    private fun dev(mode: Int, abs: Range?, soft: Range?, def: Range?): DeviceConfig =
        DeviceConfig(mode, abs, soft, def)

    private fun sample(): VolumeConfig = VolumeConfig(
        true, 100, 25,
        // 模式A 范围只对蓝牙持久化：非蓝牙夹具留 null，与落盘口径一致
        dev(Prefs.BT_MODE_DEFAULT, null, Range(0, 100, 1), Range(0, 100, 0)),
        dev(Prefs.BT_MODE_SOFTWARE, null, Range(1, 80, 2), Range(0, 100, 0)),
        dev(Prefs.BT_MODE_ABSOLUTE, Range(10, 127, 0), Range(0, 100, 1), Range(0, 100, 0)),
    )

    @Test
    fun jsonRoundTrip_stable() {
        val c = sample()
        val json = c.toJson()
        val back = VolumeConfig.fromJson(json)
        assertNotNull(back)
        assertEquals(json, back!!.toJson())
    }

    @Test
    fun layoutIsIrrelevantForSemantics() {
        val c = sample()
        val pretty = c.toJson()
        assertTrue(pretty.startsWith("{\n  \"module\": true,"))
        // 手写配置常把格式化串挤成单行：排版不同但语义相等的两种串解析结果一致
        val singleLine = pretty.replace("\n", "").replace(", ", ",").replace(": ", ":")
        val fromSingleLine = VolumeConfig.fromJson(singleLine)
        assertNotNull(fromSingleLine)
        assertEquals(c, fromSingleLine)
        assertEquals(pretty, fromSingleLine!!.toJson())
    }

    @Test
    fun jsonShapeIsLocked() {
        val c = VolumeConfig(
            true, 50, 15,
            dev(Prefs.BT_MODE_DEFAULT, Range(0, 127, 0), Range(0, 100, 0), Range(0, 50, 0)),
            dev(Prefs.BT_MODE_SOFTWARE, Range(0, 127, 0), Range(1, 40, 1), Range(0, 100, 0)),
            dev(Prefs.BT_MODE_ABSOLUTE, Range(10, 127, 2), Range(0, 100, 0), Range(0, 100, 0)),
        )
        // 键序固定（写出器手写，不受 JSONObject 的 HashMap 顺序影响）；模式A 范围仅蓝牙写。
        assertEquals(
            """{
              |  "module": true,
              |  "enabled": true,
              |  "mediaSteps": 50,
              |  "keySteps": 15,
              |  "devices": {
              |    "speaker": {
              |      "mode": 0,
              |      "b": {
              |        "min": 0,
              |        "max": 100,
              |        "curve": 0
              |      },
              |      "d": {
              |        "min": 0,
              |        "max": 50,
              |        "curve": 0
              |      }
              |    },
              |    "wired": {
              |      "mode": 2,
              |      "b": {
              |        "min": 1,
              |        "max": 40,
              |        "curve": 1
              |      },
              |      "d": {
              |        "min": 0,
              |        "max": 100,
              |        "curve": 0
              |      }
              |    },
              |    "bt": {
              |      "mode": 1,
              |      "a": {
              |        "min": 10,
              |        "max": 127,
              |        "curve": 2
              |      },
              |      "b": {
              |        "min": 0,
              |        "max": 100,
              |        "curve": 0
              |      },
              |      "d": {
              |        "min": 0,
              |        "max": 100,
              |        "curve": 0
              |      }
              |    }
              |  }
              |}""".trimMargin(),
            c.toJson(),
        )
    }

    @Test
    fun fieldsPreservedAcrossRoundTrip() {
        val back = VolumeConfig.fromJson(sample().toJson())
        assertNotNull(back)
        assertTrue(back!!.enabled)
        assertEquals(100, back.mediaSteps)
        assertEquals(25, back.keySteps)
        assertEquals(Prefs.BT_MODE_SOFTWARE, back.modeFor(OutputDevice.WIRED))
        assertEquals(Prefs.BT_MODE_ABSOLUTE, back.modeFor(OutputDevice.BT))
        assertEquals(Prefs.BT_MODE_DEFAULT, back.modeFor(OutputDevice.SPEAKER))
        assertEquals(Range(10, 127, 0), back.bluetooth.absolute)
        assertEquals(Range(1, 80, 2), back.wired.software)
    }

    @Test
    fun malformedOrNullReturnsNull() {
        assertNull(VolumeConfig.fromJson(null))
        assertNull(VolumeConfig.fromJson("   "))
        assertNull(VolumeConfig.fromJson("""{"foo":1}""")) // 无任一模块顶层键 = 不是本模块配置
        assertNull(VolumeConfig.fromJson("[1,2,3]")) // 根为数组
        assertNull(VolumeConfig.fromJson("not json")) // 解析异常
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val back = VolumeConfig.fromJson("""{"enabled":true}""")
        assertNotNull(back)
        assertTrue(back!!.enabled)
        assertEquals(Prefs.MEDIA_STEPS_DEFAULT, back.mediaSteps)
        assertEquals(Prefs.KEY_STEP_DEFAULT, back.keySteps)
        // 无 devices 子对象 → 各设备回落默认直通模式
        assertEquals(Prefs.BT_MODE_DEFAULT, back.modeFor(OutputDevice.BT))
    }

    @Test
    fun tolerantToHandEditedValues() {
        // 手工改配置常把布尔写成 1/0、把数字写成字符串；未知键忽略不影响其余字段
        val back = VolumeConfig.fromJson(
            """{"enabled":1,"mediaSteps":"50","keySteps":"20","note":"hi",""" +
                """"devices":{"bt":{"mode":"2","b":{"min":"1","max":40}}}}""",
        )
        assertNotNull(back)
        assertTrue(back!!.enabled)
        assertEquals(50, back.mediaSteps)
        assertEquals(20, back.keySteps)
        assertEquals(Prefs.BT_MODE_SOFTWARE, back.modeFor(OutputDevice.BT))
        assertEquals(Range(1, 40, Prefs.CURVE_TYPE_DEFAULT), back.bluetooth.software)
    }

    @Test
    fun outOfRangeFieldsAreClampedOnParse() {
        val back = VolumeConfig.fromJson(
            """{"enabled":true,"mediaSteps":9999,"keySteps":0,"devices":{"bt":{"mode":7,""" +
                """"a":{"min":-5,"max":999,"curve":42}}}}""",
        )
        assertNotNull(back)
        assertEquals(Prefs.MEDIA_STEPS_MAX, back!!.mediaSteps)
        assertEquals(Prefs.KEY_STEP_MIN, back.keySteps)
        // 非法模式回落默认；范围端点按 AVRCP 口径钳到 0~127，曲线钳到 0~2
        assertEquals(Prefs.BT_MODE_DEFAULT, back.modeFor(OutputDevice.BT))
        assertEquals(Range(0, Prefs.AVRCP_MAX_VOLUME, Prefs.CURVE_SQRT), back.bluetooth.absolute)
    }

    @Test
    fun unsupportedAbsoluteModeOnNonBluetoothFallsBackToSoftware() {
        val back = VolumeConfig.fromJson(
            """{"enabled":true,"devices":{"speaker":{"mode":1,"a":{"min":0,"max":9,"curve":0}}}}""",
        )
        assertNotNull(back)
        assertEquals(Prefs.BT_MODE_SOFTWARE, back!!.modeFor(OutputDevice.SPEAKER))
    }

    @Test
    fun constructorClampsOutOfRange() {
        val c = VolumeConfig(
            false, 9999, 0,
            dev(0, Range(0, 127, 0), Range(0, 100, 0), Range(0, 100, 0)),
            dev(0, Range(0, 127, 0), Range(0, 100, 0), Range(0, 100, 0)),
            dev(0, Range(0, 127, 0), Range(0, 100, 0), Range(0, 100, 0)),
        )
        assertEquals(100, c.mediaSteps) // clampMediaSteps(9999)
        assertEquals(10, c.keySteps) // clampKeySteps(0)
    }

    @Test
    fun nestedEmptyObjectsDoNotCrash() {
        // 手写配置留下的空对象：范围缺失回落默认，不抛异常
        val back = VolumeConfig.fromJson("""{"enabled":true,"devices":{"bt":{}}}""")
        assertNotNull(back)
        assertEquals(Prefs.BT_MODE_DEFAULT, back!!.modeFor(OutputDevice.BT))
        assertEquals(FALLBACK_ABSOLUTE, back.bluetooth.absolute)
    }

    @Test
    fun nonBluetoothAbsolute_isNotPersisted_soItReadsBackNull() {
        // 模式A 只对蓝牙持久化：外放 / 有线既不写 "a"，读回也必须是 null。
        // 两侧同口径，默认回落值构造的配置才能无损往返（否则首次保存会被误判为失败）。
        val cfg = VolumeConfig(
            true, 100, 29,
            dev(Prefs.BT_MODE_DEFAULT, null, Range(0, 100, 1), Range(0, 100, 1)),
            dev(Prefs.BT_MODE_DEFAULT, null, Range(0, 100, 1), Range(0, 100, 1)),
            dev(Prefs.BT_MODE_ABSOLUTE, Range(8, 127, 0), Range(0, 100, 1), Range(0, 100, 1)),
        )
        val back = requireNotNull(VolumeConfig.fromJson(cfg.toJson()))
        assertNull(back.speaker.absolute)
        assertNull(back.wired.absolute)
        assertEquals(Range(8, 127, 0), back.bluetooth.absolute)
        assertEquals(cfg, back)
        assertEquals(cfg.toJson(), back.toJson())
        // 反向锁形：给非蓝牙塞一个模式A 范围，落盘时会被丢弃 → 它不得参与相等判断
        val polluted = cfg.withDevice(OutputDevice.SPEAKER, cfg.speaker.copy(absolute = Range(8, 127, 0)))
        assertNotEquals(polluted, VolumeConfig.fromJson(polluted.toJson()))
    }

    @Test
    fun roundTripIdentity_allDeviceModeCombinations() {
        // 属性化往返：3 模式 × 3 设备全组合（含 module/enabled 边界）逐组断言无损往返。
        // 非蓝牙不落盘模式A（其 absolute 必为 null），且非蓝牙不可处于 ABSOLUTE 模式
        // （解析会归一为 SOFTWARE）——故非蓝牙只取 DEFAULT/SOFTWARE 两态，保持典范形。
        val soft = Range(0, 100, 1)
        val def = Range(0, 100, 0)
        val btAbs = Range(0, 127, 0)
        var cases = 0
        for (module in booleanArrayOf(true, false)) {
            for (enabled in booleanArrayOf(true, false)) {
                for (btMode in intArrayOf(Prefs.BT_MODE_DEFAULT, Prefs.BT_MODE_SOFTWARE, Prefs.BT_MODE_ABSOLUTE)) {
                    for (wiredMode in intArrayOf(Prefs.BT_MODE_DEFAULT, Prefs.BT_MODE_SOFTWARE)) {
                        for (spkMode in intArrayOf(Prefs.BT_MODE_DEFAULT, Prefs.BT_MODE_SOFTWARE)) {
                            val cfg = VolumeConfig(
                                enabled, 50, 20,
                                dev(spkMode, null, soft, def),
                                dev(wiredMode, null, soft, def),
                                dev(btMode, btAbs, soft, def),
                                module,
                            )
                            val back = VolumeConfig.fromJson(cfg.toJson())
                            assertNotNull("解析失败: mode=$btMode/$wiredMode/$spkMode module=$module", back)
                            assertEquals(
                                "往返不等: module=$module enabled=$enabled bt=$btMode wired=$wiredMode spk=$spkMode",
                                cfg, back,
                            )
                            assertEquals(cfg.toJson(), back!!.toJson())
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(48, cases)
    }

    @Test
    fun roundTripIdentity_dirtyNonBluetoothAbsoluteNormalizesToClean() {
        // 负例补全组合：给非蓝牙塞入不落盘的 absolute，round-trip 后必然被丢弃、
        // 与同样的干净配置归一到同一个典范对象（脏字段不得影响相等）。
        val clean = VolumeConfig(
            true, 50, 20,
            dev(Prefs.BT_MODE_SOFTWARE, null, Range(0, 100, 1), Range(0, 100, 0)),
            dev(Prefs.BT_MODE_DEFAULT, null, Range(0, 100, 1), Range(0, 100, 0)),
            dev(Prefs.BT_MODE_ABSOLUTE, Range(10, 127, 0), Range(0, 100, 1), Range(0, 100, 0)),
        )
        val polluted = clean
            .withDevice(OutputDevice.SPEAKER, clean.speaker.copy(absolute = Range(8, 127, 2)))
            .withDevice(OutputDevice.WIRED, clean.wired.copy(absolute = Range(5, 60, 1)))
        val fromPolluted = requireNotNull(VolumeConfig.fromJson(polluted.toJson()))
        val fromClean = requireNotNull(VolumeConfig.fromJson(clean.toJson()))
        assertEquals(fromClean, fromPolluted)
        assertNull(fromPolluted.speaker.absolute)
        assertNull(fromPolluted.wired.absolute)
    }

    @Test
    fun moduleGate_disablesEveryHookCriterion() {
        // 总闸关闭：即使「启用档位修改」开着且范围已收窄，所有生效判据均给出「不介入」结果
        val narrowed = VolumeConfig(
            true, 50, 15,
            dev(Prefs.BT_MODE_SOFTWARE, Range(0, 127, 0), Range(5, 60, 1), Range(0, 100, 0)),
            dev(Prefs.BT_MODE_SOFTWARE, Range(0, 127, 0), Range(1, 40, 1), Range(0, 100, 0)),
            dev(Prefs.BT_MODE_ABSOLUTE, Range(10, 127, 2), Range(0, 100, 0), Range(0, 100, 0)),
        )
        assertTrue(narrowed.engaged)
        assertNotNull(narrowed.activeRangeFor(OutputDevice.WIRED))
        assertNotNull(narrowed.bluetoothAvrcpMode())

        val off = narrowed.withModuleEnabled(false)
        assertFalse(off.moduleEnabled)
        assertTrue(off.enabled) // 主页开关不被总闸改写
        assertFalse(off.engaged)
        assertFalse(off.remapActive)
        assertNull(off.activeRangeFor(OutputDevice.WIRED))
        assertNull(off.bluetoothAvrcpMode())
        assertFalse(off.suppressBtAbsoluteVolume())
        // 其他不可变辅助不得静默丢掉总闸状态
        assertFalse(off.withEnabled(false).moduleEnabled)
        assertFalse(off.withSteps(60, 20).moduleEnabled)
        assertFalse(off.withAllDevicesDefault().moduleEnabled)
    }

    @Test
    fun missingModuleKey_isParsedAsEnabled() {
        // 历史暂存/存档不带 module 键：不能因此把模块误判为停用
        val back = VolumeConfig.fromJson("""{"enabled":true,"devices":{"bt":{}}}""")
        assertNotNull(back)
        assertTrue(back!!.moduleEnabled)
        assertTrue(back.engaged)
        // 显式写成字符串 "0" / "false" 同样识别为关闭（手写配置容忍）
        assertFalse(VolumeConfig.fromJson("""{"module":"0","enabled":true}""")!!.moduleEnabled)
        assertFalse(VolumeConfig.fromJson("""{"module":false,"enabled":true}""")!!.moduleEnabled)
    }

    private companion object {
        /** 模式A 范围缺失时的兜底（AVRCP 满量程 + 默认曲线）。 */
        val FALLBACK_ABSOLUTE = Range(
            Prefs.ABS_VOLUME_MIN_DEFAULT, Prefs.ABS_VOLUME_MAX_DEFAULT, Prefs.CURVE_TYPE_DEFAULT,
        )
    }
}
