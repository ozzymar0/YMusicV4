package io.ymusic.ui

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
