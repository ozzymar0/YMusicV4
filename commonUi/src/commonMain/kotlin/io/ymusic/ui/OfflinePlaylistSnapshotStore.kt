package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtPageHeader
import io.ymusic.dynamicengine.yt.YtSection
import io.ymusic.dynamicengine.yt.YtThumb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Account-scoped device-local playlist metadata. A successful online browse replaces/extends the
 * saved snapshot. Only a failed server fetch reads it; YouTube remains authoritative.
 * Removal tokens are intentionally not cached because offline mutations cannot use them.
 */
class OfflinePlaylistSnapshotStore(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val lock = Mutex()

    suspend fun load(accountId: String, url: String): YtFeedPage? = withContext(Dispatchers.IO) {
        lock.withLock { read(accountId, url)?.asPage() }
    }

    /** Playlists saved on this device, for the account tab when the network is unavailable. */
    suspend fun list(accountId: String): List<OfflinePlaylistLink> = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext emptyList()
        lock.withLock {
            fileSystem.listOrNull(directory).orEmpty().asSequence()
                .filter { it.name.startsWith("playlist-") && it.name.endsWith(".json") }
                .mapNotNull { path ->
                    try {
                        val root = Json.parseToJsonElement(fileSystem.source(path).buffer().use { it.readUtf8() }).jsonObject
                        if (root["version"]?.jsonPrimitive?.content != "1" ||
                            root["account"]?.jsonPrimitive?.content != accountId) return@mapNotNull null
                        val url = root["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val title = root["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: url
                        OfflinePlaylistLink(
                            url = url,
                            title = title,
                            count = root["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                .sortedBy { it.title.lowercase() }
                .toList()
        }
    }

    suspend fun savePage(accountId: String, url: String, page: YtFeedPage, continuation: String?) {
        if (accountId.isBlank() || url.isBlank()) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                val previous = if (continuation == null) null else read(accountId, url)
                val added = page.sections.flatMap { it.items }
                    .filter { it.kind == YtFeedItem.KIND_VIDEO && it.id.isNotBlank() }
                val items = (previous?.items.orEmpty() + added).take(MAX_ENTRIES)
                val root = buildJsonObject {
                    put("version", 1)
                    put("account", accountId)
                    put("url", url)
                    (page.pageHeader?.title ?: previous?.title)?.let { put("title", it) }
                    (page.pageHeader?.itemCount ?: previous?.itemCount)?.let { put("count", it) }
                    put("items", buildJsonArray {
                        for (video in items) add(buildJsonObject {
                            put("id", video.id)
                            put("title", video.title)
                            video.channelTitle?.let { put("channel", it) }
                            video.channelUrl?.let { put("channelUrl", it) }
                            video.subtitleLine1?.let { put("subtitle", it) }
                            video.duration?.let { put("duration", it) }
                            video.durationMs?.let { put("durationMs", it) }
                            video.thumbs.firstOrNull()?.let { put("thumb", it.url) }
                            put("live", video.live)
                        })
                    })
                }
                fileSystem.createDirectories(directory)
                val target = file(accountId, url)
                val temp = directory / (target.name + ".tmp")
                try {
                    fileSystem.sink(temp).buffer().use { it.writeUtf8(root.toString()) }
                    fileSystem.atomicMove(temp, target)
                } finally {
                    fileSystem.delete(temp, mustExist = false)
                }
            }
        }
    }

    private data class Snapshot(val title: String?, val itemCount: Int?, val items: List<YtFeedItem>) {
        fun asPage(): YtFeedPage = YtFeedPage(
            sections = listOf(YtSection(items = items)),
            continuation = null,
            pageHeader = YtPageHeader(title = title, itemCount = itemCount),
        )
    }

    private fun read(accountId: String, url: String): Snapshot? {
        return try {
            val target = file(accountId, url)
            if (!fileSystem.exists(target)) return null
            val root = Json.parseToJsonElement(fileSystem.source(target).buffer().use { it.readUtf8() }).jsonObject
            if (root["version"]?.jsonPrimitive?.content != "1" ||
                root["account"]?.jsonPrimitive?.content != accountId ||
                root["url"]?.jsonPrimitive?.content != url) return null
            val items = root["items"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                YtFeedItem(
                    kind = YtFeedItem.KIND_VIDEO,
                    id = id,
                    title = title,
                    channelTitle = obj["channel"]?.jsonPrimitive?.contentOrNull,
                    channelUrl = obj["channelUrl"]?.jsonPrimitive?.contentOrNull,
                    subtitleLine1 = obj["subtitle"]?.jsonPrimitive?.contentOrNull,
                    duration = obj["duration"]?.jsonPrimitive?.contentOrNull,
                    durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull,
                    thumbs = obj["thumb"]?.jsonPrimitive?.contentOrNull?.let { listOf(YtThumb(it)) }.orEmpty(),
                    live = obj["live"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.orEmpty()
            Snapshot(
                title = root["title"]?.jsonPrimitive?.contentOrNull,
                itemCount = root["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                items = items,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private fun file(accountId: String, url: String): Path {
        // Deterministic FNV-1a file key. Read also checks exact URL and account for safety.
        val identity = accountId + ":" + url
        val fingerprint = identity.encodeToByteArray().fold(0xcbf29ce484222325UL) { hash, byte ->
            (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
        }
        return directory / ("playlist-" + fingerprint.toString(16) + ".json")
    }

    private companion object { const val MAX_ENTRIES = 20_000 }
}

/** Clear on explicit sign-out or account switch; never persist authentication secrets. */
internal const val LastAuthenticatedChannelId = "unified.offline.lastAuthenticatedChannelId"

/** A playlist previously loaded on this device, with a private account-scoped snapshot. */
internal data class OfflinePlaylistLink(val url: String, val title: String, val count: Int?)
