package com.xiefeihong.hypervolume.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiefeihong.hypervolume.app.BlockedChoice
import com.xiefeihong.hypervolume.app.ConfigStore
import com.xiefeihong.hypervolume.app.EditorState
import com.xiefeihong.hypervolume.app.FollowUp
import com.xiefeihong.hypervolume.app.NativeAxis
import com.xiefeihong.hypervolume.app.SaveAndRestart
import com.xiefeihong.hypervolume.app.SaveEngine
import com.xiefeihong.hypervolume.app.SavePlan
import com.xiefeihong.hypervolume.app.StatusInput
import com.xiefeihong.hypervolume.app.StatusProbe
import com.xiefeihong.hypervolume.app.StatusSnapshot
import com.xiefeihong.hypervolume.core.NativeVolumeCurve
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.VolumeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 界面状态容器（替代旧 `MainActivity` 这个「控件宿主」）。
 *
 * 旧实现把编辑态散落在 Activity 字段 + 控件当前值上，靠 `suppressListeners` 防回写；
 * 这里收敛成单一 [UiState]：交互 = 纯函数换快照（[EditorState] 自身不可变），
 * root / 系统查询 = `suspend` 动作切到 IO，瞬时提示走 [notices]（等价旧 `Toast`）。
 *
 * 冷启动流程与旧 `onCreate` 逐条对应：按当前音频输出定位设备标签、以暂存配置为
 * 「已生效基线」（不虚报未保存修改）、后台读取 ROM 真实曲线、`onResume` 只做状态刷新。
 */
class HyperVolumeViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ConfigStore(application)
    private val probe = StatusProbe(application, store)
    private val engine = SaveEngine(store, probe)

    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState> get() = _state.asStateFlow()

    /** 一次性提示（旧 `Toast.makeText`），由界面收集后弹出。 */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    init {
        // 冷启动：按当前输出设备定位标签；基线取暂存配置（等价旧 loadConfigIntoUi 末尾的
        // lastSavedXml = model.toXml()），避免首次打开就提示「有未保存的修改」。
        val cold = EditorState.load(
            store = store,
            systemDefaultSteps = probe.systemDefaultSteps(),
            detectedDevice = probe.detectCurrentDevice(),
        )
        _state = MutableStateFlow(
            UiState(editor = cold.markSaved(cold.model.toXml()), status = null),
        )
        refreshStatus()
        // 后台读取 ROM audio policy 媒体曲线（root），完成后让默认设备以真实曲线重绘。
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { NativeVolumeCurve.load() }.isSuccess }
            if (loaded) {
                _state.update { it.copy(curveRevision = it.curveRevision + 1) }
                refreshStatus()
            }
        }
    }

    // ==================== 编辑交互 ====================

    fun onToggleEnabled(value: Boolean) = edit { it.toggleEnabled(value) }

    fun onMediaSteps(value: Int) = edit { it.changeMediaSteps(value) }

    fun onKeySteps(value: Int) = edit { it.changeKeySteps(value) }

    /** 切换设备选项卡（仅载入切片，标签切换本身不算改动）。 */
    fun onSelectDeviceTab(index: Int) = edit {
        it.selectDevice(ConfigStore.DEVICE_BY_TAB_ORDER.getOrElse(index) { OutputDevice.SPEAKER })
    }

    /** 选择绝对/相对音量模式。 */
    fun onSelectMode(mode: VolumeMode) = edit { it.selectMode(mode) }

    /** 选择「默认（系统原生）」模式。 */
    fun onSelectDefault() = edit { it.selectDefault() }

    fun onRangeMin(value: Int) = edit { it.changeRangeMin(value) }

    fun onRangeMax(value: Int) = edit { it.changeRangeMax(value) }

    fun onCurve(curveType: Int) = edit { it.changeCurve(curveType) }

    /** 默认预览纵轴口径（Spinner 位置 → [ConfigStore.NATIVE_MODE_OPTIONS]）。 */
    fun onNativeModeIndex(index: Int) = edit {
        it.changeNativeMode(ConfigStore.NATIVE_MODE_OPTIONS.getOrElse(index) { NativeVolumeCurve.Mode.PERCEPTUAL })
    }

    /** 默认预览横轴口径（0=档位，1=百分比）。 */
    fun onNativeAxisIndex(index: Int) = edit {
        it.changeNativeAxis(if (index == 1) NativeAxis.PERCENT else NativeAxis.STEPS)
    }

    /**
     * 统一编辑入口：换快照 → 落暂存区（旧 `onConfigChanged` 的 `persistToPrefs`）。
     * 预览由界面按新快照重算，无需在此触发。
     */
    private fun edit(transform: (EditorState) -> EditorState) {
        val next = transform(_state.value.editor)
        _state.update { it.copy(editor = next) }
        store.persist(next.model)
    }

    // ==================== 状态刷新 ====================

    /** 一次运行态探测（root / LSPosed / 当前档位 / 蓝牙）；App 数据被清空后自动采纳系统配置。 */
    fun refreshStatus() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val e = _state.value.editor
                probe.probe(
                    StatusInput(
                        enabled = e.enabled,
                        mediaSteps = e.mediaSteps,
                        keySteps = e.keySteps,
                        bluetoothMode = e.model.modeFor(e.editingDevice),
                    ),
                )
            }
            var editor = _state.value.editor.copy(systemDefaultSteps = snapshot.systemDefaultSteps)
            if (snapshot.adopted != null) {
                // 采纳系统侧配置：重载界面（保留当前设备标签）并以采纳值为基线。
                val reloaded = EditorState.load(
                    store = store,
                    systemDefaultSteps = snapshot.systemDefaultSteps,
                    detectedDevice = null,
                    keepDevice = editor.editingDevice,
                )
                editor = reloaded.markSaved(reloaded.model.toXml())
                notify("已从系统读取现有配置并恢复界面")
            }
            _state.update { it.copy(status = snapshot, editor = editor) }
        }
    }

    // ==================== 保存 / 重启 ====================

    /** 点「保存修改」。 */
    fun onSave() = presentPlan(engine.planSave(_state.value.editor, forceDefault = false))

    /** 点「恢复系统默认（关闭模块）」：等价强制默认配置的保存流程。 */
    fun onResetToDefault() = presentPlan(engine.planSave(_state.value.editor, forceDefault = true))

    /** 门控警告里选定处理方式。 */
    fun onBlockedChoice(choice: BlockedChoice) {
        val blocked = (_state.value.dialog as? DialogSpec.Blocked)?.plan ?: return
        presentPlan(engine.resolve(_state.value.editor, blocked, choice))
    }

    /** 确认保存对话框：推送配置并按结果决定后续对话框。 */
    fun onConfirmSave() {
        val plan = (_state.value.dialog as? DialogSpec.SaveConfirm)?.plan ?: return
        setDialog(DialogSpec.None)
        launchBusy {
            val result = engine.commit(plan)
            val savedXml = result.savedXml
            if (savedXml != null) markSaved(savedXml)
            when (val follow = result.followUp) {
                is FollowUp.Failed -> notify(follow.message)
                FollowUp.Done -> {
                    refreshStatus()
                    notify(SaveEngine.Messages.saveDone)
                }

                is FollowUp.AskRestart -> {
                    refreshStatus()
                    setDialog(DialogSpec.Restart(follow.restartBt, follow.restartSystem))
                }

                is FollowUp.AskClearData -> {
                    refreshStatus()
                    setDialog(DialogSpec.ClearData(follow.restartBt, follow.restartSystem))
                }
            }
        }
    }

    /** 保存成功后点「立即重启」。 */
    fun onRestartNow() {
        val spec = _state.value.dialog as? DialogSpec.Restart ?: return
        setDialog(DialogSpec.None)
        launchBusy {
            notify(SaveEngine.Messages.restarting)
            val outcome = engine.restart(spec.restartBt, spec.restartSystem)
            if (outcome.bluetoothFailed) notify(SaveEngine.Messages.btRestartFail)
            if (!spec.restartSystem) refreshStatus()
        }
    }

    /** 「恢复默认」后确认清除应用写入的系统侧数据。 */
    fun onClearData() {
        val spec = _state.value.dialog as? DialogSpec.ClearData ?: return
        setDialog(DialogSpec.None)
        launchBusy {
            val baseline = engine.clearAppData(_state.value.editor.model)
            markSaved(baseline)
            notify(SaveEngine.Messages.dataCleared)
            if (spec.restartBt || spec.restartSystem) {
                setDialog(DialogSpec.Restart(spec.restartBt, spec.restartSystem))
            } else {
                refreshStatus()
            }
        }
    }

    /** 「重启蓝牙（模式/音量范围调整后）」：自动保存后重启蓝牙。 */
    fun onSaveAndRestartBluetooth() {
        setDialog(DialogSpec.None)
        launchBusy {
            notify(SaveEngine.Messages.btRestarting)
            val result = engine.saveAndRestartBluetooth(_state.value.editor.model)
            handleSaveAndRestart(result)
        }
    }

    /** 「重启系统框架（档位修改后）」确认后的执行入口。 */
    fun onSaveAndRestartSystem() {
        setDialog(DialogSpec.None)
        launchBusy {
            val result = engine.saveAndRestartSystemServer(_state.value.editor.model)
            handleSaveAndRestart(result)
        }
    }

    /** 打开「重启系统框架」确认框（档位修改后的生效入口）。 */
    fun onAskRestartSystem() = setDialog(DialogSpec.RestartSystemConfirm)

    private fun handleSaveAndRestart(result: SaveAndRestart) {
        if (result.pushFailed) {
            notify(SaveEngine.Messages.autoSaveFail)
            return
        }
        markSaved(result.savedXml)
        if (result.bluetoothFailed) notify(SaveEngine.Messages.btRestartFail)
        refreshStatus()
    }

    // ==================== 模块日志 ====================

    /** 读取并展示模块日志。 */
    fun showLogs() {
        launchBusy {
            val text = withContext(Dispatchers.IO) { probe.readModuleLogs() }
            setDialog(DialogSpec.Logs(if (text.isEmpty()) StatusProbe.LOG_EMPTY else text))
        }
    }

    /** 清理模块自写日志文件（日志框的「清理」按钮）。 */
    fun clearLogs() {
        launchBusy {
            withContext(Dispatchers.IO) { probe.clearModuleLogs() }
            setDialog(DialogSpec.None)
            notify("日志文件已清理")
        }
    }

    // ==================== 对话框 / 提示 ====================

    /** 把保存计划翻译成对应的弹框（无可保存项时降级为提示框）。 */
    private fun presentPlan(plan: SavePlan) {
        setDialog(
            when (plan) {
                is SavePlan.Nothing -> DialogSpec.Notice(SaveEngine.Messages.saveTitle, plan.message)
                is SavePlan.Blocked -> DialogSpec.Blocked(plan)
                is SavePlan.Confirm -> DialogSpec.SaveConfirm(plan)
            },
        )
    }

    fun setDialog(spec: DialogSpec) = _state.update { it.copy(dialog = spec) }

    fun dismissDialog() = setDialog(DialogSpec.None)

    /** 发出一条瞬时提示（界面用 Toast 呈现，等价旧 `Toast.makeText`）。 */
    fun notify(text: String) {
        _notices.tryEmit(text)
    }

    private fun markSaved(xml: String) =
        _state.update { it.copy(editor = it.editor.markSaved(xml)) }

    /** 串行执行 root 动作并置忙碌位（按钮在此期间禁用）。 */
    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (t: Throwable) {
                Log.w(StatusProbe.LOG_TAG, "action failed", t)
                notify("操作失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/** 界面唯一状态源。 */
data class UiState(
    val editor: EditorState,
    val status: StatusSnapshot?,
    val busy: Boolean = false,
    val dialog: DialogSpec = DialogSpec.None,
    /** ROM 真实曲线载入完成后自增，驱动预览重绘（旧 `NativeVolumeCurve.load()` 回调）。 */
    val curveRevision: Int = 0,
)

/** 当前应显示的弹框（内容由界面按类型渲染，动作回调统一走 ViewModel）。 */
sealed interface DialogSpec {

    /** 无弹框。 */
    data object None : DialogSpec

    /** 保存确认框：列出改动。 */
    data class SaveConfirm(val plan: SavePlan.Confirm) : DialogSpec

    /** 「无法可靠调整档位」门控警告：三个处理入口。 */
    data class Blocked(val plan: SavePlan.Blocked) : DialogSpec

    /** 保存成功后的重启询问。 */
    data class Restart(val restartBt: Boolean, val restartSystem: Boolean) : DialogSpec

    /** 「恢复系统默认」后的清除数据询问。 */
    data class ClearData(val restartBt: Boolean, val restartSystem: Boolean) : DialogSpec

    /** 手动重启系统框架的确认框。 */
    data object RestartSystemConfirm : DialogSpec

    /** 纯提示框（无改动可保存等）。 */
    data class Notice(val title: String, val text: String) : DialogSpec

    /** 模块日志框。 */
    data class Logs(val text: String) : DialogSpec
}
