package com.example.platerecognizer.data

import com.example.platerecognizer.data.ImageStore.Companion.cleanupOrphansIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 纯 JVM 测试 [ImageStore.cleanupOrphansIn]。
 * 隔离掉 Android Uri / Context 依赖，单测核心算法。
 */
class ImageStoreCleanupTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun touch(name: String, ageMillis: Long, now: Long): File {
        val f = File(tmp.root, name)
        f.writeText("x")
        // setLastModified 在某些 FS 上只精确到秒，足够测试
        f.setLastModified(now - ageMillis)
        return f
    }

    @Test fun keeps_files_referenced_by_db() {
        val now = 1_700_000_000_000L
        val kept = touch("imported_a.jpg", ageMillis = 7L * 24 * 3600_000, now = now)
        val orphan = touch("imported_b.jpg", ageMillis = 7L * 24 * 3600_000, now = now)

        val ref = listOf("file://${kept.absolutePath}")
        val deleted = cleanupOrphansIn(tmp.root, ref, retentionMillis = 24L * 3600_000, now = now)

        assertEquals(1, deleted)
        assertTrue("被引用文件应保留", kept.exists())
        assertFalse("孤儿文件应被删除", orphan.exists())
    }

    @Test fun keeps_recent_files_within_retention() {
        val now = 1_700_000_000_000L
        val recent = touch("imported_a.jpg", ageMillis = 5 * 60_000L, now = now)  // 5 分钟前
        val old = touch("imported_b.jpg", ageMillis = 48L * 3600_000, now = now)  // 48 小时前

        val deleted = cleanupOrphansIn(tmp.root, emptyList(), retentionMillis = 24L * 3600_000, now = now)

        assertEquals(1, deleted)
        assertTrue("保留期内的文件不应被删", recent.exists())
        assertFalse("超过保留期的孤儿应被删", old.exists())
    }

    @Test fun handles_empty_dir_and_missing_dir() {
        val now = 1_700_000_000_000L
        assertEquals(0, cleanupOrphansIn(tmp.root, emptyList(), 0L, now))

        val nonexistent = File(tmp.root, "does-not-exist")
        assertEquals(0, cleanupOrphansIn(nonexistent, emptyList(), 0L, now))
    }

    @Test fun ignores_directories_in_root() {
        val now = 1_700_000_000_000L
        File(tmp.root, "subdir").mkdirs()
        val orphan = touch("orphan.jpg", ageMillis = 48L * 3600_000, now = now)

        val deleted = cleanupOrphansIn(tmp.root, emptyList(), retentionMillis = 24L * 3600_000, now = now)

        assertEquals(1, deleted)
        assertFalse(orphan.exists())
        assertTrue("目录不应被影响", File(tmp.root, "subdir").exists())
    }

    @Test fun handles_unparseable_uris_in_referenced() {
        val now = 1_700_000_000_000L
        val orphan = touch("orphan.jpg", ageMillis = 48L * 3600_000, now = now)

        // 非 file:// URI、空字符串、相对路径 —— 都应该被静默忽略，不影响删除决策
        val ref = listOf("content://media/external/123", "", "/no/scheme.jpg", "http://x.com/y.jpg")
        val deleted = cleanupOrphansIn(tmp.root, ref, retentionMillis = 24L * 3600_000, now = now)

        assertEquals(1, deleted)
        assertFalse(orphan.exists())
    }

    @Test fun zero_retention_aggressively_deletes() {
        val now = 1_700_000_000_000L
        val recent = touch("fresh.jpg", ageMillis = 0L, now = now)

        // retention=0 时，"now - lastModified < 0" 永假，全部按孤儿处理
        val deleted = cleanupOrphansIn(tmp.root, emptyList(), retentionMillis = 0L, now = now)

        assertEquals(1, deleted)
        assertFalse(recent.exists())
    }
}
