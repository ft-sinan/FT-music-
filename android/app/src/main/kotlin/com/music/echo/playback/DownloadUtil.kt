package echo.music.iad1tya.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Requirements
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.music.innertube.YouTube
import com.music.innertube.models.IpVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.constants.DownloadOnWifiOnlyKey
import echo.music.iad1tya.constants.DownloadQuality
import echo.music.iad1tya.constants.DownloadQualityKey
import echo.music.iad1tya.constants.DownloadWithMetadataKey
import echo.music.iad1tya.constants.IpVersionKey
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.FormatEntity
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.SongEntity
import echo.music.iad1tya.di.DownloadCache
import echo.music.iad1tya.R
import echo.music.iad1tya.lyrics.LyricsProviderRegistry
import echo.music.iad1tya.lyrics.LyricsUtils
import echo.music.iad1tya.ui.utils.resize
import echo.music.iad1tya.utils.YTPlayerUtils
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.enumPreference
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Dns
import okhttp3.OkHttpClient

@Singleton
class DownloadUtil
@Inject
constructor(
  @ApplicationContext private val context: Context,
  val database: MusicDatabase,
  val databaseProvider: DatabaseProvider,
  @DownloadCache val downloadCache: SimpleCache,
  val sharedDownloadStore: SharedDownloadStore,
  private val sourceRegistry: DownloadSourceRegistry,
) {
  private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
  private val downloadQuality by
    enumPreference(
      context,
      DownloadQualityKey,
      DownloadQuality.HIGH
    )
  private val ipVersion by enumPreference(context, IpVersionKey, IpVersion.AUTO)
  private val songUrlCache = ConcurrentHashMap<String, ResolvedStream>()

  // Injectable in instrumented tests so cache isolation can be exercised without YouTube access.
  @Volatile internal var resolveDownloadSource: suspend (String, AudioQuality) -> YTPlayerUtils.PlaybackData = { id, quality ->
    YTPlayerUtils.playerResponseForPlayback(id, audioQuality = quality, connectivityManager = connectivityManager).getOrThrow()
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
  val legacyWritePermissionNeeded = MutableStateFlow(false)
  val sharedDownloadProblem = MutableStateFlow<String?>(null)
  val downloadSourceProblem = MutableStateFlow<String?>(null)
  val sharedDownloadBytes = MutableStateFlow(0L)
  private val publishJobs = ConcurrentHashMap<String, Job>()
  private val downloadedFlagLock = Mutex()
  @Volatile private var permissionPromptDismissed = false

  /** Persist the current manager state, not a stale callback or startup snapshot. */
  private suspend fun syncDownloadedFlag(id: String) {
    downloadedFlagLock.withLock {
      val current = downloads.value[id]
      val isComplete = current?.state == Download.STATE_COMPLETED &&
        (!sharedDownloadStore.hasRecord(id) || sharedDownloadStore.state(id) != SharedDownloadStore.FileState.MISSING)
      val date = if (isComplete) {
        val timestamp = current!!.updateTimeMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)
      } else null
      database.updateDownloadedInfo(id, isComplete, date)
    }
  }

  fun requestLegacyWritePermission() {
    permissionPromptDismissed = false
    if (sharedDownloadStore.requiresLegacyPermission()) legacyWritePermissionNeeded.value = true
  }

  fun onLegacyWritePermissionResult(granted: Boolean) {
    legacyWritePermissionNeeded.value = false
    permissionPromptDismissed = !granted
    if (granted) refreshSharedDownloads()
    else sharedDownloadProblem.value = "Storage access is needed to save downloads in Files. Existing private downloads were kept."
  }

  private fun scheduleSharedCopy(download: Download) {
    val id = download.request.id
    if (downloads.value[id]?.state != Download.STATE_COMPLETED) return
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        if (downloads.value[id]?.state != Download.STATE_COMPLETED) return@launch
        when (sharedDownloadStore.publish(download)) {
          SharedDownloadStore.PublishResult.NEEDS_PERMISSION -> {
            if (!permissionPromptDismissed) legacyWritePermissionNeeded.value = true
          }
          SharedDownloadStore.PublishResult.UNAVAILABLE -> {
            sharedDownloadProblem.value = "Some downloads are still private. Check free storage and try again in Storage settings."
          }
          SharedDownloadStore.PublishResult.PUBLISHED -> sharedDownloadBytes.value = sharedDownloadStore.totalSizeBytes()
          SharedDownloadStore.PublishResult.ALREADY_PRESENT -> Unit
        }
      } finally {
        publishJobs.remove(id, coroutineContext[Job])
      }
    }
    if (publishJobs.putIfAbsent(id, job) == null) job.start() else job.cancel()
  }

  /** Reconcile on app resume and when the Downloaded playlist is opened/refreshed. */
  fun refreshSharedDownloads() {
    scope.launch {
      sharedDownloadStore.pendingRemovalIds().forEach { id ->
        if (!sharedDownloadStore.delete(id)) {
          sharedDownloadProblem.value = "A download could not be removed from Files. Check storage access and retry."
          if (sharedDownloadStore.requiresLegacyPermission() && !permissionPromptDismissed) legacyWritePermissionNeeded.value = true
        }
      }
      val missing = sharedDownloadStore.ids().filter {
        sharedDownloadStore.state(it) == SharedDownloadStore.FileState.MISSING
      }.toSet()
      missing.forEach { id ->
        publishJobs.remove(id)?.cancel()
        sharedDownloadStore.markRemovalPending(id)
        if (downloads.value.containsKey(id)) {
          kotlinx.coroutines.withContext(Dispatchers.Main) { downloadManager.removeDownload(id) }
        } else {
          sharedDownloadStore.delete(id)
          syncDownloadedFlag(id)
        }
      }
      downloads.value.values
        .filter { it.state == Download.STATE_COMPLETED && it.request.id !in missing && !sharedDownloadStore.hasRecord(it.request.id) }
        .forEach(::scheduleSharedCopy)
      sharedDownloadStore.ids().filterNot { it in missing }.forEach { sharedDownloadStore.cleanupRedundantCache(it) }
      sharedDownloadBytes.value = sharedDownloadStore.totalSizeBytes()
    }
  }

  internal val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
    ResolvingDataSource.Factory(
      ChunkingDataSourceFactory(
        // Streaming cache is keyed only by song ID and may contain a different format.
        // DownloadManager writes into downloadCache itself; fetch its misses directly.
        OkHttpDataSource.Factory(
          OkHttpClient.Builder()
            .dns(
              object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                  val addresses = Dns.SYSTEM.lookup(hostname)
                  return when (this@DownloadUtil.ipVersion) {
                    IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                    IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                    IpVersion.AUTO -> addresses
                  }
                }
              }
            )
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
              YouTube.proxyAuth?.let { auth ->
                response.request.newBuilder().header("Proxy-Authorization", auth).build()
              } ?: response.request
            }
            .build(),
        )
      )
    ) { dataSpec ->
      // If this is a Canvas request (e.g., Apple Music or Tidal), bypass YouTube resolution!
      if (
        dataSpec.uri.toString().contains("apple.com") ||
          dataSpec.uri.toString().contains("music.apple") ||
          dataSpec.uri.toString().contains(".m3u8") ||
          dataSpec.uri.toString().contains(".mp4") ||
          dataSpec.key?.endsWith("_canvas") == true
      ) {
        return@Factory dataSpec
      }

      val mediaId = dataSpec.key ?: error("No media id")

      // Lock the setting and selected format for the lifetime of this download. In
      // particular, a later setting change or a newly resolved URL must not splice
      // a different representation into already-downloaded bytes.
      val quality = try {
        sourceRegistry.qualityFor(mediaId, downloadQuality)
      } catch (error: IOException) {
        downloadSourceProblem.value = context.getString(R.string.ft_download_restart_needed)
        throw error
      }
      songUrlCache[mediaId]
        ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
        ?.let { return@Factory it.resolve(dataSpec) }

      val playbackData = runBlocking(Dispatchers.IO) { resolveDownloadSource(mediaId, quality.resolverQuality()) }
      val format = playbackData.format
      val source = DownloadSourceIdentity.from(playbackData.streamUrl, format.itag, format.mimeType, format.contentLength)
      val accepted = try {
        sourceRegistry.acceptSource(mediaId, source, downloadCache.getCachedSpans(mediaId).isNotEmpty())
      } catch (error: IOException) {
        downloadSourceProblem.value = context.getString(R.string.ft_download_restart_needed)
        throw error
      }
      if (!accepted) {
        downloadSourceProblem.value = context.getString(R.string.ft_download_restart_needed)
        throw IOException("The unfinished download cannot safely resume with this source")
      }

      database.query {
        upsert(
          FormatEntity(
            id = mediaId,
            itag = format.itag,
            mimeType = format.mimeType.split(";")[0],
            codecs =
              format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "unknown",
            bitrate = format.bitrate,
            sampleRate = format.audioSampleRate,
            contentLength = format.contentLength ?: 0L,
            loudnessDb = playbackData.audioConfig?.loudnessDb,
            perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
            playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
          ),
        )

        val now = LocalDateTime.now()
        val existingSongInfo = getSongByIdBlocking(mediaId)
        val existing = existingSongInfo?.song

        val downloadMetadata = runBlocking {
          context.dataStore.data.first()[DownloadWithMetadataKey] ?: true
        }
        if (downloadMetadata) {
          scope.launch(Dispatchers.IO) {
            try {
              val title = existingSongInfo?.title ?: playbackData.videoDetails?.title ?: ""
              val artist =
                existingSongInfo?.artists?.joinToString { it.name }
                  ?: playbackData.videoDetails?.author
                  ?: ""
              val albumName = existingSongInfo?.album?.title ?: title
              val duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0

              // 2. Fetch Lyrics
              val providers =
                LyricsProviderRegistry.getDefaultProviderOrder().mapNotNull {
                  LyricsProviderRegistry.getProviderByName(it)
                }
              var foundLyrics: String? = null
              var foundProvider: String? = null

              for (provider in providers) {
                if (!provider.isEnabled(context)) continue
                val res = provider.getLyrics(mediaId, title, artist, duration, null).getOrNull()
                if (res != null && res != LyricsEntity.LYRICS_NOT_FOUND && res.isNotBlank()) {
                  foundLyrics = res
                  foundProvider = provider.name
                  if (LyricsUtils.hasSyncedLyrics(res)) {
                    break // Synced lyrics, stop searching
                  }
                }
              }
              if (foundLyrics != null && foundProvider != null) {
                database.query {
                  upsert(LyricsEntity(id = mediaId, lyrics = foundLyrics, provider = foundProvider))
                }
              }
            } catch (e: Exception) {
              e.printStackTrace()
            }
          }
        }

        val updatedSong =
          if (existing != null) {
            existing.copy(
              dateDownload = existing.dateDownload ?: now,
              thumbnailUrl =
                existing.thumbnailUrl
                  ?: playbackData.videoDetails
                    ?.thumbnail
                    ?.thumbnails
                    ?.lastOrNull()
                    ?.url
                    ?.resize(1200, 1200)
            )
          } else {
            SongEntity(
              id = mediaId,
              title = playbackData.videoDetails?.title ?: "Unknown",
              duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
              thumbnailUrl =
                playbackData.videoDetails
                  ?.thumbnail
                  ?.thumbnails
                  ?.lastOrNull()
                  ?.url
                  ?.resize(1200, 1200),
              dateDownload = now,
              isDownloaded = false
            )
          }

        upsert(updatedSong)

        updatedSong.thumbnailUrl?.let { url ->
          val request =
            ImageRequest.Builder(context)
              .data(url)
              .memoryCachePolicy(CachePolicy.ENABLED)
              .diskCachePolicy(CachePolicy.ENABLED)
              .build()
          SingletonImageLoader.get(context).enqueue(request)
        }
      }

      val stream = ResolvedStream.from(playbackData)
      songUrlCache[mediaId] = stream
      stream.resolve(dataSpec)
    }

  val downloadNotificationHelper =
    DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

  @OptIn(DelicateCoroutinesApi::class)
  val downloadManager: DownloadManager =
    DownloadManager(
        context,
        databaseProvider,
        downloadCache,
        dataSourceFactory,
        Executors.newFixedThreadPool(3)
      )
      .apply {
        maxParallelDownloads = 3
        addListener(
          object : DownloadManager.Listener {
            override fun onDownloadChanged(
              downloadManager: DownloadManager,
              download: Download,
              finalException: Exception?,
            ) {
              downloads.update { map ->
                map.toMutableMap().apply { set(download.request.id, download) }
              }
              if (download.state == Download.STATE_REMOVING) {
                publishJobs.remove(download.request.id)?.cancel()
              }
              if ((download.state == Download.STATE_QUEUED || download.state == Download.STATE_DOWNLOADING) &&
                sharedDownloadStore.requiresLegacyPermission() && !permissionPromptDismissed) {
                legacyWritePermissionNeeded.value = true
              }

              scope.launch {
                when (download.state) {
                  Download.STATE_COMPLETED,
                  Download.STATE_FAILED,
                  Download.STATE_STOPPED,
                  Download.STATE_REMOVING -> {
                    val id = download.request.id
                    syncDownloadedFlag(id)
                    downloads.value[id]?.takeIf { it.state == Download.STATE_COMPLETED }?.let(::scheduleSharedCopy)
                  }
                  else -> {}
                }
              }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
              val id = download.request.id
              publishJobs.remove(id)?.cancel()
              downloads.update { it - id }
              songUrlCache.remove(id)
              try {
                sourceRegistry.remove(id)
              } catch (error: IOException) {
                downloadSourceProblem.value = context.getString(R.string.ft_download_restart_needed)
              }
              sharedDownloadStore.markRemovalPending(id)
              scope.launch {
                syncDownloadedFlag(id)
                if (!sharedDownloadStore.delete(id)) {
                  sharedDownloadProblem.value = "A download could not be removed from Files. Check storage access and retry."
                  if (sharedDownloadStore.requiresLegacyPermission() && !permissionPromptDismissed) legacyWritePermissionNeeded.value = true
                }
                sharedDownloadBytes.value = sharedDownloadStore.totalSizeBytes()
              }
            }
          }
        )
      }

  init {
    val result = mutableMapOf<String, Download>()
    downloadManager.downloadIndex.getDownloads().use { cursor ->
      while (cursor.moveToNext()) {
        result[cursor.download.request.id] = cursor.download
      }
    }
    // A process can die after Media3 removed a download but before its callback
    // cleared the identity. Do this before the manager can start new work.
    try {
      sourceRegistry.prune(result.keys)
    } catch (_: IOException) {
      downloadSourceProblem.value = context.getString(R.string.ft_download_restart_needed)
    }
    downloads.value = result
    refreshSharedDownloads()

    scope.launch(Dispatchers.IO) {
      result.keys.forEach { id -> syncDownloadedFlag(id) }
    }

    // Wi-Fi-only downloads: DownloadManager pauses queued downloads whenever the
    // active requirements aren't met, so flipping this pref mid-download stops it
    // on mobile data without losing progress.
    scope.launch {
      context.dataStore.data
        .map { it[DownloadOnWifiOnlyKey] ?: false }
        .distinctUntilChanged()
        .collectLatest { wifiOnly ->
          // Media3 requires all DownloadManager access on its application looper.
          // Updating the watcher on IO can race a download/release on the main thread.
          kotlinx.coroutines.withContext(Dispatchers.Main) {
            downloadManager.requirements =
              Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
          }
        }
    }
  }

  internal suspend fun copyPrivateDownloadForSharing(id: String): SharedDownloadStore.TemporaryShareCopy? {
    val original = downloads.value[id] ?: return null
    return sharedDownloadStore.copyPrivateForSharing(original) {
      val current = downloads.value[id]
      current != null && current.state == Download.STATE_COMPLETED &&
        current.startTimeMs == original.startTimeMs && current.updateTimeMs == original.updateTimeMs &&
        current.request.uri == original.request.uri
    }
  }

  /** Preview available YouTube formats without creating or altering a download. */
  internal suspend fun inspectDownloadOptions(songId: String): DownloadOptionDiscoveryResult =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
      discoverYouTubeDownloadOptions(songId, resolveDownloadSource)
    }

  fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

  fun release() {
    scope.cancel()
  }
}
