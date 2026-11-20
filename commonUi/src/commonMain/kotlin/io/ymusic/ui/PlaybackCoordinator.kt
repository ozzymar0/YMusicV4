package io.ymusic.ui

import io.ymusic.download.DownloadState
import io.ymusic.download.DownloadTask
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.PlaybackState
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.Player
import io.ymusic.player.PlayerError
import io.ymusic.player.QueueOrigin
import io.ymusic.player.QueueSource
import io.ymusic.player.QueueState
import io.ymusic.player.isEnded
import io.ymusic.ui.playhistory.PlayAgainSession
import kmpcommon.log.Logger
import kmpcommon.log.info
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import projectcommon.error.TechnicalException
import kotlin.time.TimeSource

/**
 * UI-side playback glue over the [Player] facade. Every queue item is self-describing — a
 * [PlayableItem.LocalMedia] / [PlayableItem.YtMedia] carries its own id + display metadata — so the
 * coordinator holds NO side map: Now Playing reads the item's `display*` accessors straight off the
 * item. Watch-time ranking (when [AppGraph.playHistory] is set) credits elapsed play while Prepared.
 * Queues that keep growing (a video's radio, a playlist past its loaded page) carry a
 * [NextQueueSource].
 */
class PlaybackCoordinator(
    internal val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    val player: Player get() = graph.player

    private val log = graph.logger?.fork("player")
    private val playHistory = graph.playHistory
    private val mediaStore = graph.mediaStore

    init {
        // The player surfaces failure as state, not a throw — this transition IS the job-death
        // frame, so the one crash record per technical playback failure is captured here. A
        // playability verdict is the user's to act on: a warning, not a crash.
        if (log != null) {
            scope.launch {
                combine(player.status, player.queue, player.config) { status, queue, config ->
                    playTrail(status, queue, config)
                }.distinctUntilChanged().collect { log.info(it) }
            }
            scope.launch {
                player.queue.filter { it.queueId != null }.distinctUntilChangedBy { it.queueId }.collect { queue ->
                    log.info("queue origin=${queue.origin.name.lowercase()} size=${queue.items.size} index=${queue.currentIndex}")
                }
            }
            scope.launch {
                player.status.map { it.error }.distinctUntilChanged().collect { err ->
                    val id = itemId(player.queue.value.currentItem)
                    if (err != null && err.playability() != null) {
                        log.log(Logger.Level.WARN, "playability ${err.code} id=$id: ${err.message}")
                    } else if (err != null && err.code == PlayerError.FILE_MISSING) {
                        log.log(Logger.Level.WARN, "file missing id=$id: ${err.message}", err.cause)
                    } else if (err != null && err.code == PlayerError.FOLDER_ACCESS_LOST) {
                        log.log(Logger.Level.WARN, "folder access lost id=$id: ${err.message}", err.cause)
                    } else if (err != null && err.code == "network") {
                        log.log(Logger.Level.WARN, "network id=$id: ${err.message}", err.cause)
                    } else if (err != null && err.code == PlayerError.PLAYBACK_UNAVAILABLE) {
                        log.log(Logger.Level.WARN, "playback unavailable id=$id: ${err.message}")
                    } else if (err != null) {
                        log.captureError(
                            TechnicalException(err.code, err.message ?: "player error", cause = err.cause),
                            prop = mapOf("id" to id, "canRetry" to err.canRetry, "needReset" to err.needReset),
                        )
                    }
                }
            }
        }
        val pruneUngrantedFolders = graph.pruneUngrantedFolders
        if (pruneUngrantedFolders != null) {
            scope.launch {
                player.status.map { it.error?.code == PlayerError.FOLDER_ACCESS_LOST }.distinctUntilChanged().collect { lost ->
                    if (lost) pruneUngrantedFolders()
                }
            }
        }
        // Watch-time ranking: credit elapsedRealtime only while Prepared + playing, not buffering.
        // Same item staying playing (volume/size ticks) keeps the mark — resetting it would drop time.
        if (playHistory != null) {
            scope.launch {
                var trackingItem: PlayableItem? = null
                var trackedAt: TimeSource.Monotonic.ValueTimeMark? = null
                suspend fun flush() {
                    val item = trackingItem
                    val mark = trackedAt
                    trackingItem = null
                    trackedAt = null
                    if (item == null || mark == null) return
                    val watchTimeMs = mark.elapsedNow().inWholeMilliseconds
                    if (watchTimeMs <= 0L) return
                    try {
                        addWatchTime(item, watchTimeMs)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        log?.log(Logger.Level.WARN, "addWatchTime failed", e)
                    }
                }
                try {
                    combine(player.status, player.queue) { status, queue -> status to queue }
                        .collect { (status, queue) ->
                            val item = queue.currentItem
                            val prepared = status.state as? PlaybackState.Prepared
                            val playing = prepared != null &&
                                prepared.playWhenReady &&
                                !prepared.buffering &&
                                !prepared.ended &&
                                item != null
                            if (!playing) {
                                flush()
                            } else if (item != trackingItem) {
                                flush()
                                trackingItem = item
                                trackedAt = TimeSource.Monotonic.markNow()
                            }
                        }
                } finally {
                    withContext(NonCancellable) { flush() }
                }
            }
        }
    }

    /** Download actions stay available even when the cloud entry is playing from a local copy. */
    fun downloadable(item: PlayableItem?): YtFeedItem? {
        val videoId = item?.videoId ?: return null
        val yt = item as? PlayableItem.YtMedia
        if (yt?.live == true) return null
        return YtFeedItem(
            kind = YtFeedItem.KIND_VIDEO,
            id = videoId,
            title = when (item) {
                is PlayableItem.YtMedia -> item.title ?: videoId
                is PlayableItem.LocalMedia -> item.title ?: videoId
            },
            subtitleLine1 = when (item) {
                is PlayableItem.YtMedia -> item.artist
                is PlayableItem.LocalMedia -> item.artist
            },
            channelTitle = when (item) {
                is PlayableItem.YtMedia -> item.artist
                is PlayableItem.LocalMedia -> item.artist
            },
            channelUrl = yt?.channelUrl,
            duration = yt?.duration,
            thumbs = item.displayThumbnailUrl?.let { listOf(YtThumb(it)) } ?: emptyList(),
        )
    }

    /** Snapshot on a worker, select the newest actually-readable audio file for each video ID.
     * Neither playback nor download lookup changes any YouTube playlist membership. */
    private suspend fun verifiedAudioCopies(): Map<CloudVideoKey, VerifiedAudioCopy> {
        val service = graph.downloads ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            service.snapshot().asSequence()
                .filter { it.state == DownloadState.COMPLETED }
                .filter { it.format.name in setOf("M4A", "MP3", "AUDIO_WEBM") }
                .sortedByDescending { it.updatedAt }
                .mapNotNull { task ->
                    try {
                        val uri = Uri.parse(task.fileUri ?: return@mapNotNull null)
                        if (uri.scheme != "file") return@mapNotNull null
                        val metadata = FileSystem.SYSTEM.metadataOrNull(uri.filePath().toPath())
                        if (metadata?.isRegularFile != true || (metadata.size ?: 0L) <= 0L) return@mapNotNull null
                        val key = CloudVideoKey(task.videoId)
                        key to VerifiedAudioCopy(key, uri.toString())
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        null
                    }
                }
                .distinctBy { it.first }
                .toMap()
        }
    }

    private fun playableWithCopy(item: PlayableItem.YtMedia, copies: Map<CloudVideoKey, VerifiedAudioCopy>): PlayableItem {
        if (item.live) return item
        return when (val choice = chooseUnifiedPlayback(CloudVideoKey(item.videoId), copies, isOnline = true)) {
            is UnifiedPlaybackChoice.Local -> PlayableItem.LocalMedia(
                uri = choice.uri,
                title = item.title,
                artist = item.artist,
                thumbnailUrl = item.thumbnailUrl,
                durationMs = item.durationMs,
                videoId = item.videoId,
            )
            else -> item
        }
    }

    /** New pages of a YouTube playlist use the same local-first policy as the first page. */
    private fun withDownloadedPages(source: QueueSource?): QueueSource? {
        if (source == null || graph.downloads == null) return source
        return object : QueueSource {
            override val totalCount: Int? get() = source.totalCount
            override val resumeToken: String? get() = source.resumeToken
            override suspend fun loadMore(): QueueSource.Page {
                val page = source.loadMore()
                val copies = verifiedAudioCopies()
                return QueueSource.Page(
                    page.items.map { item ->
                        if (item is PlayableItem.YtMedia) playableWithCopy(item, copies) else item
                    },
                    page.hasMore,
                )
            }
        }
    }

    private var lastRequest: QueueRequest? = null
    private var lastRequestQueueId: String? = null

    /** A streamed and downloaded video are the same queue entry, selected by video ID. */
    fun playNow(video: YtFeedItem) {
        if (graph.downloads == null) {
            val items = listOf(video.toYtMedia())
            startOnce(queueRequest(QueueOrigin.Radio, items, 0, "radio:${video.id}")) {
                player.setQueue(items, source = NextQueueSource.radio(graph.engines, video.id, log?.fork("queue-source")), origin = QueueOrigin.Radio)
            }
            return
        }
        scope.launch {
            val copies = verifiedAudioCopies()
            val items = listOf(playableWithCopy(video.toYtMedia(), copies))
            withContext(Dispatchers.Main) {
                startOnce(queueRequest(QueueOrigin.Radio, items, 0, "radio:${video.id}")) {
                    player.setQueue(items, source = withDownloadedPages(NextQueueSource.radio(graph.engines, video.id, log?.fork("queue-source"))), origin = QueueOrigin.Radio)
                }
            }
        }
    }

    fun playSingle(video: YtFeedItem) {
        if (graph.downloads == null) {
            val items = listOf(video.toYtMedia())
            startOnce(queueRequest(QueueOrigin.Single, items, 0, null)) {
                player.setQueue(items, origin = QueueOrigin.Single)
            }
            return
        }
        scope.launch {
            val copies = verifiedAudioCopies()
            val items = listOf(playableWithCopy(video.toYtMedia(), copies))
            withContext(Dispatchers.Main) {
                startOnce(queueRequest(QueueOrigin.Single, items, 0, null)) {
                    player.setQueue(items, origin = QueueOrigin.Single)
                }
            }
        }
    }

    /** The playlist queue keeps its original YouTube order, selecting local audio where available. */
    fun playAll(videos: List<YtFeedItem>, startIndex: Int = 0, shuffle: Boolean? = null, source: QueueSource? = null) {
        val playable = videos.filter { it.kind == YtFeedItem.KIND_VIDEO }
        if (playable.isEmpty()) return
        val index = startIndex.coerceIn(0, playable.size - 1)
        if (graph.downloads == null) {
            val items = playable.map { it.toYtMedia() }
            startOnce(queueRequest(QueueOrigin.Playlist, items, index, source?.let { "playlist" }, shuffle)) {
                shuffle?.let(player::setShuffle)
                player.setQueue(items, playIndex = index, source = source, origin = QueueOrigin.Playlist)
            }
            return
        }
        scope.launch {
            val copies = verifiedAudioCopies()
            val items = playable.map { playableWithCopy(it.toYtMedia(), copies) }
            withContext(Dispatchers.Main) {
                startOnce(queueRequest(QueueOrigin.Playlist, items, index, source?.let { "playlist" }, shuffle)) {
                    shuffle?.let(player::setShuffle)
                    player.setQueue(items, playIndex = index, source = withDownloadedPages(source), origin = QueueOrigin.Playlist)
                }
            }
        }
    }

    fun playPlaylist(url: String, page: YtFeedPage, videoId: String): Boolean {
        val videos = page.sections.flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }
        val index = videos.indexOfFirst { it.id == videoId }
        if (index < 0) return false
        playAll(
            videos,
            startIndex = index,
            source = NextQueueSource.playlist(graph.engines, url, videos, page.pageHeader?.itemCount, log?.fork("queue-source")),
        )
        return true
    }

    /** Play local library [songs] as the queue, starting at [index]. [shuffle] null keeps the user's shuffle mode. */
    fun playLocal(songs: List<MediaStoreItem>, index: Int, shuffle: Boolean? = null) {
        val items = songs.map { it.toLocalMedia() }
        startOnce(queueRequest(QueueOrigin.Library, items, index, null, shuffle)) {
            shuffle?.let(player::setShuffle)
            player.setQueue(items, playIndex = index, origin = QueueOrigin.Library)
        }
    }

    /** Play just [song] now (queue of one). */
    fun playLocalSingle(song: MediaStoreItem) {
        val items = listOf(song.toLocalMedia())
        startOnce(queueRequest(QueueOrigin.Single, items, 0, null)) {
            player.setQueue(items, origin = QueueOrigin.Single)
        }
    }

    /**
     * Play the finished [downloads] as the queue, in order, starting at the one with [startId]. Each is its
     * library item when the store has indexed it — same id, tags and embedded-cover art as the Library;
     * else the file by uri with its video's thumbnail, resolved at the view size by the `art:` fetcher.
     */
    suspend fun playDownloads(downloads: List<DownloadTask>, startId: String) {
        val finished = downloads.mapNotNull { task -> task.fileUri?.takeIf { task.state == DownloadState.COMPLETED }?.let { task to it } }
        val startIndex = finished.indexOfFirst { (task, _) -> task.id == startId }
        if (startIndex < 0) return
        val store = mediaStore
        val downloadsLog = log?.fork("play-downloads")
        val items = withContext(Dispatchers.IO) {
            finished.map { (task, uri) ->
                val libraryItem = try {
                    store?.findItemByUri(Uri.parse(uri))
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    downloadsLog?.log(Logger.Level.WARN, "library lookup failed", e)
                    null
                }
                libraryItem?.toLocalMedia() ?: PlayableItem.LocalMedia(
                    uri = uri,
                    title = task.title,
                    artist = task.artist,
                    thumbnailUrl = if (store != null) youtubeArtUrl(task.videoId) else youtubeThumbUrl(task.videoId),
                    loudnessGainDb = task.loudnessGainDb,
                    videoId = task.videoId,
                )
            }
        }
        startOnce(queueRequest(QueueOrigin.Downloads, items, startIndex, null)) {
            player.setQueue(items, playIndex = startIndex, origin = QueueOrigin.Downloads)
        }
    }

    /**
     * Resume a "Play again" queue as the same queue (its session keeps updating), its playlist
     * continuing from where the queue had loaded to. Mid-item only for long content
     * ([PlayAgainSession.resumesMidItem]) unless [fromBeginning]. False when the session is gone.
     */
    suspend fun playAgain(session: PlayAgainSession, fromBeginning: Boolean = false): Boolean {
        val store = playHistory?.playAgain ?: return false
        val (saved, items) = store.load(session.queueId) ?: return false
        if (items.isEmpty()) return false
        val index = saved.queueIndex.coerceIn(0, items.lastIndex)
        startOnce(queueRequest(QueueOrigin.Resume, items, index, "resume:${saved.queueId}:$fromBeginning")) {
            player.setQueue(
                items,
                playIndex = index,
                startPositionMs = if (fromBeginning || !saved.resumesMidItem) 0L else saved.positionMs,
                origin = QueueOrigin.Resume,
                queueId = saved.queueId,
                source = saved.sourceToken?.let { NextQueueSource.resume(it, graph.engines, items, log?.fork("queue-source")) },
            )
        }
        return true
    }

    /**
     * Child-proofing: runs [start] unless [request] repeats the last one while the queue it made is
     * still on its item. A repeat tap then reloads nothing; a paused item resumes. An ended or failed
     * item, a different item or a queue replaced since all start again.
     */
    private inline fun startOnce(request: QueueRequest, start: () -> Unit) {
        val queue = player.queue.value
        val status = player.status.value
        val repeat = request == lastRequest &&
            queue.queueId != null &&
            queue.queueId == lastRequestQueueId &&
            itemId(queue.currentItem) == request.itemIds.getOrNull(request.index) &&
            !status.state.isEnded() &&
            status.error == null
        if (repeat) {
            if (!status.playWhenReady) player.play()
            return
        }
        start()
        lastRequest = request
        lastRequestQueueId = player.queue.value.queueId
    }

    /** Queue [songs], in order, right after the current item. */
    fun queueLocalNext(songs: List<MediaStoreItem>) {
        player.addToPlayNext(songs.map { it.toLocalMedia() })
    }

    /** Append [songs], in order, to the end of the queue. */
    fun queueLocalLast(songs: List<MediaStoreItem>) {
        player.add(songs.map { it.toLocalMedia() })
    }

    fun playNext(video: YtFeedItem) {
        if (graph.downloads == null) return player.addToPlayNext(listOf(video.toYtMedia()))
        scope.launch {
            val item = playableWithCopy(video.toYtMedia(), verifiedAudioCopies())
            withContext(Dispatchers.Main) { player.addToPlayNext(listOf(item)) }
        }
    }

    fun playLater(video: YtFeedItem) {
        if (graph.downloads == null) return player.add(listOf(video.toYtMedia()))
        scope.launch {
            val item = playableWithCopy(video.toYtMedia(), verifiedAudioCopies())
            withContext(Dispatchers.Main) { player.add(listOf(item)) }
        }
    }

    private suspend fun addWatchTime(item: PlayableItem, watchTimeMs: Long) {
        val history = playHistory ?: return
        when (item) {
            is PlayableItem.LocalMedia -> {
                val row = item.mediaId?.let { mediaStore?.findItem(it) }
                if (row != null) history.trackLocal(row.playlistCode, watchTimeMs)
                val vid = item.videoId ?: row?.youtubeVid
                if (vid != null && !graph.guest) history.trackYoutube(vid, watchTimeMs)
            }
            is PlayableItem.YtMedia -> {
                if (graph.guest) return
                history.saveYoutubeMetadata(
                    videoId = item.videoId,
                    title = item.title ?: item.videoId,
                    artist = item.artist,
                    thumbnailUrl = item.thumbnailUrl ?: youtubeThumbUrl(item.videoId),
                    duration = item.duration,
                    durationMs = item.durationMs,
                    live = item.live,
                )
                history.trackYoutube(item.videoId, watchTimeMs)
            }
        }
    }
}

/** What a play tap asked for, compared by value to spot a repeat tap ([PlaybackCoordinator.startOnce]). */
private data class QueueRequest(
    val origin: QueueOrigin,
    val itemIds: List<String>,
    val index: Int,
    val source: String?,
    val shuffle: Boolean? = null,
)

private fun queueRequest(origin: QueueOrigin, items: List<PlayableItem>, index: Int, source: String?, shuffle: Boolean? = null) =
    QueueRequest(origin, items.map(::itemId), index, source, shuffle)

/** One line per playback-state change — what a report needs for buffering / black screen / stuck. */
private fun itemId(item: PlayableItem?): String = when (item) {
    is PlayableItem.YtMedia -> item.videoId
    is PlayableItem.LocalMedia -> item.uri.substringAfterLast('/')
    null -> "-"
}

private fun playTrail(status: PlaybackStatus, queue: QueueState, config: PlaybackConfig): String {
    val id = itemId(queue.currentItem)
    val phase = when (val st = status.state) {
        is PlaybackState.Uninitialized -> if (st.isInitializing) "init" else "uninit"
        is PlaybackState.Idle -> "idle"
        is PlaybackState.Error -> "error ${st.error.code}"
        is PlaybackState.Prepared -> when {
            st.ended -> "ended"
            st.buffering -> "buffering"
            st.playWhenReady -> "playing"
            else -> "paused"
        }
    }
    val size = status.videoSize?.let { "${it.width}x${it.height}" } ?: "-"
    return buildString {
        append(phase)
        append(" id=")
        append(id)
        if (status.live) append(" live")
        append(" ao=")
        append(if (config.audioOnly) "1" else "0")
        append(" q=")
        append(config.preferVideoQuality.first)
        append(" vol=${config.volume}")
        append(" speed=${config.speed} pitch=${config.pitchFactor}")
        append(' ')
        append(size)
        append(" queue=${queue.currentIndex + 1}/${queue.items.size}")
        append(" via=${queue.lastChange.name.lowercase()}")
    }
}

// ---- self-describing queue items ----
// Shared with the platform shells (the Android session service converts controller MediaItems
// back through these), so the queue-item mapping never forks.

/** A library item as a self-describing queue item (id + display metadata travel with it). */
fun MediaStoreItem.toLocalMedia() = PlayableItem.LocalMedia(
    uri = uri.toString(),
    mediaId = id,
    title = title,
    artist = artist,
    album = album,
    thumbnailUrl = localArtUrl(this),
    durationMs = durationMs,
    loudnessGainDb = loudnessGainDb,
    videoId = youtubeVid,
)

/** A scraped video as a self-describing queue item — keeps just what the UI + downloads need. */
fun YtFeedItem.toYtMedia() = PlayableItem.YtMedia(
    videoId = id,
    title = title,
    artist = channelTitle,
    channelUrl = channelUrl,
    thumbnailUrl = thumbs.firstOrNull()?.url,
    duration = duration,
    durationMs = durationMs,
    live = live,
)
