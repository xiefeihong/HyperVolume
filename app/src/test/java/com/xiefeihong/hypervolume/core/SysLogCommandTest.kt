package com.xiefeihong.hypervolume.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用侧日志命令的纯函数单测。
 *
 * 回归目标：App 以 root 追加日志时若抢先在 `/data/system/` 建文件，属主会变成 `root:root 0644`，
 * system_server（uid 1000）再也写不进去，模块日志整体回落到 `/data/misc/`，系统侧日志被劈成两份
 * （真机实证：同一时刻 /data/system 只有 [App] 行、/data/misc 全是模块行）。
 * 因此命令里必须先建文件 + 校正属主，再追加。
 */
class SysLogCommandTest {

    @Test
    fun append_correctsOwnerBeforeRootWrite() {
        val cmd = Shell.appendSysLogCommand("push ok")
        val log = Shell.SYS_LOG_FILE

        assertTrue("必须先绑定主路径变量", cmd.startsWith("f=$log; "))
        assertTrue("必须先建文件", cmd.contains("touch \$f"))
        // chown/chmod 必须早于 echo，否则文件先被 root 创建完，属主就已经是 root 了
        assertTrue("chown 必须在追加之前", cmd.indexOf("chown 1000:1000 \$f") in 0 until cmd.indexOf("echo "))
        assertTrue("chmod 必须在追加之前", cmd.indexOf("chmod 0660 \$f") in 0 until cmd.indexOf("echo "))
        assertTrue("仍然追加到主路径", cmd.trimEnd().endsWith(">> $log 2>/dev/null"))
    }

    @Test
    fun append_keepsAppMarkerAndMessage() {
        val cmd = Shell.appendSysLogCommand("push perf total=495ms")
        assertTrue(cmd.contains("[App] push perf total=495ms"))
        assertTrue(cmd.contains("[\$(date '+%m-%d %H:%M:%S')]"))
    }

    @Test
    fun clear_recreatesMainLogAfterDelete() {
        val cmd = Shell.clearModuleLogsCommand()
        val log = Shell.SYS_LOG_FILE
        val rm = cmd.indexOf("rm -f $log")

        assertTrue("清理必须删掉主路径", rm >= 0)
        assertTrue("删完必须立刻重建主路径", cmd.indexOf("touch \$f") > rm)
        // 清理后第一次写入无论来自 App（root）还是模块（system），属主都必须是 system
        assertTrue("清理后要校正属主", cmd.indexOf("chown 1000:1000 \$f") > rm)
        assertTrue("清理后要校正权限", cmd.indexOf("chmod 0660 \$f") > rm)
        assertTrue("仍要清掉模块自己的 logcat 行", cmd.contains("logcat -c"))
        assertTrue("不得删 LSPosed 日志文件本身", !cmd.contains("rm -f /data/adb/lspd/log"))
    }

    @Test
    fun clearAndAppend_keepSameEnsureFragment() {
        // 两条命令必须共用同一段校正片段，否则改一处就会漏掉另一处
        val ensure = "f=${Shell.SYS_LOG_FILE}; touch \$f"
        // 清理：先删，再校正
        val clear = Shell.clearModuleLogsCommand()
        assertTrue("清理命令要先删后建", clear.indexOf("rm -f") < clear.indexOf(ensure))
        assertTrue("清理命令要含校正片段", clear.contains(ensure))
        // 追加：校正直接开头（文件可能还没被任何人建过）
        assertTrue("追加命令要以校正片段开头", Shell.appendSysLogCommand("x").startsWith(ensure))
    }

    @Test
    fun append_escapesShellMetacharacters() {
        // 双引号、$、反引号、反斜杠都会破坏 shell 引用，必须先转义
        val raw = "a\"b\$c`d\\e"
        val escaped = "a\\\"b\\\$c\\`d\\\\e"
        val cmd = Shell.appendSysLogCommand(raw)
        assertTrue("转义后的消息应原样出现在命令里: $cmd", cmd.contains(escaped))
        assertEquals(1, cmd.split(escaped).size - 1)
    }
}
