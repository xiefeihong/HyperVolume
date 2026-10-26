package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.core.Avrcp
import com.xiefeihong.hypervolume.core.DeviceConfig
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.PushMarks
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
 * 动态文案在本层经 [com.xiefeihong.hypervolume.core.AppText] 按资源生成（与 [PreviewBuilder]、
 * [StatusProbe] 同一口径），因此随语言切换；单测注入解析 `values/strings.xml` 的取值器
 * 即可继续直接断言中文，不依赖 Android 运行时。
 */
class SaveEngine(private val store: ConfigStore, private val probe: StatusProbe) {

    // ==================== 计划：门控 + 改动比对 ====================

    /**
     * 生成保存计划（旧 `runSave`）。
     *
     * @param state 当前编辑态；其 `model` 即待保存配置（Compose 侧每次交互都已回写模型，
     *              等价旧实现进入保存前先调 `writeUiIntoModel(true)`）
     */
    fun planSave(state: EditorState): SavePlan {
        val pending = state.model
        val base = state.lastSaved
        val blocked = !pending.enabled && pending.anyDeviceNonDefault() &&
            store.capturedSystemDefaultSteps() == null && !store.systemDefaultAcknowledged
        return if (blocked) {
            blockedPlan(state, pending)
        } else {
            confirmPlan(state, pending, base)
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
        return confirmPlan(state, pending, state.lastSaved)
    }

    /** 常规确认计划（旧 `proceedSave`）：与已生效基线比对列出改动，并算出需重启哪些组件。 */
    private fun confirmPlan(
        state: EditorState, pending: VolumeConfig, base: VolumeConfig?,
    ): SavePlan {
        if (base != null && base == pending) {
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
        )
    }

    // ==================== 执行：写入系统 ====================

    /**
     * 把全应用**唯一**的一份配置一次 root 往返下发到三个落点（[Shell.pushAll]：
     * `Settings.Global` 主键 + 配置镜像 `printf '%s'`（无尾随换行）+ 条件写「停用绝对音量」），
     * 再按幂等判据决定是否重写开机脚本；回读后与待保存模型各自归一成格式化 JSON 再比
     * （见 [readBackMatches]，不参与序列化的字段与排版都不算差异）。
     *
     * 落点缺失由 [PushMarks] 判定：**只有主键写入失败（[PushMarks.GLOBAL] 缺失）才算 [PushResult.putFailed]**，
     * 界面提示不变；镜像 / 绝对音量开关缺失只记入模块日志并透出到 [PushResult.missingMarks]。
     *
     * 幂等跳过（性能主线，实测 su 固定开销≈0、主成本在命令本身）：
     *  - 绝对音量开关：App 进程用 [ConfigStore.readGlobalSetting]（无需 root）读现值，相同则不写该段；
     *  - 开机脚本：`(active, steps)` 与 [ConfigStore.bootScriptRecord] 一致则跳过重写。
     *
     * 回读刻意用独立的 root `settings get`（新进程无 SettingsProvider 缓存），不与 [Shell.pushAll] 合并。
     * 全程 root 阻塞，切到 [Dispatchers.IO] 执行。
     */
    suspend fun pushConfig(model: VolumeConfig): PushResult = withContext(Dispatchers.IO) {
        val perf = store.perfLogEnabled
        val t0 = if (perf) System.nanoTime() else 0L
        val pushJson = model.toJson()           // 全链路唯一形态：格式化串（Global / 镜像同源）
        val softwareMode = model.modeFor(OutputDevice.BT) == Prefs.BT_MODE_SOFTWARE
        val active = model.remapActive
        val avTarget = if (active && softwareMode) "1" else "0"
        val avValue = if (store.readGlobalSetting(Shell.SETTING_AV_DISABLE) == avTarget) null else avTarget
        val expected = if (avValue != null) PushMarks.LANDING + PushMarks.AV else PushMarks.LANDING
        val push = Shell.pushAll(Prefs.GLOBAL_KEY, pushJson, avValue)
        val missing = PushMarks.missing(push.output, expected)
        val putFailed = PushMarks.GLOBAL in missing
        val steps = Prefs.clampMediaSteps(model.mediaSteps)
        val record = store.bootScriptRecord()
        val bootChanged = bootScriptShouldRewrite(active, steps, record)
        if (!putFailed && bootChanged) {
            Shell.writeBootScript(active, steps)
            store.setBootScriptRecord(active, steps)
        }
        val verified = if (putFailed) {
            false
        } else {
            readBackMatches(Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output, model)
        }
        Shell.appendSysLog("push config saved=$verified putFailed=$putFailed missing=${missing.joinToString(",")}")
        if (perf) {
            val total = (System.nanoTime() - t0) / 1_000_000
            Shell.appendSysLog(
                "push perf total=${total}ms av=${if (avValue == null) "skip" else "write"}" +
                    " boot=${if (!putFailed && bootChanged) "write" else "skip"}",
            )
        }
        PushResult(
            verified = verified, putFailed = putFailed, savedConfig = model, missingMarks = missing,
        )
    }

    /**
     * 确认保存：把当前内存模型推送系统（旧 `commitSave` + `saveToSystem`）。
     * 成功后返回后续动作（询问重启 / 直接完成），由界面呈现。
     */
    suspend fun commit(plan: SavePlan.Confirm): CommitResult {
        val push = pushConfig(plan.pending)
        if (!push.verified) {
            return CommitResult(savedConfig = null, followUp = FollowUp.Failed(push.failMessage))
        }
        val followUp = when {
            plan.restartBt || plan.restartSystem -> FollowUp.AskRestart(plan.restartBt, plan.restartSystem)
            else -> FollowUp.Done
        }
        return CommitResult(savedConfig = push.savedConfig, followUp = followUp)
    }

    /**
     * 清除此应用写入的系统侧数据：配置（Settings.Global 键 / 镜像 / boot 脚本 / 被改写的
     * ro 属性）与模块日志。返回新的「已生效基线」模型（= 当前模型，此后不再提示未保存修改）。
     *
     * 由「设置 → 备份与恢复 → 清除数据」直接调用（不再附属于某个保存流程）。
     * 刻意**保留**本地 SharedPreferences：其中的原生档位数等属设备环境事实，且清除后框架
     * 未重启时 `getStreamMaxVolume` 仍返回刚退下的旧覆盖值，一并抹掉会导致重启前把它重新误固化。
     */
    suspend fun clearAppData(model: VolumeConfig): VolumeConfig {
        withContext(Dispatchers.IO) {
            Shell.clearWrittenConfig()
            Shell.clearModuleLogs()
            // 系统侧落点已抹掉：一并移除开机脚本幂等基线，否则清完改同样参数保存会漏写脚本。
            store.clearBootScriptRecord()
        }
        return model
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
            return SaveAndRestart(
                pushFailed = true, putFailed = push.putFailed, savedConfig = null, bluetoothFailed = false,
            )
        }
        val outcome = restart(restartBt, restartSystem)
        return SaveAndRestart(
            pushFailed = false, putFailed = false, savedConfig = push.savedConfig,
            bluetoothFailed = outcome.bluetoothFailed,
        )
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

        val saveNeedSystemDefaultTitle: String get() = AppText.str(R.string.save_need_default_title)
        val saveTitle: String get() = AppText.str(R.string.save_title)
        val saveNone: String get() = AppText.str(R.string.save_none)
        val saveFirst: String get() = AppText.str(R.string.save_first)
        val saveConfirm: String get() = AppText.str(R.string.save_confirm)
        val cancel: String get() = AppText.str(R.string.cancel)
        val resolveEnable: String get() = AppText.str(R.string.resolve_enable)
        val resolveDefaultMode: String get() = AppText.str(R.string.resolve_default_mode)
        val resolveDefaultSteps: String get() = AppText.str(R.string.resolve_default_steps)
        val pushFail: String get() = AppText.str(R.string.push_fail)
        val pushVerifyFail: String get() = AppText.str(R.string.push_verify_fail)
        val autoSaveFail: String get() = AppText.str(R.string.auto_save_fail)
        val saveDone: String get() = AppText.str(R.string.save_done)
        val saveDoneTitle: String get() = AppText.str(R.string.save_done_title)
        val restartNow: String get() = AppText.str(R.string.restart_now)
        val restartLater: String get() = AppText.str(R.string.restart_later)
        val restarting: String get() = AppText.str(R.string.restarting)
        val btRestarting: String get() = AppText.str(R.string.bt_restarting)
        val btRestartFail: String get() = AppText.str(R.string.bt_restart_fail)
        val sysRestarting: String get() = AppText.str(R.string.sys_restarting)
        val clearDataTitle: String get() = AppText.str(R.string.clear_data_title)
        val clearDataMsg: String get() = AppText.str(R.string.clear_data_msg)
        val clearDataConfirm: String get() = AppText.str(R.string.clear_data_confirm)
        val dataCleared: String get() = AppText.str(R.string.data_cleared)
        val restartSystemTitle: String get() = AppText.str(R.string.restart_system_title)
        val restartSystemMsg: String get() = AppText.str(R.string.restart_system_msg)
        val restartSystemConfirm: String get() = AppText.str(R.string.restart_system_confirm)
        val pendingUnsaved: String get() = AppText.str(R.string.pending_unsaved)

        /** 门控警告正文：嵌入当前非默认模式名。 */
        fun saveNeedSystemDefault(modeShort: String): String =
            AppText.str(R.string.save_need_default_body, modeShort)

        /** 重启询问正文中「重启什么」的成分名。 */
        fun restartWhat(restartBt: Boolean, restartSystem: Boolean): String = AppText.str(
            when {
                restartBt && restartSystem -> R.string.restart_what_both
                restartBt -> R.string.restart_what_bt
                else -> R.string.restart_what_sys
            },
        )

        fun restartNowMsg(restartBt: Boolean, restartSystem: Boolean): String =
            AppText.str(R.string.restart_now_msg, restartWhat(restartBt, restartSystem))
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
    ) : SavePlan
}

/**
 * [SaveEngine.pushConfig] 的结果：[savedConfig] 无论成功与否都是本轮的比对基线模型。
 *
 * [putFailed] 用来分流失败原因：true = 主键写入没成功（su 未授权 / 不可用）；
 * false 而 [verified] 为 false = 写入命令成功、但系统回读的内容与待保存配置不一致。
 * [missingMarks] 是本轮 [PushMarks] 未命中的落点标记（镜像 / 绝对音量开关等），
 * 已记入模块日志，供排障时定位是哪个落点没写到。
 */
data class PushResult(
    val verified: Boolean,
    val putFailed: Boolean,
    val savedConfig: VolumeConfig,
    val missingMarks: List<String> = emptyList(),
) {

    /** 未生效时给用户的提示：两种原因措辞不同，不把校验不一致也推给 su。 */
    val failMessage: String
        get() = if (putFailed) SaveEngine.Messages.pushFail else SaveEngine.Messages.pushVerifyFail
}

/** [SaveEngine.commit] 成功后界面该做的下一步。 */
sealed interface FollowUp {

    /** 推送失败（su 未授权等）。 */
    data class Failed(val message: String) : FollowUp

    /** 保存成功，询问是否立即重启对应组件使其生效。 */
    data class AskRestart(val restartBt: Boolean, val restartSystem: Boolean) : FollowUp

    /** 保存成功且无需重启任何组件。 */
    data object Done : FollowUp
}

data class CommitResult(val savedConfig: VolumeConfig?, val followUp: FollowUp)

/** [SaveEngine.restart] 的结果。 */
data class RestartOutcome(val bluetoothFailed: Boolean)

/** 「保存并重启」组合动作的结果：推送失败时不执行重启。 */
data class SaveAndRestart(
    val pushFailed: Boolean,
    val putFailed: Boolean,
    val savedConfig: VolumeConfig?,
    val bluetoothFailed: Boolean,
)

// ==================== 纯函数（供 JVM 单测直接断言） ====================

/**
 * 写入后的回读校验：把 `settings get` 读回的原文与待保存模型**各自归一成唯一的格式化 JSON 再比**，
 * 判据就是「系统里真正落盘的内容」是否等于「打算落盘的内容」。
 *
 * 刻意不比原文串——历史存量的紧凑串、用户手改的单行串都属于同一配置，排版差异不该判成写入失败；
 * 也刻意不比模型对象——[VolumeConfig.equals] 覆盖模式A 范围，而它只对蓝牙持久化，外放 / 有线的
 * `absolute` 根本不进 JSON，用默认回落值构造的模型回读后必然不相等（表现为写入成功却提示失败）。
 * 两侧都经 [VolumeConfig.toJson] 归一，故排版与字段顺序都不参与差异。
 */
internal fun readBackMatches(readBackRaw: String, pending: VolumeConfig): Boolean =
    VolumeConfig.fromJson(readBackRaw)?.toJson() == pending.toJson()

/**
 * 开机脚本幂等判据（纯函数）：仅当本轮 `(active, steps)` 与上次烘进脚本的 [BootScriptRecord]
 * 不同才需重写。**未记录**（`null`，旧存档 / 从未写过 / 刚清除数据）一律保守地必写。
 */
internal fun bootScriptShouldRewrite(active: Boolean, steps: Int, recorded: BootScriptRecord?): Boolean =
    recorded == null || recorded.active != active || recorded.steps != steps

/** 列出待保存配置相对已生效配置的改动（可读条目）；[base] 为 null 时返回空列表。 */
internal fun describeChanges(base: VolumeConfig?, pending: VolumeConfig): List<String> {
    if (base == null) {
        return emptyList()
    }
    val out = mutableListOf<String>()
    if (base.moduleEnabled != pending.moduleEnabled) {
        out.add(AppText.str(R.string.change_module, enabledLabel(base.moduleEnabled), enabledLabel(pending.moduleEnabled)))
    }
    if (base.enabled != pending.enabled) {
        out.add(AppText.str(R.string.change_enabled, enabledLabel(base.enabled), enabledLabel(pending.enabled)))
    }
    if (base.mediaSteps != pending.mediaSteps) {
        out.add(AppText.str(R.string.change_steps, base.mediaSteps, pending.mediaSteps))
    }
    if (base.keySteps != pending.keySteps) {
        out.add(AppText.str(R.string.change_key_steps, base.keySteps, pending.keySteps))
    }
    appendDeviceChanges(out, deviceTabLabel(OutputDevice.BT), base.bluetooth, pending.bluetooth)
    appendDeviceChanges(out, deviceTabLabel(OutputDevice.WIRED), base.wired, pending.wired)
    appendDeviceChanges(out, deviceTabLabel(OutputDevice.SPEAKER), base.speaker, pending.speaker)
    return out
}

/** 追加单设备的模式改动 + A/B/默认 三套范围改动条目（前缀设备名）。 */
internal fun appendDeviceChanges(out: MutableList<String>, name: String, from: DeviceConfig, to: DeviceConfig) {
    if (from.mode != to.mode) {
        out.add(AppText.str(R.string.change_mode, name, modeLabelShort(from.mode), modeLabelShort(to.mode)))
    }
    addRangeChange(out, AppText.str(R.string.range_suffix_absolute, name), from.absolute, to.absolute)
    addRangeChange(out, AppText.str(R.string.range_suffix_software, name), from.software, to.software)
    addRangeChange(out, AppText.str(R.string.range_suffix_default, name), from.defaultRange, to.defaultRange)
}

private fun addRangeChange(out: MutableList<String>, name: String, from: Range?, to: Range?) {
    if (from == to) {
        return
    }
    out.add(AppText.str(R.string.change_range, name, rangeLabel(from), rangeLabel(to)))
}

private fun rangeLabel(range: Range?): String =
    if (range == null) {
        AppText.str(R.string.range_unset)
    } else {
        AppText.str(R.string.range_value, range.min, range.max, Avrcp.curveLabel(range.curve))
    }

private fun enabledLabel(enabled: Boolean): String =
    AppText.str(if (enabled) R.string.enabled_on else R.string.enabled_off)

/** 对话框用的模式简名：绝对→「绝对音量」、相对→「相对音量」、默认→「默认」。 */
internal fun modeLabelShort(mode: Int): String = AppText.str(
    when (mode) {
        Prefs.BT_MODE_SOFTWARE -> R.string.mode_software
        Prefs.BT_MODE_DEFAULT -> R.string.mode_default
        else -> R.string.mode_absolute
    },
)

/** 设备选项卡名：与界面标签一致（蓝牙 / 有线耳机 / 外放）。 */
internal fun deviceTabLabel(device: OutputDevice): String = AppText.str(
    when (device) {
        OutputDevice.BT -> R.string.device_bt
        OutputDevice.WIRED -> R.string.device_wired
        OutputDevice.SPEAKER -> R.string.device_speaker
    },
)
