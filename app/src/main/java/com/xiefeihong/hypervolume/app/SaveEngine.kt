package com.xiefeihong.hypervolume.app

import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.core.AppText
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
     * @param forceDefault true=「恢复系统默认」完整重置（关闭总开关、各设备默认直通）
     */
    fun planSave(state: EditorState, forceDefault: Boolean): SavePlan {
        val pending = if (forceDefault) store.defaultConfig(state.systemDefaultSteps) else state.model
        val base = VolumeConfig.fromJson(state.lastSavedJson)
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
        return confirmPlan(state, pending, VolumeConfig.fromJson(state.lastSavedJson), fromReset = false)
    }

    /** 常规确认计划（旧 `proceedSave`）：与已生效基线比对列出改动，并算出需重启哪些组件。 */
    private fun confirmPlan(
        state: EditorState, pending: VolumeConfig, base: VolumeConfig?, fromReset: Boolean,
    ): SavePlan {
        if (base != null && base.toJson() == pending.toJson()) {
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
     * 「停用绝对音量」开发者选项，并回读解析后与紧凑 JSON 比对验证（与排版无关）。
     *
     * 全程为 root 阻塞调用，切到 [Dispatchers.IO] 执行。
     */
    suspend fun pushConfig(model: VolumeConfig): PushResult = withContext(Dispatchers.IO) {
        store.persist(model)
        val configJson = model.toJson()          // 紧凑：暂存与差异比对基线
        val pushJson = model.toPrettyJson()      // 格式化：写入 Settings.Global 与镜像，便于查看
        val softwareMode = model.modeFor(OutputDevice.BT) == Prefs.BT_MODE_SOFTWARE
        val active = model.remapActive
        val putResult = Shell.putGlobalConfig(Prefs.GLOBAL_KEY, pushJson)
        Shell.writeGlobalMirror(pushJson)
        Shell.writeBootScript(active, model.mediaSteps)
        Shell.putGlobalConfig(Shell.SETTING_AV_DISABLE, if (active && softwareMode) "1" else "0")
        var verified = false
        if (putResult.isSuccess) {
            val readBack = VolumeConfig.fromJson(Shell.getGlobalConfig(Prefs.GLOBAL_KEY).output)
            verified = readBack != null && readBack.toJson() == configJson
        }
        Shell.appendSysLog("push config saved=$verified")
        PushResult(verified = verified, configJson = configJson)
    }

    /**
     * 确认保存：落到暂存区并推送系统（旧 `commitSave` + `saveToSystem`）。
     * 成功后返回后续动作（询问清数据 / 询问重启 / 直接完成），由界面呈现。
     */
    suspend fun commit(plan: SavePlan.Confirm): CommitResult {
        val push = pushConfig(plan.pending)
        if (!push.verified) {
            return CommitResult(savedJson = null, followUp = FollowUp.Failed(Messages.pushFail))
        }
        val followUp = when {
            plan.thenClearData -> FollowUp.AskClearData(plan.restartBt, plan.restartSystem)
            plan.restartBt || plan.restartSystem -> FollowUp.AskRestart(plan.restartBt, plan.restartSystem)
            else -> FollowUp.Done
        }
        return CommitResult(savedJson = push.configJson, followUp = followUp)
    }

    /**
     * 清除此应用写入的系统侧数据：配置（Settings.Global 键 / 镜像 / boot 脚本 / 被改写的
     * ro 属性）与模块日志。返回新的「已生效基线」JSON（= 当前模型，此后不再提示未保存修改）。
     *
     * 刻意**保留**本地 SharedPreferences：其中的原生档位数等属设备环境事实，且清除后框架
     * 未重启时 `getStreamMaxVolume` 仍返回刚退下的旧覆盖值，一并抹掉会导致重启前把它重新误固化。
     */
    suspend fun clearAppData(model: VolumeConfig): String {
        val baseline = model.toJson()
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
            return SaveAndRestart(pushFailed = true, savedJson = "", bluetoothFailed = false)
        }
        val outcome = restart(restartBt, restartSystem)
        return SaveAndRestart(pushFailed = false, savedJson = push.configJson, bluetoothFailed = outcome.bluetoothFailed)
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
        /** true=「恢复系统默认」路径，保存成功后追加询问是否清除数据。 */
        val thenClearData: Boolean,
    ) : SavePlan
}

/** [SaveEngine.pushConfig] 的结果：[configJson] 无论成功与否都是本轮比对基线。 */
data class PushResult(val verified: Boolean, val configJson: String)

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

data class CommitResult(val savedJson: String?, val followUp: FollowUp)

/** [SaveEngine.restart] 的结果。 */
data class RestartOutcome(val bluetoothFailed: Boolean)

/** 「保存并重启」组合动作的结果：推送失败时不执行重启。 */
data class SaveAndRestart(
    val pushFailed: Boolean,
    val savedJson: String,
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
