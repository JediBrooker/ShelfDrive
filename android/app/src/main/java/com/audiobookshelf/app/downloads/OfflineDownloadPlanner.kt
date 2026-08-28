package com.audiobookshelf.app.downloads

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.audiobookshelf.app.data.AudioFile
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

internal enum class OfflinePlanFailure {
  NOT_AUDIOBOOK,
  NO_AUDIO,
  INCOMPLETE_SERVER_METADATA,
  INVALID_SERVER,
  STORAGE_UNAVAILABLE,
  INSUFFICIENT_STORAGE,
  CAPACITY_EXCEEDED
}

internal sealed class OfflinePlanResult {
  data class Ready(val item: DownloadItem, val expectedBytes: Long) : OfflinePlanResult()
  data class Rejected(val reason: OfflinePlanFailure) : OfflinePlanResult()
}

/**
 * Builds an immutable, account-owned download plan without persisting a token
 * or a server-provided filesystem path. Android's DownloadManager receives the
 * bearer separately as a request header when each part is enqueued.
 */
internal object OfflineDownloadPlanner {
  const val MANAGED_JOB_PREFIX = "aaos-download-"
  const val MANAGED_FOLDER_ID = "internal-aaos-book-v1"
  const val MAX_TRACKS_PER_BOOK = 512
  private const val STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L

  fun plan(
    context: Context,
    libraryItem: LibraryItem,
    owner: ServerConnectionConfig,
    jobInstanceToken: String? = null
  ): OfflinePlanResult {
    if (libraryItem.mediaType != "book") {
      return OfflinePlanResult.Rejected(OfflinePlanFailure.NOT_AUDIOBOOK)
    }
    val book = libraryItem.media as? Book
      ?: return OfflinePlanResult.Rejected(OfflinePlanFailure.NOT_AUDIOBOOK)
    val tracks = book.getAudioTracks().sortedBy(AudioTrack::index)
    if (tracks.isEmpty()) {
      return OfflinePlanResult.Rejected(OfflinePlanFailure.NO_AUDIO)
    }
    if (tracks.size > MAX_TRACKS_PER_BOOK) {
      return OfflinePlanResult.Rejected(OfflinePlanFailure.CAPACITY_EXCEEDED)
    }
    val serverBase = normalizedServerBase(owner.address)
      ?: return OfflinePlanResult.Rejected(OfflinePlanFailure.INVALID_SERVER)
    val storageRoot = managedRoot(context)
      ?: return OfflinePlanResult.Rejected(OfflinePlanFailure.STORAGE_UNAVAILABLE)

    val jobId = jobId(owner.id, libraryItem.id, jobInstanceToken)
    val itemFolder = File(storageRoot, jobId)
    if (!isInside(storageRoot, itemFolder)) {
      return OfflinePlanResult.Rejected(OfflinePlanFailure.STORAGE_UNAVAILABLE)
    }

    val audioFiles = book.audioFiles.orEmpty()
    val parts = tracks.mapIndexed { position, track ->
      val audioFile = matchingAudioFile(track, audioFiles)
        ?: return OfflinePlanResult.Rejected(OfflinePlanFailure.INCOMPLETE_SERVER_METADATA)
      val ino = audioFile.ino.takeIf { it.isNotBlank() }
        ?: return OfflinePlanResult.Rejected(OfflinePlanFailure.INCOMPLETE_SERVER_METADATA)
      val extension = safeExtension(track, audioFile)
      val finalFile = File(itemFolder, "track-${(position + 1).toString().padStart(4, '0')}.$extension")
      val partialFile = File(itemFolder, "${finalFile.name}.part")
      val url = downloadUrl(serverBase, libraryItem.id, ino)
      val expectedSize = track.metadata?.size?.takeIf { it > 0L }
        ?: audioFile.metadata.size?.coerceAtLeast(0L)
        ?: 0L
      DownloadItemPart(
        id = stableId("$jobId:${position + 1}"),
        downloadItemId = jobId,
        filename = finalFile.name,
        fileSize = expectedSize,
        finalDestinationPath = finalFile.absolutePath,
        serverPath = url.encodedPath,
        localFolderName = "ShelfDrive offline storage",
        localFolderUrl = "",
        localFolderId = MANAGED_FOLDER_ID,
        ebookFile = null,
        audioTrack = track,
        episode = null,
        completed = false,
        moved = false,
        isMoving = false,
        failed = false,
        uri = Uri.parse(url.toString()),
        destinationUri = Uri.fromFile(partialFile),
        finalDestinationUri = Uri.fromFile(finalFile),
        finalDestinationSubfolder = jobId,
        downloadId = null,
        progress = 0L,
        bytesDownloaded = 0L
      )
    }.toMutableList()

    val expectedBytes = parts.fold(0L) { total, part ->
      if (Long.MAX_VALUE - total < part.fileSize) Long.MAX_VALUE else total + part.fileSize
    }
    val folder = LocalFolder(
      id = MANAGED_FOLDER_ID,
      name = "ShelfDrive offline storage",
      contentUrl = "",
      basePath = storageRoot.absolutePath,
      absolutePath = storageRoot.absolutePath,
      simplePath = "offline",
      storageType = "internal",
      mediaType = "book"
    )
    return OfflinePlanResult.Ready(
      DownloadItem(
        id = jobId,
        libraryItemId = libraryItem.id,
        episodeId = null,
        userMediaProgress = libraryItem.userMediaProgress,
        serverConnectionConfigId = owner.id,
        serverAddress = owner.address,
        serverUserId = owner.userId,
        mediaType = "book",
        itemFolderPath = itemFolder.absolutePath,
        localFolder = folder,
        itemTitle = libraryItem.title,
        itemSubfolder = jobId,
        media = libraryItem.media,
        downloadItemParts = parts
      ),
      expectedBytes
    )
  }

  fun jobId(
    connectionId: String,
    libraryItemId: String,
    jobInstanceToken: String? = null
  ): String {
    val identity = if (jobInstanceToken == null) {
      "$connectionId\u0000$libraryItemId"
    } else {
      "$connectionId\u0000$libraryItemId\u0000$jobInstanceToken"
    }
    return "$MANAGED_JOB_PREFIX${stableId(identity).take(32)}"
  }

  fun localLibraryItemId(item: DownloadItem): String =
    if (isManagedJob(item)) {
      localLibraryItemId(item.serverConnectionConfigId, item.libraryItemId)
    } else {
      "local_${item.libraryItemId}"
    }

  fun localLibraryItemId(connectionId: String, libraryItemId: String): String =
    "local_${stableId("$connectionId\u0000$libraryItemId").take(32)}"

  fun isManagedJob(item: DownloadItem): Boolean =
    item.id.startsWith(MANAGED_JOB_PREFIX) && item.localFolder.id == MANAGED_FOLDER_ID

  fun managedRoot(context: Context): File? {
    val external = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return null
    return File(external, "offline-audiobooks")
  }

  fun isManagedItemPath(context: Context, path: String): Boolean {
    val root = managedRoot(context) ?: return false
    return isInside(root, File(path))
  }

  /** Delete one managed item without ever following a symbolic link. */
  fun deleteManagedItemDirectory(context: Context, path: String): Boolean {
    if (!isManagedItemPath(context, path)) return false
    val directory = File(path)
    if (!directory.exists()) return true
    return runCatching {
      Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          Files.deleteIfExists(file)
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
          if (exc != null) throw exc
          Files.deleteIfExists(dir)
          return FileVisitResult.CONTINUE
        }
      })
      true
    }.getOrDefault(false)
  }

  fun hasStorageFor(context: Context, expectedBytes: Long): Boolean {
    val root = managedRoot(context) ?: return false
    return expectedBytes <= 0L || hasStorageFor(root, expectedBytes)
  }

  internal fun stableId(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }

  private fun matchingAudioFile(track: AudioTrack, audioFiles: List<AudioFile>): AudioFile? {
    val trackPath = track.metadata?.path
    val pathMatches = audioFiles.filter { audioFile ->
      !trackPath.isNullOrBlank() && audioFile.metadata.path == trackPath
    }
    if (pathMatches.size == 1) return pathMatches.single()
    val expectedIndex = track.serverIndex ?: track.index
    return audioFiles.filter { it.index == expectedIndex }.singleOrNull()
  }

  private fun safeExtension(track: AudioTrack, audioFile: AudioFile): String {
    val candidate = track.metadata?.ext
      ?.ifBlank { null }
      ?: audioFile.metadata.ext
    return candidate
      .lowercase(Locale.ROOT)
      .filter(Char::isLetterOrDigit)
      .take(10)
      .ifBlank { "audio" }
  }

  private fun normalizedServerBase(address: String): HttpUrl? {
    val normalized = "${address.trim().trimEnd('/')}/"
    val url = normalized.toHttpUrlOrNull() ?: return null
    return url.takeIf {
      it.scheme == "https" && it.username.isBlank() && it.password.isBlank() &&
        it.query == null && it.fragment == null
    }
  }

  private fun downloadUrl(base: HttpUrl, libraryItemId: String, ino: String): HttpUrl =
    base.newBuilder()
      .addPathSegments("api/items")
      .addPathSegment(libraryItemId)
      .addPathSegment("file")
      .addPathSegment(ino)
      .addPathSegment("download")
      .build()

  private fun hasStorageFor(root: File, expectedBytes: Long): Boolean {
    val base = root.parentFile ?: root
    val available = runCatching { StatFs(base.absolutePath).availableBytes }.getOrDefault(0L)
    val reserve = maxOf(STORAGE_RESERVE_BYTES, expectedBytes / 10L)
    val required = if (Long.MAX_VALUE - expectedBytes < reserve) Long.MAX_VALUE else expectedBytes + reserve
    return available >= required
  }

  private fun isInside(root: File, candidate: File): Boolean = runCatching {
    val rootPath = root.canonicalFile.toPath()
    val candidatePath = candidate.canonicalFile.toPath()
    candidatePath.startsWith(rootPath) && candidatePath != rootPath
  }.getOrDefault(false)
}
