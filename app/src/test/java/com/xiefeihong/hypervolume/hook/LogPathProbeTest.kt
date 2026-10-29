package com.xiefeihong.hypervolume.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 日志路径探测（[XposedKit.pickWritableLogPath]）单测。
 *
 * 回归目标有两个：
 *  1. 探针**不得写入任何字节**。早先用「写一个 0」证明可写，结果日志文件首字节留下 NUL，
 *     复制日志时接收端把它当字符串结束符，整段内容显示为乱码（真机实证）。
 *  2. 候选失败要留下原因。旧实现静默 catch，蓝牙 DE 路径为何不可写只能靠推断。
 */
class LogPathProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun probeCreatesFileWithoutWritingBytes() {
        val target = File(tmp.newFolder("logs"), "hypervolume_sys.log")
        val choice = XposedKit.pickWritableLogPath(listOf(target.absolutePath))
        assertEquals(target.absolutePath, choice.path)
        assertTrue(target.exists())
        assertEquals(0L, target.length())
        assertTrue(choice.failures.isEmpty())
    }

    @Test
    fun unwritableCandidateIsSkippedWithReason() {
        val good = File(tmp.newFolder("logs2"), "hypervolume_sys.log")
        // /proc 下无法创建普通文件（root 也不行），用作不可写候选
        val bad = "/proc/hypervolume_probe_dir/hypervolume_sys.log"
        val choice = XposedKit.pickWritableLogPath(listOf(bad, good.absolutePath))
        assertEquals(good.absolutePath, choice.path)
        assertEquals(1, choice.failures.size)
        assertTrue(choice.failures.first().startsWith(bad))
    }

    @Test
    fun allCandidatesFail_returnsNullWithAllReasons() {
        val choice = XposedKit.pickWritableLogPath(
            listOf("/proc/hv_probe_a/x.log", "/proc/hv_probe_b/y.log"),
        )
        assertNull(choice.path)
        assertEquals(2, choice.failures.size)
    }

    // 蓝牙：DE 必须排第一（direct-boot 就能写），CE 永远只能当兜底
    private val btDe = "/data/user_de/0/com.android.bluetooth/files/hypervolume_bt.log"
    private val btCe = "/data/data/com.android.bluetooth/files/hypervolume_bt.log"

    @Test
    fun btCandidates_deviceProtectedFirstLegacyLast() {
        // 模块加载早于 Application 绑定 → 解析不到任何路径，仍要保留确定性 DE 主路径
        assertEquals(listOf(btDe, btCe), btLogCandidatePaths(emptyList(), btDe, btCe))
    }

    @Test
    fun btCandidates_resolvedPathsKeepPriorityAndDedup() {
        assertEquals(
            listOf(btDe, btCe),
            btLogCandidatePaths(listOf(btCe, btDe), btDe, btCe),
        )
        assertEquals(
            listOf(btDe, "/tmp/extra.log", btCe),
            btLogCandidatePaths(listOf("/tmp/extra.log"), btDe, btCe),
        )
    }
}
