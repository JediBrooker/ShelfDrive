package com.audiobookshelf.app.player

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps oversized MediaBrowser results below the per-node item limit.
 *
 * A result with at most [MAX_CHILDREN] entries passes through untouched. Larger results are
 * replaced by browsable range nodes. Looking up a range node returns either at most
 * [MAX_CHILDREN] original entries or another bounded level of range nodes.
 *
 * Generated IDs contain only fixed-size hashes, numeric routing coordinates, and a keyed
 * integrity tag. When [persistenceDirectory] is supplied, a metadata-only copy of each tree is
 * kept in that app-private directory. This lets a newly-created media service resolve a deep
 * range ID after process death without exposing raw parent IDs or server-provided labels in the
 * generated ID. Persisted trees never contain artwork, extras, media URIs, or bitmaps.
 */
internal class BrowseResultTree(
  private val maxCachedParentTrees: Int = DEFAULT_MAX_CACHED_PARENT_TREES,
  persistenceDirectory: File? = null,
  private val scopeIdProvider: () -> String = { DEFAULT_SCOPE_ID }
) {
  private data class Route(val level: Int, val groupIndex: Int)

  private data class ParsedId(
    val scopeToken: String,
    val ownerToken: String,
    val treeToken: String,
    val route: Route
  )

  private data class CachedNode(
    val scopeToken: String,
    val ownerCacheKey: String,
    val children: List<MediaBrowserCompat.MediaItem>
  )

  private data class CachedRoot(
    val ownerCacheKey: String,
    val generatedIds: Set<String>
  )

  private data class RangeNode(
    val item: MediaBrowserCompat.MediaItem,
    val firstOriginalIndex: Int,
    val lastOriginalIndex: Int
  )

  private data class BuiltTree(
    val rootChildren: List<MediaBrowserCompat.MediaItem>,
    val nodesByRoute: Map<Route, List<MediaBrowserCompat.MediaItem>>
  )

  private val lock = Any()
  private val persistentStore = persistenceDirectory?.let(PersistentStore::open)
  private val signingKey = persistentStore?.signingKey ?: randomKey()
  private val rootsByKey = LinkedHashMap<String, CachedRoot>(16, 0.75f, true)
  private val nodesById = HashMap<String, CachedNode>()

  init {
    require(maxCachedParentTrees > 0) { "maxCachedParentTrees must be positive" }
    persistentStore?.prune()
  }

  /**
   * Atomically replaces any cached descendants of [parentMediaId] and returns the children that
   * should be sent to MediaBrowser. The exact [children] instance is returned when grouping is not
   * needed.
   */
  fun replaceChildren(
    parentMediaId: String,
    children: List<MediaBrowserCompat.MediaItem>
  ): List<MediaBrowserCompat.MediaItem> = synchronized(lock) {
    val scopeToken = scopeToken()
    val ownerToken = token("owner\u0000$scopeToken\u0000$parentMediaId")
    val ownerCacheKey = ownerCacheKey(scopeToken, ownerToken)

    if (children.size <= MAX_CHILDREN) {
      removeOwnerLocked(ownerCacheKey)
      persistentStore?.deleteOwner(scopeToken, ownerToken)
      return@synchronized children
    }

    val treeToken = contentToken(children)
    val builtTree = buildTree(scopeToken, ownerToken, treeToken, children)

    // Do not discard the live tree until its replacement is fully constructed.
    removeOwnerLocked(ownerCacheKey)
    persistentStore?.deleteOwner(scopeToken, ownerToken)
    cacheTreeLocked(scopeToken, ownerToken, treeToken, builtTree.nodesByRoute)
    persistentStore?.writeTree(scopeToken, ownerToken, treeToken, builtTree.nodesByRoute)
    trimToSizeLocked()
    builtTree.rootChildren
  }

  /**
   * Returns children for a valid generated range ID. A cache miss is rehydrated from bounded
   * app-private persistence when available. Malformed, cross-profile, stale, and tampered IDs
   * return null without touching disk.
   */
  fun lookup(generatedMediaId: String): List<MediaBrowserCompat.MediaItem>? = synchronized(lock) {
    val activeScopeToken = scopeToken()
    nodesById[generatedMediaId]?.let { node ->
      if (node.scopeToken != activeScopeToken) return@synchronized null
      rootsByKey[node.ownerCacheKey]
      return@synchronized node.children
    }

    val parsed = parseAndValidate(generatedMediaId) ?: return@synchronized null
    if (parsed.scopeToken != activeScopeToken) return@synchronized null
    val store = persistentStore ?: return@synchronized null
    val persistedNodes = store.readTree(
      parsed.scopeToken,
      parsed.ownerToken,
      parsed.treeToken
    ) ?: return@synchronized null
    if (!persistedNodes.containsKey(parsed.route)) return@synchronized null

    val cacheKey = ownerCacheKey(parsed.scopeToken, parsed.ownerToken)
    removeOwnerLocked(cacheKey)
    cacheTreeLocked(
      parsed.scopeToken,
      parsed.ownerToken,
      parsed.treeToken,
      persistedNodes
    )
    trimToSizeLocked()
    store.touch(parsed.scopeToken, parsed.ownerToken, parsed.treeToken)
    nodesById[generatedMediaId]?.children
  }

  /** True for the reserved range-ID namespace, including stale or tampered candidates. */
  fun isGeneratedIdCandidate(mediaId: String): Boolean = mediaId.startsWith("$ID_VERSION$ID_SEPARATOR")

  /** Drops in-memory references while retaining process-recreation data on disk. */
  fun clearMemory() = synchronized(lock) {
    rootsByKey.clear()
    nodesById.clear()
  }

  /** Drops every in-memory and persisted range tree. The signing key itself is retained. */
  fun clear() = synchronized(lock) {
    clearMemory()
    persistentStore?.clearTrees()
  }

  private fun buildTree(
    scopeToken: String,
    ownerToken: String,
    treeToken: String,
    sourceChildren: List<MediaBrowserCompat.MediaItem>
  ): BuiltTree {
    val builtNodes = LinkedHashMap<Route, List<MediaBrowserCompat.MediaItem>>()
    var level = 0
    var currentLevel = sourceChildren
      .asSequence()
      .chunked(MAX_CHILDREN)
      .mapIndexed { groupIndex, chunk ->
        val first = groupIndex * MAX_CHILDREN
        val last = first + chunk.lastIndex
        createRangeNode(
          scopeToken,
          ownerToken,
          treeToken,
          level,
          groupIndex,
          first,
          last
        ).also {
          builtNodes[Route(level, groupIndex)] = immutableCopy(chunk)
        }
      }
      .toList()

    while (currentLevel.size > MAX_CHILDREN) {
      level += 1
      currentLevel = currentLevel
        .asSequence()
        .chunked(MAX_CHILDREN)
        .mapIndexed { groupIndex, chunk ->
          val first = chunk.first().firstOriginalIndex
          val last = chunk.last().lastOriginalIndex
          createRangeNode(
            scopeToken,
            ownerToken,
            treeToken,
            level,
            groupIndex,
            first,
            last
          ).also {
            builtNodes[Route(level, groupIndex)] = immutableCopy(chunk.map { node -> node.item })
          }
        }
        .toList()
    }

    return BuiltTree(
      rootChildren = immutableCopy(currentLevel.map { it.item }),
      nodesByRoute = builtNodes
    )
  }

  private fun createRangeNode(
    scopeToken: String,
    ownerToken: String,
    treeToken: String,
    level: Int,
    groupIndex: Int,
    firstOriginalIndex: Int,
    lastOriginalIndex: Int
  ): RangeNode {
    val generatedId = generatedId(
      scopeToken,
      ownerToken,
      treeToken,
      Route(level, groupIndex)
    )
    val description = MediaDescriptionCompat.Builder()
      .setMediaId(generatedId)
      .setTitle("Items ${firstOriginalIndex + 1}\u2013${lastOriginalIndex + 1}")
      .build()
    return RangeNode(
      item = MediaBrowserCompat.MediaItem(
        description,
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
      ),
      firstOriginalIndex = firstOriginalIndex,
      lastOriginalIndex = lastOriginalIndex
    )
  }

  private fun cacheTreeLocked(
    scopeToken: String,
    ownerToken: String,
    treeToken: String,
    nodesByRoute: Map<Route, List<MediaBrowserCompat.MediaItem>>
  ) {
    val cacheKey = ownerCacheKey(scopeToken, ownerToken)
    val generatedIds = LinkedHashSet<String>(nodesByRoute.size)
    nodesByRoute.forEach { (route, children) ->
      val id = generatedId(scopeToken, ownerToken, treeToken, route)
      generatedIds += id
      nodesById[id] = CachedNode(scopeToken, cacheKey, immutableCopy(children))
    }
    rootsByKey[cacheKey] = CachedRoot(cacheKey, generatedIds)
  }

  private fun generatedId(
    scopeToken: String,
    ownerToken: String,
    treeToken: String,
    route: Route
  ): String {
    val level = route.level.toString(RADIX)
    val group = route.groupIndex.toString(RADIX)
    val unsigned = listOf(ID_VERSION, scopeToken, ownerToken, treeToken, level, group)
      .joinToString(ID_SEPARATOR)
    val tag = hmac(unsigned).take(TAG_HEX_LENGTH)
    return "$unsigned$ID_SEPARATOR$tag"
  }

  private fun parseAndValidate(generatedId: String): ParsedId? {
    if (generatedId.length !in MIN_GENERATED_ID_LENGTH..MAX_GENERATED_ID_LENGTH) return null
    val parts = generatedId.split(ID_SEPARATOR)
    if (parts.size != ID_PART_COUNT || parts[0] != ID_VERSION) return null
    val scope = parts[1].takeIf(::isToken) ?: return null
    val owner = parts[2].takeIf(::isToken) ?: return null
    val tree = parts[3].takeIf(::isToken) ?: return null
    val level = parseCoordinate(parts[4]) ?: return null
    val group = parseCoordinate(parts[5]) ?: return null
    val suppliedTag = parts[6].takeIf(::isTag) ?: return null
    val unsigned = parts.take(ID_PART_COUNT - 1).joinToString(ID_SEPARATOR)
    val expectedTag = hmac(unsigned).take(TAG_HEX_LENGTH)
    if (!MessageDigest.isEqual(
        suppliedTag.toByteArray(StandardCharsets.US_ASCII),
        expectedTag.toByteArray(StandardCharsets.US_ASCII)
      )) {
      return null
    }
    return ParsedId(scope, owner, tree, Route(level, group))
  }

  private fun parseCoordinate(value: String): Int? {
    if (value.isEmpty() || value.length > MAX_COORDINATE_LENGTH) return null
    if (value.length > 1 && value[0] == '0') return null
    if (!value.all { it in '0'..'9' || it in 'a'..'z' }) return null
    return value.toIntOrNull(RADIX)?.takeIf { it >= 0 }
  }

  private fun isToken(value: String): Boolean =
    value.length == TOKEN_HEX_LENGTH && value.all(::isLowerHex)

  private fun isTag(value: String): Boolean =
    value.length == TAG_HEX_LENGTH && value.all(::isLowerHex)

  private fun isLowerHex(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f'

  private fun trimToSizeLocked() {
    while (rootsByKey.size > maxCachedParentTrees) {
      val eldestKey = rootsByKey.entries.iterator().next().key
      removeOwnerLocked(eldestKey)
    }
  }

  private fun removeOwnerLocked(ownerCacheKey: String) {
    val root = rootsByKey.remove(ownerCacheKey) ?: return
    root.generatedIds.forEach { generatedId ->
      if (nodesById[generatedId]?.ownerCacheKey == root.ownerCacheKey) {
        nodesById.remove(generatedId)
      }
    }
  }

  private fun ownerCacheKey(scopeToken: String, ownerToken: String): String =
    "$scopeToken$ID_SEPARATOR$ownerToken"

  private fun scopeToken(): String = token("scope\u0000${scopeIdProvider()}")

  private fun contentToken(items: List<MediaBrowserCompat.MediaItem>): String {
    val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
    updateInt(digest, items.size)
    items.forEach { item ->
      updateInt(digest, item.flags)
      val mediaId = item.mediaId
      if (mediaId == null) {
        updateInt(digest, -1)
      } else {
        val bytes = mediaId.toByteArray(StandardCharsets.UTF_8)
        updateInt(digest, bytes.size)
        digest.update(bytes)
      }
    }
    return digest.digest().toHex().take(TOKEN_HEX_LENGTH)
  }

  private fun updateInt(digest: MessageDigest, value: Int) {
    digest.update((value ushr 24).toByte())
    digest.update((value ushr 16).toByte())
    digest.update((value ushr 8).toByte())
    digest.update(value.toByte())
  }

  private fun token(value: String): String = MessageDigest
    .getInstance(DIGEST_ALGORITHM)
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .toHex()
    .take(TOKEN_HEX_LENGTH)

  private fun hmac(value: String): String {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(signingKey, HMAC_ALGORITHM))
    return mac.doFinal(value.toByteArray(StandardCharsets.US_ASCII)).toHex()
  }

  private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
      HEX_DIGITS[byte.toInt() and 0x0f]
  }

  private fun <T> immutableCopy(items: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(items))

  private class PersistentStore private constructor(
    private val directory: File,
    val signingKey: ByteArray
  ) {
    fun writeTree(
      scopeToken: String,
      ownerToken: String,
      treeToken: String,
      nodesByRoute: Map<Route, List<MediaBrowserCompat.MediaItem>>
    ) {
      if (nodesByRoute.size > MAX_PERSISTED_NODES) return
      val target = treeFile(scopeToken, ownerToken, treeToken)
      val temporary = File(directory, "${target.name}.tmp")
      try {
        val fileStream = FileOutputStream(temporary)
        val boundedStream = BoundedOutputStream(
          BufferedOutputStream(fileStream),
          MAX_PERSISTED_TREE_BYTES - FILE_TAG_BYTES
        )
        val fileMac = newMac(signingKey)
        val authenticatedStream = MacOutputStream(boundedStream, fileMac)
        DataOutputStream(authenticatedStream).use { output ->
          output.writeInt(FILE_MAGIC)
          output.writeInt(FILE_VERSION)
          writeRequiredString(output, scopeToken, TOKEN_HEX_LENGTH)
          writeRequiredString(output, ownerToken, TOKEN_HEX_LENGTH)
          writeRequiredString(output, treeToken, TOKEN_HEX_LENGTH)
          output.writeInt(nodesByRoute.size)
          nodesByRoute.forEach { (route, children) ->
            if (children.size > MAX_CHILDREN) throw InvalidPersistenceData()
            output.writeInt(route.level)
            output.writeInt(route.groupIndex)
            output.writeInt(children.size)
            children.forEach { item -> writeItem(output, item) }
          }
          output.flush()
          authenticatedStream.finishAuthenticationTag()
          boundedStream.flush()
          fileStream.fd.sync()
        }
        if (target.exists() && !target.delete()) throw InvalidPersistenceData()
        if (!temporary.renameTo(target)) throw InvalidPersistenceData()
        prune()
      } catch (_: Exception) {
        temporary.delete()
      }
    }

    fun readTree(
      scopeToken: String,
      ownerToken: String,
      treeToken: String
    ): Map<Route, List<MediaBrowserCompat.MediaItem>>? {
      val file = treeFile(scopeToken, ownerToken, treeToken)
      if (!file.isFile || file.length() !in (FILE_TAG_BYTES + 1)..MAX_PERSISTED_TREE_BYTES) {
        return null
      }
      return try {
        val fileBytes = BufferedInputStream(file.inputStream()).use { it.readBytes() }
        val payloadSize = fileBytes.size - FILE_TAG_BYTES
        val payload = fileBytes.copyOfRange(0, payloadSize)
        val suppliedTag = fileBytes.copyOfRange(payloadSize, fileBytes.size)
        val expectedTag = newMac(signingKey).doFinal(payload)
        if (!MessageDigest.isEqual(suppliedTag, expectedTag)) {
          file.delete()
          return null
        }

        DataInputStream(ByteArrayInputStream(payload)).use { input ->
          if (input.readInt() != FILE_MAGIC || input.readInt() != FILE_VERSION) {
            throw InvalidPersistenceData()
          }
          if (readRequiredString(input, TOKEN_HEX_LENGTH) != scopeToken ||
            readRequiredString(input, TOKEN_HEX_LENGTH) != ownerToken ||
            readRequiredString(input, TOKEN_HEX_LENGTH) != treeToken
          ) {
            throw InvalidPersistenceData()
          }
          val nodeCount = input.readInt()
          if (nodeCount !in 1..MAX_PERSISTED_NODES) throw InvalidPersistenceData()
          val restored = LinkedHashMap<Route, List<MediaBrowserCompat.MediaItem>>(nodeCount)
          repeat(nodeCount) {
            val level = input.readInt()
            val groupIndex = input.readInt()
            if (level < 0 || groupIndex < 0) throw InvalidPersistenceData()
            val childCount = input.readInt()
            if (childCount !in 1..MAX_CHILDREN) throw InvalidPersistenceData()
            val children = ArrayList<MediaBrowserCompat.MediaItem>(childCount)
            repeat(childCount) { children += readItem(input) }
            if (restored.put(Route(level, groupIndex), immutableList(children)) != null) {
              throw InvalidPersistenceData()
            }
          }
          if (input.available() != 0) throw InvalidPersistenceData()
          restored
        }
      } catch (_: Exception) {
        file.delete()
        null
      }
    }

    fun deleteOwner(scopeToken: String, ownerToken: String) {
      val prefix = "$scopeToken-$ownerToken-"
      directory.listFiles()?.forEach { file ->
        if (file.name.startsWith(prefix) &&
          (file.name.endsWith(TREE_FILE_SUFFIX) || file.name.endsWith(TEMP_FILE_SUFFIX))
        ) {
          file.delete()
        }
      }
    }

    fun touch(scopeToken: String, ownerToken: String, treeToken: String) {
      treeFile(scopeToken, ownerToken, treeToken).setLastModified(System.currentTimeMillis())
    }

    fun clearTrees() {
      directory.listFiles()?.forEach { file ->
        if (file.name.endsWith(TREE_FILE_SUFFIX) || file.name.endsWith(TEMP_FILE_SUFFIX)) {
          file.delete()
        }
      }
    }

    fun prune() {
      val now = System.currentTimeMillis()
      directory.listFiles()
        ?.filter { it.name.endsWith(TEMP_FILE_SUFFIX) }
        ?.forEach(File::delete)
      val newestFirst = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(TREE_FILE_SUFFIX) }
        ?.onEach { file ->
          if (file.lastModified() <= 0L || now - file.lastModified() > MAX_PERSISTED_AGE_MS) {
            file.delete()
          }
        }
        ?.filter(File::exists)
        ?.sortedByDescending(File::lastModified)
        .orEmpty()
      var retainedBytes = 0L
      newestFirst.forEachIndexed { index, file ->
        val nextSize = retainedBytes + file.length()
        if (index >= MAX_PERSISTED_TREES || nextSize > MAX_PERSISTED_TOTAL_BYTES) {
          file.delete()
        } else {
          retainedBytes = nextSize
        }
      }
    }

    private fun treeFile(scopeToken: String, ownerToken: String, treeToken: String): File =
      File(directory, "$scopeToken-$ownerToken-$treeToken$TREE_FILE_SUFFIX")

    companion object {
      fun open(directory: File): PersistentStore? = synchronized(KEY_FILE_LOCK) {
        try {
          if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) return null
          val keyFile = File(directory, KEY_FILE_NAME)
          val existing = keyFile
            .takeIf { it.isFile && it.length() == SIGNING_KEY_BYTES.toLong() }
            ?.readBytes()
          val key = if (existing?.size == SIGNING_KEY_BYTES) {
            existing
          } else {
            // A missing/corrupt key makes every old routing ID unverifiable.
            directory.listFiles()?.forEach { file ->
              if (file.name.endsWith(TREE_FILE_SUFFIX) || file.name.endsWith(TEMP_FILE_SUFFIX)) {
                file.delete()
              }
            }
            randomKey().also { generated ->
              val temporary = File(directory, "$KEY_FILE_NAME.tmp")
              FileOutputStream(temporary).use { output ->
                output.write(generated)
                output.fd.sync()
              }
              if (keyFile.exists()) keyFile.delete()
              if (!temporary.renameTo(keyFile)) throw InvalidPersistenceData()
            }
          }
          PersistentStore(directory, key)
        } catch (_: Exception) {
          null
        }
      }
    }
  }

  private class BoundedOutputStream(
    output: OutputStream,
    private val maximumBytes: Long
  ) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
      ensureCapacity(1)
      out.write(value)
      written += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      ensureCapacity(length)
      out.write(buffer, offset, length)
      written += length
    }

    private fun ensureCapacity(additionalBytes: Int) {
      if (additionalBytes < 0 || written > maximumBytes - additionalBytes) {
        throw InvalidPersistenceData()
      }
    }
  }

  private class MacOutputStream(
    output: OutputStream,
    private val mac: Mac
  ) : FilterOutputStream(output) {
    private var finished = false

    override fun write(value: Int) {
      if (finished) throw InvalidPersistenceData()
      mac.update(value.toByte())
      out.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      if (finished) throw InvalidPersistenceData()
      mac.update(buffer, offset, length)
      out.write(buffer, offset, length)
    }

    fun finishAuthenticationTag() {
      if (finished) return
      finished = true
      out.write(mac.doFinal())
    }
  }

  private class InvalidPersistenceData : Exception()

  companion object {
    const val MAX_CHILDREN = 100
    const val MAX_GENERATED_ID_LENGTH = 160
    private const val MIN_GENERATED_ID_LENGTH = 140
    private const val DEFAULT_MAX_CACHED_PARENT_TREES = 64
    private const val DEFAULT_SCOPE_ID = "default"
    private const val ID_VERSION = "sdr2"
    private const val ID_SEPARATOR = ":"
    private const val ID_PART_COUNT = 7
    private const val TOKEN_HEX_LENGTH = 32
    private const val TAG_HEX_LENGTH = 32
    private const val MAX_COORDINATE_LENGTH = 6
    private const val RADIX = 36
    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SIGNING_KEY_BYTES = 32
    private const val FILE_TAG_BYTES = 32
    private const val FILE_MAGIC = 0x53445254
    private const val FILE_VERSION = 2
    private const val MAX_PERSISTED_NODES = 20_000
    private const val MAX_PERSISTED_TREE_BYTES = 16L * 1024L * 1024L
    private const val MAX_PERSISTED_TOTAL_BYTES = 64L * 1024L * 1024L
    private const val MAX_PERSISTED_TREES = 64
    private const val MAX_PERSISTED_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_MEDIA_ID_BYTES = 4_096
    private const val MAX_TITLE_BYTES = 1_024
    private const val MAX_SUBTITLE_BYTES = 1_024
    private const val MAX_DESCRIPTION_BYTES = 2_048
    private const val KEY_FILE_NAME = "routing.key"
    private const val TREE_FILE_SUFFIX = ".brt"
    private const val TEMP_FILE_SUFFIX = ".tmp"
    private val KEY_FILE_LOCK = Any()
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun randomKey(): ByteArray = ByteArray(SIGNING_KEY_BYTES).also(SecureRandom()::nextBytes)

    private fun newMac(key: ByteArray): Mac = Mac.getInstance(HMAC_ALGORITHM).apply {
      init(SecretKeySpec(key, HMAC_ALGORITHM))
    }

    private fun writeItem(output: DataOutputStream, item: MediaBrowserCompat.MediaItem) {
      output.writeInt(item.flags)
      writeNullableString(output, item.mediaId, MAX_MEDIA_ID_BYTES, truncate = false)
      writeNullableString(output, item.description.title?.toString(), MAX_TITLE_BYTES)
      writeNullableString(output, item.description.subtitle?.toString(), MAX_SUBTITLE_BYTES)
      writeNullableString(output, item.description.description?.toString(), MAX_DESCRIPTION_BYTES)
    }

    private fun readItem(input: DataInputStream): MediaBrowserCompat.MediaItem {
      val flags = input.readInt()
      val allowedFlags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE or
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
      if (flags and allowedFlags.inv() != 0 || flags and allowedFlags == 0) {
        throw InvalidPersistenceData()
      }
      val description = MediaDescriptionCompat.Builder()
        .setMediaId(readNullableString(input, MAX_MEDIA_ID_BYTES))
        .setTitle(readNullableString(input, MAX_TITLE_BYTES))
        .setSubtitle(readNullableString(input, MAX_SUBTITLE_BYTES))
        .setDescription(readNullableString(input, MAX_DESCRIPTION_BYTES))
        .build()
      return MediaBrowserCompat.MediaItem(description, flags)
    }

    private fun writeRequiredString(
      output: DataOutputStream,
      value: String,
      exactBytes: Int
    ) {
      val bytes = value.toByteArray(StandardCharsets.US_ASCII)
      if (bytes.size != exactBytes) throw InvalidPersistenceData()
      output.writeInt(bytes.size)
      output.write(bytes)
    }

    private fun readRequiredString(input: DataInputStream, exactBytes: Int): String {
      val length = input.readInt()
      if (length != exactBytes) throw InvalidPersistenceData()
      return ByteArray(length).also(input::readFully).toString(StandardCharsets.US_ASCII)
    }

    private fun writeNullableString(
      output: DataOutputStream,
      value: String?,
      maxBytes: Int,
      truncate: Boolean = true
    ) {
      if (value == null) {
        output.writeInt(-1)
        return
      }
      val bytes = boundedUtf8Bytes(value, maxBytes, truncate)
      output.writeInt(bytes.size)
      output.write(bytes)
    }

    private fun boundedUtf8Bytes(value: String, maxBytes: Int, truncate: Boolean): ByteArray {
      // UTF-8 requires at least one byte per UTF-16 code unit, so this rejects an oversized
      // non-truncatable identifier before allocating another attacker-sized byte array.
      if (!truncate && value.length > maxBytes) throw InvalidPersistenceData()
      if (value.length <= maxBytes) {
        val direct = value.toByteArray(StandardCharsets.UTF_8)
        if (direct.size <= maxBytes) return direct
        if (!truncate) throw InvalidPersistenceData()
      }
      val output = ByteArrayOutputStream(maxBytes)
      var index = 0
      while (index < value.length) {
        val codePoint = Character.codePointAt(value, index)
        val characterCount = Character.charCount(codePoint)
        val encoded = value.substring(index, index + characterCount)
          .toByteArray(StandardCharsets.UTF_8)
        if (output.size() + encoded.size > maxBytes) break
        output.write(encoded)
        index += characterCount
      }
      return output.toByteArray()
    }

    private fun readNullableString(input: DataInputStream, maxBytes: Int): String? {
      val length = input.readInt()
      if (length == -1) return null
      if (length !in 0..maxBytes) throw InvalidPersistenceData()
      return ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun <T> immutableList(items: Collection<T>): List<T> =
      Collections.unmodifiableList(ArrayList(items))
  }
}

/** Bounded, deduplicated set of deep pages that need a second post-enrichment notification. */
internal class PendingBrowseRestoreTracker(
  private val maximumEntries: Int = 32,
  private val maximumIdLength: Int = 512
) {
  private val lock = Any()
  private val parentIds = LinkedHashSet<String>()

  init {
    require(maximumEntries > 0) { "maximumEntries must be positive" }
    require(maximumIdLength > 0) { "maximumIdLength must be positive" }
  }

  fun remember(parentMediaId: String) = synchronized(lock) {
    if (parentMediaId.isEmpty() || parentMediaId.length > maximumIdLength) return@synchronized
    // Reinsert an existing value at the warm end before applying the bound.
    parentIds.remove(parentMediaId)
    parentIds += parentMediaId
    while (parentIds.size > maximumEntries) {
      parentIds.remove(parentIds.first())
    }
  }

  fun snapshot(): List<String> = synchronized(lock) { parentIds.toList() }

  fun clear() = synchronized(lock) { parentIds.clear() }
}
