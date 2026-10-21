package com.xiefeihong.hypervolume.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 更新检查（关于页「检查更新」）。
 *
 * 只做「读最新版本 + 比版本号」两件事：网络失败抛 [IOException]（界面提示网络不可用），
 * 响应缺字段抛 [IllegalArgumentException]（界面提示解析失败）。版本比较是纯函数，可单测。
 */
object UpdateChecker {

    /** 仓库坐标（更新检查与 GitHub 入口共用）。 */
    const val REPO = "xiefeihong/HyperVolume"

    /** 项目主页（关于页跳转用）。 */
    const val REPO_URL = "https://github.com/$REPO"

    private const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 一条 Release 的关键字段。 */
    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val publishedAt: String,
        val url: String,
    )

    /**
     * 拉取最新 Release（**阻塞网络**，须在 IO 协程调用）。
     *
     * GitHub 匿名访问限流时返回 403/429，同样以 [IOException] 抛出，界面统一提示检查网络。
     */
    fun latest(): Release {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "HyperVolume")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** 解析 GitHub Release JSON；缺 `tag_name` 视为无效响应。 */
    internal fun parse(body: String): Release {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("响应缺少 tag_name")
        return Release(
            tag = tag,
            title = json.optString("name").ifEmpty { tag },
            notes = json.optString("body"),
            publishedAt = json.optString("published_at").take(10),
            url = json.optString("html_url"),
        )
    }

    /**
     * 判断 [candidate] 是否比 [current] 更新：按 `.` 分段逐位比数值（多出的段按 0 补齐），
     * 数值相同时再比剩余非数字后缀，保证 `1.10.0 > 1.9.0`、`1.0.0-beta < 1.0.0`。
     */
    fun isNewer(candidate: String?, current: String): Boolean {
        val left = normalizeTag(candidate ?: return false)
        val right = normalizeTag(current)
        if (left.isEmpty() || right.isEmpty()) return false
        val a = left.split('.')
        val b = right.split('.')
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { "0" }
            val y = b.getOrElse(i) { "0" }
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            if (xn != null && yn != null) {
                if (xn != yn) return xn > yn
                continue
            }
            // 同位出现非数字（预发布后缀）：数字段视为更新，字符串按字典序兜底
            if (xn != null) return true
            if (yn != null) return false
            if (x != y) return x > y
        }
        return false
    }

    /** 去掉 `v` 前缀与空白，截掉 `-`/`+` 之后的预发布标记。 */
    internal fun normalizeTag(tag: String): String =
        tag.trim().removePrefix("v").substringBefore('-').substringBefore('+')
}
