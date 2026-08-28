package com.audiobookshelf.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverDiskStoreTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `same item id is isolated by server namespace`() {
    val store = newStore(maxTotalBytes = 100L, maxEntryBytes = 50L)

    val first = writeCover(store, "server-a", "shared-item", byteArrayOf(1, 2, 3))

    assertNull(store.cachedFile("server-b", "shared-item"))
    val second = writeCover(store, "server-b", "shared-item", byteArrayOf(7, 8, 9))

    assertNotEquals(first.canonicalPath, second.canonicalPath)
    assertArrayEquals(byteArrayOf(1, 2, 3), store.cachedFile("server-a", "shared-item")!!.readBytes())
    assertArrayEquals(byteArrayOf(7, 8, 9), store.cachedFile("server-b", "shared-item")!!.readBytes())
  }

  @Test
  fun `cache hit touches entry and post-write pruning evicts global oldest`() {
    val store = newStore(maxTotalBytes = 8L, maxEntryBytes = 8L)
    val first = writeCover(store, "scope-a", "first", ByteArray(4) { 1 })
    val second = writeCover(store, "scope-b", "second", ByteArray(4) { 2 })
    assertTrue(first.setLastModified(1_000L))
    assertTrue(second.setLastModified(2_000L))

    assertEquals(first.canonicalPath, store.cachedFile("scope-a", "first")!!.canonicalPath)
    assertTrue(first.lastModified() > second.lastModified())

    val third = writeCover(store, "scope-c", "third", ByteArray(4) { 3 })
    val maintenance = store.pruneNow()

    assertTrue(first.isFile)
    assertFalse(second.exists())
    assertTrue(third.isFile)
    assertTrue(maintenance.totalBytes <= 8L)
  }

  @Test
  fun `startup removes legacy partial empty and oversized files safely`() {
    val store = newStore(maxTotalBytes = 20L, maxEntryBytes = 10L)
    val valid = writeCover(store, "scope", "valid", byteArrayOf(1, 2, 3))
    val root = valid.parentFile!!.parentFile!!
    val legacy = root.resolve("legacy.jpg").apply { writeBytes(byteArrayOf(4)) }
    val partial = store.tempFile("scope", "partial").apply { writeBytes(byteArrayOf(5)) }
    val empty = store.fileFor("scope", "empty").apply {
      parentFile!!.mkdirs()
      createNewFile()
    }
    val oversized = store.fileFor("scope", "oversized").apply {
      parentFile!!.mkdirs()
      writeBytes(ByteArray(11))
    }

    val result = store.startupMaintenance()

    assertEquals(listOf(valid.canonicalPath), result.files.map { it.canonicalPath })
    assertEquals(4, result.removedFiles)
    assertEquals(13L, result.removedBytes)
    assertEquals(3L, result.totalBytes)
    assertFalse(legacy.exists())
    assertFalse(partial.exists())
    assertFalse(empty.exists())
    assertFalse(oversized.exists())
  }

  private fun newStore(maxTotalBytes: Long, maxEntryBytes: Long): CoverDiskStore =
    CoverDiskStore(temporaryFolder.newFolder("covers"), maxTotalBytes, maxEntryBytes)

  private fun writeCover(
    store: CoverDiskStore,
    namespace: String,
    itemId: String,
    bytes: ByteArray
  ): java.io.File {
    val temp = store.tempFile(namespace, itemId)
    temp.writeBytes(bytes)
    assertTrue(store.commit(temp, namespace, itemId))
    return store.fileFor(namespace, itemId)
  }
}
