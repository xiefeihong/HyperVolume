package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VolumeConfig] 的 XML 序列化/解析往返、畸形输入回落、构造期钳制与 XXE 加固的 JVM 单元测试。
 */
class VolumeConfigTest {

    private fun dev(mode: Int, abs: Range?, soft: Range?, def: Range?): DeviceConfig =
        DeviceConfig(mode, abs, soft, def)

    private fun sample(): VolumeConfig = VolumeConfig(
        true, 100, 25,
        dev(Prefs.BT_MODE_DEFAULT, Range(0, 127, 0), Range(0, 100, 1), Range(0, 100, 0)),
        dev(Prefs.BT_MODE_SOFTWARE, Range(0, 127, 0), Range(1, 80, 2), Range(0, 100, 0)),
        dev(Prefs.BT_MODE_ABSOLUTE, Range(10, 127, 0), Range(0, 100, 1), Range(0, 100, 0)),
    )

    @Test
    fun xmlRoundTrip_stable() {
        val c = sample()
        val xml = c.toXml()
        val back = VolumeConfig.fromXml(xml)
        assertNotNull(back)
        assertEquals(xml, back!!.toXml())
    }

    @Test
    fun prettyXml_parsesEquivalentToCompact() {
        val c = sample()
        val compact = c.toXml()
        val fromPretty = VolumeConfig.fromXml(c.toPrettyXml())
        assertNotNull(fromPretty)
        assertEquals(compact, fromPretty!!.toXml())
    }

    @Test
    fun fieldsPreservedAcrossRoundTrip() {
        val back = VolumeConfig.fromXml(sample().toXml())
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
        assertNull(VolumeConfig.fromXml(null))
        assertNull(VolumeConfig.fromXml("   "))
        assertNull(VolumeConfig.fromXml("<notconfig enabled=\"1\"/>")) // 根标签非 config
        assertNull(VolumeConfig.fromXml("<<<broken>>>")) // 解析异常
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val back = VolumeConfig.fromXml("<config enabled=\"1\"></config>")
        assertNotNull(back)
        assertTrue(back!!.enabled)
        assertEquals(Prefs.MEDIA_STEPS_DEFAULT, back.mediaSteps)
        assertEquals(Prefs.KEY_STEP_DEFAULT, back.keySteps)
        // 无 <device> 子节点 → 各设备回落默认直通模式
        assertEquals(Prefs.BT_MODE_DEFAULT, back.modeFor(OutputDevice.BT))
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
    fun doctypeRejectedForXxe() {
        val evil = "<?xml version=\"1.0\"?>" +
            "<!DOCTYPE config [<!ENTITY x \"y\">]>" +
            "<config enabled=\"1\" mediaSteps=\"100\" keySteps=\"25\"/>"
        assertNull(VolumeConfig.fromXml(evil)) // disallow-doctype-decl → 解析异常 → null
    }
}
