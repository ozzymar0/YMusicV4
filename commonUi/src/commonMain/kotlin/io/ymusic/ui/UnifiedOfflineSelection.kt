package io.ymusic.ui

import io.ymusic.download.DownloadState
import io.ymusic.download.DownloadTask
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import okio.FileSystem
import okio.Path.Companion.toPath


/** A YouTube video stays the same library entry regardless of the playlists containing it. */
internal data class CloudVideoKey(val videoId: String) {
    init { require(videoId.isNotBlank()) }
}

/** Separate playlist membership from the local audio download. Repeated video IDs remain separate rows. */
internal data class CloudPlaylistMembership(
    val playlistId: String,
    val itemKey: String,
    val video: CloudVideoKey,
) {
    init { require(playlistId.isNotBlank()); require(itemKey.isNotBlank()) }
}

/** Only create this after checking the downloaded audio exists on disk. */
internal data class VerifiedAudioCopy(val video: CloudVideoKey, val fileUri: String) {
    init { require(fileUri.isNotBlank()) }
}

internal sealed interface UnifiedPlaybackChoice {
    data class Local(val uri: String) : UnifiedPlaybackChoice
    data class Online(val videoId: String) : UnifiedPlaybackChoice
    data object UnavailableOffline : UnifiedPlaybackChoice
}

/** A local copy wins over streaming, with the YouTube video ID kept as the playlist identity. */
internal fun chooseUnifiedPlayback(
    video: CloudVideoKey,
    copies: Map<CloudVideoKey, VerifiedAudioCopy>,
    isOnline: Boolean,
): UnifiedPlaybackChoice {
    val file = copies[video]
    if (file != null) return UnifiedPlaybackChoice.Local(file.fileUri)
    return if (isOnline) UnifiedPlaybackChoice.Online(video.videoId)
    else UnifiedPlaybackChoice.UnavailableOffline
}

internal fun downloadedInPlaylist(
    membership: CloudPlaylistMembership,
    copies: Map<CloudVideoKey, VerifiedAudioCopy>,
): Boolean = membership.video in copies

/** Map existing download records to *one verified audio copy per YouTube video ID*.
 * Multiple playlist memberships still refer to the original video ID. A stale row never shadows a
 * newer valid file; completed video-only or interrupted downloads never appear as available audio. */
internal fun indexCompletedAudioDownloads(
    tasks: List<DownloadTask>,
    readable: (String) -> Boolean,
): Map<CloudVideoKey, VerifiedAudioCopy> =
    tasks.asSequence()
        .filter { it.state == DownloadState.COMPLETED && it.format.name in setOf("M4A", "MP3", "AUDIO_WEBM") }
        .sortedByDescending { it.updatedAt }
        .mapNotNull { task ->
            val uri = task.fileUri ?: return@mapNotNull null
            if (task.videoId.isBlank() || !readable(uri)) return@mapNotNull null
            val key = CloudVideoKey(task.videoId)
            key to VerifiedAudioCopy(key, uri)
        }
        .distinctBy { it.first }
        .toMap()

/** Filesystem boundary for both playback and downloaded indicators; never trusts a DB row alone. */
internal fun isReadableDownloadedFile(fileUri: String): Boolean = try {
    val uri = Uri.parse(fileUri)
    if (uri.scheme != "file") false else {
        val file = FileSystem.SYSTEM.metadataOrNull(uri.filePath().toPath())
        file?.isRegularFile == true && (file.size ?: 0L) > 0L
    }
} catch (_: Exception) {
    false
}
