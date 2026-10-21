package com.xiefeihong.hypervolume.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的版本比较与响应解析单测（网络请求本身不在单测内覆盖）。
 */
class UpdateCheckerTest {

    @Test
    fun numericSegmentsCompareAsNumbers() {
        // 十位进位必须按数值比，不能按字典序（1.9.0 < 1.10.0）
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.10.0"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun shorterVersionIsPaddedWithZero() {
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun tagPrefixAndBuildMetadataAreIgnored() {
        assertTrue(UpdateChecker.isNewer("v1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.1+build7", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0.0"))
    }

    @Test
    fun prereleaseSuffixIsNotNewerThanStable() {
        // `-beta` 之前版本号相同 → 不提示更新，避免刷到预发布
        assertFalse(UpdateChecker.isNewer("1.0.0-beta", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.1-beta", "1.0.0"))
    }

    @Test
    fun blankOrMissingCandidateNeverUpdates() {
        assertFalse(UpdateChecker.isNewer(null, "1.0.0"))
        assertFalse(UpdateChecker.isNewer("", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("  ", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", ""))
    }

    @Test
    fun normalizeTagStripsPrefixAndSuffix() {
        assertEquals("1.2.3", UpdateChecker.normalizeTag(" v1.2.3 "))
        assertEquals("1.2.3", UpdateChecker.normalizeTag("1.2.3-rc.1"))
        assertEquals("1.2.3", UpdateChecker.normalizeTag("1.2.3+meta"))
    }

    @Test
    fun parseReleaseReadsKeyFields() {
        val release = UpdateChecker.parse(
            """
            {
              "tag_name": "v1.1.0",
              "name": "1.1.0",
              "body": "- 新增设置页",
              "published_at": "2026-10-21T08:30:00Z",
              "html_url": "https://github.com/xiefeihong/HyperVolume/releases/tag/v1.1.0"
            }
            """.trimIndent(),
        )
        assertEquals("v1.1.0", release.tag)
        assertEquals("1.1.0", release.title)
        assertEquals("- 新增设置页", release.notes)
        // 只保留日期部分，界面一行展示
        assertEquals("2026-10-21", release.publishedAt)
        assertTrue(release.url.endsWith("v1.1.0"))
    }

    @Test
    fun parseFallsBackToTagWhenNameMissing() {
        val release = UpdateChecker.parse("""{"tag_name":"1.2.0"}""")
        assertEquals("1.2.0", release.title)
        assertEquals("", release.notes)
    }

    @Test
    fun parseRejectsResponseWithoutTag() {
        try {
            UpdateChecker.parse("""{"name":"untitled"}""")
            assertTrue("缺少 tag_name 应当判为无效响应", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
