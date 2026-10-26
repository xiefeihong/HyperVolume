package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Shell] 合并下发命令与落点/探测解析的纯函数单测（不跑真机 shell，只断言命令形态与解析）。
 *
 * 覆盖计划 B1/C/E：pushAll 命令骨架（变量复用 + `printf '%s'` + `exit 0` + 标记顺序）、
 * 开机脚本逐行 quote（注入面）、[PushMarks] 缺失解析、root + LSPosed 合并探测解析。
 */
class ShellPushCommandTest {

    private val json = "{\n  \"enabled\": true\n}"

    @Test
    fun pushAllCommand_hasVariableAssignPrintfAndExitZero() {
        val cmd = Shell.pushAllCommand(Prefs.GLOBAL_KEY, json, avValue = "1")
        // V=<quote(json)> 前置赋值，主键与镜像共用同一变量，避免多行 JSON 二次转义
        assertTrue(cmd, cmd.startsWith("V=" + Shell.quote(json) + "; "))
        assertTrue(cmd, cmd.contains("settings put global ${Prefs.GLOBAL_KEY} \"\$V\""))
        // 镜像用 printf '%s'（无尾随换行），并 chmod/restorecon
        assertTrue(cmd, cmd.contains("printf '%s' \"\$V\" > ${Prefs.MIRROR_CONFIG_FILE}"))
        assertTrue(cmd, cmd.contains("chmod 644 ${Prefs.MIRROR_CONFIG_FILE}"))
        // 末尾显式 exit 0：整体 rc 不受最后一条影响
        assertTrue(cmd, cmd.endsWith("exit 0"))
    }

    @Test
    fun pushAllCommand_marksAppearInGlobalMirrorAvOrder() {
        val cmd = Shell.pushAllCommand(Prefs.GLOBAL_KEY, json, avValue = "0")
        val g = cmd.indexOf(PushMarks.GLOBAL)
        val m = cmd.indexOf(PushMarks.MIRROR)
        val a = cmd.indexOf(PushMarks.AV)
        assertTrue("global 标记缺失", g >= 0)
        assertTrue("mirror 标记缺失", m >= 0)
        assertTrue("av 标记缺失", a >= 0)
        assertTrue("标记顺序应为 global<mirror<av", g < m && m < a)
        // 绝对音量段写目标值 0（safe 归一）
        assertTrue(cmd, cmd.contains("${Shell.SETTING_AV_DISABLE} 0"))
    }

    @Test
    fun pushAllCommand_omitsAvSegmentWhenValueIsNull() {
        val cmd = Shell.pushAllCommand(Prefs.GLOBAL_KEY, json, avValue = null)
        assertFalse(cmd, cmd.contains(PushMarks.AV))
        assertFalse(cmd, cmd.contains(Shell.SETTING_AV_DISABLE))
    }

    @Test
    fun pushMarks_missingReturnsOnlyAbsentInExpectedOrder() {
        val out = "noise ${PushMarks.GLOBAL} more ${PushMarks.MIRROR}"
        val expected = listOf(PushMarks.GLOBAL, PushMarks.MIRROR, PushMarks.AV)
        // global + mirror 命中，仅 av 缺失
        assertEquals(listOf(PushMarks.AV), PushMarks.missing(out, expected))
        // 全命中
        assertTrue(PushMarks.missing("$out ${PushMarks.AV}", expected).isEmpty())
        // 主键缺失（判定 putFailed 的依据）
        assertEquals(
            listOf(PushMarks.GLOBAL, PushMarks.MIRROR, PushMarks.AV),
            PushMarks.missing("", expected),
        )
    }

    @Test
    fun bootScriptCommand_quotesEveryLineIntoSinglePrintf() {
        val cmd = Shell.bootScriptCommand(active = true, steps = 100)
        // 逐行走 quote()：不再出现裸的单引号包裹，而是 `printf '%s\n' '<行1>' '<行2>' …`
        assertTrue(cmd, cmd.startsWith("printf '%s\\n' "))
        for (line in Shell.bootScriptLines(true, 100)) {
            assertTrue("每个脚本行都应被 quote 包裹：$line", cmd.contains(Shell.quote(line)))
        }
        assertTrue(cmd, cmd.contains("> ${Shell.BOOT_SCRIPT_FILE}"))
        // 立即对齐属性并入同一次 su
        assertTrue(cmd, cmd.contains("resetprop ro.config.media_vol_steps 100"))
    }

    @Test
    fun bootScriptCommand_inactiveDeletesProperty() {
        val cmd = Shell.bootScriptCommand(active = false, steps = 100)
        assertTrue(cmd, cmd.contains("resetprop --delete ro.config.media_vol_steps"))
    }

    @Test
    fun quote_escapesSingleQuoteToAvoidInjection() {
        // 值里的单引号必须闭开为 '\''，否则破腔形成注入面
        assertEquals("'ab'\\''cd'", Shell.quote("ab'cd"))
        assertEquals("''", Shell.quote(null))
    }

    @Test
    fun rootProbe_parsesRootAndLsposedFromCombinedOutput() {
        val both = "uid=0(root) gid=0(root)\n/data/adb/lspd"
        val rp = RootProbe.parse(both)
        assertTrue(rp.rootGranted)
        assertTrue(rp.lsposedInstalled)

        val rootOnly = "uid=0(root) gid=0(root)"
        assertFalse(RootProbe.parse(rootOnly).lsposedInstalled)
        assertTrue(RootProbe.parse(rootOnly).rootGranted)

        val denied = "uid=2000(shell) gid=2000(shell)"
        assertFalse(RootProbe.parse(denied).rootGranted)
        assertFalse(RootProbe.parse(denied).lsposedInstalled)
    }
}
