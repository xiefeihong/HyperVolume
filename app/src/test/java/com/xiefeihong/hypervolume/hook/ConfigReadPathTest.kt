package com.xiefeihong.hypervolume.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 热路径读配置分支判定的纯函数单测（计划 D：负面退避 + TTL，用假时钟注入，不碰 Android 运行时）。
 *
 * [decideConfigReadPath] 覆盖 [ConfigReadPath] 三态：缓存命中 / 负面退避跳过 / 走慢路径。
 */
class ConfigReadPathTest {

    private val ttl = 800L
    private val backoff = 5_000L

    @Test
    fun freshCacheIsReturnedWithoutSlowPath() {
        assertEquals(
            ConfigReadPath.CACHE_HIT,
            decideConfigReadPath(nowMs = 1_000, cachedAtMs = 500, missAtMs = 0, hasCached = true, ttlMs = ttl, backoffMs = backoff),
        )
    }

    @Test
    fun expiredCacheGoesSlowPath() {
        assertEquals(
            ConfigReadPath.SLOW_PATH,
            decideConfigReadPath(nowMs = 2_000, cachedAtMs = 500, missAtMs = 0, hasCached = true, ttlMs = ttl, backoffMs = backoff),
        )
    }

    @Test
    fun recentMissIsNegativelyCached() {
        assertEquals(
            ConfigReadPath.NEGATIVE_SKIP,
            decideConfigReadPath(nowMs = 3_000, cachedAtMs = 0, missAtMs = 1_000, hasCached = false, ttlMs = ttl, backoffMs = backoff),
        )
    }

    @Test
    fun expiredBackoffRetriesSlowPath() {
        // miss 于 t=1000，退避 5000ms，t=6001 已过退避 → 重新慢路径（新写入的配置得以读到）
        assertEquals(
            ConfigReadPath.SLOW_PATH,
            decideConfigReadPath(nowMs = 6_001, cachedAtMs = 0, missAtMs = 1_000, hasCached = false, ttlMs = ttl, backoffMs = backoff),
        )
    }

    @Test
    fun zeroTtlNeverHitsCache() {
        // TTL<=0 关闭缓存时，即便刚填充也不走 CACHE_HIT（有缓存也走慢路径）
        assertEquals(
            ConfigReadPath.SLOW_PATH,
            decideConfigReadPath(nowMs = 1_000, cachedAtMs = 999, missAtMs = 0, hasCached = true, ttlMs = 0, backoffMs = backoff),
        )
    }
}
