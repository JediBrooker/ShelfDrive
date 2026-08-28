package com.audiobookshelf.app.media

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Small synchronized disk store used by [CoverCache].
 *
 * Scope and item identifiers are hashed independently, producing
 * `covers/<scope hash>/<item hash>.jpg`.  Keeping the scope in the path prevents
 * two servers (or two users on one server) that use the same item ID from ever
 * sharing artwork.
 */
internal class CoverDiskStore(
  private val root: File,
  private val maxTotalBytes: Long,
  private val maxEntryBytes: Long
) {
  init {
    require(maxTotalBytes > 0L)
    require(maxEntryBytes > 0L)
  }

  private val diskLock = Any()

  internal data class MaintenanceResult(
    val files: List<File>,
    val removedFiles: Int,
    val removedBytes: Long,
    val totalBytes: Long
  )

  /** Removes stale/legacy files and applies the size cap without failing startup. */
  fun startupMaintenance(): MaintenanceResult = synchronized(diskLock) {
    if (root.exists() && !root.isDirectory) root.delete()
    root.mkdirs()
    var removedFiles = 0
    var removedBytes = 0L

    allFilesLocked().forEach { file ->
      val length = file.length().coerceAtLeast(0L)
      if (!isValidCoverFileLocked(file) || length == 0L || length > maxEntryBytes) {
        if (file.delete()) {
          removedFiles++
          removedBytes = saturatingAdd(removedBytes, length)
        }
      }
    }

    val pruned = pruneLocked()
    removedFiles += pruned.first
    removedBytes = saturatingAdd(removedBytes, pruned.second)
    removeEmptyDirectoriesLocked()
    val files = coverFilesLocked()
    MaintenanceResult(files, removedFiles, removedBytes, totalLength(files))
  }

  /** Returns and LRU-touches a valid scoped cover, or null for a miss. */
  fun cachedFile(namespace: String, itemId: String): File? = synchronized(diskLock) {
    val file = fileForLocked(namespace, itemId)
    val length = file.length().coerceAtLeast(0L)
    if (!file.isFile || length == 0L || length > maxEntryBytes) {
      if (file.exists()) file.delete()
      return@synchronized null
    }
    touchLocked(file)
    file
  }

  fun tempFile(namespace: String, itemId: String): File = synchronized(diskLock) {
    val destination = fileForLocked(namespace, itemId)
    destination.parentFile?.mkdirs()
    File(destination.parentFile, "${destination.nameWithoutExtension}.tmp")
  }

  /** Atomically publishes a completed temp file, then enforces the global LRU cap. */
  fun commit(temp: File, namespace: String, itemId: String): Boolean = synchronized(diskLock) {
    val length = temp.length().coerceAtLeast(0L)
    if (!temp.isFile || length == 0L || length > maxEntryBytes) return@synchronized false

    val destination = fileForLocked(namespace, itemId)
    destination.parentFile?.mkdirs()
    val moved = try {
      Files.move(
        temp.toPath(),
        destination.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
      true
    } catch (_: AtomicMoveNotSupportedException) {
      runCatching {
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }.isSuccess
    } catch (_: Exception) {
      false
    }
    if (!moved) return@synchronized false

    touchLocked(destination)
    pruneLocked()
    removeEmptyDirectoriesLocked()
    true
  }

  /** Snapshot for URI re-grants; does not alter LRU timestamps. */
  fun existingFiles(): List<File> = synchronized(diskLock) {
    coverFilesLocked().toList()
  }

  internal fun fileFor(namespace: String, itemId: String): File = synchronized(diskLock) {
    fileForLocked(namespace, itemId)
  }

  internal fun pruneNow(): MaintenanceResult = synchronized(diskLock) {
    val pruned = pruneLocked()
    removeEmptyDirectoriesLocked()
    val files = coverFilesLocked()
    MaintenanceResult(files, pruned.first, pruned.second, totalLength(files))
  }

  private fun fileForLocked(namespace: String, itemId: String): File {
    val scopeDirectory = File(root, sha256Hex(namespace))
    return File(scopeDirectory, "${sha256Hex(itemId)}.jpg")
  }

  private fun pruneLocked(): Pair<Int, Long> {
    val files = coverFilesLocked().sortedWith(
      compareBy<File> { it.lastModified() }.thenBy { it.absolutePath }
    )
    var total = totalLength(files)
    var removedFiles = 0
    var removedBytes = 0L

    for (file in files) {
      if (total <= maxTotalBytes) break
      val length = file.length().coerceAtLeast(0L)
      if (file.delete()) {
        total = (total - length).coerceAtLeast(0L)
        removedFiles++
        removedBytes = saturatingAdd(removedBytes, length)
      }
    }
    return removedFiles to removedBytes
  }

  private fun touchLocked(file: File) {
    // A failed timestamp update is only an LRU-quality degradation, never a
    // reason to make otherwise-valid artwork unavailable.
    runCatching { file.setLastModified(System.currentTimeMillis()) }
  }

  private fun coverFilesLocked(): List<File> =
    allFilesLocked().filter { file ->
      isValidCoverFileLocked(file) && file.length() in 1..maxEntryBytes
    }

  private fun isValidCoverFileLocked(file: File): Boolean {
    val scope = file.parentFile ?: return false
    val directParent = runCatching { scope.parentFile?.canonicalFile }.getOrNull()
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
    return directParent == canonicalRoot &&
      SCOPE_NAME.matches(scope.name) &&
      COVER_NAME.matches(file.name)
  }

  private fun allFilesLocked(): List<File> {
    val files = mutableListOf<File>()
    fun visit(file: File) {
      if (file.isFile) {
        files += file
      } else if (file.isDirectory) {
        file.listFiles()?.forEach(::visit)
      }
    }
    visit(root)
    return files
  }

  private fun removeEmptyDirectoriesLocked() {
    fun visit(directory: File) {
      directory.listFiles()?.filter { it.isDirectory }?.forEach(::visit)
      if (directory != root && directory.listFiles()?.isEmpty() == true) {
        directory.delete()
      }
    }
    visit(root)
  }

  private fun totalLength(files: List<File>): Long =
    files.fold(0L) { total, file -> saturatingAdd(total, file.length().coerceAtLeast(0L)) }

  private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

  private companion object {
    val SCOPE_NAME = Regex("^[0-9a-f]{64}$")
    val COVER_NAME = Regex("^[0-9a-f]{64}\\.jpg$")
  }
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8))
  .joinToString("") { byte -> "%02x".format(byte) }
