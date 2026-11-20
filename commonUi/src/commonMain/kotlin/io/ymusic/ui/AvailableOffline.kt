package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.ymusic.dynamicengine.yt.YtFeedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onStart
import projectcommon.log.captureUnlessEnvironmental

/** YouTube video ids the Library holds a file of — empty where there is no media store. */
internal val LocalAvailableOffline = compositionLocalOf<Set<String>> { emptySet() }

/** The Library's YouTube video ids, re-read on every media-store item change (a finished download,
 *  a scan, a delete). */
@Composable
internal fun rememberAvailableOffline(graph: AppGraph): Set<String> {
    val store = graph.mediaStore
    val mediaIds by produceState(emptySet<String>(), store) {
        if (store != null) {
            val log = graph.logger?.fork("available-offline")
            store.itemsChanges().onStart { emit(Unit) }.collect {
                try {
                    // The metadata DB can outlive an externally deleted/moved file.
                    // Verify file:// entries before marking them downloaded. Content-provider
                    // URIs are validated by the platform's persisted grant when opened.
                    val items = store.allItems()
                    value = withContext(Dispatchers.IO) {
                        items.asSequence()
                            .filter { it.uri.scheme != "file" || isReadableDownloadedFile(it.uri.toString()) }
                            .mapNotNull { it.youtubeVid }
                            .toSet()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    log?.captureUnlessEnvironmental(e, "available-offline ids failed to load")
                }
            }
        }
    }
    val downloads = graph.downloads
    val downloadIds by produceState(emptySet<String>(), downloads) {
        if (downloads != null) {
            val log = graph.logger?.fork("available-offline-downloads")
            suspend fun refresh() {
                try {
                    value = indexCompletedAudioDownloads(downloads.snapshot(), ::isReadableDownloadedFile).keys.mapTo(mutableSetOf()) { it.videoId }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    log?.captureUnlessEnvironmental(e, "downloaded ids failed to load")
                }
            }
            refresh()
            downloads.changes.collect { refresh() }
        }
    }
    return mediaIds + downloadIds
}

/** True when [item] is a video the Library already holds. */
@Composable
internal fun isAvailableOffline(item: YtFeedItem): Boolean =
    item.kind == YtFeedItem.KIND_VIDEO && item.id in LocalAvailableOffline.current

/** The "available offline" mark: an accent triangle in the thumbnail's top-left corner. */
@Composable
internal fun AvailableOfflineMark(modifier: Modifier = Modifier) {
    val color = DesignColors.Accent
    Canvas(modifier.size(20.dp)) {
        val corner = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(corner, color)
    }
}
