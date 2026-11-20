package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtPageHeader
import io.ymusic.dynamicengine.yt.YtSection
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflinePlaylistSnapshotStoreTest {
    private fun video(id: String, title: String = id) = YtFeedItem(
        kind = YtFeedItem.KIND_VIDEO, id = id, title = title, removeToken = "server-only-token"
    )

    private fun page(vararg songs: YtFeedItem) = YtFeedPage(
        sections = listOf(YtSection(items = songs.toList())),
        pageHeader = YtPageHeader(title = "My YouTube playlist"),
    )

    private fun withStore(block: suspend (OfflinePlaylistSnapshotStore) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("ymusic-playlist-snapshot").toString().toPath()
        try {
            block(OfflinePlaylistSnapshotStore(dir))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
        }
    }

    @Test fun offlineSnapshotKeepsOrderAndDuplicateEntries() = withStore { store ->
        store.savePage("UC-one", "/playlist?list=PLone", page(video("v1"), video("v2"), video("v1")), null)
        val items = store.load("UC-one", "/playlist?list=PLone")!!.sections.single().items
        assertEquals(listOf("v1", "v2", "v1"), items.map { it.id })
        assertEquals("My YouTube playlist", store.load("UC-one", "/playlist?list=PLone")!!.pageHeader?.title)
        assertEquals(listOf(null, null, null), items.map { it.removeToken })
    }

    @Test fun continuationAppendsRatherThanReplacing() = withStore { store ->
        store.savePage("UC-one", "/playlist?list=PLone", page(video("v1")), null)
        store.savePage("UC-one", "/playlist?list=PLone", page(video("v2")), "continuation-token")
        assertEquals(listOf("v1", "v2"), store.load("UC-one", "/playlist?list=PLone")!!.sections.single().items.map { it.id })
        assertNull(store.load("UC-one", "/playlist?list=PLone")!!.continuation)
    }

    @Test fun accountAndPlaylistAreIsolated() = withStore { store ->
        store.savePage("UC-one", "/playlist?list=PLone", page(video("v1")), null)
        assertNull(store.load("UC-two", "/playlist?list=PLone"))
        assertNull(store.load("UC-one", "/playlist?list=PLtwo"))
    }

    @Test fun cloudRemovalUpdatesOnlyOneRepeatedPlaylistEntry() = withStore { store ->
        store.savePage("UC-own", "/playlist?list=PLone", page(video("v1"), video("v2"), video("v1")), null)
        assertEquals(true, store.removeCachedVideo("UC-own", "/playlist?list=PLone", "v1"))
        assertEquals(listOf("v2", "v1"),
            store.load("UC-own", "/playlist?list=PLone")!!.sections.single().items.map { it.id })
        assertEquals(false, store.removeCachedVideo("UC-other", "/playlist?list=PLone", "v1"))
        assertEquals(false, store.removeCachedVideo("UC-own", "/playlist?list=PLone", "missing"))
    }

    @Test fun newOnlinePageReplacesOldSnapshot() = withStore { store ->
        store.savePage("UC-one", "/playlist?list=PLone", page(video("old")), null)
        store.savePage("UC-one", "/playlist?list=PLone", page(video("new")), null)
        assertEquals(listOf("new"), store.load("UC-one", "/playlist?list=PLone")!!.sections.single().items.map { it.id })
    }
}
