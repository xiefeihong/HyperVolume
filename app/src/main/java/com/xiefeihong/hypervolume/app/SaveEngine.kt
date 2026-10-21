package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.core.Avrcp
import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.Range
import com.xiefeihong.hypervolume.core.Shell
import com.xiefeihong.hypervolume.core.VolumeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 保存生效与重启流程（移植自旧 `SaveFlow`）。
 *
 * 旧实现直接弹 `AlertDialog` + `Toast` + 后台线程 `Handler` 回主线程；Compose 侧改为
 * **先算计划、再执行计划**两步，界面只消费数据：
 *  - [planSave] / [resolve] 纯算出 [SavePlan]（门控警告 / 改动清单 / 是否需重启），
 *    其中改动清单与重启判定由文件末尾的纯函数完成，可在 JVM 单测直接断言；
 *  - [pushConfig] / [commit] / [clearAppData] / [restart] 是 `suspend` 的 root shell
 *    动作，统一放到 [Dispatchers.IO]，不再有手工线程调度。
 *
 * 判据与旧实现逐字一致：
 *  - 门控：关闭总开关 + 任一设备非默认 + 未固化系统默认档位 + 用户未确认过 → 先给三选项；
 *  - 是否需重启系统框架：优先以**实时读到**的当前档位数为准，读不到才与上次基线比对；
 *  - 是否需重启蓝牙：仅蓝牙模式变化影响 AVRCP 协商（有线/外放经 Hook 实时读配置，无需重启）；
 *  - 只重启系统框架时不再单独重启蓝牙（蓝牙进程随框架重启）。
 *
 * 动态文案在本层以中文字面量生成（与 [PreviewBuilder]、[StatusProbe] 同一口径），
 * 使单测不依赖 R 资源；静态标签留给界面层。
 */
class SaveEngine(private val store: ConfigStore, private val probe: StatusProbe) {

    // ==================== 计划：门控 + 改动比对 ====================

    /**
     * 生成保存计划（旧 `runSave`）。
     *
     * @param state 当前编辑态；其 `model` 即待保存配置（Compose 侧每次交互都已回写模型，
     *              等价旧实现进入保存前先调 `writeUiIntoModel(true)`）
     * @param forceDefault true=「恢复系统默认」完整重置（关闭总开关、各设备默认直通）
     */
    fun planSave(state: EditorState, forceDefault: Boolean): SavePlan {
        val pending = if (forceDefault) store.defaultConfig(state.systemDefaultSteps) else state.model
        val base = VolumeConfig.fromXml(state.lastSavedXml)
        val blocked = !forceDefault && !pending.enabled && pending.anyDeviceNonDefault() &&
            store.capturedSystemDefaultSteps() == null && !store.systemDefaultAcknowledged
        return if (blocked) {
            blockedPlan(state, pending)
        } else {
            confirmPlan(state, pending, base, fromReset = forceDefault)
        }
    }

    /**
     * 门控计划：未获取系统默认档位时给出三个处理入口（启用档位修改 / 使用默认模式 /
     * 使用档位默认值），选定后由 [resolve] 进入常规确认框。
     *
     * 唯一可信来源是系统服务实时读到的媒体档位上限（`getStreamMaxVolume`，1~100 视为原生值，
     * &gt;100 可能被模块放大过、不予固化）；本机引擎表值不一定被系统采用，绝不用于固化。
     */
    private fun blockedPlan(state: EditorState, pending: VolumeConfig): SavePlan.Blocked {
        val realSteps = probe.readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX)
        val trusted = realSteps in 1..100
        val steps = Prefs.clampMediaSteps(if (trusted) realSteps else state.systemDefaultSteps)
        return SavePlan.Blocked(
            pending = pending,
            message = Messages.saveNeedSystemDefault(modeLabelShort(warnModeId(pending, state.editingDevice))),
            steps = steps,
            keySteps = Prefs.clampKeySteps(steps),
            trustedRealSteps = trusted,
        )
    }

    /**
     * 执行门控三选项，返回后续计划（旧警告框的三个按钮回调）。
     *
     * 选「使用档位默认值」后置 [ConfigStore.systemDefaultAcknowledged] 不再重复警告；
     * 但 [Prefs.KEY_SYSTEM_DEFAULT_STEPS] 只在读到可信系统真实档位时才固化，否则留待再探测。
     */
    fun resolve(state: EditorState, blocked: SavePlan.Blocked, choice: BlockedChoice): SavePlan {
        val pending = when (choice) {
            BlockedChoice.ENABLE_STEPS -> blocked.pending.withEnabled(true)
            BlockedChoice.DEFAULT_MODE -> blocked.pending.withAllDevicesDefault()
            BlockedChoice.DEFAULT_STEPS -> blocked.pending.withSteps(blocked.steps, blocked.keySteps)
        }
        if (choice == BlockedChoice.DEFAULT_STEPS) {
            if (blocked.trustedRealSteps) store.captureSystemDefaultSteps(blocked.steps)
            store.systemDefaultAcknowledged = true
        }
        return confirmPlan(state, pending, VolumeConfig.fromXml(state.lastSavedXml), fromReset = false)
    }

    /** 常规确认计划（旧 `proceedSave`）：与已生效基线比对列出改动，并算出需重启哪些组件。 */
    private fun confirmPlan(
        state: EditorState, pending: VolumeConfig, base: VolumeConfig?, fromReset: Boolean,
    ): SavePlan {
        if (base != null && base.toXml() == pending.toXml()) {
            return SavePlan.Nothing(Messages.saveNone)
        }
        val changes = describeChanges(base, pending)
        // 生效后的物理档位数与当前系统实际档位数不同才需重启系统框架：优先以实时读到的
        // 档位数为准（等于当前值就不提示重启），读不到（<=0）才回退到与上次基线比对。
        val currentPhysical = probe.readStreamMaxSafe(Prefs.STREAM_MUSIC_INDEX)
        val targetSteps = state.targetPhysicalSteps(pending)
        val needsSystem = if (currentPhysical > 0) {
            targetSteps != currentPhysical
        } else {
            base == null || targetSteps != state.targetPhysicalSteps(base)
        }
        // 仅蓝牙模式变化影响 AVRCP 绝对音量协商（需重连）；有线/外放的模式或范围改动
        // 经 Hook 每次实时读配置生效，无需重启任何组件。
        val needsBt = base == null || base.bluetooth.mode != pending.bluetooth.mode
        val message = buildString {
            if (changes.isEmpty()) {
                append(Messages.saveFirst)
            } else {
                for (line in changes) {
                    append("• ").append(line).append('\n')
                }
            }
        }
        return SavePlan.Confirm(
            pending = pending,
            message = message,
            restartBt = !needsSystem && needsBt,
            restartSystem = needsSystem,
            thenClearData = fromReset,
        )
    }

    // ==================== 执行：写入系统 ====================

    /**
     * 把配置写入系统（Settings.Global 供 Hook 端实时读取）+ 镜像文件 + boot 脚本 +
     * 「停用绝对音量」开发者选项，并回读解析后与紧凑 XML 比对验证（与排版无关）。
     *
     * 全程为 root 阻塞调用，切到 [Dispatchers.IO] 执行。
     */
    suspend fun pushConfig(model: VolumeConfig): PushResult = withContext(Dispatchers.IO) {
        store.persist(model)
        val configXml = model.toXml()          // 紧凑：暂存与差异比对基线
        val pushXml = model.toPrettyXml()      // 格式化：写入 Settings.Global 与镜像，便于查看
        val softwareMode = model.modeFor(OutputDevice.BT) == Prefs.BT_MODE_SOFTWARE
        val active = model.remapActive
        val putResult = Shell.putGlobalConfig(Prefs.GLOBAL_KEY, pushXml)
        Shell.writeGlobalMirror(pushXml)
        Shell.writeBootScript(active, model.mediaSteps)
        Shell.putGlobalConfig(Shell.SETTING_AV_DISABLE, if (active && softwareMode) "1" else "0")
        var verified = false
        if (putResult.isSuccess) {
            val readBack = VolumeConfig.fromXml(Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output)
            verified = readBack != null && readBack.toXml() == configXml
        }
        Shell.appendSysLog("push config saved=$verified")
        PushResult(verified = verified, configXml = configXml)
    }

    /**
     * 确认保存：落到暂存区并推送系统（旧 `commitSave` + `saveToSystem`）。
     * 成功后返回后续动作（询问清数据 / 询问重启 / 直接完成），由界面呈现。
     */
    suspend fun commit(plan: SavePlan.Confirm): CommitResult {
        val push = pushConfig(plan.pending)
        if (!push.verified) {
            return CommitResult(savedXml = null, followUp = FollowUp.Failed(Messages.pushFail))
        }
        val followUp = when {
            plan.thenClearData -> FollowUp.AskClearData(plan.restartBt, plan.restartSystem)
            plan.restartBt || plan.restartSystem -> FollowUp.AskRestart(plan.restartBt, plan.restartSystem)
            else -> FollowUp.Done
        }
        return CommitResult(savedXml = push.configXml, followUp = followUp)
    }

    /**
     * 清除此应用写入的系统侧数据：配置（Settings.Global 键 / 镜像 / boot 脚本 / 被改写的
     * ro 属性）与模块日志。返回新的「已生效基线」XML（= 当前模型，此后不再提示未保存修改）。
     *
     * 刻意**保留**本地 SharedPreferences：其中的原生档位数等属设备环境事实，且清除后框架
     * 未重启时 `getStreamMaxVolume` 仍返回刚退下的旧覆盖值，一并抹掉会导致重启前把它重新误固化。
     */
    suspend fun clearAppData(model: VolumeConfig): String {
        val baseline = model.toXml()
        withContext(Dispatchers.IO) {
            Shell.clearWrittenConfig()
            Shell.clearModuleLogs()
        }
        return baseline
    }

    /** 按需重启蓝牙与/或系统框架（不重复写配置，配置已在保存时推送）。 */
    suspend fun restart(restartBt: Boolean, restartSystem: Boolean): RestartOutcome =
        withContext(Dispatchers.IO) {
            var bluetoothFailed = false
            if (restartBt) {
                bluetoothFailed = !Shell.restartBluetooth().isSuccess
            }
            if (restartSystem) {
                Shell.restartSystemServer()
            }
            RestartOutcome(bluetoothFailed = bluetoothFailed)
        }

    /**
     * 保存配置并重启蓝牙（模式 / 音量范围修改后的生效方式，不重启系统框架）。
     * 推送失败时不执行重启（避免用旧配置重连）。
     */
    suspend fun saveAndRestartBluetooth(model: VolumeConfig): SaveAndRestart =
        saveThenRestart(model, restartBt = true, restartSystem = false)

    /** 保存配置并软重启系统框架（媒体档位数修改后的生效方式）。 */
    suspend fun saveAndRestartSystemServer(model: VolumeConfig): SaveAndRestart =
        saveThenRestart(model, restartBt = false, restartSystem = true)

    private suspend fun saveThenRestart(
        model: VolumeConfig, restartBt: Boolean, restartSystem: Boolean,
    ): SaveAndRestart {
        val push = pushConfig(model)
        if (!push.verified) {
            return SaveAndRestart(pushFailed = true, savedXml = "", bluetoothFailed = false)
        }
        val outcome = restart(restartBt, restartSystem)
        return SaveAndRestart(pushFailed = false, savedXml = push.configXml, bluetoothFailed = outcome.bluetoothFailed)
    }

    // ==================== 文案辅助 ====================

    /** 门控警告里的模式名：优先取当前编辑设备的非默认模式，否则取首个非默认设备模式。 */
    private fun warnModeId(cfg: VolumeConfig, editingDevice: OutputDevice): Int {
        val current = cfg.modeFor(editingDevice)
        if (current != Prefs.BT_MODE_DEFAULT) {
            return current
        }
        for (device in OutputDevice.entries) {
            val mode = cfg.modeFor(device)
            if (mode != Prefs.BT_MODE_DEFAULT) {
                return mode
            }
        }
        return current
    }

    /** 保存流程的动态文案（旧 `strings.xml` 中的对话框/Toast 内容，逐条对应）。 */
    object Messages {

        const val saveNeedSystemDefaultTitle = "无法可靠调整档位"
        const val saveTitle = "保存修改"
        const val saveNone = "没有需要保存的修改。"
        const val saveFirst = "首次保存，将写入以下配置并使其生效："
        const val saveConfirm = "保存"
        const val cancel = "取消"
        const val resolveEnable = "启用档位修改"
        const val resolveDefaultMode = "使用默认模式"
        const val resolveDefaultSteps = "使用档位默认值"
        const val pushFail = "写入系统配置失败，请确认 su 已授权"
        const val autoSaveFail = "自动保存失败：无法写入系统配置，请确认 su 已授权"
        const val saveDone = "已保存，无需重启即可生效。"
        const val saveDoneTitle = "保存成功"
        const val restartNow = "立即重启"
        const val restartLater = "稍后手动"
        const val restarting = "正在重启…"
        const val btRestarting = "正在重启蓝牙"
        const val btRestartFail = "蓝牙重启命令执行失败（svc / cmd 均不可用）"
        const val sysRestarting = "正在重启系统框架…屏幕将短暂黑屏，本应用会被关闭"
        const val clearDataTitle = "清除数据"
        const val clearDataMsg = "已恢复系统默认。是否同时清除此应用写入的所有数据（配置与日志）？"
        const val clearDataConfirm = "清除"
        const val dataCleared = "已清除应用配置与日志。"
        const val restartSystemTitle = "重启系统框架"
        const val restartSystemMsg = "将重启系统框架 (类似重启)，使档位生效。"
        const val restartSystemConfirm = "继续"
        const val pendingUnsaved = "● 有未保存的修改，点击「保存修改」后生效。"

        /** 门控警告正文：嵌入当前非默认模式名。 */
        fun saveNeedSystemDefault(modeShort: String): String =
            "⚠ 当前「启用档位修改」已关闭且处于${modeShort}模式（非默认），" +
                "但未获取到系统默认音量档位，无法可靠调整档位。请选择处理方式："

        /** 重启询问正文中「重启什么」的成分名。 */
        fun restartWhat(restartBt: Boolean, restartSystem: Boolean): String = when {
            restartBt && restartSystem -> "蓝牙与系统框架"
            restartBt -> "蓝牙"
            else -> "系统框架"
        }

        fun restartNowMsg(restartBt: Boolean, restartSystem: Boolean): String =
            "改动已保存。需重启${restartWhat(restartBt, restartSystem)}才能生效，是否立即重启？"
    }
}

/** 门控警告的三个处理入口。 */
enum class BlockedChoice { ENABLE_STEPS, DEFAULT_MODE, DEFAULT_STEPS }

/** 一次保存流程的决策结果：界面据此决定弹哪个对话框（或直接提示）。 */
sealed interface SavePlan {

    /** 与已生效配置完全一致，无需保存。 */
    data class Nothing(val message: String) : SavePlan

    /** 无法可靠调整档位的门控警告：[steps]/[keySteps] 为「使用档位默认值」选项要写入的值。 */
    data class Blocked(
        val pending: VolumeConfig,
        val message: String,
        val steps: Int,
        val keySteps: Int,
        /** 是否读到可信的系统真实档位数（决定要不要固化 `KEY_SYSTEM_DEFAULT_STEPS`）。 */
        val trustedRealSteps: Boolean,
    ) : SavePlan

    /** 常规确认框：列出改动，确认后才推送系统。 */
    data class Confirm(
        val pending: VolumeConfig,
        val message: String,
        val restartBt: Boolean,
        val restartSystem: Boolean,
        /** true=「恢复系统默认」路径，保存成功后追加询问是否清除数据。 */
        val thenClearData: Boolean,
    ) : SavePlan
}

/** [SaveEngine.pushConfig] 的结果：[configXml] 无论成功与否都是本轮比对基线。 */
data class PushResult(val verified: Boolean, val configXml: String)

/** [SaveEngine.commit] 成功后界面该做的下一步。 */
sealed interface FollowUp {

    /** 推送失败（su 未授权等）。 */
    data class Failed(val message: String) : FollowUp

    /** 保存成功，追加询问是否清除应用写入的数据。 */
    data class AskClearData(val restartBt: Boolean, val restartSystem: Boolean) : FollowUp

    /** 保存成功，询问是否立即重启对应组件使其生效。 */
    data class AskRestart(val restartBt: Boolean, val restartSystem: Boolean) : FollowUp

    /** 保存成功且无需重启任何组件。 */
    data object Done : FollowUp
}

data class CommitResult(val savedXml: String?, val followUp: FollowUp)

/** [SaveEngine.restart] 的结果。 */
data class RestartOutcome(val bluetoothFailed: Boolean)

/** 「保存并重启」组合动作的结果：推送失败时不执行重启。 */
data class SaveAndRestart(
    val pushFailed: Boolean,
    val savedXml: String,
    val bluetoothFailed: Boolean,
)

// ==================== 纯函数（供 JVM 单测直接断言） ====================

/** 列出待保存配置相对已生效配置的改动（可读条目）；[base] 为 null 时返回空列表。 */
internal fun describeChanges(base: VolumeConfig?, pending: VolumeConfig): List<String> {
    if (base == null) {
        return emptyList()
    }
    val out = mutableListOf<String>()
    if (base.enabled != pending.enabled) {
        out.add("启用档位修改：" + enabledLabel(base.enabled) + " → " + enabledLabel(pending.enabled))
    }
    if (base.mediaSteps != pending.mediaSteps) {
        out.add("档位：" + base.mediaSteps + " → " + pending.mediaSteps)
    }
    if (base.keySteps != pending.keySteps) {
        out.add("音量键步进：" + base.keySteps + " → " + pending.keySteps)
    }
    appendDeviceChanges(out, deviceTabLabel(OutputDevice.BT), base.bluetooth, pending.bluetooth)
    appendDeviceChanges(out, deviceTabLabel(OutputDevice.WIRED), base.wired, pending.wired)
    appendDeviceChanges(out, deviceTabLabel(OutputDevice.SPEAKER), base.speaker, pending.speaker)
    return out
}

/** 追加单设备的模式改动 + A/B/默认 三套范围改动条目（前缀设备名）。 */
internal fun appendDeviceChanges(out: MutableList<String>, name: String, from: DeviceConfig, to: DeviceConfig) {
    if (from.mode != to.mode) {
        out.add("$name 模式：${modeLabelShort(from.mode)} → ${modeLabelShort(to.mode)}")
    }
    addRangeChange(out, "$name·绝对音量", from.absolute, to.absolute)
    addRangeChange(out, "$name·相对音量", from.software, to.software)
    addRangeChange(out, "$name·默认", from.defaultRange, to.defaultRange)
}

private fun addRangeChange(out: MutableList<String>, name: String, from: Range?, to: Range?) {
    if (from == to) {
        return
    }
    out.add("$name：${rangeLabel(from)} → ${rangeLabel(to)}")
}

private fun rangeLabel(range: Range?): String =
    if (range == null) "未设置" else "${range.min}~${range.max}/${Avrcp.curveLabel(range.curve)}"

private fun enabledLabel(enabled: Boolean): String = if (enabled) "开启" else "关闭"

/** 对话框用的模式简名：绝对→「绝对音量」、相对→「相对音量」、默认→「默认」。 */
internal fun modeLabelShort(mode: Int): String = when (mode) {
    Prefs.BT_MODE_SOFTWARE -> "相对音量"
    Prefs.BT_MODE_DEFAULT -> "默认"
    else -> "绝对音量"
}

/** 设备选项卡名：与界面标签一致（蓝牙 / 有线耳机 / 外放）。 */
internal fun deviceTabLabel(device: OutputDevice): String = when (device) {
    OutputDevice.BT -> "蓝牙"
    OutputDevice.WIRED -> "有线耳机"
    OutputDevice.SPEAKER -> "外放"
}
