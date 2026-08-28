package com.audiobookshelf.app.player

/** Allows one bounded artwork warm-up for each browse parent and server scope. */
internal class BrowseCoverPrefetchGate {
  private val claimedParents = HashSet<String>()

  fun claim(namespace: String, parentMediaId: String): Boolean = synchronized(claimedParents) {
    claimedParents.add("$namespace\u0000$parentMediaId")
  }

  fun clear() = synchronized(claimedParents) {
    claimedParents.clear()
  }
}
