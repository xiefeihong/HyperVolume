package com.xiefeihong.hypervolume.core

import com.xiefeihong.hypervolume.R
import java.util.concurrent.TimeUnit

/**
 * root shell 执行工具。
 *
 * 所有方法都会阻塞当前线程，请在后台线程调用。
 */
object Shell {

    /** su 命令执行超时（毫秒）。 */
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    /** 开发者选项「停用绝对音量功能」对应的系统设置键（1=停用）。 */
    const val SETTING_AV_DISABLE = "bluetooth_disable_absolute_volume"

    /** 模块自写日志文件（系统框架侧，root 可读写）。 */
    const val SYS_LOG_FILE = "/data/system/hypervolume_sys.log"

    /** 系统框架日志的备用路径（`/data/system/` 被 SELinux 拦阻时回落；读/清日志都扫这一份）。 */
    const val SYS_LOG_FILE_FALLBACK = "/data/misc/hypervolume_sys.log"

    /** 蓝牙进程侧模块日志（蓝牙应用数据目录）。 */
    const val BT_LOG_FILE = "/data/data/com.android.bluetooth/files/hypervolume_bt.log"

    /** 模块日志统一标记（grep / LSPosed 日志过滤用），与 YLog tag 保持一致。 */
    const val LOG_MARKER = "HyperVolume"

    /** post-fs-data 开机脚本（Magisk / KernelSU 最早执行阶段，先于 system_server 启动）。 */
    const val BOOT_SCRIPT_FILE = "/data/adb/post-fs-data.d/hypervolume.sh"

    /** HyperOS AudioService 初始化读取的媒体档位属性。 */
    private const val PROP_MEDIA_VOL_STEPS = "ro.config.media_vol_steps"

    /** 在 root 身份下执行一条 shell 命令。 */
    fun su(command: String): ShellResult = exec("su", "-c", command)

    /** 执行命令并读取合并后的输出（stdout + stderr）。 */
    fun exec(vararg command: String): ShellResult {
        var process: Process? = null
        return try {
            val builder = ProcessBuilder(*command)
            builder.redirectErrorStream(true)
            process = builder.start()

            val output = StringBuilder()
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (output.isNotEmpty()) {
                        output.append('\n')
                    }
                    output.append(line)
                }
            }

            if (!process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return ShellResult(-1, "$output\n[timeout]")
            }
            ShellResult(process.exitValue(), output.toString())
        } catch (e: Exception) {
            ShellResult(-1, e.toString())
        } finally {
            process?.destroy()
        }
    }

    /** 检测 root 是否可用（su 已授权且可执行）。 */
    fun isRootAvailable(): Boolean {
        val result = su("id")
        return result.isSuccess && result.output.contains("uid=0")
    }

    /**
     * 一次 root 往返同时探测「su 已授权」与「LSPosed 目录存在」：把原先
     * [isRootAvailable] + 目录检查两条 su 合并为一条，省一次进程与授权往返（实测 su 固定开销虽低，仍值得合并）。
     */
    fun rootAndLsposed(): RootProbe {
        val result = su(RootProbe.CHECK_COMMAND)
        return RootProbe.parse(result.output)
    }

    /**
     * 用单引号安全包裹字符串供 root shell 使用。POSIX 单引号内除单引号外一切原样保留，
     * 故把值里的单引号闭开为 `'\''`（结束引号 + 转义单引号 + 重开引号）。
     * 当前配置 JSON 只含双引号不会产生单引号，本包裹为防御性加固，避免未来格式变化引入命令注入。
     */
    internal fun quote(s: String?): String =
        if (s == null) "''" else "'" + s.replace("'", "'\\''") + "'"

    /** 读取 Settings.Global 中的值（root 方式，用于校验写入结果）。 */
    fun getGlobalConfig(key: String): ShellResult = su("settings get global $key")

    /**
     * 一次 root 往返完成配置下发：写 `Settings.Global` 主键 → 写配置镜像（`printf '%s'` 无尾随换行）
     * → 按需写「停用绝对音量」开关。每段落成功时打一个 [PushMarks] 标记，整体末尾 `exit 0`，
     * 使整体 rc 不再受最后一条影响、失败位置由缺失的标记判定（顺带修掉「`settings put` 空值也返回 rc=0」的误判）。
     *
     * [avValue] 为 `null` 表示绝对音量开关当前值已等于目标值（幂等跳过），不生成该段命令。
     * 镜像与主键共用一个 shell 变量 `V`，避免多行 JSON 二次转义；`"$V"` 展开保留换行。
     */
    fun pushAll(key: String, json: String, avValue: String?): ShellResult =
        su(pushAllCommand(key, json, avValue))

    /** [pushAll] 的命令拼装（纯函数，供 JVM 单测直接断言形态）。 */
    internal fun pushAllCommand(key: String, json: String, avValue: String?): String {
        val sb = StringBuilder()
        sb.append("V=").append(quote(json)).append("; ")
        sb.append("settings put global ").append(key).append(" \"\$V\" ")
            .append("&& echo '").append(PushMarks.GLOBAL).append("'; ")
        sb.append("printf '%s' \"\$V\" > ").append(Prefs.MIRROR_CONFIG_FILE)
            .append(" && chmod 644 ").append(Prefs.MIRROR_CONFIG_FILE)
            .append(" && restorecon ").append(Prefs.MIRROR_CONFIG_FILE).append(" 2>/dev/null ")
            .append("&& echo '").append(PushMarks.MIRROR).append("'; ")
        if (avValue != null) {
            val safe = if (avValue == "1") "1" else "0"
            sb.append("settings put global ").append(SETTING_AV_DISABLE).append(" ").append(safe)
                .append(" && echo '").append(PushMarks.AV).append("'; ")
        }
        sb.append("exit 0")
        return sb.toString()
    }

    /**
     * 写入 post-fs-data 开机脚本（档位开机生效的终极保险，不依赖任何 Hook）：
     * 开机最早阶段（system_server 启动前）以 resetprop 直接设置 ro.config.media_vol_steps，
     * AudioService 初始化时读到的就是用户档位数。生效标志与档位数以字面量烘进脚本（配置已改为
     * JSON，shell 无法用 cut 解析）；resetprop 对 ro. 属性的修改只能靠 Magisk / KernelSU 的 resetprop
     * （setprop 不允许改 ro.），两者都不可用时静默失败。脚本写入与「立即对齐当前属性」合进**同一次 su**
     * （原实现分两条 su），停用/越界时删除属性回落 ROM 默认。
     *
     * 幂等：本方法仅在 [SaveEngine] 判定 `(active, steps)` 相对上次变化时才被调用（见 boot 事实键）。
     */
    fun writeBootScript(active: Boolean, mediaSteps: Int): ShellResult {
        val steps = Prefs.clampMediaSteps(mediaSteps)
        return su(bootScriptCommand(active, steps))
    }

    /** [writeBootScript] 的命令拼装（纯函数，供单测断言逐行 quote 与合并后的单程结构）。 */
    internal fun bootScriptCommand(active: Boolean, steps: Int): String {
        val lines = bootScriptLines(active, steps)
        val sb = StringBuilder("printf '%s\\n'")
        for (line in lines) {
            sb.append(' ').append(quote(line))
        }
        sb.append(" > ").append(BOOT_SCRIPT_FILE)
            .append("; chmod 755 ").append(BOOT_SCRIPT_FILE)
            .append("; restorecon ").append(BOOT_SCRIPT_FILE).append(" 2>/dev/null; ")
        // 立即对齐当前属性值（供本周期内任何 system_server 重启使用），并入同一次 su。
        sb.append(propCommand(active, steps))
        return sb.toString()
    }

    /** 开机脚本正文行（[active] 与已 clamp 的 [steps] 烘成字面量）。 */
    internal fun bootScriptLines(active: Boolean, steps: Int): Array<String> = arrayOf(
        "#!/system/bin/sh",
        "# HyperVolume boot script: set media volume steps before system_server starts.",
        "if [ \"${if (active) 1 else 0}\" = \"1\" ] && [ $steps -ge 10 ] && [ $steps -le 100 ]; then",
        "  resetprop $PROP_MEDIA_VOL_STEPS $steps 2>/dev/null" +
            " || magisk resetprop $PROP_MEDIA_VOL_STEPS $steps 2>/dev/null",
        "else",
        "  resetprop --delete $PROP_MEDIA_VOL_STEPS 2>/dev/null" +
            " || magisk resetprop --delete $PROP_MEDIA_VOL_STEPS 2>/dev/null",
        "fi",
    )

    /** 立即对齐/删除媒体档位属性的命令片段。 */
    private fun propCommand(active: Boolean, steps: Int): String =
        if (active) {
            "resetprop $PROP_MEDIA_VOL_STEPS $steps 2>/dev/null" +
                " || magisk resetprop $PROP_MEDIA_VOL_STEPS $steps 2>/dev/null"
        } else {
            "resetprop --delete $PROP_MEDIA_VOL_STEPS 2>/dev/null" +
                " || magisk resetprop --delete $PROP_MEDIA_VOL_STEPS 2>/dev/null"
        }

    /**
     * 探测配置内容当前落在哪些系统侧落点（[PushMarks.GLOBAL] 主键 / [PushMarks.MIRROR] 镜像文件），
     * 返回**未命中**的标记；调试信息据此提示落点状态。须在 root 下调用（读镜像文件需 root）。
     */
    fun probeLanding(): List<String> {
        val cmd =
            "V=\"\$(settings get global ${Prefs.GLOBAL_KEY} 2>/dev/null)\"; " +
                "[ -n \"\$V\" ] && [ \"\$V\" != \"null\" ] && echo '${PushMarks.GLOBAL}'; " +
                "[ -s ${Prefs.MIRROR_CONFIG_FILE} ] && echo '${PushMarks.MIRROR}'"
        return PushMarks.missing(su(cmd).output, PushMarks.LANDING)
    }

    /**
     * 清除本应用写入系统侧的全部配置，使数据不可恢复：
     * 删除 Settings.Global 配置键、配置镜像文件、post-fs-data 开机脚本，
     * 并 resetprop 删除被改写的媒体档位属性以回落 ROM 默认。
     *
     * 须在 root 下调用；各删除均静默容错（文件/键不存在时忽略）。
     */
    fun clearWrittenConfig() {
        su(
            "settings delete global ${Prefs.GLOBAL_KEY} 2>/dev/null; " +
                "rm -f ${Prefs.MIRROR_CONFIG_FILE} 2>/dev/null; " +
                "rm -f $BOOT_SCRIPT_FILE 2>/dev/null; " +
                "resetprop --delete $PROP_MEDIA_VOL_STEPS 2>/dev/null" +
                " || magisk resetprop --delete $PROP_MEDIA_VOL_STEPS 2>/dev/null",
        )
    }

    /**
     * 重启蓝牙（关闭再打开），使模块在蓝牙进程中的 Hook 重新加载。
     * 蓝牙耳机等设备会断开，需要重新连接。
     */
    fun restartBluetooth(): ShellResult = su(
        "echo \"[\$(date '+%m-%d %H:%M:%S')][App] restart bluetooth requested\" " +
            ">> $SYS_LOG_FILE 2>/dev/null; " +
            "svc bluetooth disable 2>/dev/null || cmd bluetooth_manager disable; " +
            "sleep 1.5; " +
            "svc bluetooth enable 2>/dev/null || cmd bluetooth_manager enable",
    )

    /**
     * 软重启系统框架（system_server 进程退出后由 zygote 自动拉起），
     * 使新的音量档位数生效（AudioService 只在启动时创建流档位）。
     *
     * 重启请求与新旧进程号会写入模块日志（/data/system/hypervolume_sys.log），
     * 可在应用内「查看模块日志」核对是否真正重启（old != new 即已重启）。
     */
    fun restartSystemServer(): ShellResult = su(
        "echo \"[\$(date '+%m-%d %H:%M:%S')][App] restart system_server requested\" " +
            ">> $SYS_LOG_FILE 2>/dev/null; " +
            "OLD=\$(pidof system_server); " +
            "kill -9 \$OLD 2>/dev/null || killall -9 system_server 2>/dev/null; " +
            "sleep 3; " +
            "NEW=\$(pidof system_server); " +
            "echo \"[\$(date '+%m-%d %H:%M:%S')][App] system_server old=\$OLD new=\$NEW\" " +
            ">> $SYS_LOG_FILE 2>/dev/null",
    )

    /**
     * 向系统框架模块日志追加一条应用侧记录（root 写入）。
     * 用于在「查看模块日志」中核对应用推送的配置内容与结果。
     */
    fun appendSysLog(message: String?) {
        if (message.isNullOrEmpty()) {
            return
        }
        val safe = message.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        su("echo \"[\$(date '+%m-%d %H:%M:%S')][App] $safe\" >> $SYS_LOG_FILE 2>/dev/null")
    }

    /**
     * 读取模块日志。
     *
     * 模块自写日志文件优先（不依赖 LSPosed 日志实现）：
     * system_server → [SYS_LOG_FILE]；蓝牙进程 → [BT_LOG_FILE]。
     * 再合并 LSPosed 日志文件与 logcat 结果；全部为空时附上诊断信息。
     */
    fun readModuleLogs(): String {
        val result = su(
            "echo '@SYS@'; " +
                "tail -n 200 $SYS_LOG_FILE 2>/dev/null; " +
                "tail -n 200 $SYS_LOG_FILE_FALLBACK 2>/dev/null; " +
                "echo '@BT@'; tail -n 200 $BT_LOG_FILE 2>/dev/null; " +
                "echo '@LSPD@'; grep -a -h '$LOG_MARKER' /data/adb/lspd/log/* 2>/dev/null" +
                " | tail -n 150; " +
                "echo '@LOGCAT@'; logcat -d -t 500 2>/dev/null" +
                " | grep -a '$LOG_MARKER' | tail -n 150",
        )
        val output = result.output
        val sys = section(output, "@SYS@", "@BT@")
        val bt = section(output, "@BT@", "@LSPD@")
        val lspd = section(output, "@LSPD@", "@LOGCAT@")
        val logcat = section(output, "@LOGCAT@", null)

        val report = StringBuilder()
        if (sys.isNotEmpty()) {
            report.append(AppText.str(R.string.log_sec_sys)).append(sys).append("\n\n")
        }
        if (bt.isNotEmpty()) {
            report.append(AppText.str(R.string.log_sec_bt)).append(bt).append("\n\n")
        }
        if (lspd.isNotEmpty()) {
            report.append(AppText.str(R.string.log_sec_lspd)).append(lspd).append("\n\n")
        }
        if (logcat.isNotEmpty()) {
            report.append(AppText.str(R.string.log_sec_logcat)).append(logcat)
        }

        if (report.length == 0) {
            report.append(AppText.str(R.string.log_none))
                .append(AppText.str(R.string.log_check_paths))
                .append("• $SYS_LOG_FILE\n")
                .append("• /data/adb/lspd/log/\n")
                .append("• $BT_LOG_FILE")
        }
        return report.toString().trim()
    }

    /**
     * 清理模块日志：删除模块自写的专用文件 + 清除 logcat。
     *
     * LSPosed 日志（/data/adb/lspd/log/）是多模块共享的追加文件，不能整体删除；
     * 这里用 `sed` 只剔除含本模块标记 [LOG_MARKER] 的行，保留其他模块日志。
     */
    fun clearModuleLogs() {
        su(
            "rm -f $SYS_LOG_FILE $SYS_LOG_FILE_FALLBACK $BT_LOG_FILE 2>/dev/null; " +
                "for f in /data/adb/lspd/log/*; do " +
                "[ -f \"\$f\" ] && sed -i '/$LOG_MARKER/d' \"\$f\" 2>/dev/null; done; " +
                "logcat -c 2>/dev/null",
        )
    }

    /** 截取 [startKey] 与 [endKey] 之间的输出段（endKey 为 null 时到结尾）。 */
    private fun section(output: String, startKey: String, endKey: String?): String {
        var start = output.indexOf(startKey)
        if (start < 0) {
            return ""
        }
        start += startKey.length
        var end = endKey?.let { output.indexOf(it, start) } ?: -1
        if (end < 0) {
            end = output.length
        }
        return output.substring(start, end).trim()
    }
}

/**
 * [Shell.pushAll] 一次 su 里各段落成功时回显的落点标记。
 *
 * 命令骨架每段形如 `… && echo '@hv_ok:global'`，整体末尾 `exit 0`：
 * 于是整体 rc 恒为 0，成功与否**只看对应标记是否出现**（顺带避开 `settings put`
 * 写入空值也返回 rc=0 的误判）。[missing] 为纯函数，供 JVM 单测直接断言解析。
 */
internal object PushMarks {

    const val GLOBAL = "hv_ok:global"
    const val MIRROR = "hv_ok:mirror"
    const val AV = "hv_ok:av"

    /** 配置内容的两个落点（[probeLanding] 探测范围，不含条件性的绝对音量开关）。 */
    val LANDING = listOf(GLOBAL, MIRROR)

    /** 返回 [expected] 中未在 [output] 命中的标记（保持 [expected] 顺序，子串匹配）。 */
    fun missing(output: String, expected: List<String>): List<String> =
        expected.filterNot { output.contains(it) }
}

/**
 * 一次 su 往返同时得到的「root 已授权」与「LSPosed 已安装」两个事实（合并探测的解析结果）。
 *
 * [parse] 为纯函数：root 判据沿用 [Shell.isRootAvailable] 的 `uid=0` 口径；
 * LSPosed 判据为 `ls -d /data/adb/lspd` 是否列出该目录。
 */
data class RootProbe(val rootGranted: Boolean, val lsposedInstalled: Boolean) {

    companion object {

        /** 合并探测命令：`id` 验 root、`ls -d` 验 LSPosed 目录，两条一次进程内完成。 */
        const val CHECK_COMMAND = "id 2>/dev/null; ls -d /data/adb/lspd 2>/dev/null"

        /** LSPosed 安装目录（存在即视为已安装）。 */
        private const val LSPD_MARKER = "/data/adb/lspd"

        fun parse(output: String): RootProbe = RootProbe(
            rootGranted = output.contains("uid=0"),
            lsposedInstalled = output.contains(LSPD_MARKER),
        )
    }
}
