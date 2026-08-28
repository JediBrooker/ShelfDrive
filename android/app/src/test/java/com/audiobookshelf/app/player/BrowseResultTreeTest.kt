package com.audiobookshelf.app.player

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowseResultTreeTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `one hundred or fewer items pass through unchanged`() {
    val tree = BrowseResultTree()
    val children = mediaItems(100)

    val result = tree.replaceChildren("library", children)

    assertSame(children, result)
    assertNull(tree.lookup(children.first().mediaId!!))
  }

  @Test
  fun `one hundred and one items become two bounded artwork-free ranges`() {
    val tree = BrowseResultTree()
    val children = mediaItems(101)

    val ranges = tree.replaceChildren("library", children)

    assertEquals(2, ranges.size)
    assertEquals("Items 1\u2013100", ranges[0].description.title.toString())
    assertEquals("Items 101\u2013101", ranges[1].description.title.toString())
    assertSyntheticRanges(ranges)
    assertEquals(children.take(100), tree.lookup(ranges[0].mediaId!!))
    assertEquals(children.takeLast(1), tree.lookup(ranges[1].mediaId!!))
    assertEquals(children, flattenOriginals(tree, ranges))
  }

  @Test
  fun `one thousand items preserve order behind ten ranges`() {
    val tree = BrowseResultTree()
    val children = mediaItems(1_000)

    val ranges = tree.replaceChildren("authors", children)

    assertEquals(10, ranges.size)
    assertSyntheticRanges(ranges)
    ranges.forEach { assertTrue(tree.lookup(it.mediaId!!)!!.size <= BrowseResultTree.MAX_CHILDREN) }
    assertEquals(children, flattenOriginals(tree, ranges))
  }

  @Test
  fun `generated ids are opaque and deterministic for the same parent`() {
    val tree = BrowseResultTree()
    val children = mediaItems(1_000)

    val firstIds = tree.replaceChildren("private-parent-id", children).map { it.mediaId!! }
    val secondIds = tree.replaceChildren("private-parent-id", children).map { it.mediaId!! }

    assertEquals(firstIds, secondIds)
    firstIds.forEach { generatedId ->
      assertTrue(!generatedId.contains("private-parent-id"))
      assertTrue(generatedId.length <= BrowseResultTree.MAX_GENERATED_ID_LENGTH)
    }
  }

  @Test
  fun `more than ten thousand items create bounded nested levels`() {
    val tree = BrowseResultTree()
    val children = mediaItems(10_101)

    val rootRanges = tree.replaceChildren("very-large-library", children)

    assertEquals(2, rootRanges.size)
    assertSyntheticRanges(rootRanges)
    val firstNestedLevel = tree.lookup(rootRanges.first().mediaId!!)!!
    assertEquals(100, firstNestedLevel.size)
    assertSyntheticRanges(firstNestedLevel)
    assertEquals(100, tree.lookup(firstNestedLevel.first().mediaId!!)!!.size)
    assertEveryGeneratedLevelIsBounded(tree, rootRanges)
    assertEquals(children, flattenOriginals(tree, rootRanges))
  }

  @Test
  fun `direct range lookup for one hundred and one items survives a fresh instance`() {
    val directory = temporaryFolder.newFolder("restore-101")
    val children = mediaItems(101)
    val firstTree = persistentTree(directory)
    val root = firstTree.replaceChildren("library", children)
    val directId = root.last().mediaId!!

    val restored = persistentTree(directory).lookup(directId)

    assertMediaItemsEquivalent(children.takeLast(1), restored!!)
  }

  @Test
  fun `direct range lookup for one thousand items survives a fresh instance`() {
    val directory = temporaryFolder.newFolder("restore-1000")
    val children = mediaItems(1_000)
    val firstTree = persistentTree(directory)
    val root = firstTree.replaceChildren("library", children)
    val directId = root[5].mediaId!!

    val restored = persistentTree(directory).lookup(directId)

    assertMediaItemsEquivalent(children.subList(500, 600), restored!!)
  }

  @Test
  fun `direct deeply nested lookup survives a fresh instance`() {
    val directory = temporaryFolder.newFolder("restore-10101")
    val children = mediaItems(10_101)
    val firstTree = persistentTree(directory)
    val root = firstTree.replaceChildren("very-large-library", children)
    val nestedRanges = firstTree.lookup(root.first().mediaId!!)!!
    val directDeepId = nestedRanges[37].mediaId!!

    val freshTree = persistentTree(directory)
    val restored = freshTree.lookup(directDeepId)

    assertMediaItemsEquivalent(children.subList(3_700, 3_800), restored!!)
    assertEquals(
      children.map { it.mediaId },
      flattenOriginals(freshTree, root).map { it.mediaId }
    )
  }

  @Test
  fun `malformed tampered and cross-scope ids fail closed after recreation`() {
    val directory = temporaryFolder.newFolder("tamper")
    val firstTree = persistentTree(directory)
    val validId = firstTree.replaceChildren("private-parent", mediaItems(101)).first().mediaId!!
    val replacement = if (validId.last() == '0') '1' else '0'
    val tamperedId = validId.dropLast(1) + replacement
    val malformedIds = listOf(
      "",
      "sdr2",
      "$validId:extra",
      validId.replaceFirst(':', ';'),
      "sdr2:${"a".repeat(BrowseResultTree.MAX_GENERATED_ID_LENGTH)}"
    )

    val freshTree = persistentTree(directory)

    assertNull(freshTree.lookup(tamperedId))
    assertTrue(freshTree.isGeneratedIdCandidate(tamperedId))
    malformedIds.forEach { assertNull(freshTree.lookup(it)) }
    assertTrue(!freshTree.isGeneratedIdCandidate("ordinary-server-item"))
    assertNull(persistentTree(directory, scope = "different-profile").lookup(validId))
    assertTrue(freshTree.lookup(validId)!!.isNotEmpty())
  }

  @Test
  fun `cached ids fail closed immediately when the active scope changes`() {
    var activeScope = "profile-one"
    val tree = BrowseResultTree(scopeIdProvider = { activeScope })
    val generatedId = tree.replaceChildren("library", mediaItems(101)).first().mediaId!!

    activeScope = "profile-two"

    assertNull(tree.lookup(generatedId))
  }

  @Test
  fun `persisted restore strips artwork extras media uri and bounds display text`() {
    val directory = temporaryFolder.newFolder("metadata-bounds")
    val children = mediaItems(101)
    val unsafeDescription = MediaDescriptionCompat.Builder()
      .setMediaId("item-0")
      .setTitle("x".repeat(5_000))
      .setIconBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
      .setIconUri(Uri.parse("https://server.example/private-cover"))
      .setMediaUri(Uri.parse("https://server.example/private-audio"))
      .setExtras(Bundle().apply { putString("private", "value") })
      .build()
    children[0] = MediaBrowserCompat.MediaItem(
      unsafeDescription,
      MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )
    val firstTree = persistentTree(directory)
    val directId = firstTree.replaceChildren("library", children).first().mediaId!!

    val restored = persistentTree(directory).lookup(directId)!!.first().description

    assertTrue(restored.title!!.toString().toByteArray().size <= 1_024)
    assertNull(restored.iconBitmap)
    assertNull(restored.iconUri)
    assertNull(restored.mediaUri)
    assertNull(restored.extras)
  }

  @Test
  fun `full clear removes persisted descendants while memory clear permits rehydration`() {
    val directory = temporaryFolder.newFolder("clear-persistence")
    val tree = persistentTree(directory)
    val generatedId = tree.replaceChildren("library", mediaItems(101)).first().mediaId!!

    tree.clearMemory()
    assertTrue(tree.lookup(generatedId)!!.isNotEmpty())
    tree.clear()

    assertNull(persistentTree(directory).lookup(generatedId))
  }

  @Test
  fun `refresh to a small result removes the prior persisted tree`() {
    val directory = temporaryFolder.newFolder("refresh-persistence")
    val tree = persistentTree(directory)
    val staleId = tree.replaceChildren("library", mediaItems(1_000)).first().mediaId!!

    tree.replaceChildren("library", mediaItems(10, idPrefix = "replacement"))

    assertNull(persistentTree(directory).lookup(staleId))
  }

  @Test
  fun `refresh removes every prior descendant when result no longer needs grouping`() {
    val tree = BrowseResultTree()
    val oldRoot = tree.replaceChildren("changing-library", mediaItems(10_101))
    val oldGeneratedIds = collectGeneratedIds(tree, oldRoot)

    val replacement = mediaItems(12, idPrefix = "replacement")
    val refreshed = tree.replaceChildren("changing-library", replacement)

    assertSame(replacement, refreshed)
    oldGeneratedIds.forEach { assertNull(tree.lookup(it)) }
  }

  @Test
  fun `clear removes descendants from every parent`() {
    val tree = BrowseResultTree()
    val firstRoot = tree.replaceChildren("first", mediaItems(101, "first"))
    val secondRoot = tree.replaceChildren("second", mediaItems(1_000, "second"))
    val generatedIds = collectGeneratedIds(tree, firstRoot) + collectGeneratedIds(tree, secondRoot)

    tree.clear()

    generatedIds.forEach { assertNull(tree.lookup(it)) }
  }

  @Test
  fun `least recently used parent trees are evicted at the configured bound`() {
    val tree = BrowseResultTree(maxCachedParentTrees = 2)
    val firstRoot = tree.replaceChildren("first", mediaItems(101, "first"))
    val secondRoot = tree.replaceChildren("second", mediaItems(101, "second"))
    // Lookup makes the first tree newer than the second tree.
    assertTrue(tree.lookup(firstRoot.first().mediaId!!)!!.isNotEmpty())
    tree.replaceChildren("third", mediaItems(101, "third"))

    assertTrue(tree.lookup(firstRoot.first().mediaId!!)!!.isNotEmpty())
    assertNull(tree.lookup(secondRoot.first().mediaId!!))
  }

  @Test
  fun `concurrent replacement and lookup do not corrupt independent trees`() {
    val tree = BrowseResultTree(maxCachedParentTrees = 16)
    val executor = Executors.newFixedThreadPool(8)
    try {
      val tasks = (0 until 8).map { parentIndex ->
        Callable {
          val children = mediaItems(1_000 + parentIndex, "parent-$parentIndex")
          val root = tree.replaceChildren("parent-$parentIndex", children)
          children to root
        }
      }

      val results = executor.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }

      results.forEach { (expected, root) ->
        assertEquals(expected, flattenOriginals(tree, root))
      }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `pending cold restore tracker is bounded deduplicated and rejects oversized ids`() {
    val tracker = PendingBrowseRestoreTracker(maximumEntries = 3, maximumIdLength = 8)

    tracker.remember("first")
    tracker.remember("second")
    tracker.remember("first")
    tracker.remember("third")
    tracker.remember("fourth")
    tracker.remember("oversized")

    assertEquals(listOf("third", "fourth"), tracker.snapshot().takeLast(2))
    assertEquals(3, tracker.snapshot().size)
    assertTrue("first" in tracker.snapshot())
    assertTrue("second" !in tracker.snapshot())
    assertTrue("oversized" !in tracker.snapshot())
    tracker.clear()
    assertTrue(tracker.snapshot().isEmpty())
  }

  private fun mediaItems(
    count: Int,
    idPrefix: String = "item"
  ): MutableList<MediaBrowserCompat.MediaItem> = MutableList(count) { index ->
    val description = MediaDescriptionCompat.Builder()
      .setMediaId("$idPrefix-$index")
      .setTitle("Title $index")
      .build()
    MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
  }

  private fun persistentTree(
    directory: File,
    scope: String = "profile-one"
  ): BrowseResultTree = BrowseResultTree(
    persistenceDirectory = directory,
    scopeIdProvider = { scope }
  )

  private fun assertMediaItemsEquivalent(
    expected: List<MediaBrowserCompat.MediaItem>,
    actual: List<MediaBrowserCompat.MediaItem>
  ) {
    assertEquals(expected.map { it.mediaId }, actual.map { it.mediaId })
    assertEquals(expected.map { it.flags }, actual.map { it.flags })
    assertEquals(
      expected.map { it.description.title?.toString() },
      actual.map { it.description.title?.toString() }
    )
  }

  private fun assertSyntheticRanges(items: List<MediaBrowserCompat.MediaItem>) {
    items.forEach { item ->
      assertEquals(MediaBrowserCompat.MediaItem.FLAG_BROWSABLE, item.flags)
      assertTrue(item.mediaId!!.length <= BrowseResultTree.MAX_GENERATED_ID_LENGTH)
      assertTrue(item.description.title!!.length <= 32)
      assertNull(item.description.iconBitmap)
      assertNull(item.description.iconUri)
      assertNull(item.description.extras)
    }
  }

  private fun assertEveryGeneratedLevelIsBounded(
    tree: BrowseResultTree,
    children: List<MediaBrowserCompat.MediaItem>
  ) {
    assertTrue(children.size <= BrowseResultTree.MAX_CHILDREN)
    children.forEach { child ->
      tree.lookup(child.mediaId!!)?.let { descendants ->
        assertEveryGeneratedLevelIsBounded(tree, descendants)
      }
    }
  }

  private fun flattenOriginals(
    tree: BrowseResultTree,
    children: List<MediaBrowserCompat.MediaItem>
  ): List<MediaBrowserCompat.MediaItem> = buildList {
    children.forEach { child ->
      val descendants = tree.lookup(child.mediaId!!)
      if (descendants == null) {
        add(child)
      } else {
        addAll(flattenOriginals(tree, descendants))
      }
    }
  }

  private fun collectGeneratedIds(
    tree: BrowseResultTree,
    children: List<MediaBrowserCompat.MediaItem>
  ): Set<String> = buildSet {
    children.forEach { child ->
      val descendants = tree.lookup(child.mediaId!!)
      if (descendants != null) {
        add(child.mediaId!!)
        addAll(collectGeneratedIds(tree, descendants))
      }
    }
  }
}
