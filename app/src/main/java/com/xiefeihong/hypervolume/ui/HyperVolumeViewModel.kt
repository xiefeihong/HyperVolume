package com.xiefeihong.hypervolume.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiefeihong.hypervolume.R
import com.xiefeihong.hypervolume.app.AppLanguage
import com.xiefeihong.hypervolume.app.BackupContent
import com.xiefeihong.hypervolume.app.BackupEngine
import com.xiefeihong.hypervolume.app.BlockedChoice
import com.xiefeihong.hypervolume.app.ConfigStore
import com.xiefeihong.hypervolume.app.DebugInfoCollector
import com.xiefeihong.hypervolume.app.DebugSnapshot
import com.xiefeihong.hypervolume.app.EditorState
import com.xiefeihong.hypervolume.app.FollowUp
import com.xiefeihong.hypervolume.app.NativeAxis
import com.xiefeihong.hypervolume.app.SaveAndRestart
import com.xiefeihong.hypervolume.app.SaveEngine
import com.xiefeihong.hypervolume.app.SavePlan
import com.xiefeihong.hypervolume.app.SettingsStore
import com.xiefeihong.hypervolume.app.StatusInput
import com.xiefeihong.hypervolume.app.StatusProbe
import com.xiefeihong.hypervolume.app.StatusSnapshot
import com.xiefeihong.hypervolume.app.ThemeMode
import com.xiefeihong.hypervolume.app.UpdateChecker
import com.xiefeihong.hypervolume.core.AppText
import com.xiefeihong.hypervolume.core.NativeVolumeCurve
import com.xiefeihong.hypervolume.core.OutputDevice
import com.xiefeihong.hypervolume.core.Shell
import com.xiefeihong.hypervolume.core.VolumeConfig
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
import java.io.IOException

/**
 * 界面状态容器（替代旧 `MainActivity` 这个「控件宿主」）。
 *
 * 旧实现把编辑态散落在 Activity 字段 + 控件当前值上，靠 `suppressListeners` 防回写；
 * 这里收敛成单一 [UiState]：交互 = 纯函数换快照（[EditorState] 自身不可变），
 * root / 系统查询 = `suspend` 动作切到 IO，瞬时提示走 [notices]（等价旧 `Toast`）。
 *
 * 冷启动流程与旧 `onCreate` 逐条对应：按当前音频输出定位设备标签、直接装载系统里的
 * 生效配置（全应用唯一一份）并把它作为「已生效基线」（不虚报未保存修改）、
 * 后台读取 ROM 真实曲线、`onResume` 只做状态刷新。
 */
class HyperVolumeViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ConfigStore(application)
    private val probe = StatusProbe(application, store)
    private val engine = SaveEngine(store, probe)
    private val settings = SettingsStore(application)
    private val backup = BackupEngine(application, settings)
    private val debugCollector = DebugInfoCollector(application, store, probe)

    /** 构造参数只在属性初始化器内可见，SAF 读写与版本查询等成员函数需自持一份上下文。 */
    private val appContext: Application = application

    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState> get() = _state.asStateFlow()

    /** 一次性提示（旧 `Toast.makeText`），由界面收集后弹出。 */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    init {
        // 冷启动：按当前输出设备定位标签，界面直接装载系统里的生效配置（全应用唯一一份，
        // ContentResolver 可读、无需 root）；基线取同一份装载结果（系统侧还没有配置时即回落默认值），
        // 避免首次打开就提示「有未保存的修改」。
        // 这里刻意同步读：一次 SettingsProvider 查询比旧版整档 SharedPreferences 加载更轻，
        // 而改成异步装载会让首帧闪一遍默认值，并在窗口期丢掉用户刚做的编辑。
        val systemDefault = probe.systemDefaultSteps()
        val cold = EditorState.load(
            effective = store.readEffective(),
            defaultConfig = store.defaultConfig(systemDefault),
            systemDefaultSteps = systemDefault,
            detectedDevice = probe.detectCurrentDevice(),
        )
        _state = MutableStateFlow(
            UiState(
                editor = cold.markSaved(cold.model),
                status = null,
                language = settings.language,
                themeMode = settings.themeMode,
            ),
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

    /**
     * 设置-通用「启用模块」总闸：只改模型（同主页开关一样需「保存修改」才下发）。
     * 关闭后 Hook 端全部点位直接放行，已编辑的参数一律保留。
     */
    fun onToggleModule(value: Boolean) = edit { it.copy(model = it.model.withModuleEnabled(value)) }

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
     * 统一编辑入口：只换内存快照（不再落盘任何暂存副本）。
     * 预览由界面按新快照重算，无需在此触发。未保存的编辑只存在于内存，
     * 重进应用回到生效值，需点「保存修改」才写入系统。
     */
    private fun edit(transform: (EditorState) -> EditorState) {
        _state.update { it.copy(editor = transform(it.editor)) }
    }

    // ==================== 状态刷新 ====================

    /** 一次运行态探测（root / LSPosed / 当前档位 / 蓝牙）。 */
    fun refreshStatus() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val e = _state.value.editor
                probe.probe(
                    StatusInput(
                        // 生效口径取模块总闸与档位修改开关的合取：任一个关闭都不应把用户档位数当目标值。
                        enabled = e.enabled && e.model.moduleEnabled,
                        mediaSteps = e.mediaSteps,
                        keySteps = e.keySteps,
                        bluetoothMode = e.model.modeFor(e.editingDevice),
                    ),
                )
            }
            val editor = _state.value.editor.copy(systemDefaultSteps = snapshot.systemDefaultSteps)
            _state.update { it.copy(status = snapshot, editor = editor) }
        }
    }

    // ==================== 保存 / 重启 ====================

    /** 点「保存修改」。 */
    fun onSave() = presentPlan(engine.planSave(_state.value.editor))

    /**
     * 「设置 → 备份与恢复 → 清除数据」入口：弹确认框，确认后才执行清除。
     * 清除后系统侧仍持着刚退下的旧档位数，因此成功后沿用重启询问（蓝牙 + 系统框架）。
     */
    fun onAskClearData() = setDialog(DialogSpec.ClearData(restartBt = true, restartSystem = true))

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
            val saved = result.savedConfig
            if (saved != null) markSaved(saved)
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

    /** 「设置 → 备份与恢复 → 清除数据」确认后执行清除应用写入的系统侧数据。 */
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
        markSaved(result.savedConfig)
        if (result.bluetoothFailed) notify(SaveEngine.Messages.btRestartFail)
        refreshStatus()
    }

    // ==================== 模块日志 / 调试信息（全屏子页） ====================

    /** 进入全屏子页；日志与调试页首次进入时懒加载一次。 */
    fun openRoute(route: AppRoute) {
        _state.update { it.copy(route = route) }
        when (route) {
            AppRoute.LOGS -> if (_state.value.logs == null) reloadLogs()
            AppRoute.DEBUG -> if (_state.value.debug == null) reloadDebug()
            else -> Unit
        }
    }

    /** 退出子页（顶栏返回 / 系统返回键）。 */
    fun closeRoute() = _state.update { it.copy(route = AppRoute.NONE) }

    /** 读取模块日志（root 阻塞，切 IO）；空日志回落排查提示文案。 */
    fun reloadLogs() {
        _state.update { it.copy(logLoading = true) }
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { probe.readModuleLogs() }
            _state.update {
                it.copy(logs = if (text.isEmpty()) StatusProbe.logEmpty() else text, logLoading = false)
            }
        }
    }

    /** 清理模块自写日志文件（日志页「清理」按钮），清完立即回读。 */
    fun clearLogs() {
        launchBusy {
            withContext(Dispatchers.IO) { probe.clearModuleLogs() }
            notify(AppText.str(R.string.logs_cleared))
            reloadLogs()
        }
    }

    /** 收集调试信息快照（故障排除页与关于页设备信息共用一份）。 */
    fun reloadDebug() {
        _state.update { it.copy(debugLoading = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { debugCollector.collect(Shell.isRootAvailable()) }
            }
            outcome.fold(
                onSuccess = { snapshot -> _state.update { it.copy(debug = snapshot, debugLoading = false) } },
                onFailure = { t ->
                    _state.update { it.copy(debugLoading = false) }
                    notify(AppText.str(R.string.action_failed, t.message ?: t.javaClass.simpleName))
                },
            )
        }
    }

    // ==================== 通用设置（语言 / 颜色） ====================

    /** 选择界面语言；返回是否真的变更（变更才需重建 Activity 以切换资源）。 */
    fun onLanguage(index: Int): Boolean {
        val next = AppLanguage.entries.getOrNull(index) ?: return false
        if (next == _state.value.language) return false
        settings.language = next
        _state.update { it.copy(language = next) }
        return true
    }

    /** 选择颜色模式（Miuix 主题可即时切换，无需重建界面）。 */
    fun onThemeMode(index: Int) {
        val next = ThemeMode.entries.getOrNull(index) ?: return
        settings.themeMode = next
        _state.update { it.copy(themeMode = next) }
    }

    // ==================== 备份与恢复 ====================

    /** 导出：全应用唯一的一份生效配置 + 本地偏好（环境事实/界面风格）编成 JSON，写入 SAF 选定的 [uri]。 */
    fun exportBackup(uri: Uri) {
        launchBusy {
            val effective = withContext(Dispatchers.IO) { store.readEffectiveJson() }
            val records = settings.allPreferences().size
            val json = backup.export(effective)
            val written = runCatching { withContext(Dispatchers.IO) { writeText(uri, json) } }
            written.fold(
                onSuccess = { notify(AppText.str(R.string.backup_export_ok, records)) },
                onFailure = { t ->
                    notify(AppText.str(R.string.backup_export_fail, t.message ?: t.javaClass.simpleName))
                },
            )
        }
    }

    /**
     * 导入：解析存档 → 把备份时的唯一一份配置推送到系统（需 root，失败即中止）→ 写回本地偏好 → 重载界面。
     *
     * 存档非法只提示「不是有效的备份文件」，不改动任何现有配置。
     */
    fun importBackup(uri: Uri) {
        launchBusy {
            // 读取与解析合入一个 runCatching：抛 IllegalArgumentException = 存档格式不合
            val outcome = runCatching {
                val text = withContext(Dispatchers.IO) { readText(uri) }
                backup.parse(text)
            }
            outcome.fold(
                onSuccess = { content -> applyBackup(content) },
                onFailure = { t ->
                    notify(
                        if (t is IllegalArgumentException) {
                            AppText.str(R.string.backup_import_fail)
                        } else {
                            AppText.str(R.string.backup_import_error, t.message ?: t.javaClass.simpleName)
                        },
                    )
                },
            )
        }
    }

    /**
     * 导入后半段：配置只有唯一的一份、且只能落在系统里，因此**需 root**。
     *
     * 无 root 时整份中止并提示，不留「偏好已恢复、配置却没处可放」的半套状态；
     * 有 root 时先推送配置（含镜像/开机脚本），推送失败同样中止不动偏好，成功才写回本地偏好并重载界面。
     */
    private suspend fun applyBackup(content: BackupContent) {
        val restored = VolumeConfig.fromJson(content.effectiveJson)
        var pushed: VolumeConfig? = null
        if (restored != null) {
            if (!withContext(Dispatchers.IO) { Shell.isRootAvailable() }) {
                notify(AppText.str(R.string.backup_import_root))
                return
            }
            val push = engine.pushConfig(restored)
            if (!push.verified) {
                notify(SaveEngine.Messages.pushFail)
                return
            }
            pushed = push.savedConfig
        }
        backup.applyPreferences(content)
        reloadAfterRestore(pushed)
        notify(AppText.str(R.string.backup_import_ok, content.recordCount))
        if (pushed != null) notify(AppText.str(R.string.backup_imported_restart))
    }

    /**
     * 导入后重载界面：模型取系统里刚生效的那一份，基线取回写成功的配置模型；
     * [baseline] 为 null（存档里没有配置）时基线回落为装载结果，不误报未保存修改。
     */
    private suspend fun reloadAfterRestore(baseline: VolumeConfig?) {
        val systemDefault = withContext(Dispatchers.IO) { probe.systemDefaultSteps() }
        val editor = EditorState.load(
            effective = withContext(Dispatchers.IO) { store.readEffective() },
            defaultConfig = store.defaultConfig(systemDefault),
            systemDefaultSteps = systemDefault,
            detectedDevice = null,
            keepDevice = _state.value.editor.editingDevice,
        )
        _state.update {
            it.copy(
                editor = editor.markSaved(baseline ?: editor.model),
                language = settings.language,
                themeMode = settings.themeMode,
            )
        }
        refreshStatus()
    }

    // ==================== 关于页：检查更新 ====================

    /**
     * 拉取 GitHub 最新 Release 并与当前 versionName 比对：
     * 有新版弹提示框（含更新说明与发布时间），网络失败仅提示（不弹框）。
     */
    fun checkUpdate() {
        launchBusy {
            notify(AppText.str(R.string.update_checking))
            val current = versionName()
            val outcome = withContext(Dispatchers.IO) { runCatching { UpdateChecker.latest() } }
            outcome.fold(
                onSuccess = { release ->
                    if (UpdateChecker.isNewer(release.tag, current)) {
                        setDialog(
                            DialogSpec.Notice(
                                title = AppText.str(R.string.update_found, release.tag, current),
                                text = buildString {
                                    if (release.notes.isNotBlank()) {
                                        append(AppText.str(R.string.update_notes, release.notes)).append("\n")
                                    }
                                    if (release.publishedAt.isNotBlank()) {
                                        append(AppText.str(R.string.update_time, release.publishedAt))
                                    }
                                },
                            ),
                        )
                    } else {
                        notify(AppText.str(R.string.update_latest, current))
                    }
                },
                onFailure = { t ->
                    notify(
                        if (t is IOException) {
                            AppText.str(R.string.update_network_fail)
                        } else {
                            AppText.str(R.string.update_failed, t.message ?: t.javaClass.simpleName)
                        },
                    )
                },
            )
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

    private fun markSaved(config: VolumeConfig?) =
        _state.update { it.copy(editor = it.editor.markSaved(config)) }

    /** 串行执行 root 动作并置忙碌位（按钮在此期间禁用）。 */
    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (t: Throwable) {
                Log.w(StatusProbe.LOG_TAG, "action failed", t)
                notify(AppText.str(R.string.action_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** 当前模块 versionName（检查更新比对基准）。 */
    private fun versionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** SAF 文档读写（备份导出/导入）： URI 权限由系统回调自动授予，无需持久化。 */
    private fun writeText(uri: Uri, text: String) {
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入 $uri")
    }

    private fun readText(uri: Uri): String =
        appContext.contentResolver.openInputStream(uri)?.use {
            String(it.readBytes(), Charsets.UTF_8)
        } ?: throw IllegalStateException("无法读取 $uri")
}

/** 界面唯一状态源。 */
data class UiState(
    val editor: EditorState,
    val status: StatusSnapshot?,
    val busy: Boolean = false,
    val dialog: DialogSpec = DialogSpec.None,
    /** ROM 真实曲线载入完成后自增，驱动预览重绘（旧 `NativeVolumeCurve.load()` 回调）。 */
    val curveRevision: Int = 0,
    /** 当前全屏子页（日志 / 调试信息 / 许可 / 设备信息）；主页与设置/关于页由 pager 页位决定。 */
    val route: AppRoute = AppRoute.NONE,
    /** 界面语言（与 [SettingsStore] 同步；与 Activity 已生效值不同时界面重建）。 */
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 模块日志正文；null 表示尚未读取。 */
    val logs: String? = null,
    val logLoading: Boolean = false,
    /** 调试信息快照；null 表示尚未收集。 */
    val debug: DebugSnapshot? = null,
    val debugLoading: Boolean = false,
)

/**
 * 主页三页（主页 → 设置 → 关于）之外的全屏子页。
 *
 * 子页顶栏统一提供返回，因此只记录「当前在哪一页」，不做路由栈。
 */
enum class AppRoute {
    NONE,
    LOGS,
    DEBUG,
    LICENSES,
    DEVICE,
}

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

    /** 清除数据确认框：确定后清除本应用写入的系统配置与日志。 */
    data class ClearData(val restartBt: Boolean, val restartSystem: Boolean) : DialogSpec

    /** 手动重启系统框架的确认框。 */
    data object RestartSystemConfirm : DialogSpec

    /** 纯提示框（无改动可保存、检查更新结果等）。 */
    data class Notice(val title: String, val text: String) : DialogSpec
}
