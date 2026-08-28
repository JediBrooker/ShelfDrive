package com.audiobookshelf.app.player

import java.util.LinkedHashSet

/**
 * Bounded record of media-browser packages whose package/UID pair was accepted by
 * [MediaBrowserCallerValidator].
 *
 * A playing item can expose app-private FileProvider artwork through MediaSession metadata.
 * Every accepted cross-process browser needs a read grant before that metadata is published.
 * Keeping the registry bounded prevents an unexpectedly large system image from retaining an
 * unbounded number of package names for the lifetime of the service.
 */
internal class ValidatedBrowserPackageRegistry(
  private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
  private val maximumPackageNameLength: Int = DEFAULT_MAXIMUM_PACKAGE_NAME_LENGTH
) {
  private val lock = Any()
  private val packageNames = LinkedHashSet<String>()

  init {
    require(maximumEntries > 0) { "maximumEntries must be positive" }
    require(maximumPackageNameLength > 0) { "maximumPackageNameLength must be positive" }
  }

  fun record(packageName: String) = synchronized(lock) {
    if (!isSafePackageName(packageName)) return@synchronized
    // Reinsertion makes the bounded set least-recently-used without retaining caller objects.
    packageNames.remove(packageName)
    packageNames += packageName
    while (packageNames.size > maximumEntries) {
      packageNames.remove(packageNames.first())
    }
  }

  fun snapshot(): List<String> = synchronized(lock) { packageNames.toList() }

  private fun isSafePackageName(packageName: String): Boolean =
    packageName.isNotBlank() &&
      packageName.length <= maximumPackageNameLength &&
      packageName.none { character ->
        character.isWhitespace() || character.isISOControl() || character == '/' || character == '\\'
      }

  companion object {
    private const val DEFAULT_MAXIMUM_ENTRIES = 64
    private const val DEFAULT_MAXIMUM_PACKAGE_NAME_LENGTH = 255
  }
}
