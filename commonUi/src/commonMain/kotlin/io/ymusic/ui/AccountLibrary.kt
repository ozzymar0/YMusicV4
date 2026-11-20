package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtAccountInfo
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.account_library_empty
import io.ymusic.ui.resources.account_liked_videos
import io.ymusic.ui.resources.account_my_channel
import io.ymusic.ui.resources.account_sign_out
import io.ymusic.ui.resources.account_subscriptions
import io.ymusic.ui.resources.account_switch
import io.ymusic.ui.resources.account_watch_history
import io.ymusic.ui.resources.account_watch_later
import org.jetbrains.compose.resources.stringResource

/**
 * The signed-in Discover "Library" tab (design/final/library.html): the account header (avatar ·
 * name · email · overflow), the account nav — rows in portrait, chips on a wide window — and the
 * engine's library feed sections (History / Playlists / Watch later / Liked videos) below, sharing
 * the standard [FeedList] list/grid responsiveness.
 */
@Composable
internal fun AccountLibraryScreen(
    session: FeedSession,
    coordinator: PlaybackCoordinator,
    account: YtAccountInfo?,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    onOpen: ((YtFeedItem) -> Unit)?,
    onDeeplink: ((link: String, title: String?) -> Unit)?,
) {
    val feedState by session.pager.state.collectAsState()
    if (feedState is Pager.State.Loading) {
        CenteredBox { CircularProgressIndicator() }
        return
    }
    if (feedState is Pager.State.Failed && coordinator.graph.offlinePlaylistSnapshots != null) {
        OfflineAccountPlaylists(coordinator.graph, onOpen)
        return
    }
    FeedList(
        session.pager,
        coordinator,
        emptyMessage = stringResource(Res.string.account_library_empty),
        onOpen = onOpen,
        listState = session.listState,
        onDeeplink = onDeeplink,
        topContent = account?.let { acct ->
            { AccountLibraryHeader(acct, onSignOut, onSwitchAccount, onDeeplink) }
        },
    )
}

/** Offline-only view of previously visited cloud playlists, not locally-created playlists. */
@Composable
internal fun OfflineAccountPlaylists(
    graph: AppGraph,
    onOpen: ((YtFeedItem) -> Unit)?,
    onSignIn: (() -> Unit)? = null,
) {
    val store = graph.offlinePlaylistSnapshots
    val accountId = graph.account.account.value?.channelId
        ?: graph.settings.string(LastAuthenticatedChannelId, "").value
    val entries by produceState(emptyList<OfflinePlaylistLink>(), store, accountId) {
        value = if (accountId.isBlank() || store == null) emptyList() else store.list(accountId)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Saved YouTube playlists (offline)",
                style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
        }
        if (entries.isEmpty()) {
            item { Text("No previously opened playlists are saved on this device.", color = DesignColors.InkSecondary) }
        }
        items(entries, key = { it.url }) { entry ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DesignColors.SurfaceDim)
                    .clickable(enabled = onOpen != null) {
                        onOpen?.invoke(
                            YtFeedItem(
                                kind = YtFeedItem.KIND_PLAYLIST,
                                id = entry.url,
                                title = entry.title,
                                url = entry.url,
                            )
                        )
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Subscriptions, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(entry.title, fontWeight = FontWeight.Medium, color = DesignColors.Ink)
                    entry.count?.let { count ->
                        Text(count.toString() + " songs", color = DesignColors.InkSecondary)
                    }
                }
            }
        }
        if (onSignIn != null) {
            item { OutlinedButton(onClick = onSignIn) { Text("Sign in") } }
        }
    }
}

@Composable
private fun AccountLibraryHeader(
    account: YtAccountInfo,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    onDeeplink: ((link: String, title: String?) -> Unit)?,
) {
    Column {
        AccountCard(account, onSignOut, onSwitchAccount)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = DesignColors.Divider)
        // The nav targets are the app's OWN links in the one scheme it parses (Deeplink.kt): the
        // account's channel, its own feeds, and YouTube's eternal Watch-later/Liked pseudo-playlists.
        // These are fixed chrome (icons + localized labels), not engine content — a deliberate exception
        // to "urls come from the engine": if WL/LL ever rot, no engine-composed url would save
        // rows the app draws itself.
        val name = account.name
        val navRows = buildList {
            account.channelId?.let {
                add(NavTarget(Icons.Outlined.AccountCircle, stringResource(Res.string.account_my_channel), "/channel/$it", name))
            }
            add(NavTarget(Icons.Outlined.History, stringResource(Res.string.account_watch_history), "/feed/history", null))
            add(NavTarget(Icons.Outlined.Schedule, stringResource(Res.string.account_watch_later), "/playlist?list=WL", null))
            add(NavTarget(Icons.Outlined.ThumbUp, stringResource(Res.string.account_liked_videos), "/playlist?list=LL", null))
            add(NavTarget(Icons.Outlined.Subscriptions, stringResource(Res.string.account_subscriptions), "/feed/subscriptions", null))
        }
        BoxWithConstraints {
            if (railStableWidth(maxWidth) >= 600.dp) {
                val chipScroll = rememberScrollState()
                Row(
                    Modifier
                        .horizontalMouseDragScroll(chipScroll)
                        .horizontalScroll(chipScroll)
                        .padding(horizontal = DesignSpacing.MarginCompact, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    navRows.forEach { t -> AccountNavChip(t, onDeeplink) }
                }
            } else {
                Column(Modifier.padding(vertical = 4.dp)) {
                    navRows.forEach { t -> AccountNavRow(t, onDeeplink) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = DesignColors.Divider)
    }
}

private class NavTarget(
    val icon: ImageVector,
    val label: String,
    val url: String,
    /** Seeds the opened page's header while it loads (the account name for My channel). */
    val seedTitle: String?,
)

@Composable
private fun BoxScope.AccountAvatarFallback(name: String?) {
    Box(
        Modifier.matchParentSize().background(DesignColors.Ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.firstOrNull()?.uppercaseChar()
        if (initial != null) {
            Text(
                initial.toString(),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.OnInk),
            )
        } else {
            Icon(Icons.Filled.Person, null, tint = DesignColors.OnInk)
        }
    }
}

@Composable
private fun AccountCard(account: YtAccountInfo, onSignOut: () -> Unit, onSwitchAccount: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 15.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(46.dp).a11ySilent()) {
            // The disc only backs the fallback: under the photo its AA edge bleeds through as a dark ring.
            val fallback: @Composable BoxScope.() -> Unit = { AccountAvatarFallback(account.name) }
            if (account.thumbs.isNotEmpty()) ThumbImage(account.thumbs, CircleShape, fallback) else fallback()
        }
        Column(Modifier.weight(1f)) {
            Text(
                account.name.orEmpty(),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            (account.email ?: account.handle)?.let { line ->
                Text(
                    line,
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MoreMenu(
            ItemActions(
                buildList {
                    if (platformUsesTouch) {
                        add(stringResource(Res.string.account_switch) to onSwitchAccount)
                    }
                    add(stringResource(Res.string.account_sign_out) to onSignOut)
                },
            ),
            inFocusableItem = false,
        )
    }
}

@Composable
private fun AccountNavRow(target: NavTarget, onDeeplink: ((String, String?) -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onDeeplink?.invoke(target.url, target.seedTitle ?: target.label) }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(target.icon, null, Modifier.size(24.dp), tint = DesignColors.InkMuted)
        Text(target.label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DesignColors.Ink))
    }
}

@Composable
private fun AccountNavChip(target: NavTarget, onDeeplink: ((String, String?) -> Unit)?) {
    val shape = RoundedCornerShape(999.dp)
    ChipTapArea({ onDeeplink?.invoke(target.url, target.seedTitle ?: target.label) }) { indication ->
        Row(
            Modifier
                .border(1.dp, DesignColors.OutlineSoft, shape)
                .clip(shape)
                .then(indication)
                .chipPadding(leadingIcon = true),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(target.icon, null, Modifier.size(24.dp), tint = DesignColors.InkMuted)
            Text(target.label, style = TextStyle(fontSize = ChipTextSize, color = DesignColors.Ink), maxLines = 1)
        }
    }
}
