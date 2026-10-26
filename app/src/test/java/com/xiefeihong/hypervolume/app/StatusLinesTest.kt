package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.TextBackedTest
import com.xiefeihong.hypervolume.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态区文案单测（移植旧 `strings.xml` 的 `status_*` 措辞，逐行核对）。
 *
 * 文案在 app 层以中文字面量生成，因此可在 JVM 直接断言，不需要 Robolectric 与 R 资源。
 */
class StatusLinesTest : TextBackedTest() {

    private fun snapshot(
        root: Boolean = true,
        lsposed: Boolean = true,
        actual: Int = 15,
        systemDefault: Int = 15,
        source: StepsSource = StepsSource.SERVICE,
        keySteps: Int = 15,
        mode: Int = Prefs.BT_MODE_SOFTWARE,
        devices: List<String> = emptyList(),
        verdict: ModuleVerdict = ModuleVerdict.INACTIVE_DEFAULT,
    ): StatusSnapshot = StatusSnapshot(
        rootGranted = root,
        lsposedInstalled = lsposed,
        actualMediaSteps = actual,
        systemDefaultSteps = systemDefault,
        stepsSource = source,
        keySteps = keySteps,
        bluetoothMode = mode,
        bluetoothDevices = devices,
        verdict = verdict,
        pendingText = "",
    )

    @Test
    fun rootAndLsposedTexts() {
        assertEquals("Root：可用（su 已授权）", statusRootText(true))
        assertEquals("Root：不可用，无法写入系统配置", statusRootText(false))
        assertEquals("LSPosed：已安装", statusLsposedText(true))
        assertEquals("LSPosed：未检测到，模块将无法加载", statusLsposedText(false))
    }

    @Test
    fun moduleTextsCoverFourVerdicts() {
        assertEquals("模块配置：已生效", statusModuleText(ModuleVerdict.ACTIVE))
        assertEquals("模块配置：未生效，需重启系统框架", statusModuleText(ModuleVerdict.PENDING_RESTART))
        assertEquals("模块配置：未启用（系统默认）", statusModuleText(ModuleVerdict.INACTIVE_DEFAULT))
        assertEquals(
            "模块配置：已关闭，需重启系统框架恢复",
            statusModuleText(ModuleVerdict.INACTIVE_PENDING_RESTORE),
        )
    }

    @Test
    fun volumeLineEmbedsCurrentDefaultSourceAndKeySteps() {
        assertEquals(
            "档位：当前 100 · 默认 15（服务）· 音量键步进 25",
            statusVolumeLine(snapshot(actual = 100, systemDefault = 15, source = StepsSource.SERVICE, keySteps = 25)),
        )
        assertEquals(
            "档位：当前 15 · 默认 15（引擎表）· 音量键步进 15",
            statusVolumeLine(snapshot(source = StepsSource.ENGINE_XML)),
        )
        assertEquals(
            "档位：当前 15 · 默认 15（默认）· 音量键步进 15",
            statusVolumeLine(snapshot(source = StepsSource.BUILTIN)),
        )
    }

    @Test
    fun bluetoothLineJoinsDevicesOrReportsNone() {
        assertEquals(
            "蓝牙音频设备：未连接",
            statusBluetoothText(emptyList()),
        )
        assertEquals(
            "蓝牙音频设备：AirPods（蓝牙音乐 A2DP）、Watch（蓝牙 LE 耳机）",
            statusBluetoothText(listOf("AirPods（蓝牙音乐 A2DP）", "Watch（蓝牙 LE 耳机）")),
        )
    }

    @Test
    fun modeLineUsesShortModeName() {
        assertEquals("音量模式：绝对音量", statusModeText(Prefs.BT_MODE_ABSOLUTE))
        assertEquals("音量模式：相对音量", statusModeText(Prefs.BT_MODE_SOFTWARE))
        assertEquals("音量模式：默认", statusModeText(Prefs.BT_MODE_DEFAULT))
    }

    @Test
    fun statusLinesOrderMatchesLegacyLayout() {
        val lines = statusLines(
            snapshot(
                root = false,
                actual = 60,
                verdict = ModuleVerdict.ACTIVE,
                mode = Prefs.BT_MODE_ABSOLUTE,
                devices = listOf("耳机（蓝牙音乐 A2DP）"),
            ),
        )
        assertEquals(
            listOf(
                "Root：不可用，无法写入系统配置",
                "LSPosed：已安装",
                "模块配置：已生效",
                "档位：当前 60 · 默认 15（服务）· 音量键步进 15",
                "蓝牙音频设备：耳机（蓝牙音乐 A2DP）",
                "音量模式：绝对音量",
            ),
            lines,
        )
        assertEquals(6, lines.size)
    }
}
