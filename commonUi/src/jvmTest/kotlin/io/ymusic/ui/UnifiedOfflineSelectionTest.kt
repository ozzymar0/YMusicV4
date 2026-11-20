package io.ymusic.ui

import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadState
import io.ymusic.download.DownloadTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedOfflineSelectionTest {
    private val video = CloudVideoKey("abcd1234xyz")
    private val saved = mapOf(video to VerifiedAudioCopy(video, "file:///music/song.m4a"))

    @Test fun localAudioWinsEvenWhenOnline() {
        assertEquals(UnifiedPlaybackChoice.Local("file:///music/song.m4a"), chooseUnifiedPlayback(video, saved, true))
    }
    @Test fun localAudioWinsWhenOffline() {
        assertEquals(UnifiedPlaybackChoice.Local("file:///music/song.m4a"), chooseUnifiedPlayback(video, saved, false))
    }
    @Test fun oneDownloadIsSharedByMultiplePlaylistEntries() {
        val one = CloudPlaylistMembership("PL_1", "first", video)
        val two = CloudPlaylistMembership("PL_2", "second", video)
        assertTrue(downloadedInPlaylist(one, saved))
        assertTrue(downloadedInPlaylist(two, saved))
        assertEquals("first", one.itemKey)
        assertEquals("second", two.itemKey)
    }
    @Test fun noDownloadStreamsWhenConnected() {
        assertEquals(UnifiedPlaybackChoice.Online(video.videoId), chooseUnifiedPlayback(video, emptyMap(), true))
    }
    @Test fun offlineMissingCopyIsUnavailable() {
        assertEquals(UnifiedPlaybackChoice.UnavailableOffline, chooseUnifiedPlayback(video, emptyMap(), false))
    }
    @Test fun removingDownloadNeverRemovesPlaylistMembership() {
        val membership = CloudPlaylistMembership("PL_1", "item", video)
        assertFalse(downloadedInPlaylist(membership, emptyMap()))
        assertEquals(video, membership.video)
        assertEquals("PL_1", membership.playlistId)
    }

    private fun download(
        id: String,
        state: DownloadState = DownloadState.COMPLETED,
        uri: String? = "file:///music/$id.m4a",
        format: DownloadFormat = DownloadFormat.M4A,
        updated: Long = 1L,
    ) = DownloadTask(
        id = id,
        videoId = "abcd1234xyz",
        title = "Unofficial remix",
        artist = "Uploader",
        thumbnailUrl = null,
        format = format,
        requestedHeight = null,
        state = state,
        downloadedBytes = 100L,
        totalBytes = 100L,
        fileUri = uri,
        errorMessage = null,
        createdAt = 0L,
        updatedAt = updated,
    )

    @Test fun onlyVerifiedCompletedAudioIsIndexed() {
        val records = listOf(
            download("paused", state = DownloadState.PAUSED),
            download("video", format = DownloadFormat.VIDEO_MP4),
            download("stale", updated = 9L),
            download("good", updated = 2L),
        )
        val copies = indexCompletedAudioDownloads(records) { it == "file:///music/good.m4a" }
        assertEquals("file:///music/good.m4a", copies[video]?.fileUri)
        assertEquals(1, copies.size)
    }

    @Test fun duplicateDownloadsChooseNewestReadableCopy() {
        val records = listOf(download("old", updated = 1L), download("new", updated = 5L))
        val copies = indexCompletedAudioDownloads(records) { true }
        assertEquals("file:///music/new.m4a", copies[video]?.fileUri)
    }

    @Test fun deletingDownloadRemovesOnlyLocalAvailability() {
        val membership = CloudPlaylistMembership("PL_one", "entry", video)
        val found = indexCompletedAudioDownloads(listOf(download("good"))) { true }
        val removed = indexCompletedAudioDownloads(emptyList()) { true }
        assertTrue(downloadedInPlaylist(membership, found))
        assertFalse(downloadedInPlaylist(membership, removed))
        assertEquals("entry", membership.itemKey)
    }
}