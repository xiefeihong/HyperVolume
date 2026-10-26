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

    /** 模块自写日志：系统框架侧文件（system_server 可写，root 可读）。 */
    private const val SYS_LOG_FILE = "/data/system/hypervolume_sys.log"

    /** 备用日志路径（当 /data/system/ 被 SELinux 阻止时使用）。 */
    private const val SYS_LOG_FILE_FALLBACK = "/data/misc/hypervolume_sys.log"

    /** 模块自写日志：蓝牙进程侧文件名（写入蓝牙应用数据目录）。 */
    private const val BT_LOG_FILE_NAME = "hypervolume_bt.log"

    private const val BT_LOG_FILE_FALLBACK =
        "/data/data/com.android.bluetooth/files/$BT_LOG_FILE_NAME"

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

    /** 追加写入当前进程的日志文件（带级别标记）；文件过大时清空重写，连续失败 3 次后放弃文件日志。 */
    private fun writeLogFile(level: String, message: String?) {
        // 放弃文件日志的提示需在锁外发出：logErrorOnce→log→writeLogFile 会重入同一 LOG_LOCK，
        // 虽同线程可重入不死锁，但会在持锁期间触发文件路径重复探测/写失败计数错乱。锁内只置标志。
        var giveUp = false
        var failures = 0
        synchronized(LOG_LOCK) {
            try {
                val path = resolveLogPath() ?: return
                val file = File(path)
                if (file.length() > LOG_FILE_MAX_BYTES) {
                    file.delete()
                }
                file.parentFile?.takeIf { !it.exists() }?.mkdirs()
                val time = synchronized(LOG_TIME_FORMAT) { LOG_TIME_FORMAT.format(Date()) }
                val bytes = ("[$level] $time $message\n").toByteArray(StandardCharsets.UTF_8)
                FileOutputStream(file, true).use { it.write(bytes) }
                sLogFileFailureCount = 0
            } catch (t: Throwable) {
                failures = ++sLogFileFailureCount
                if (failures >= 3 && sLogFilePath != null) {
                    // 连续失败（如 SELinux 限制）：放弃文件日志，依赖 logcat 兜底
                    sLogFilePath = null
                    sLogFileFailureCount = 4
                    giveUp = true
                }
            }
        }
        if (giveUp) {
            logErrorOnce("logfile-give-up", "log file write failed $failures times, switching to logcat only")
        }
    }

    /** 解析当前进程的日志文件路径（系统框架固定路径；蓝牙优先应用数据目录）。 */
    private fun resolveLogPath(): String? {
        sLogFilePath?.let { return it }
        if (sLogFileFailureCount > 3) {
            return null
        }
        val path = if (sIsSystemProcess) {
            // 先尝试主路径，失败后用备用路径；两个都不可用则依赖 logcat 兜底
            pickWritableLogPath(SYS_LOG_FILE, SYS_LOG_FILE_FALLBACK)
        } else {
            bluetoothContext()?.filesDir?.let { File(it, BT_LOG_FILE_NAME).absolutePath }
                ?: BT_LOG_FILE_FALLBACK
        }
        sLogFilePath = path
        return path
    }

    /** 依次探测候选日志路径的可写性，返回首个可写路径；全不可写返回 null。 */
    private fun pickWritableLogPath(primary: String, fallback: String): String? {
        for (candidate in arrayOf(primary, fallback)) {
            val file = File(candidate)
            try {
                file.parentFile?.takeIf { !it.exists() }?.mkdirs()
                // 测试是否可写
                FileOutputStream(file, true).use { it.write(0) }
                return candidate
            } catch (ignored: Throwable) {
                // 该路径不可写，尝试下一个
            }
        }
        return null
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
        // 缓存快路径：有效期内直接返回已解析配置，跳过 SettingsProvider binder IPC 与镜像文件读。
        val now = System.currentTimeMillis()
        val cached = sCachedConfig
        if (cached != null && CONFIG_CACHE_TTL_MS > 0 && now - sConfigCacheAtMs < CONFIG_CACHE_TTL_MS) {
            return cached
        }
        val fromGlobal = booleanArrayOf(false)
        val config = readConfigUncached(context, fromGlobal)
        if (config != null) {
            sConfigCacheAtMs = System.currentTimeMillis()
            // 仅当本次确实从 Settings.Global 读到配置时才尝试注册观察器：
            // system_server 的早期 Context 无 IContentService（getString/observer 均 NPE），
            // 实际走镜像文件通道，该通道变更无法用 ContentObserver 监听——此时 TTL 即为失效手段。
            if (fromGlobal[0]) {
                ensureConfigObserver(context)
            }
        }
        return config
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
                logOnce("global-failed", "read global config failed: $t")
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
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < buffer.size) {
                    val count = input.read(buffer, offset, buffer.size - offset)
                    if (count <= 0) {
                        break
                    }
                    offset += count
                }
            }
            String(buffer, StandardCharsets.UTF_8).trim()
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
