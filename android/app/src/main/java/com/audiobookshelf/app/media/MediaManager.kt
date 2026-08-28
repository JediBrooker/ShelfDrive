package com.audiobookshelf.app.media

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.util.SafeJsonObject as JSObject
import java.util.*
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Keep automatic failover bounded so a detached AAOS root cannot spend an
 * unbounded number of network timeouts probing stale profiles. The selected
 * profile is always tried exactly once, followed by at most one distinct
 * fallback; users can explicitly select any remaining profile in Settings.
 */
internal fun orderedServerConnectionCandidates(
  selected: ServerConnectionConfig?,
  saved: List<ServerConnectionConfig>,
  maxAlternatives: Int = 1
): List<ServerConnectionConfig> {
  val selectedId = selected?.id
  val alternatives = saved
    .asSequence()
    .filter { it.id != selectedId }
    .distinctBy { it.id }
    .take(maxAlternatives.coerceAtLeast(0))
    .toList()
  return listOfNotNull(selected) + alternatives
}

class MediaManager(private var apiHandler: ApiHandler, var ctx: Context) {
  val tag = "MediaManager"

  // Server callbacks complete on different OkHttp threads. Keying by item ID
  // makes registration and playback lookup atomic while personalized shelves,
  // progress, browse, and search are loading concurrently.
  private val serverLibraryItems = ConcurrentHashMap<String, LibraryItem>()
  private val cacheGeneration = AtomicLong(0L)
  private val cacheLock = Any()

  private val cachedLibraryAuthors = ConcurrentHashMap<String, MutableMap<String, LibraryAuthorItem>>()
  private val cachedLibraryAuthorItems = ConcurrentHashMap<String, MutableMap<String, List<LibraryItem>>>()
  private val cachedLibraryAuthorSeriesItems = ConcurrentHashMap<String, MutableMap<String, List<LibraryItem>>>()
  private val cachedLibrarySeries = ConcurrentHashMap<String, List<LibrarySeriesItem>>()
  private val cachedLibrarySeriesItem = ConcurrentHashMap<String, MutableMap<String, List<LibraryItem>>>()
  private val cachedLibraryCollections = ConcurrentHashMap<String, MutableMap<String, LibraryCollection>>()
  private val cachedLibraryRecentShelves = ConcurrentHashMap<String, MutableList<LibraryShelfType>>()
  private val cachedLibraryDiscovery = ConcurrentHashMap<String, MutableList<LibraryItem>>()
  private val cachedLibraryBooks = ConcurrentHashMap<String, List<LibraryItem>>()
  private val cachedLibraryPodcasts = ConcurrentHashMap<String, MutableMap<String, LibraryItem>>()
  private val isLibraryPodcastsCached = ConcurrentHashMap<String, Boolean>()
  @Volatile var allLibraryPersonalizationsDone : Boolean = false
  private var libraryPersonalizationsDone : Int = 0

  private var selectedPodcast:Podcast? = null
  private var selectedLibraryItemId:String? = null
  private val podcastEpisodeLibraryItemMap = ConcurrentHashMap<String, LibraryItemWithEpisode>()
  private var serverConfigIdUsed:String? = null
  /**
   * The exact saved-account lifetime that owns every remote object in this
   * cache generation. A connection ID alone is insufficient because removing
   * and re-adding the same server/username produces the same deterministic ID.
   */
  private var serverConnectionLeaseUsed: ConnectionLease? = null
  private var serverConfigLastPing:Long = 0L
  @Volatile var serverUserMediaProgress:MutableList<MediaProgress> = mutableListOf()
  @Volatile var serverItemsInProgress = listOf<ItemInProgress>()
  @Volatile var serverLibraries = listOf<Library>()

  var userSettingsPlaybackRate:Float? = null

  private fun getLocalDownloadForCurrentServer(libraryItemId: String): LocalLibraryItem? =
    DeviceManager.dbManager.getLocalLibraryItemByLId(
      libraryItemId,
      DeviceManager.serverConnectionConfigId
    )

  fun getIsLibrary(id:String) : Boolean {
    return serverLibraries.find { it.id == id } != null
  }

  /**
   * Check if there is discovery shelf for [libraryId]
   * If personalized shelves are not yet populated for library then populate
   *
   */
  fun getHasDiscovery(libraryId: String) : Boolean {
    val discovery = cachedLibraryDiscovery[libraryId]
    if (discovery != null) {
      return discovery.isNotEmpty()
    } else {
      populatePersonalizedDataForLibrary(libraryId){}
    }
    return false
  }

  fun getLibrary(id:String) : Library? {
    return serverLibraries.find { it.id == id }
  }

  /**
   * Add [libraryItem] to [serverLibraryItems] if it is not already added
   */
  private fun addServerLibrary(libraryItem: LibraryItem) {
    serverLibraryItems.putIfAbsent(libraryItem.id, libraryItem)
  }

  fun getSavedPlaybackRate():Float {
    if (userSettingsPlaybackRate != null) {
      return normalizedPlaybackRate(userSettingsPlaybackRate)
    }

    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          if (userSettings.has("playbackRate")) {
            userSettingsPlaybackRate = normalizedPlaybackRate(
              userSettings.getDouble("playbackRate").toFloat()
            )
            return userSettingsPlaybackRate ?: 1f
          }
        } catch(je:JSONException) {
          Log.e(tag, "Failed to parse userSettings JSON ${je.localizedMessage}")
        }
      }
    }
    return 1f
  }

  fun setSavedPlaybackRate(newRate: Float) {
    val safeRate = normalizedPlaybackRate(newRate)
    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    val sharedPrefEditor = sharedPrefs.edit()
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          // toString().toDouble() to prevent float conversion issues (ex 1.2f becomes 1.2000000476837158d)
          userSettings.put("playbackRate", safeRate.toString().toDouble())
          sharedPrefEditor.putString("userSettings", userSettings.toString())
          sharedPrefEditor.apply()
          userSettingsPlaybackRate = safeRate
          Log.d(tag, "Saved userSettings JSON from Android Auto with playbackRate=$safeRate")
        } catch(je:JSONException) {
          Log.e(tag, "Failed to save userSettings JSON ${je.localizedMessage}")
        }
      } else {
        // Not sure if this is the best place for this, but if a user has not changed any user settings in the app
        // the object will not exist yet, could be moved to a centralized place or created on first app load
        val userSettings = JSONObject()
        userSettings.put("playbackRate", safeRate.toString().toDouble())
        sharedPrefEditor.putString("userSettings", userSettings.toString())
        sharedPrefEditor.apply()
        userSettingsPlaybackRate = safeRate
        Log.d(tag, "Created and saved userSettings JSON from Android Auto with playbackRate=$safeRate")
      }
    }
  }

  private fun normalizedPlaybackRate(rate: Float?): Float =
    rate?.takeIf { it.isFinite() && it > 0f && it <= 5f } ?: 1f

  fun checkResetServerItems(forceReset: Boolean = false):Boolean {
    // When opening android auto need to check if still connected to server
    //   and reset any server data already set
    val serverConnConfig = if (DeviceManager.isConnectedToServer) {
      DeviceManager.serverConnectionConfig
    } else {
      DeviceManager.getLastServerConnectionConfig()
    }

    if (forceReset || !DeviceManager.isConnectedToServer || !DeviceManager.checkConnectivity(ctx) || serverConnConfig == null || serverConnConfig.id != serverConfigIdUsed) {
      synchronized(cacheLock) {
        cacheGeneration.incrementAndGet()
        podcastEpisodeLibraryItemMap.clear()
        serverLibraries = listOf()
        serverLibraryItems.clear()
        serverConfigIdUsed = null
        serverConnectionLeaseUsed = null
        serverConfigLastPing = 0L
        serverUserMediaProgress = mutableListOf()
        cachedLibraryAuthors.clear()
        cachedLibraryAuthorItems.clear()
        cachedLibraryAuthorSeriesItems.clear()
        cachedLibrarySeries.clear()
        cachedLibrarySeriesItem.clear()
        cachedLibraryCollections.clear()
        cachedLibraryRecentShelves.clear()
        cachedLibraryDiscovery.clear()
        cachedLibraryBooks.clear()
        cachedLibraryPodcasts.clear()
        isLibraryPodcastsCached.clear()
        serverItemsInProgress = listOf()
        allLibraryPersonalizationsDone = false
        libraryPersonalizationsDone = 0
      }
      return true
    }
    return false
  }

  private fun loadItemsInProgressForAllLibraries(
    expectedGeneration: Long = cacheGeneration.get(),
    cb: (List<ItemInProgress>) -> Unit
  ) {
    val cachedItems = synchronized(cacheLock) {
      if (expectedGeneration == cacheGeneration.get()) serverItemsInProgress else emptyList()
    }
    if (cachedItems.isNotEmpty()) {
      cb(cachedItems)
    } else {
      apiHandler.getAllItemsInProgress { itemsInProgress ->
        val filteredItems = itemsInProgress.filter {
          (it.libraryItemWrapper as? LibraryItem)?.checkHasTracks() == true
        }
        val committedItems = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) {
            emptyList()
          } else {
            serverItemsInProgress = filteredItems
            serverItemsInProgress
          }
        }
        cb(committedItems)
      }
    }
  }

  /**
   * Load personalized shelves from server for all libraries.
   * [cb] resolves when all libraries are processed
   */
  fun populatePersonalizedDataForAllLibraries(cb: () -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    val libraries = synchronized(cacheLock) {
      if (expectedGeneration == cacheGeneration.get()) serverLibraries else emptyList()
    }
    val remaining = AtomicInteger(libraries.size)
    if (remaining.get() == 0) {
      cb()
      return
    }

    libraries.forEach { lib ->
      Log.d(tag, "Loading personalization for library ${lib.name}")
      populatePersonalizedDataForLibrary(lib.id, expectedGeneration) {
        Log.d(tag, "Loaded personalization for library ${lib.name}")
        if (remaining.decrementAndGet() == 0) {
          val committed = synchronized(cacheLock) {
            if (expectedGeneration != cacheGeneration.get()) {
              false
            } else {
              allLibraryPersonalizationsDone = true
              true
            }
          }
          if (committed) {
            Log.d(tag, "Finished loading all library personalization data")
          }
          cb()
        }
      }
    }
  }

  /**
   * Get personalized shelves from server for selected [libraryId].
   * Populates [cachedLibraryRecentShelves] and [cachedLibraryDiscovery].
   */
  private fun populatePersonalizedDataForLibrary(
    libraryId: String,
    expectedGeneration: Long = cacheGeneration.get(),
    cb: () -> Unit
  ) {
    apiHandler.getLibraryPersonalized(libraryId) { shelves ->
      Log.d(tag, "populatePersonalizedDataForLibrary $libraryId")
      if (shelves === null) {
        cb()
        return@getLibraryPersonalized
      }
      val podcastItemsToLoad = mutableSetOf<Pair<String, String>>()
      val accepted = synchronized(cacheLock) {
        if (expectedGeneration != cacheGeneration.get()) {
          false
        } else {
          shelves.forEach shelfLoop@ { shelf ->
            Log.d(tag, "$shelf")
            if (shelf.type == "book") {
              val bookShelf = shelf as LibraryShelfBookEntity
              // Every book exposed by a personalized shelf must also be resolvable
              // when Car Media later sends its mediaId to the active session callback.
              bookShelf.entities
                .orEmpty()
                .filter { item -> item.checkHasTracks() }
                .forEach { item -> addServerLibrary(item) }

              if (shelf.id == "continue-listening") return@shelfLoop
              else if (shelf.id == "listen-again") return@shelfLoop
              else if (shelf.id == "recently-added") {
                if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                  cachedLibraryRecentShelves[libraryId] = mutableListOf()
                }
                if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                  cachedLibraryRecentShelves[libraryId]!!.add(shelf)
                }
              }
              else if (shelf.id == "discover") {
                if (!cachedLibraryDiscovery.containsKey(libraryId)) {
                  cachedLibraryDiscovery[libraryId] = mutableListOf()
                }
                bookShelf.entities?.forEach {
                  cachedLibraryDiscovery[libraryId]!!.add(it)
                }
              }
              else if (shelf.id == "continue-reading") return@shelfLoop
              else if (shelf.id == "continue-series") return@shelfLoop
            } else if (shelf.type == "series") {
              if (shelf.id == "recent-series") {
                if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                  cachedLibraryRecentShelves[libraryId] = mutableListOf()
                }
                if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                  cachedLibraryRecentShelves[libraryId]!!.add(shelf)
                }
              }
            } else if (shelf.type == "episode") {
              if (shelf.id == "continue-listening") return@shelfLoop
              else if (shelf.id == "listen-again") return@shelfLoop
              else if (shelf.id == "newest-episodes") {
                if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                  cachedLibraryRecentShelves[libraryId] = mutableListOf()
                }
                if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                  cachedLibraryRecentShelves[libraryId]!!.add(shelf)
                }

                (shelf as LibraryShelfEpisodeEntity).entities?.forEach { libraryItem ->
                  podcastItemsToLoad.add(Pair(libraryItem.libraryId, libraryItem.id))
                }
              }
            } else if (shelf.type == "podcast") {
              if (shelf.id == "recently-added"){
                if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                  cachedLibraryRecentShelves[libraryId] = mutableListOf()
                }
                if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                  cachedLibraryRecentShelves[libraryId]!!.add(shelf)
                }
              }
              else if (shelf.id == "discover"){
                return@shelfLoop
              }
            } else if (shelf.type =="authors") {
              if (shelf.id == "newest-authors") {
                if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
                  cachedLibraryRecentShelves[libraryId] = mutableListOf()
                }
                if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
                  cachedLibraryRecentShelves[libraryId]!!.add(shelf)
                }
              }
            }
          }
          true
        }
      }
      if (accepted) {
        podcastItemsToLoad.forEach { (podcastLibraryId, podcastItemId) ->
          loadPodcastItem(podcastLibraryId, podcastItemId, expectedGeneration) {}
        }
        Log.d(tag, "populatePersonalizedDataForLibrary $libraryId DONE")
      }
      cb()
    }
  }

  /**
   * Returns podcasts for selected library.
   * If data is not found from local cache it is loaded from server
   */
  fun loadLibraryBooksWithAudio(libraryId: String, cb: (List<LibraryItem>) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    val cached = cachedLibraryBooks[libraryId]
    if (cached != null) {
      cb(cached)
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        val items = libraryItems.filter { it.checkHasTracks() }
            .sortedBy { it.title?.lowercase() }
        val committed = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) emptyList()
          else items.also { current ->
            cachedLibraryBooks[libraryId] = current
            current.forEach(::addServerLibrary)
          }
        }
        cb(committed)
      }
    }
  }

  fun loadLibraryPodcasts(libraryId:String, cb: (List<LibraryItem>?) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    // Without this there is possibility that only recent podcasts get loaded
    // Loading recent podcasts will also create cachedLibraryPodcasts entry for library
    if (!isLibraryPodcastsCached.containsKey(libraryId)) {
      isLibraryPodcastsCached[libraryId] = false
    }
    // Ensure that there is map for library
    cachedLibraryPodcasts.putIfAbsent(libraryId, ConcurrentHashMap())
    if (isLibraryPodcastsCached.getOrElse(libraryId) {false}) {
      Log.d(tag, "loadLibraryPodcasts: Found from cache: $libraryId")
      cb(cachedLibraryPodcasts[libraryId]?.values
        ?.filter { it.media is Podcast }
        ?.sortedBy { libraryItem ->
          ((libraryItem.media as? Podcast)?.metadata?.title).orEmpty()
        })
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        val libraryItemsWithAudio = libraryItems.filter { li ->
          li.media is Podcast && li.checkHasTracks()
        }

        val committed = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) {
            emptyList()
          } else {
            val podcastCache = cachedLibraryPodcasts.getOrPut(libraryId) { ConcurrentHashMap() }
            libraryItemsWithAudio.forEach { libraryItem ->
              podcastCache[libraryItem.id] = libraryItem
              addServerLibrary(libraryItem)
            }
            isLibraryPodcastsCached[libraryId] = true
            libraryItemsWithAudio.sortedBy { libraryItem ->
              ((libraryItem.media as? Podcast)?.metadata?.title).orEmpty()
            }
          }
        }
        Log.d(tag, "loadLibraryPodcasts: loaded from server: $libraryId")
        cb(committed)
      }
    }
  }

  /**
   *  Returns series with audio books from selected library.
   *  If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesWithAudio(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    // Check "cache" first
    val cached = cachedLibrarySeries[libraryId]
    if (cached != null) {
      Log.d(tag, "Series with audio found from cache | Library $libraryId ")
      cb(cached)
    } else {
      apiHandler.getLibrarySeries(libraryId) { seriesItems ->
        Log.d(tag, "Series with audio loaded from server | Library $libraryId")
        val seriesItemsWithAudio = seriesItems.filter { si -> si.audiobookCount > 0 }

        val committed = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) emptyList()
          else seriesItemsWithAudio.also { cachedLibrarySeries[libraryId] = it }
        }

        cb(committed)
      }
    }
  }

  /**
   * Returns series with audiobooks from selected library using filter for paging.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesWithAudio(libraryId:String, seriesFilter:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    loadLibrarySeriesWithAudio(libraryId) { seriesItems ->
      val normalizedFilter = seriesFilter.uppercase()
      cb(seriesItems.filter { series -> series.title.uppercase().startsWith(normalizedFilter) })
    }
  }

  /**
   * Sorts books in series. Assumes that sequence is main.minor
   */
  private fun sortSeriesBooks(seriesBooks: List<LibraryItem>) : List<LibraryItem> {
    val sortingLogic = compareBy<LibraryItem> { it.seriesSequenceParts[0].length }
      .thenBy { it.seriesSequenceParts[0].ifEmpty { "" } }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" }.length }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" } }
    return seriesBooks.sortedWith(sortingLogic)
  }

  /**
   * Returns books for series from library.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibrarySeriesItemsWithAudio(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    // Check "cache" first
    val libraryCache = cachedLibrarySeriesItem.getOrPut(libraryId) { ConcurrentHashMap() }
    val cached = libraryCache[seriesId]
    if (cached != null) {
      Log.d(tag, "Items for series $seriesId found from cache | Library $libraryId")
      cb(cached)
    } else {
      apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
        Log.d(tag, "Items for series $seriesId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsWithAudio)
        val committed = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) emptyList()
          else sortedLibraryItemsWithAudio.also { current ->
            cachedLibrarySeriesItem.getOrPut(libraryId) { ConcurrentHashMap() }[seriesId] = current
            current.forEach(::addServerLibrary)
          }
        }
        cb(committed)
      }
    }
  }

  /**
   * Returns authors with books from library.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorsWithBooks(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    // Check "cache" first
    val cached = cachedLibraryAuthors[libraryId]
    if (cached != null) {
      Log.d(tag, "Authors with books found from cache | Library $libraryId ")
      cb(cached.values.toList())
    } else {
      // Fetch data from server and add it to local "cache"
      apiHandler.getLibraryAuthors(libraryId) { authorItems ->
        Log.d(tag, "Authors with books loaded from server | Library $libraryId ")
        // TO-DO: This check won't ensure that there is audiobooks. Current API won't offer ability to do so
        var authorItemsWithBooks = authorItems.filter { it.bookCount > 0 }
        authorItemsWithBooks = authorItemsWithBooks.sortedBy { it.name }
        val committed = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) emptyList()
          else authorItemsWithBooks.also { current ->
            val authorCache = ConcurrentHashMap<String, LibraryAuthorItem>()
            current.forEach { authorCache.putIfAbsent(it.id, it) }
            cachedLibraryAuthors[libraryId] = authorCache
          }
        }
        cb(committed)
      }
    }
  }

  /**
   * Returns authors with books from selected library using filter for paging.
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorsWithBooks(libraryId:String, authorFilter: String, cb: (List<LibraryAuthorItem>) -> Unit) {
    loadAuthorsWithBooks(libraryId) { authorItems ->
      val normalizedFilter = authorFilter.uppercase()
      cb(authorItems.filter { author -> author.name.uppercase().startsWith(normalizedFilter) })
    }
  }

  /**
   * Returns audiobooks for author from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorBooksWithAudio(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    // Ensure that there is map for library
    val libraryCache = cachedLibraryAuthorItems.getOrPut(libraryId) { ConcurrentHashMap() }
    // Check "cache" first
    val cached = libraryCache[authorId]
    if (cached != null) {
      Log.d(tag, "Items for author $authorId found from cache | Library $libraryId")
      cb(cached)
    } else {
      apiHandler.getLibraryItemsFromAuthor(libraryId, authorId) { libraryItems ->
        Log.d(tag, "Items for author $authorId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        val committed = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) emptyList()
          else libraryItemsWithAudio.also { current ->
            cachedLibraryAuthorItems.getOrPut(libraryId) { ConcurrentHashMap() }[authorId] = current
            current.forEach(::addServerLibrary)
          }
        }

        cb(committed)
      }
    }
  }

  /**
   * Returns audiobooks for author from specified series within library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadAuthorSeriesBooksWithAudio(libraryId:String, authorId:String, seriesId: String, cb: (List<LibraryItem>) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    val authorSeriesKey = "$authorId|$seriesId"
    val libraryCache = cachedLibraryAuthorSeriesItems.getOrPut(libraryId) { ConcurrentHashMap() }
    // Check "cache" first
    val cached = libraryCache[authorSeriesKey]
    if (cached != null) {
      Log.d(tag, "Items for series $seriesId with author $authorId found from cache | Library $libraryId")
      cb(cached)
    } else {
      loadAuthorsWithBooks(libraryId) { authorItems ->
        val authorName = authorItems.find { author -> author.id == authorId }?.name
        if (authorName == null) {
          Log.w(tag, "Author is missing from the selected library")
          cb(emptyList())
          return@loadAuthorsWithBooks
        }

        apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
          if (expectedGeneration != cacheGeneration.get()) {
            cb(emptyList())
            return@getLibrarySeriesItems
          }
          Log.d(tag, "Items for series $seriesId with author $authorId loaded from server | Library $libraryId")
          val libraryItemsFromAuthorWithAudio = libraryItems
            .filter { item -> item.checkHasTracks() }
            .filter { item -> item.authorName.contains(authorName, ignoreCase = true) }

          val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsFromAuthorWithAudio)
          val committed = synchronized(cacheLock) {
            if (expectedGeneration != cacheGeneration.get()) emptyList()
            else sortedLibraryItemsWithAudio.also { current ->
              cachedLibraryAuthorSeriesItems
                .getOrPut(libraryId) { ConcurrentHashMap() }[authorSeriesKey] = current
              current.forEach(::addServerLibrary)
            }
          }
          cb(committed)
        }
      }
    }
  }

  /**
   * Returns collections with audiobooks from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryCollectionsWithAudio(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    val expectedGeneration = cacheGeneration.get()
    val cached = cachedLibraryCollections[libraryId]
    if (cached != null) {
      Log.d(tag, "Collections with books found from cache | Library $libraryId ")
      cb(cached.values.toList())
    } else {
      apiHandler.getLibraryCollections(libraryId) { libraryCollections ->
        Log.d(tag, "Collections with books loaded from server | Library $libraryId ")
        val libraryCollectionsWithAudio = libraryCollections.filter { lc -> lc.audiobookCount > 0 }

        val committed = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get()) emptyList()
          else libraryCollectionsWithAudio.also { current ->
            val collectionCache = ConcurrentHashMap<String, LibraryCollection>()
            current.forEach { collectionCache.putIfAbsent(it.id, it) }
            cachedLibraryCollections[libraryId] = collectionCache
          }
        }
        cb(committed)
      }
    }
  }

  /**
   * Returns audiobooks for collection from library
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryCollectionBooksWithAudio(libraryId: String, collectionId: String, cb: (List<LibraryItem>) -> Unit) {
    loadLibraryCollectionsWithAudio(libraryId) {
      Log.d(tag, "Trying to find collection $collectionId items from cache | Library $libraryId")
      val books = cachedLibraryCollections[libraryId]
        ?.get(collectionId)
        ?.books
        .orEmpty()
        .filter { item -> item.checkHasTracks() }
      books.forEach { libraryItem -> addServerLibrary(libraryItem) }
      cb(books)
    }
  }

  /**
   * Returns audiobooks from discovery shelf for [libraryId]
   * If data is not found from local cache then it will be fetched from server
   */
  fun loadLibraryDiscoveryBooksWithAudio(libraryId: String, cb: (List<LibraryItem>) -> Unit) {
    val libraryItemsWithAudio = synchronized(cacheLock) {
      cachedLibraryDiscovery[libraryId].orEmpty().toList()
    }.filter { item -> item.checkHasTracks() }
    libraryItemsWithAudio.forEach { libraryItem -> addServerLibrary(libraryItem) }
    cb(libraryItemsWithAudio)
  }

  /**
   * Returns recent shelves for [libraryId]
   * If data is not shelves are found returns empty list
   */
  fun getLibraryRecentShelfs(libraryId: String, cb: (List<LibraryShelfType>) -> Unit) {
    val shelves = synchronized(cacheLock) {
      cachedLibraryRecentShelves[libraryId]?.toList()
    }
    if (shelves == null) {
      Log.d(tag, "getLibraryRecentShelfs: No shelves $libraryId")
      cb(listOf())
      return
    }
    cb(shelves)
  }

  /**
   * Returns recent shelf by [type] for [libraryId]
   * If shelf is not found returns null
   */
  fun getLibraryRecentShelfByType(libraryId: String, type:String, cb: (LibraryShelfType?) -> Unit) {
    Log.d(tag, "getLibraryRecentShelfByType: $libraryId | $type")
    val shelves = synchronized(cacheLock) {
      cachedLibraryRecentShelves[libraryId]?.toList()
    }
    if (shelves == null) {
      cb(null)
      return
    }
    for (shelf in shelves) {
      if (shelf.type == type.lowercase()) {
        cb(shelf)
        return
      }
    }
    cb(null)
  }

  /**
   * Loads podcasts for newest episodes shelf
   */
  private fun loadPodcastItem(
    libraryId: String,
    libraryItemId: String,
    expectedGeneration: Long = cacheGeneration.get(),
    cb: (LibraryItem?) -> Unit
  ) {
    val cachedPodcast = synchronized(cacheLock) {
      if (expectedGeneration != cacheGeneration.get()) {
        null
      } else {
        cachedLibraryPodcasts.getOrPut(libraryId) { mutableMapOf() }[libraryItemId]
      }
    }
    if (cachedPodcast != null) {
      Log.d(tag, "loadPodcastItem: Podcast found from cache | Library $libraryItemId ")
      cb(cachedPodcast)
    } else {
      if (expectedGeneration != cacheGeneration.get()) {
        cb(null)
        return
      }
      Log.d(tag, "loadPodcastItem: Calling getLibraryItem $libraryItemId")
      apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
        val committedPodcast = synchronized(cacheLock) {
          if (expectedGeneration != cacheGeneration.get() || libraryItem == null) {
            null
          } else {
            val podcast = libraryItem.media as? Podcast ?: return@synchronized null
            podcast.episodes?.forEach { podcastEpisode ->
              podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItem, podcastEpisode)
            }
            cachedLibraryPodcasts.getOrPut(libraryId) { mutableMapOf() }[libraryItemId] = libraryItem
            libraryItem
          }
        }
        if (committedPodcast != null) {
          Log.d(tag, "loadPodcastItem: Got library item ${committedPodcast.id} ${committedPodcast.media.metadata.title}")
        }
        cb(committedPodcast)
      }
    }
  }

  private fun loadLibraryItem(libraryItemId:String, cb: (LibraryItemWrapper?) -> Unit) {
    if (libraryItemId.startsWith("local")) {
      cb(DeviceManager.dbManager.getLocalLibraryItem(libraryItemId))
    } else {
      Log.d(tag, "loadLibraryItem: $libraryItemId")
      apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
        Log.d(tag, "loadLibraryItem: Got library item $libraryItem")
        cb(libraryItem)
      }
    }
  }

  fun loadPodcastEpisodeMediaBrowserItems(libraryItemId:String, ctx:Context, cb: (MutableList<MediaBrowserCompat.MediaItem>) -> Unit) {
      loadLibraryItem(libraryItemId) { libraryItemWrapper ->
        Log.d(tag, "Loaded Podcast library item $libraryItemWrapper")

        if (libraryItemWrapper == null) {
          cb(mutableListOf())
          return@loadLibraryItem
        }

        libraryItemWrapper.let {
          if (libraryItemWrapper is LocalLibraryItem) { // Local podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as? Podcast
              if (podcast == null) {
                cb(mutableListOf())
                return@loadLibraryItem
              }
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast

              val children = podcast.episodes?.map { podcastEpisode ->
                Log.d(tag, "Local Podcast Episode ${podcastEpisode.title} | ${podcastEpisode.id}")

                val progress = DeviceManager.dbManager.getLocalMediaProgress("${libraryItemWrapper.id}-${podcastEpisode.id}")
                val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)

                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          } else if (libraryItemWrapper is LibraryItem) { // Server podcast episodes
            if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
              cb(mutableListOf())
            } else {
              val podcast = libraryItemWrapper.media as? Podcast
              if (podcast == null) {
                cb(mutableListOf())
                return@loadLibraryItem
              }
              podcast.episodes?.forEach { podcastEpisode ->
                podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItemWrapper, podcastEpisode)
              }
              selectedLibraryItemId = libraryItemWrapper.id
              selectedPodcast = podcast
              val episodes = podcast.episodes?.sortedByDescending { it.publishedAt }
              val children = episodes?.map { podcastEpisode ->

                val progress = serverUserMediaProgress.find { it.libraryItemId == libraryItemWrapper.id && it.episodeId == podcastEpisode.id }

                // to show download icon
                val localLibraryItem = getLocalDownloadForCurrentServer(libraryItemWrapper.id)
                localLibraryItem?.let { lli ->
                  val localEpisode = (lli.media as? Podcast)?.episodes?.find { it.serverEpisodeId == podcastEpisode.id }
                  podcastEpisode.localEpisodeId = localEpisode?.id
                }

                val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)
                MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
              }
              children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
            }
          }
        }
      }
  }

  /**
   * Loads libraries for selected server with stats
   */
  private fun loadLibraries(
    expectedGeneration: Long,
    cb: (List<Library>) -> Unit
  ) {
    val cachedLibraries = synchronized(cacheLock) {
      if (expectedGeneration == cacheGeneration.get()) serverLibraries else emptyList()
    }
    if (cachedLibraries.isNotEmpty()) {
      cb(cachedLibraries)
      return
    }

    apiHandler.getLibraries { loadedLibraries ->
      val committedLibraries = synchronized(cacheLock) {
        if (expectedGeneration != cacheGeneration.get()) {
          emptyList()
        } else {
          serverLibraries = loadedLibraries
          serverLibraries
        }
      }
      cb(committedLibraries)
    }
  }

  private suspend fun checkServerConnection(config:ServerConnectionConfig) : Boolean {
    var successfulPing = false
    suspendCoroutine { cont ->
      apiHandler.pingServer(config) {
        Log.d(tag, "checkServerConnection: Checked server conn for ${config.address} result = $it")
        successfulPing = it
        cont.resume(it)
      }
    }
    return successfulPing
  }

  private suspend fun authorize(config:ServerConnectionConfig) : MutableList<MediaProgress> {
    var mediaProgress:MutableList<MediaProgress> = mutableListOf()
    suspendCoroutine { cont ->
      apiHandler.authorize(config) {
        Log.d(tag, "authorize: Authorized server config ${config.address} result = $it")
        if (!it.isNullOrEmpty()) {
          mediaProgress = it
        }
        cont.resume(mediaProgress)
      }
    }
    return mediaProgress
  }

  private fun checkSetValidServerConnectionConfig(
    expectedGeneration: Long,
    cb: (Boolean) -> Unit
  ) = runBlocking {
    val lastServerConnectionConfig = DeviceManager.getLastServerConnectionConfig()
    Log.d(tag, "checkSetValidServerConnectionConfig | serverConfigIdUsed=$serverConfigIdUsed | lastServerConnectionConfigId=${lastServerConnectionConfig?.id}")

    if (!DeviceManager.checkConnectivity(ctx) ||
      lastServerConnectionConfig == null
    ) {
      synchronized(cacheLock) {
        if (expectedGeneration == cacheGeneration.get()) {
          serverUserMediaProgress = mutableListOf()
        }
      }
      Log.d(tag, "checkSetValidServerConnectionConfig: No connectivity or saved server")
      cb(false)
      return@runBlocking
    }

    val currentConfigId = DeviceManager.serverConnectionConfig?.id
      ?: lastServerConnectionConfig.id
    val canReusePing = synchronized(cacheLock) {
      expectedGeneration == cacheGeneration.get() &&
        !serverConfigIdUsed.isNullOrEmpty() &&
        serverConfigIdUsed == currentConfigId &&
        serverConfigLastPing > 0L &&
        System.currentTimeMillis() - serverConfigLastPing < 5000
    }
    if (canReusePing) {
      Log.d(tag, "checkSetValidServerConnectionConfig last ping less than 5 seconds ago")
      cb(true)
      return@runBlocking
    }

    synchronized(cacheLock) {
      if (expectedGeneration == cacheGeneration.get()) {
        serverUserMediaProgress = mutableListOf()
      }
    }

    var selectedConfig: ServerConnectionConfig? = DeviceManager.serverConnectionConfig
      ?: lastServerConnectionConfig
    var hasValidConnection = false
    val candidates = orderedServerConnectionCandidates(
      selectedConfig,
      DeviceManager.snapshotServerConnectionConfigs()
    )
    for (config in candidates) {
      if (checkServerConnection(config)) {
        if (DeviceManager.serverConnectionConfig?.id != config.id) {
          val accepted = synchronized(cacheLock) {
            if (expectedGeneration != cacheGeneration.get()) {
              false
            } else {
              DeviceManager.trySelectServerConnectionConfig(config)
            }
          }
          if (!accepted) {
            cb(false)
            return@runBlocking
          }
          selectedConfig = DeviceManager.serverConnectionConfig
          Log.d(tag, "checkSetValidServerConnectionConfig: Set server connection config ${config.id}")
        }
        hasValidConnection = true
        Log.d(tag, "checkSetValidServerConnectionConfig: Config ${config.address} is pingable")
        break
      }
      if (expectedGeneration != cacheGeneration.get()) {
        cb(false)
        return@runBlocking
      }
    }

    val configToAuthorize = selectedConfig
    if (!hasValidConnection || configToAuthorize == null) {
      cb(false)
      return@runBlocking
    }

    Log.d(tag, "Has valid conn now get user media progress")
    val mediaProgress = authorize(configToAuthorize)
    val committed = synchronized(cacheLock) {
      if (expectedGeneration != cacheGeneration.get()) {
        false
      } else {
        serverConfigLastPing = System.currentTimeMillis()
        serverUserMediaProgress = mediaProgress
        true
      }
    }
    cb(committed)
  }

  fun loadServerUserMediaProgress(
    config: ServerConnectionConfig? = DeviceManager.serverConnectionConfig,
    lease: ConnectionLease? = config?.let(DeviceManager::captureConnectionLease),
    cb: (Boolean) -> Unit
  ) {
    Log.d(tag, "Loading server media progress")
    val expectedGeneration = cacheGeneration.get()
    if (config == null || lease == null ||
      config.id != lease.connectionId ||
      DeviceManager.getServerConnectionConfig(lease) !== config
    ) {
      return cb(false)
    }

    apiHandler.authorize(config) {
      val committed = synchronized(cacheLock) {
        if (expectedGeneration != cacheGeneration.get() ||
          DeviceManager.getServerConnectionConfig(lease) !== config ||
          it == null
        ) {
          false
        } else {
          // A successful empty response is authoritative and must clear stale
          // completion data before selecting the next podcast episode.
          serverUserMediaProgress = it
          true
        }
      }
      if (committed) {
        Log.d(tag, "loadServerUserMediaProgress: Authorized server config ${config.address} result = $it")
      }
      cb(committed)
    }
  }

  fun initializeInProgressItems(cb: () -> Unit) {
    Log.d(tag, "Initializing inprogress items")
    val expectedGeneration = cacheGeneration.get()

    loadItemsInProgressForAllLibraries(expectedGeneration) { itemsInProgress ->
      val committed = synchronized(cacheLock) {
        if (expectedGeneration != cacheGeneration.get()) {
          false
        } else {
          itemsInProgress.forEach {
            val libraryItem = it.libraryItemWrapper as? LibraryItem ?: return@forEach
            addServerLibrary(libraryItem)

            if (it.episode != null) {
              podcastEpisodeLibraryItemMap[it.episode.id] = LibraryItemWithEpisode(it.libraryItemWrapper, it.episode)
            }
          }
          true
        }
      }
      if (committed) {
        Log.d(tag, "Initializing inprogress items done")
      }
      cb()
    }
  }

  fun loadAndroidAutoItems(cb: (Boolean) -> Unit) {
    Log.d(tag, "Load android auto items")
    val expectedGeneration = cacheGeneration.get()

    // Check if any valid server connection if not use locally downloaded books
    checkSetValidServerConnectionConfig(expectedGeneration) { isConnected ->
      if (!isConnected) {
        cb(false)
        return@checkSetValidServerConnectionConfig
      }
      val selectedConfig = DeviceManager.serverConnectionConfig
      val selectedLease = DeviceManager.captureConnectionLease(selectedConfig)
      if (selectedConfig == null || selectedLease == null) {
        cb(false)
        return@checkSetValidServerConnectionConfig
      }
      val configId = selectedConfig.id
      val committedConfig = synchronized(cacheLock) {
        if (expectedGeneration != cacheGeneration.get() ||
          DeviceManager.getServerConnectionConfig(selectedLease) !== selectedConfig
        ) {
          false
        } else {
          serverConfigIdUsed = configId
          serverConnectionLeaseUsed = selectedLease
          true
        }
      }
      if (!committedConfig) {
        cb(false)
        return@checkSetValidServerConnectionConfig
      }
      Log.d(tag, "loadAndroidAutoItems: Connected to server config id=$configId")

      loadLibraries(expectedGeneration) { libraries ->
        if (expectedGeneration != cacheGeneration.get()) {
          cb(false)
          return@loadLibraries
        }
        if (libraries.isEmpty()) {
          Log.w(tag, "No libraries returned from server request")
          cb(true)
        } else {
          cb(true) // Fully loaded
        }
      }
    }
  }

  /**
   * Handles search requests.
   * Searches from books, series and authors
   */
  suspend fun doSearch(libraryId: String, queryString: String) : Map<String, List<MediaBrowserCompat.MediaItem>> {
    val expectedGeneration = cacheGeneration.get()
    return suspendCoroutine {
      apiHandler.getSearchResults(libraryId, queryString) { searchResult ->
        if (expectedGeneration != cacheGeneration.get()) {
          it.resume(emptyMap())
          return@getSearchResults
        }
        Log.d(tag, "searchLocalCache: $searchResult")
        // Nothing found from server
        if (searchResult === null) {
          it.resume(mapOf())
          return@getSearchResults
        }

        val foundItems: MutableMap<String, List<MediaBrowserCompat.MediaItem>> = mutableMapOf()

        val serverLibrary = serverLibraries.find { sl -> sl.id == libraryId }

        // Books
        if (searchResult.book !== null && searchResult.book!!.isNotEmpty()) {
          Log.d(tag, "searchLocalCache: found ${searchResult.book!!.size} books")
          val children = searchResult.book!!.filter { it.libraryItem.checkHasTracks() }.map { bookResult ->
            val libraryItem = bookResult.libraryItem

            addServerLibrary(libraryItem)
            val progress = serverUserMediaProgress.find { it.libraryItemId == libraryItem.id }
            val localLibraryItem = getLocalDownloadForCurrentServer(libraryItem.id)
            libraryItem.localLibraryItemId = localLibraryItem?.id
            val description = libraryItem.getMediaDescription(progress, ctx, null, null, "Books (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
          }
          foundItems["book"] = children
        }
        if (searchResult.series !== null && searchResult.series!!.isNotEmpty()) {
          Log.d(tag, "onSearch: found ${searchResult.series!!.size} series")
          val children = searchResult.series!!.map { seriesResult ->
            val seriesItem = seriesResult.series
            seriesItem.books = seriesResult.books.orEmpty().toMutableList()
            val description = seriesItem.getMediaDescription(null, ctx, "Series (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
          }
          foundItems["series"] = children
        }
        if (searchResult.authors !== null && searchResult.authors!!.isNotEmpty()) {
          Log.d(tag, "onSearch: found ${searchResult.authors!!.size} authors")
          val children = searchResult.authors!!.map { authorItem ->
            val description = authorItem.getMediaDescription(null, ctx, "Authors (${serverLibrary?.name})")
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
          }
          foundItems["authors"] = children
        }
        if (searchResult.podcast !== null && searchResult.podcast!!.isNotEmpty()) {
          Log.d(tag, "onSearch: found ${searchResult.podcast!!.size} podcasts")
          val children = searchResult.podcast!!
            .map { podcastResult -> podcastResult.libraryItem }
            .filter { libraryItem -> libraryItem.checkHasTracks() }
            .map { libraryItem ->
              addServerLibrary(libraryItem)
              val description = libraryItem.getMediaDescription(null, ctx)
              MediaBrowserCompat.MediaItem(
                description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
              )
            }
          foundItems["podcast"] = children
        }

        it.resume(foundItems)
      }
    }
  }

  fun getFirstItem() : LibraryItemWrapper? {
    val remoteItem = synchronized(cacheLock) {
      val lease = serverConnectionLeaseUsed
      if (lease != null && DeviceManager.isConnectionLeaseCurrent(lease)) {
        serverLibraryItems.values.firstOrNull()
      } else {
        null
      }
    }
    remoteItem?.let { return it }
    return DeviceManager.dbManager.getLocalLibraryItems("book").firstOrNull()
  }

  fun getPodcastWithEpisodeByEpisodeId(id:String) : LibraryItemWithEpisode? {
    return if (id.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItemWithEpisode(id)
    } else {
      synchronized(cacheLock) {
        val lease = serverConnectionLeaseUsed
        if (lease != null && DeviceManager.isConnectionLeaseCurrent(lease)) {
          podcastEpisodeLibraryItemMap[id]
        } else {
          null
        }
      }
    }
  }

  fun getById(id:String) : LibraryItemWrapper? {
    return if (id.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItem(id)
    } else {
      synchronized(cacheLock) {
        val lease = serverConnectionLeaseUsed
        if (lease != null && DeviceManager.isConnectionLeaseCurrent(lease)) {
          serverLibraryItems[id]
        } else {
          null
        }
      }
    }
  }

  fun getFromSearch(query:String?) : LibraryItemWrapper? {
    if (query.isNullOrEmpty()) return getFirstItem()
    return serverLibraryItems.values.find {
      it.title.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
    }
  }

  /**
   * Resolves an Android voice request without assuming the browse cache is warm.
   *
   * This is a suspending API because a cold Automotive launch may need to select a
   * saved server, authorize, load its libraries, and query the search endpoint. The
   * MediaSession callback always invokes it off the main thread.
   */
  internal suspend fun resolveVoiceSearch(request: VoiceSearchRequest): VoiceSearchResult {
    findCachedVoiceResolution(request)?.let { return VoiceSearchResult.Found(it) }

    val serverAvailable = suspendCoroutine<Boolean> { continuation ->
      loadAndroidAutoItems { continuation.resume(it) }
    }
    if (!serverAvailable) {
      return findCachedVoiceResolution(request)?.let { VoiceSearchResult.Found(it) }
        ?: VoiceSearchResult.Unavailable
    }

    if (request.isGeneralRequest) {
      // An empty query means "play some media". Prefer the most recently active
      // audiobook or podcast episode after loading that cold-start data.
      suspendCoroutine<Unit> { continuation ->
        initializeInProgressItems { continuation.resume(Unit) }
      }
    }
    findCachedVoiceResolution(request)?.let { return VoiceSearchResult.Found(it) }

    val resolved = if (request.isGeneralRequest) {
      loadDefaultVoiceResolution()
    } else {
      searchServerForVoiceResolution(request)
    }

    return resolved?.let { VoiceSearchResult.Found(it) } ?: VoiceSearchResult.NoMatch
  }

  private suspend fun findCachedVoiceResolution(
    request: VoiceSearchRequest
  ): VoiceSearchResolution? {
    if (request.isGeneralRequest) {
      serverItemsInProgress
        .sortedByDescending { it.progressLastUpdate }
        .forEach { inProgress ->
          playableVoiceResolution(
            inProgress.libraryItemWrapper,
            inProgress.episode
          )?.let { return it }
        }
    }

    val localItems = runCatching {
      DeviceManager.dbManager.getLocalLibraryItems("book") +
        DeviceManager.dbManager.getLocalLibraryItems("podcast")
    }.onFailure { error ->
      Log.w(tag, "Unable to inspect local media for voice search", error)
    }.getOrDefault(emptyList())

    val candidates = (serverLibraryItems.values.toList() + localItems)
      .map { item -> item to item.toVoiceSearchCandidate() }

    val ranked = if (request.isGeneralRequest) {
      candidates.sortedBy { (_, candidate) -> candidate.title.lowercase(Locale.ROOT) }
    } else {
      candidates
        .map { candidate -> candidate to request.score(candidate.second) }
        .filter { (_, score) -> score > 0 }
        .sortedByDescending { (_, score) -> score }
        .map { (candidate, _) -> candidate }
    }

    ranked.forEach { (item, _) ->
      playableVoiceResolution(item)?.let { return it }
    }
    return null
  }

  private suspend fun searchServerForVoiceResolution(
    request: VoiceSearchRequest
  ): VoiceSearchResolution? {
    val libraries = serverLibraries.toList()
    for (term in request.searchTerms) {
      val foundItems = mutableListOf<LibraryItemWrapper>()
      for (library in libraries) {
        val searchResults = try {
          doSearch(library.id, term)
        } catch (error: Exception) {
          Log.w(tag, "Voice search failed for a library", error)
          emptyMap()
        }

        listOf("book", "podcast").forEach { resultType ->
          searchResults[resultType].orEmpty().forEach { mediaItem ->
            mediaItem.mediaId?.let(::getById)?.let(foundItems::add)
          }
        }
      }

      val distinctItems = foundItems.distinctBy { it.id }
      val rankedItems = distinctItems.sortedByDescending {
        request.score(it.toVoiceSearchCandidate())
      }
      // The server search endpoint is authoritative even when its fuzzy result
      // does not contain the literal voice transcription in returned metadata.
      rankedItems.forEach { item ->
        playableVoiceResolution(item)?.let { return it }
      }
    }
    return null
  }

  private suspend fun loadDefaultVoiceResolution(): VoiceSearchResolution? {
    for (library in serverLibraries.toList()) {
      val items = if (library.mediaType == "podcast") {
        suspendCoroutine<List<LibraryItem>> { continuation ->
          loadLibraryPodcasts(library.id) { continuation.resume(it.orEmpty()) }
        }
      } else {
        suspendCoroutine { continuation ->
          loadLibraryBooksWithAudio(library.id) { continuation.resume(it) }
        }
      }

      for (item in items) {
        playableVoiceResolution(item)?.let { return it }
      }
    }
    return null
  }

  private suspend fun playableVoiceResolution(
    item: LibraryItemWrapper,
    knownEpisode: PodcastEpisode? = null
  ): VoiceSearchResolution? {
    if (item is LocalLibraryItem) {
      if (item.mediaType != "podcast") {
        return if (item.hasTracks(null)) VoiceSearchResolution(item) else null
      }
      val podcast = item.media as? Podcast ?: return null
      val episode = knownEpisode
        ?: podcast.episodes?.sortedByDescending { it.publishedAt }?.firstOrNull()
        ?: return null
      return if (item.hasTracks(episode)) VoiceSearchResolution(item, episode) else null
    }

    val serverItem = item as? LibraryItem ?: return null
    if (!serverItem.checkHasTracks()) return null
    if (serverItem.mediaType != "podcast") return VoiceSearchResolution(serverItem)

    var playablePodcast = serverItem
    var podcast = playablePodcast.media as? Podcast ?: return null
    if (podcast.episodes.isNullOrEmpty()) {
      playablePodcast = suspendCoroutine { continuation ->
        loadPodcastItem(serverItem.libraryId, serverItem.id) {
          continuation.resume(it ?: serverItem)
        }
      }
      podcast = playablePodcast.media as? Podcast ?: return null
    }

    val episode = knownEpisode
      ?: podcast.getNextUnfinishedEpisode(playablePodcast.id, this)
      ?: podcast.episodes?.sortedByDescending { it.publishedAt }?.firstOrNull()
      ?: return null
    return VoiceSearchResolution(playablePodcast, episode)
  }

  private fun LibraryItemWrapper.toVoiceSearchCandidate(): VoiceSearchCandidate {
    val media = when (this) {
      is LibraryItem -> this.media
      is LocalLibraryItem -> this.media
      else -> null
    }
    val metadata = media?.metadata
    val artists = mutableListOf<String>()
    val albums = mutableListOf<String>()
    val genres = mutableListOf<String>()

    when (metadata) {
      is BookMetadata -> {
        metadata.authorName?.let(artists::add)
        metadata.authorNameLF?.let(artists::add)
        metadata.authors.orEmpty().mapTo(artists) { it.name }
        metadata.narratorName?.let(artists::add)
        artists.addAll(metadata.narrators.orEmpty())
        metadata.seriesName?.let(albums::add)
        metadata.series.orEmpty().mapTo(albums) { it.name }
        genres.addAll(metadata.genres)
      }
      is PodcastMetadata -> {
        metadata.author?.let(artists::add)
        genres.addAll(metadata.genres)
      }
    }

    return VoiceSearchCandidate(
      id = id,
      title = metadata?.title.orEmpty(),
      artists = artists,
      albums = albums,
      genres = genres
    )
  }

  fun play(
    libraryItemWrapper: LibraryItemWrapper,
    episode: PodcastEpisode?,
    playItemRequestPayload: PlayItemRequestPayload,
    ownerConfig: ServerConnectionConfig? = null,
    ownerLease: ConnectionLease? = null,
    cb: (PlaybackSession?) -> Unit
  ) {
    if (libraryItemWrapper is LocalLibraryItem) {
      cb(libraryItemWrapper.getPlaybackSession(episode, playItemRequestPayload.deviceInfo))
    } else {
      val libraryItem = libraryItemWrapper as LibraryItem
      val resolvedLease = ownerLease ?: synchronized(cacheLock) {
        if (serverLibraryItems[libraryItem.id] === libraryItem) {
          serverConnectionLeaseUsed
        } else {
          null
        }
      }
      val resolvedConfig = if (ownerConfig != null && resolvedLease != null &&
        ownerConfig.id == resolvedLease.connectionId &&
        DeviceManager.getServerConnectionConfig(resolvedLease) === ownerConfig
      ) {
        ownerConfig
      } else {
        resolvedLease?.let(DeviceManager::getServerConnectionConfig)
      }
      if (resolvedLease == null || resolvedConfig == null) {
        Log.w(tag, "Refusing to play a remote item without a current cache-owner lease")
        cb(null)
        return
      }
      apiHandler.playLibraryItem(
        libraryItem.id,
        episode?.id ?: "",
        playItemRequestPayload,
        resolvedConfig
      ) {
        if (it == null) {
          cb(null)
        } else {
          cb(it)
        }
      }
    }
  }

  private fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1..rhsLength-1) {
      newCost[0] = i

      for (j in 1..lhsLength-1) {
        val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

        val costReplace = cost[j - 1] + match
        val costInsert = cost[j] + 1
        val costDelete = newCost[j - 1] + 1

        newCost[j] = Math.min(Math.min(costInsert, costDelete), costReplace)
      }

      val swap = cost
      cost = newCost
      newCost = swap
    }

    return cost[lhsLength - 1]
  }
}

internal data class VoiceSearchRequest(
  val query: String?,
  val mediaFocus: String?,
  val title: String?,
  val artist: String?,
  val album: String?,
  val genre: String?,
  val playlist: String?
) {
  val isGeneralRequest: Boolean
    get() = listOf(query, title, artist, album, genre, playlist).all { it.isNullOrBlank() }

  val searchTerms: List<String>
    get() = listOf(title, artist, album, playlist, genre, query)
      .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
      .distinctBy { it.lowercase(Locale.ROOT) }

  val displayTerm: String
    get() = title ?: query ?: artist ?: album ?: playlist ?: genre ?: "your library"

  fun score(candidate: VoiceSearchCandidate): Int {
    if (isGeneralRequest) return 1

    var score = 0
    score += matchScore(title, listOf(candidate.title), 16)
    score += matchScore(artist, candidate.artists, 10)
    score += matchScore(album, candidate.albums, 8)
    score += matchScore(playlist, candidate.albums, 8)
    score += matchScore(genre, candidate.genres, 6)
    score += matchScore(
      query,
      listOf(candidate.title) + candidate.artists + candidate.albums + candidate.genres,
      4
    )
    return score
  }

  companion object {
    fun from(query: String?, extras: Bundle?): VoiceSearchRequest {
      val cleanedQuery = query.cleanedVoiceValue()
      val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS).cleanedVoiceValue()
      var title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE).cleanedVoiceValue()
      var artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST).cleanedVoiceValue()
      var album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM).cleanedVoiceValue()
      var genre = extras?.getString(MediaStore.EXTRA_MEDIA_GENRE).cleanedVoiceValue()
      var playlist = extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST).cleanedVoiceValue()

      // Android may put the recognized entity only in query and use MEDIA_FOCUS
      // to tell the app how to interpret it.
      when (focus) {
        MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> if (title == null) title = cleanedQuery
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> if (artist == null) artist = cleanedQuery
        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> if (album == null) album = cleanedQuery
        MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE -> if (genre == null) genre = cleanedQuery
        MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> if (playlist == null) playlist = cleanedQuery
      }

      return VoiceSearchRequest(
        query = cleanedQuery,
        mediaFocus = focus,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        playlist = playlist
      )
    }
  }
}

internal data class VoiceSearchCandidate(
  val id: String,
  val title: String,
  val artists: List<String> = emptyList(),
  val albums: List<String> = emptyList(),
  val genres: List<String> = emptyList()
)

internal data class VoiceSearchResolution(
  val item: LibraryItemWrapper,
  val episode: PodcastEpisode? = null
)

internal sealed class VoiceSearchResult {
  data class Found(val resolution: VoiceSearchResolution) : VoiceSearchResult()
  object NoMatch : VoiceSearchResult()
  object Unavailable : VoiceSearchResult()
}

private fun String?.cleanedVoiceValue(): String? =
  this?.trim()?.takeIf { it.isNotEmpty() }

private fun matchScore(requested: String?, values: List<String>, weight: Int): Int {
  val needle = requested.normalizedVoiceValue()
  if (needle.isEmpty()) return 0

  return values.maxOfOrNull { rawValue ->
    val value = rawValue.normalizedVoiceValue()
    when {
      value.isEmpty() -> 0
      value == needle -> weight + 4
      value.startsWith(needle) || needle.startsWith(value) -> weight + 2
      value.contains(needle) || needle.contains(value) -> weight
      else -> 0
    }
  } ?: 0
}

private fun String?.normalizedVoiceValue(): String =
  this.orEmpty()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
