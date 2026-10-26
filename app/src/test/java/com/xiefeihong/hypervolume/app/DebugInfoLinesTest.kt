package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.TextBackedTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 调试信息 / 设备信息排版单测（采集是 root + 系统属性，单测只覆盖纯函数部分）。
 *
 * 断言按默认（中文）资源逐行核对，行序即界面显示序；资源缺失时 [com.xiefeihong.hypervolume.core.AppText]
 * 会返回 `<missing:id>`，因此这些断言同时兜住了新增 `debug_*` 键的完整性。
 */
class DebugInfoLinesTest : TextBackedTest() {

    private fun snapshot(
        root: Boolean = true,
        lsposed: Boolean = true,
        source: StepsSource = StepsSource.SERVICE,
        defaultSteps: Int = 15,
        effective: String = "{\n  \"module\": true,\n  \"enabled\": true,\n  \"mediaSteps\": 25\n}",
        staged: String = "",
    ): DebugSnapshot = DebugSnapshot(
        moduleVersion = "1.1.2",
        versionCode = 4,
        buildType = "debug",
        packageName = "com.xiefeihong.hypervolume",
        manufacturer = "Xiaomi",
        model = "2210132C",
        romDisplay = "OS3.0.30.0",
        androidRelease = "16",
        sdkInt = 36,
        incremental = "BP2A.250605",
        rootGranted = root,
        lsposedInstalled = lsposed,
        framework = "libxposed API 102",
        systemDefaultSteps = defaultSteps,
        stepsSource = source,
        effectiveConfig = effective,
        stagedConfig = staged,
        logPaths = listOf("/data/system/hypervolume_sys.log", "/data/data/com.android.bluetooth/files/hypervolume_bt.log"),
    )

    @Test
    fun deviceLinesCoverHardwareAndSystem() {
        val lines = deviceInfoLines(snapshot())
        assertEquals(8, lines.size)
        assertEquals("模块版本：1.1.2 (4)", lines[0])
        assertEquals("构建类型：debug", lines[1])
        assertEquals("包名：com.xiefeihong.hypervolume", lines[2])
        assertEquals("设备：Xiaomi 2210132C", lines[3])
        assertEquals("系统 ROM：OS3.0.30.0", lines[4])
        assertEquals("Android：16（API 36）", lines[5])
        assertEquals("构建标识：BP2A.250605", lines[6])
        assertEquals("Xposed 框架：libxposed API 102", lines[7])
    }

    @Test
    fun debugLinesAppendEnvironmentAndRawConfigs() {
        val lines = debugInfoLines(snapshot())
        // 设备信息 8 行 + Root / LSPosed / 默认档位 / 来源 / 日志路径 + 两个小标题 + 两段原文
        assertEquals(17, lines.size)
        assertEquals("模块版本：1.1.2 (4)", lines[0])
        assertEquals("Root：可用", lines[8])
        assertEquals("LSPosed：已安装", lines[9])
        assertEquals("默认档位：15", lines[10])
        assertEquals("档位数来源：服务", lines[11])
        assertEquals(
            "日志路径：/data/system/hypervolume_sys.log、/data/data/com.android.bluetooth/files/hypervolume_bt.log",
            lines[12],
        )
        assertEquals("生效配置（Settings.Global）", lines[13])
        assertEquals("{\n  \"module\": true,\n  \"enabled\": true,\n  \"mediaSteps\": 25\n}", lines[14])
        assertEquals("应用暂存配置", lines[15])
        assertEquals("尚无配置记录", lines[16])
    }

    @Test
    fun unavailableEnvironmentIsStatedExplicitly() {
        val lines = debugInfoLines(
            snapshot(
                root = false,
                lsposed = false,
                source = StepsSource.BUILTIN,
                defaultSteps = 10,
                effective = "",
            ),
        )
        assertEquals("Root：不可用", lines[8])
        assertEquals("LSPosed：未检测到", lines[9])
        // 默认档位行不依赖 root，永远给出当前判定结果
        assertEquals("默认档位：10", lines[10])
        assertEquals("档位数来源：默认", lines[11])
        // 无 root 时两段配置都以占位句呈现，界面不会出现空白行
        assertEquals("尚无配置记录", lines[14])
        assertEquals("尚无配置记录", lines[16])
    }

    @Test
    fun engineSourceLabelIsUsedForEngineTable() {
        assertEquals("档位数来源：引擎表", debugInfoLines(snapshot(source = StepsSource.ENGINE_XML))[11])
    }
}
