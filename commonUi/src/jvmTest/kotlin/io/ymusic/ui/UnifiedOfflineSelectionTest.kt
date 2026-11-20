package io.ymusic.ui

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
}