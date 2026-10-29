package com.xiefeihong.hypervolume.hook

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookChain
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.xiefeihong.hypervolume.core.Prefs
import com.xiefeihong.hypervolume.core.SettingsGlobal
import com.xiefeihong.hypervolume.core.Shell
import com.xiefeihong.hypervolume.core.VolumeConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块共享基础层（YukiHookAPI，作用域：系统框架 + 蓝牙 + Android 系统）。
 *
 *
 * 集中提供：进程状态、分级日志（自写文件 + logcat + YLog 三通道）、反射工具
 * （字段读写沿用纯反射口径，与 ROM 隐藏字段兼容）、配置读取
 * （Settings.Global + 镜像文件双通道 + TTL 缓存 + ContentObserver 失效）、
 * 两个进程各自的 Context 解析。
 *
 * Hook 挂载本身交给 YukiHookAPI（见 [AudioHooks] / [BtHooks] 使用本文件末尾的
 * [hookAllNamed] / [hookAllConstructors]，目标成员由纯反射解析后交给 YukiHookAPI 面对
 * `Collection<Member>` 的 `interceptAll` 链式拦截器），本对象只保留与框架无关的基础设施。
 */
object XposedKit {

    /** 错误日志前缀：仅错误日志以此外发（LSPosed 日志页），可在日志页直接检索。 */
    const val LOG_PREFIX = "HyperVolume: "

    /** LSPosed 日志 / logcat 的 tag（与 [com.xiefeihong.hypervolume.core.Shell.LOG_MARKER] 一致）。 */
    const val LOG_TAG = "HyperVolume"

    /** 系统框架与蓝牙的 AudioService / 相关类路径。 */
    const val AUDIO_SERVICE_CLASS = "com.android.server.audio.AudioService"
    const val AUDIO_DEVICE_BROKER_CLASS = "com.android.server.audio.AudioDeviceBroker"
    const val VOLUME_STREAM_STATE_CLASS = "com.android.server.audio.AudioService\$VolumeStreamState"

    /** 蓝牙作用域包名。 */
    const val BT_PACKAGE = "com.android.bluetooth"

    /** 模块自写日志：系统框架侧文件（system_server 可写，root 可读）；绝对路径归一到 [Shell]（const 编译期内联，hook 进程无额外类加载）。 */
    private const val SYS_LOG_FILE = Shell.SYS_LOG_FILE

    /** 备用日志路径（当 /data/system/ 被 SELinux 阻止时使用）。 */
    private const val SYS_LOG_FILE_FALLBACK = Shell.SYS_LOG_FILE_FALLBACK

    /** 模块自写日志：蓝牙进程侧文件名（需拼 filesDir，故保留本地常量）。 */
    private const val BT_LOG_FILE_NAME = "hypervolume_bt.log"

    /**
     * 蓝牙侧最后的兜底路径（CE 存储绝对路径）。
     *
     * 不能指回 [Shell.BT_LOG_FILE]——那是 DE 主路径，兜底与主路径同一个文件等于没有兜底。
     */
    private const val BT_LOG_FILE_FALLBACK = Shell.BT_LOG_FILE_LEGACY

    /** 文件日志暂缓后的重试间隔：direct-boot 期 CE 存储未解锁导致写失败，解锁后应能自行恢复。 */
    private const val LOG_PATH_RETRY_MS = 60_000L

    /** 单个日志文件大小上限（超过后清空重写，避免无限增长）。 */
    private const val LOG_FILE_MAX_BYTES = 512L * 1024L

    /**
     * 配置镜像可接受的字节上限：配置已改为只有一种格式化串（缩进与换行使长度比旧紧凑串
     * 约增长一倍，实测约 1.1KB），故把上限从 4096 放宽到 8192，留出余量避免后续新增字段
     * 撑满上限后镜像兜底静默失效（超限一律当作读不到处理）。
     */
    private const val MIRROR_MAX_BYTES = 8192L

    // ==================== 进程状态 ====================

    /** 是否系统框架进程（决定日志文件位置与 Context 解析方式）。 */
    @Volatile
    private var sIsSystemProcess = false

    /** 蓝牙进程缓存的 Application Context（用于读取 Settings.Global）。 */
    @Volatile
    private var sBtContext: Context? = null

    /** system_server 缓存的 Context（AudioService 构造参数 / mContext）。 */
    @Volatile
    private var sSysContext: Context? = null

    /** 标记当前进程类型（各进程安装 Hook 时调用一次）。 */
    fun init(isSystemServer: Boolean) {
        sIsSystemProcess = isSystemServer
    }

    // ==================== 分级日志 ====================

    /** 已记录过的一次性日志键集合（同键只记录一次，避免高频路径刷屏）。 */
    private val sLoggedOnceKeys = ConcurrentHashMap.newKeySet<String>()

    /** 日志文件路径（一旦解析成功即固定）；写入连续失败 3 次后放弃文件日志。 */
    @Volatile
    private var sLogFilePath: String? = null

    private var sLogFileFailureCount = 0

    /** 暂缓文件日志的到期时刻（0 表示未进入暂缓）；到期后重新探测路径。 */
    @Volatile
    private var sLogRetryAtMs = 0L

    /**
     * 日志路径解析结果的一次性留证（最终落到哪个文件、跳过了哪些候选）。
     * 由 [writeLogFile] 在锁外输出：resolveLogPath 在 LOG_LOCK 内被调用，
     * 在那里直接打日志会重入写入流程。
     */
    @Volatile
    private var sLogPathNote: String? = null

    /** 全路径不可写时的失败摘要（供 [writeLogFile] 的错误提示带上真实原因）。 */
    @Volatile
    private var sLogPathFailures: String? = null

    /** 蓝牙日志候选的一次性留证（DE 上下文/ filesDir 的实际取值）。 */
    @Volatile
    private var sBtCandidateNote: String? = null

    private val LOG_LOCK = Any()

    private val LOG_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 普通日志（INFO）：写自写文件 + logcat + YLog。
     * 三通道全写，确保至少一个通道可见：
     *  - 文件日志可能被 SELinux 阻止
     *  - `Log.i()` 从 system_server 可能被 Android 15 日志策略过滤
     *  - `YLog.info()` 经 YukiHook 框架通道写入 LSPosed 日志文件，最可靠
     */
    fun log(message: String?) {
        writeLogFile("I", message)
        Log.i(LOG_TAG, LOG_PREFIX + message)
        YLog.info(message)
    }

    /** 错误日志（ERROR）：写自写文件，并以 E 级输出到 LSPosed 日志页与 logcat。 */
    fun logError(message: String?) {
        writeLogFile("E", message)
        Log.e(LOG_TAG, LOG_PREFIX + message)
        YLog.error(message)
    }

    /** 一次性日志（INFO）：同键只记录一次，避免高频路径刷屏。 */
    fun logOnce(key: String, message: String?) {
        if (sLoggedOnceKeys.add(key)) {
            log(message)
        }
    }

    /** 一次性日志（ERROR）：同键只记录一次。 */
    fun logErrorOnce(key: String, message: String?) {
        if (sLoggedOnceKeys.add(key)) {
            logError(message)
        }
    }

    /**
     * 追加写入当前进程的日志文件（带级别标记）；文件过大时清空重写。
     *
     * 写入连续失败 3 次不再永久放弃：暂缓 [LOG_PATH_RETRY_MS] 后重新探测路径。
     * 蓝牙进程在 direct-boot 阶段就装 hook 时 CE 存储尚未解锁，旧实现会因此丢掉整轮蓝牙日志。
     */
    private fun writeLogFile(level: String, message: String?) {
        // 降级提示需在锁外发出：logErrorOnce→log→writeLogFile 会重入同一 LOG_LOCK，
        // 虽同线程可重入不死锁，但会在持锁期间触发文件路径重复探测/写失败计数错乱。锁内只置标志。
        var giveUp = false
        var failures = 0
        var lastError: String? = null
        var noPath = false
        synchronized(LOG_LOCK) {
            val path = resolveLogPath()
            if (path == null) {
                noPath = true
            } else {
                try {
                    val file = File(path)
                    if (file.length() > LOG_FILE_MAX_BYTES) {
                        file.delete()
                    }
                    file.parentFile?.takeIf { !it.exists() }?.mkdirs()
                    val time = synchronized(LOG_TIME_FORMAT) { LOG_TIME_FORMAT.format(Date()) }
                    val bytes = ("[$level] $time $message\n").toByteArray(StandardCharsets.UTF_8)
                    FileOutputStream(file, true).use { it.write(bytes) }
                    sLogFileFailureCount = 0
                    sLogRetryAtMs = 0L
                } catch (t: Throwable) {
                    failures = ++sLogFileFailureCount
                    lastError = t.toString()
                    if (failures >= 3) {
                        // 连续失败（如 direct-boot 期 CE 未解锁）：暂缓文件日志、依赖 logcat 兜底
                        sLogFilePath = null
                        sLogFileFailureCount = 4
                        sLogRetryAtMs = System.currentTimeMillis() + LOG_PATH_RETRY_MS
                        giveUp = true
                    }
                }
            }
        }
        if (giveUp) {
            logErrorOnce(
                "logfile-give-up",
                "log file write failed $failures times ($lastError), retry in ${LOG_PATH_RETRY_MS / 1000}s; " +
                    "candidates=${logCandidates().joinToString(",")}",
            )
        } else if (lastError != null) {
            // 首次失败就把异常文本留证（旧实现只计数、不记 Throwable，只能靠推断）
            logOnce("logfile-write-failed", "log file write failed: $lastError")
        } else if (noPath) {
            val reason = sLogPathFailures?.let { "; $it" } ?: ""
            logErrorOnce(
                "logfile-no-path",
                "no writable log path, candidates=${logCandidates().joinToString(",")}$reason",
            )
        }
        // 路径一次性留证：蓝牙侧 DE 是否可用、最终写到哪个文件（上一次重启后仍落到 CE，
        // 而日志里没有任何关于 DE 的信息，只能靠推断）
        sBtCandidateNote?.let { note ->
            sBtCandidateNote = null
            logOnce("bt-log-candidates", note)
        }
        sLogPathNote?.let { note ->
            sLogPathNote = null
            logOnce("logfile-path", note)
        }
    }

    /** 解析当前进程的日志文件路径（系统框架：固定路径；蓝牙：DE → CE → 兜底路径）。 */
    private fun resolveLogPath(): String? {
        sLogFilePath?.let { return it }
        if (sLogFileFailureCount > 3) {
            // 暂缓期内直接返回 null，避免每条日志都重探路径；到期后清零重试
            if (System.currentTimeMillis() < sLogRetryAtMs) {
                return null
            }
            sLogFileFailureCount = 0
        }
        // 先尝试主路径，失败后用备用路径；两个都不可用则依赖 logcat 兜底
        val candidates = if (sIsSystemProcess) {
            listOf(SYS_LOG_FILE, SYS_LOG_FILE_FALLBACK)
        } else {
            btLogCandidates()
        }
        val choice = pickWritableLogPath(candidates)
        val path = choice.path
        if (path == null) {
            // 全不可写：记满计数并进入暂缓，到期再试
            sLogFileFailureCount = 4
            sLogRetryAtMs = System.currentTimeMillis() + LOG_PATH_RETRY_MS
            sLogPathFailures = choice.failures.joinToString("; ")
            return null
        }
        sLogFilePath = path
        sLogPathNote = "log file: $path" + if (choice.failures.isEmpty()) {
            ""
        } else {
            " (skipped ${choice.failures.size}: ${choice.failures.joinToString("; ")})"
        }
        return path
    }

    /**
     * 蓝牙进程的日志候选路径，按可写性优先级排列：
     * DE 存储（direct-boot 即可写）→ CE 存储（用户解锁后可写）→ 固定兜底路径。
     *
     * DE 主路径用**固定绝对路径**而非 Context 推导：模块加载早于 ActivityThread 绑定
     * Application（实测 01:00:00.591 装上 hook），此时 [bluetoothContext] 返回 null，
     * 旧实现直接退化成单条 CE 兜底，DE 候选连构造的机会都没有——这就是 1.1.6 一直写 CE 的根因。
     * 进程名固定为 com.android.bluetooth，DE 绝对路径是确定的。
     */
    private fun btLogCandidates(): List<String> {
        val note = StringBuilder()
        val context = bluetoothContext()
        val resolved = ArrayList<String>(2)
        if (context == null) {
            note.append("context=null")
        } else {
            val deDir = runCatching { context.createDeviceProtectedStorageContext().filesDir }
                .onFailure { note.append("deFilesDir=").append(it).append("; ") }
                .getOrNull()
            deDir?.let { resolved += File(it, BT_LOG_FILE_NAME).absolutePath }
            val ceDir = runCatching { context.filesDir }
                .onFailure { note.append("ceFilesDir=").append(it).append("; ") }
                .getOrNull()
            ceDir?.let { resolved += File(it, BT_LOG_FILE_NAME).absolutePath }
            note.append("deDir=").append(deDir).append("; ceDir=").append(ceDir)
        }
        val candidates = btLogCandidatePaths(resolved, Shell.BT_LOG_FILE, BT_LOG_FILE_FALLBACK)
        if (sBtCandidateNote == null) {
            sBtCandidateNote = "bt log candidates: ${candidates.joinToString(" | ")} [$note]"
        }
        return candidates
    }

    /** 当前进程的日志候选路径（仅用于失败提示，不参与探测）。 */
    private fun logCandidates(): List<String> =
        if (sIsSystemProcess) listOf(SYS_LOG_FILE, SYS_LOG_FILE_FALLBACK) else btLogCandidates()

    /** 依次探测候选日志路径的可写性，返回首个可写路径与各候选的失败摘要。 */
    internal fun pickWritableLogPath(candidates: List<String>): LogPathChoice {
        val failures = ArrayList<String>(candidates.size)
        for (candidate in candidates) {
            val file = File(candidate)
            try {
                file.parentFile?.takeIf { !it.exists() }?.mkdirs()
                // 打开即证明可创建可写，不写入任何字节：早先写 0 做探测，结果日志文件首字节多了个
                // NUL，复制日志时接收端把它当字符串结束符，整段内容显示为乱码。
                FileOutputStream(file, true).use { }
                return LogPathChoice(candidate, failures)
            } catch (t: Throwable) {
                // 该路径不可写：记下真实原因（旧实现静默 catch，蓝牙 DE 失败时无从判断）
                failures += "$candidate: $t"
            }
        }
        return LogPathChoice(null, failures)
    }

    // ==================== 反射工具（纯反射，兼容 ROM 隐藏字段） ====================

    /**
     * 字段解析缓存：热路径（如 setStreamVolumeIndex 每次读 mStreamType/this$0/mIndexMax 等）
     * 此前每次都沿类层次 `getDeclaredField` 遍历。key 带 `I:`/`S:` 前缀区分
     * 实例/静态口径，含未命中的 `Optional.empty()` 负缓存，避免对不存在的字段反复遍历。
     */
    private val sFieldCache = ConcurrentHashMap<String, Optional<Field>>()

    /** 沿类层次解析实例字段（任意可见性）；带缓存，未命中缓存 empty 并抛 [NoSuchFieldException]。 */
    @Throws(Exception::class)
    private fun resolveInstanceField(startClass: Class<*>, name: String): Field {
        val key = "I:" + startClass.name + "#" + name
        sFieldCache[key]?.let { return it.orElseThrow { NoSuchFieldException(name) } }
        var c: Class<*>? = startClass
        var found: Field? = null
        while (c != null && c != Any::class.java) {
            try {
                found = c.getDeclaredField(name).apply { isAccessible = true }
                break
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        sFieldCache[key] = Optional.ofNullable(found)
        return found ?: throw NoSuchFieldException(name)
    }

    /** 解析静态字段（带缓存）；未命中缓存 empty 并抛 [NoSuchFieldException]。 */
    @Throws(Exception::class)
    private fun resolveStaticField(clazz: Class<*>, name: String): Field {
        val key = "S:" + clazz.name + "#" + name
        sFieldCache[key]?.let { return it.orElseThrow { NoSuchFieldException(name) } }
        return try {
            clazz.getDeclaredField(name).apply {
                isAccessible = true
                sFieldCache[key] = Optional.of(this)
            }
        } catch (e: NoSuchFieldException) {
            sFieldCache[key] = Optional.empty()
            throw e
        }
    }

    /** 读实例字段（任意可见性）；失败抛出异常由调用方决定。 */
    @Throws(Exception::class)
    fun getField(obj: Any, name: String): Any? = resolveInstanceField(obj.javaClass, name).get(obj)

    /** 读实例 int 字段；失败抛出异常。 */
    @Throws(Exception::class)
    fun getIntField(obj: Any, name: String): Int {
        val value = getField(obj, name)
        if (value is Int) {
            return value
        }
        throw NoSuchFieldException("$name not int")
    }

    /** 写实例 int 字段；失败抛出异常。 */
    @Throws(Exception::class)
    fun setIntField(obj: Any, name: String, value: Int) {
        resolveInstanceField(obj.javaClass, name).setInt(obj, value)
    }

    /** 读静态字段（任意可见性）。 */
    @Throws(Exception::class)
    fun getStaticField(clazz: Class<*>, name: String): Any? = resolveStaticField(clazz, name).get(null)

    /** 读静态 int 字段。 */
    @Throws(Exception::class)
    fun getStaticIntField(clazz: Class<*>, name: String): Int {
        val value = getStaticField(clazz, name)
        if (value is Int) {
            return value
        }
        throw NoSuchFieldException("$name not int")
    }

    /** 判断静态字段是否为 int[] 并读取（用于 MAX_STREAM_VOLUME）。 */
    fun getStaticIntArrayField(clazz: Class<*>, name: String): IntArray? = try {
        getStaticField(clazz, name) as? IntArray
    } catch (t: Throwable) {
        logError("getStaticIntArrayField(${clazz.simpleName}.$name) failed: $t")
        null
    }

    // ==================== 配置读取（双通道 + TTL 缓存） ====================

    /**
     * 配置对象缓存有效期（毫秒）：有效期内热路径直接返回 [sCachedConfig]，
     * 跳过 SettingsProvider binder IPC 与镜像文件读。一次音量按键会命中 2~4 个 hook，
     * 每个都 readConfig，此前每次一次 binder IPC。配置仅在 App 保存时变化，
     * 实时性由 [ensureConfigObserver] 的 ContentObserver 立即失效保证，TTL 仅作
     * observer 注册失败（开机早期未就绪）时的兜底。
     */
    private const val CONFIG_CACHE_TTL_MS = 800L

    /** 上次尝试注册观察器的时间：开机早期 IContentService 未就绪时按节流重试，避免高频刷屏。 */
    private const val OBSERVER_RETRY_MS = 15_000L

    /** 上次成功填充配置缓存的时间戳（毫秒）；ContentObserver 触发时置 0 立即失效。 */
    @Volatile
    private var sConfigCacheAtMs = 0L

    /** ContentObserver 经弱引用持有，必须保留强引用防止被 GC 回收后静默失效；非 null 即已注册。 */
    @Volatile
    private var sConfigObserver: ContentObserver? = null

    /** 上次尝试注册观察器的时间。 */
    @Volatile
    private var sConfigObserverAttemptMs = 0L

    /** 按原始串缓存已解析配置（DOM 解析较 `split` 重，高频 hook 路径避免重复解析）。 */
    @Volatile
    private var sCachedRaw: String? = null

    @Volatile
    private var sCachedConfig: VolumeConfig? = null

    /**
     * 负面退避：双通道都读不到配置后记下失败时刻，退避期内快路径直接返回 null。
     * 避免系统侧无配置时，音量键 / VolumeStreamState / AVRCP 转换这些高频热路径每次都走
     * binder IPC + 文件 stat/open。新配置写入后最多 [CONFIG_MISS_BACKOFF_MS] 即被下一次慢路径读到。
     */
    @Volatile
    private var sConfigMissAtMs = 0L

    private const val CONFIG_MISS_BACKOFF_MS = 5_000L

    /** single-flight：缓存过期时仅首个线程走慢路径，其余沿用旧缓存，杜绝并发 binder 风暴。 */
    private val sConfigReading = AtomicBoolean(false)

    /**
     * 读取配置（TTL 缓存快路径 + 多重通道兜底，任一成功即返回）。
     *
     *
     * 有效期内直接返回缓存对象；否则走 [readConfigUncached] 逐通道读取，
     * 成功后刷新缓存时间戳。App 保存配置后经 ContentObserver 立即失效，保证热路径
     * 及时读到新值。
     *
     * @return 解析后的 [VolumeConfig]（任一通道成功即返回），全部失败返回 null
     */
    fun readConfig(context: Context?): VolumeConfig? {
        // 缓存快路径 / 负面退避的分支判定抽到纯函数 [decideConfigReadPath]（假时钟可测，见单测）。
        val now = System.currentTimeMillis()
        val cached = sCachedConfig
        when (
            decideConfigReadPath(
                nowMs = now, cachedAtMs = sConfigCacheAtMs, missAtMs = sConfigMissAtMs,
                hasCached = cached != null, ttlMs = CONFIG_CACHE_TTL_MS, backoffMs = CONFIG_MISS_BACKOFF_MS,
            )
        ) {
            ConfigReadPath.CACHE_HIT -> return cached
            ConfigReadPath.NEGATIVE_SKIP -> return null
            ConfigReadPath.SLOW_PATH -> Unit
        }
        // single-flight：仅首个线程进入慢路径，其余返回当前缓存（含可能过期的 null）。
        if (!sConfigReading.compareAndSet(false, true)) {
            return cached
        }
        try {
            val fromGlobal = booleanArrayOf(false)
            val config = readConfigUncached(context, fromGlobal)
            val at = System.currentTimeMillis()
            if (config != null) {
                sConfigCacheAtMs = at
                sConfigMissAtMs = 0L
                // 仅当本次确实从 Settings.Global 读到配置时才尝试注册观察器：
                // system_server 的早期 Context 无 IContentService（getString/observer 均 NPE），
                // 实际走镜像文件通道，该通道变更无法用 ContentObserver 监听——此时 TTL 即为失效手段。
                if (fromGlobal[0]) {
                    ensureConfigObserver(context)
                }
            } else {
                // 两通道皆失败：置负面缓存时间戳，退避期内后续命中直接返回 null。
                sConfigMissAtMs = at
            }
            return config
        } finally {
            sConfigReading.set(false)
        }
    }

    /**
     * 逐通道读取配置（无 TTL 缓存）：Settings.Global → 镜像文件兜底。
     *
     * @param outFromGlobal 长度≥1 的出参：`outFromGlobal[0]=true` 表示本次成功来自 Settings.Global
     * （可被 ContentObserver 监听），`false` 表示来自镜像文件通道。
     */
    private fun readConfigUncached(context: Context?, outFromGlobal: BooleanArray): VolumeConfig? {
        // 通道1：Settings.Global
        if (context != null) {
            try {
                val raw = SettingsGlobal.getString(context, Prefs.GLOBAL_KEY)
                val config = parseCached(raw)
                if (config != null) {
                    outFromGlobal[0] = true
                    return config
                }
                val trimmed = raw?.trim()
                if (!trimmed.isNullOrEmpty() && trimmed != "null") {
                    logErrorOnce("global-invalid", "global config invalid: [$trimmed]")
                }
            } catch (t: Throwable) {
                // 冷启动早期 ActivityManager 尚未把自己发布到 ServiceManager，ContentResolver 拿不到
                // settings provider 的入口，会在 null 上调用 IActivityManager.getContentProvider 抛 NPE。
                // 属预期情形（镜像通道会接住），单独给一条可读日志，避免被当成真故障去查。
                if (isProviderHostNotReady(t)) {
                    logOnce("global-not-ready", "read global config skipped: ActivityManager not ready (early boot)")
                } else {
                    logOnce("global-failed", "read global config failed: $t")
                }
            }
        }
        // 通道2：配置镜像文件（不依赖 SettingsProvider；冷启动早期唯一可靠通道）
        return try {
            val config = parseCached(readMirrorConfig())
            if (config != null) {
                logOnce("mirror-used", "config loaded from mirror file")
            }
            config
        } catch (t: Throwable) {
            logErrorOnce("mirror-failed", "read mirror config failed: $t")
            null
        }
    }

    /**
     * 懒注册 Settings.Global 配置变更观察器（每进程一次）：App 保存配置经
     * `Settings.Global.putString` 下发，触发 `onChange` 把缓存时间戳置 0，
     * 下次热路径 [readConfig] 立即走 IPC 重取，无需等待 TTL 过期。App 同时写
     * Settings.Global 与镜像文件，二者内容一致，单一失效点即覆盖两个通道。
     */
    private fun ensureConfigObserver(context: Context?) {
        if (CONFIG_CACHE_TTL_MS <= 0 || sConfigObserver != null || context == null) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - sConfigObserverAttemptMs < OBSERVER_RETRY_MS) {
            return // 节流：早期 IContentService 未就绪，避免每次慢路径都触发失败重试/刷屏
        }
        sConfigObserverAttemptMs = now
        try {
            val uri = SettingsGlobal.getUriFor(Prefs.GLOBAL_KEY)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    sConfigCacheAtMs = 0 // 配置已变：立即失效，下次 readConfig 走 IPC 重取
                }
            }
            context.contentResolver.registerContentObserver(uri, false, observer)
            sConfigObserver = observer // 保强引用，防弱引用注册被 GC；非 null 兼作「已注册」标志
            log("config ContentObserver registered")
        } catch (t: Throwable) {
            // 失败不置 sConfigObserver，保留强引用外不标记成功，后续慢路径按节流重试
            logErrorOnce("config-observer", "register config observer failed: $t")
        }
    }

    private fun parseCached(raw: String?): VolumeConfig? {
        if (raw == null) {
            return null
        }
        if (raw == sCachedRaw) {
            return sCachedConfig
        }
        val config = VolumeConfig.fromJson(raw)
        if (config != null) {
            sCachedRaw = raw
            sCachedConfig = config
        }
        return config
    }

    /** 读取 App 以 root 写入的配置镜像（[Prefs.MIRROR_CONFIG_FILE]）。 */
    private fun readMirrorConfig(): String? = try {
        val file = File(Prefs.MIRROR_CONFIG_FILE)
        val length = file.length()
        if (!file.isFile || length <= 0 || length > MIRROR_MAX_BYTES) {
            null
        } else {
            val buffer = ByteArray(length.toInt())
            var read = 0
            FileInputStream(file).use { input ->
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count < 0) {
                        break // 真 EOF：剩余长度按实际读到的算，不用 0x00 填充后靠 trim 兑
                    }
                    read += count
                }
            }
            String(buffer, 0, read, StandardCharsets.UTF_8).trim()
        }
    } catch (t: Throwable) {
        logErrorOnce("mirror-read-fail", "read mirror config file failed: $t")
        null
    }

    // ==================== Context 解析 ====================

    /**
     * system_server 进程内解析 Context（多重兜底，不依赖类名判断）：
     * 先用构造器缓存；再取传入对象的 mContext（AudioService 与 AudioDeviceBroker
     * 均有该字段）；最后回退 ActivityThread 的系统上下文（system_server 进程必有）。
     */
    fun systemServerContext(thisObject: Any?): Context? {
        sSysContext?.let { return it }
        var lastError: Throwable? = null
        try {
            if (thisObject != null) {
                val context = getField(thisObject, "mContext")
                if (context is Context) {
                    sSysContext = context
                    return context
                }
            }
        } catch (t: Throwable) {
            lastError = t
        }
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            if (thread != null) {
                val context = thread.javaClass.getMethod("getSystemContext").invoke(thread)
                if (context is Context) {
                    sSysContext = context
                    return context
                }
            }
        } catch (t: Throwable) {
            lastError = t
        }
        logErrorOnce("sys-context", "system context unavailable" + (lastError?.let { ": $it" } ?: ""))
        return null
    }

    /** 缓存 system_server 的 Context（AudioService 构造器参数即系统上下文）。 */
    fun cacheSystemContext(context: Context?) {
        if (context != null) {
            sSysContext = context
        }
    }

    /** 蓝牙进程的上下文（ActivityThread 反射，不缓存失败结果，下次继续尝试）。 */
    fun bluetoothContext(): Context? {
        sBtContext?.let { return it }
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            if (thread != null) {
                val app = thread.javaClass.getMethod("getApplication").invoke(thread)
                if (app is Context) {
                    sBtContext = app
                    return app
                }
            }
        } catch (t: Throwable) {
            logErrorOnce("bt-context", "get bluetooth app context failed: $t")
        }
        return null
    }
}

/** [decideConfigReadPath] 的三种快路径判定结果。 */
internal enum class ConfigReadPath { CACHE_HIT, NEGATIVE_SKIP, SLOW_PATH }

/**
 * 日志路径探测结果：[path] 为首个可写候选（全失败为 null），[failures] 记录每个不可写候选的原因。
 * 旧实现静默 `catch`，蓝牙 DE 路径为何失败只能靠推断。
 */
internal data class LogPathChoice(val path: String?, val failures: List<String>)

/**
 * 蓝牙日志候选路径的排序与去重（纯函数，供单测）：固定 DE → 运行时解析出的路径 → 固定 CE 兜底。
 * DE 永远排第一，因为它最早可写且不会因为用户未解锁而丢掉整轮 direct-boot 日志。
 */
internal fun btLogCandidatePaths(
    resolved: List<String>, fixedDe: String, fixedCe: String,
): List<String> = (listOf(fixedDe) + resolved + fixedCe).distinct()

/**
 * 判定读全局配置的异常是否属于「冷启动早期 provider 宿主还没就绪」（纯函数，供单测）。
 *
 * 表现固定为 `NullPointerException`，消息里点名 `IActivityManager`：
 * `Attempt to invoke interface method '…IActivityManager.getContentProvider(…)' on a null object reference`
 * ——ActivityManager.getService() 此刻返回 null。其余异常（超时、死对象等）仍按真失败记录。
 */
internal fun isProviderHostNotReady(t: Throwable): Boolean =
    t is NullPointerException && t.message?.contains("IActivityManager") == true

/**
 * 热路径读配置的分支判定（纯函数，假时钟注入即可单测，不碰 Android 运行时）：
 *  - [CACHE_HIT]：有缓存且未过期；
 *  - [NEGATIVE_SKIP]：无缓存且上次读失败仍在负面退避期内；
 *  - [SLOW_PATH]：其余情形（含过期、退避到期）需走一次慢路径。
 */
internal fun decideConfigReadPath(
    nowMs: Long, cachedAtMs: Long, missAtMs: Long, hasCached: Boolean,
    ttlMs: Long, backoffMs: Long,
): ConfigReadPath {
    if (hasCached && ttlMs > 0 && nowMs - cachedAtMs < ttlMs) {
        return ConfigReadPath.CACHE_HIT
    }
    if (!hasCached && nowMs - missAtMs < backoffMs) {
        return ConfigReadPath.NEGATIVE_SKIP
    }
    return ConfigReadPath.SLOW_PATH
}

/**
 * 挂载指定名称的全部声明方法（等价于旧 API 的 `hookAllMethods`）：目标集合用纯反射解析，
 * 挂载与调用链交给 YukiHookAPI 的 chain 拦截器。返回挂载数量，0 表示方法不存在（由调用方记录）。
 */
internal fun PackageParam.hookAllNamed(
    clazz: Class<*>,
    methodName: String,
    hooker: (HookChain) -> Any?,
): Int {
    val methods = clazz.declaredMethods.filter { it.name == methodName }
    if (methods.isNotEmpty()) {
        methods.interceptAll { hooker(this) }
    }
    return methods.size
}

/** 挂载指定类的全部构造器（等价于旧 API 的 `hookAllConstructors`）；返回挂载数量。 */
internal fun PackageParam.hookAllConstructors(
    clazz: Class<*>,
    hooker: (HookChain) -> Any?,
): Int {
    val constructors: List<Member> = clazz.declaredConstructors.toList<Constructor<*>>()
    if (constructors.isNotEmpty()) {
        constructors.interceptAll { hooker(this) }
    }
    return constructors.size
}

/**
 * 禁止 JIT/AOT 内联指定类的全部构造器与 [methodNames] 命中的方法（等价于旧 API 的
 * `deoptimize`）：这些方法内部会读取系统属性，一旦被内联到调用点就会绕过属性拦截。
 * 返回提交反优化的成员数量。
 */
internal fun PackageParam.deoptimizeCtorsAndMethods(
    clazz: Class<*>,
    vararg methodNames: String,
): Int {
    val targets: List<Member> =
        clazz.declaredConstructors.toList<Constructor<*>>() +
            clazz.declaredMethods.filter { m -> methodNames.any { it == m.name } }
    if (targets.isNotEmpty()) {
        targets.deoptimizeAll()
    }
    return targets.size
}
