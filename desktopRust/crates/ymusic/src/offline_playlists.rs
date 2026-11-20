//! Persist previously visited YouTube playlist *metadata* per signed-in account. Downloaded
//! bytes remain in ym-download/ym-store; this cache never duplicates or moves audio files.
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use ym_engine::yt::{YtFeedItem, YtFeedPage, YtPageHeader, YtSection, YtThumb};

pub const ACCOUNT_KEY: &str = "unified.offline.lastAuthenticatedChannelId";
const MAX_ENTRIES: usize = 20_000;
const VERSION: u32 = 1;

#[derive(Serialize, Deserialize)]
struct SavedVideo {
    id: String,
    title: String,
    channel_title: Option<String>,
    channel_url: Option<String>,
    subtitle_line1: Option<String>,
    duration: Option<String>,
    duration_ms: Option<i64>,
    thumb_url: Option<String>,
    live: bool,
}

impl SavedVideo {
    fn capture(video: &YtFeedItem) -> Self {
        Self {
            id: video.id.clone(),
            title: video.title.clone(),
            channel_title: video.channel_title.clone(),
            channel_url: video.channel_url.clone(),
            subtitle_line1: video.subtitle_line1.clone(),
            duration: video.duration.clone(),
            duration_ms: video.duration_ms,
            thumb_url: video.thumbs.first().map(|thumb| thumb.url.clone()),
            live: video.live,
        }
    }

    fn into_feed(self) -> YtFeedItem {
        YtFeedItem {
            kind: "video".to_owned(),
            id: self.id,
            title: self.title,
            channel_title: self.channel_title,
            channel_url: self.channel_url,
            subtitle_line1: self.subtitle_line1,
            duration: self.duration,
            duration_ms: self.duration_ms,
            thumbs: self.thumb_url.map(|url| vec![YtThumb { url, w: None, h: None }]).unwrap_or_default(),
            live: self.live,
            ..Default::default()
        }
    }
}

#[derive(Serialize, Deserialize)]
struct SavedPlaylist {
    version: u32,
    account: String,
    url: String,
    title: Option<String>,
    item_count: Option<i32>,
    videos: Vec<SavedVideo>,
}

impl SavedPlaylist {
    fn as_page(self) -> YtFeedPage {
        YtFeedPage {
            sections: vec![YtSection {
                items: self.videos.into_iter().map(SavedVideo::into_feed).collect(),
                ..Default::default()
            }],
            continuation: None,
            page_header: Some(YtPageHeader {
                title: self.title,
                item_count: self.item_count,
                ..Default::default()
            }),
            ..Default::default()
        }
    }
}

pub struct OfflinePlaylists {
    directory: PathBuf,
    lock: Mutex<()>,
}

impl OfflinePlaylists {
    pub fn new(directory: PathBuf) -> Self {
        Self { directory, lock: Mutex::new(()) }
    }

    fn file(&self, account: &str, url: &str) -> PathBuf {
        // Same stable 64-bit FNV-1a key as Kotlin's cache. The body independently verifies the
        // exact channel ID and URL: collisions cannot expose another account's playlists.
        let mut fingerprint = 0xcbf29ce484222325_u64;
        for byte in format!("{account}:{url}").bytes() {
            fingerprint ^= u64::from(byte);
            fingerprint = fingerprint.wrapping_mul(0x100000001b3);
        }
        self.directory.join(format!("playlist-{fingerprint:016x}.json"))
    }

    fn read_file(path: &Path) -> Option<SavedPlaylist> {
        let saved: SavedPlaylist = serde_json::from_slice(&fs::read(path).ok()?).ok()?;
        (saved.version == VERSION).then_some(saved)
    }

    fn read(&self, account: &str, url: &str) -> Option<SavedPlaylist> {
        let saved = Self::read_file(&self.file(account, url))?;
        (saved.account == account && saved.url == url).then_some(saved)
    }

    pub fn load(&self, account: &str, url: &str) -> Option<YtFeedPage> {
        let _guard = self.lock.lock().ok()?;
        self.read(account, url).map(SavedPlaylist::as_page)
    }

    pub fn save_page(
        &self,
        account: &str,
        url: &str,
        page: &YtFeedPage,
        continuation: Option<&str>,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        if account.is_empty() || url.is_empty() {
            return Ok(());
        }
        let _guard = self.lock.lock().map_err(|error| error.to_string())?;
        let old = continuation.and_then(|_| self.read(account, url));
        let mut videos = old.as_ref().map_or_else(Vec::new, |previous| {
            previous.videos.iter().map(|video| SavedVideo {
                id: video.id.clone(),
                title: video.title.clone(),
                channel_title: video.channel_title.clone(),
                channel_url: video.channel_url.clone(),
                subtitle_line1: video.subtitle_line1.clone(),
                duration: video.duration.clone(),
                duration_ms: video.duration_ms,
                thumb_url: video.thumb_url.clone(),
                live: video.live,
            }).collect()
        });
        videos.extend(
            page.sections.iter().flat_map(|section| &section.items)
                .filter(|item| item.kind == "video" && !item.id.is_empty())
                .map(SavedVideo::capture),
        );
        videos.truncate(MAX_ENTRIES);
        let saved = SavedPlaylist {
            version: VERSION,
            account: account.to_owned(),
            url: url.to_owned(),
            title: page.page_header.as_ref().and_then(|header| header.title.clone())
                .or_else(|| old.as_ref().and_then(|previous| previous.title.clone())),
            item_count: page.page_header.as_ref().and_then(|header| header.item_count)
                .or_else(|| old.as_ref().and_then(|previous| previous.item_count)),
            videos,
        };
        fs::create_dir_all(&self.directory)?;
        let target = self.file(account, url);
        let temp = target.with_extension("json.tmp");
        fs::write(&temp, serde_json::to_vec(&saved)?)?;
        // Windows RenameFile does not replace an existing target. Preserve the previous
        // snapshot as a backup until the new snapshot is safely published.
        let backup = target.with_extension("json.bak");
        if target.exists() {
            let _ = fs::remove_file(&backup);
            if let Err(error) = fs::rename(&target, &backup) {
                let _ = fs::remove_file(&temp);
                return Err(Box::new(error));
            }
        }
        if let Err(error) = fs::rename(&temp, &target) {
            if backup.exists() {
                let _ = fs::rename(&backup, &target);
            }
            let _ = fs::remove_file(&temp);
            return Err(Box::new(error));
        }
        let _ = fs::remove_file(&backup);
        Ok(())
    }

    fn list(&self, account: &str) -> Vec<SavedPlaylist> {
        if account.is_empty() {
            return Vec::new();
        }
        fs::read_dir(&self.directory)
            .into_iter().flatten().flatten()
            .filter_map(|entry| Self::read_file(&entry.path()))
            .filter(|saved| saved.account == account)
            .collect()
    }

    /// Previously visited cloud playlists are navigable even if the account feed cannot load.
    pub fn account_page(&self, account: &str) -> Option<YtFeedPage> {
        let _guard = self.lock.lock().ok()?;
        let mut saved = self.list(account);
        if saved.is_empty() {
            return None;
        }
        saved.sort_by(|a, b| a.title.cmp(&b.title));
        let items = saved.into_iter().map(|playlist| YtFeedItem {
            kind: "playlist".to_owned(),
            id: playlist.url.clone(),
            title: playlist.title.unwrap_or_else(|| playlist.url.clone()),
            url: Some(playlist.url),
            item_count: playlist.item_count.map(|count| count.to_string()),
            ..Default::default()
        }).collect();
        Some(YtFeedPage {
            sections: vec![YtSection { items, ..Default::default() }],
            ..Default::default()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_store() -> (PathBuf, OfflinePlaylists) {
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let root = std::env::temp_dir().join(format!("ymusic-unified-{}-{nanos}", std::process::id()));
        (root.clone(), OfflinePlaylists::new(root))
    }

    fn page(items: &[(&str, &str)]) -> YtFeedPage {
        YtFeedPage {
            sections: vec![YtSection {
                items: items.iter().map(|(id, title)| YtFeedItem {
                    kind: "video".into(), id: (*id).into(), title: (*title).into(),
                    remove_token: Some("stale-server-token".into()), ..Default::default()
                }).collect(), ..Default::default()
            }],
            page_header: Some(YtPageHeader { title: Some("My remixes".into()), ..Default::default() }),
            ..Default::default()
        }
    }

    #[test]
    fn offline_playlist_preserves_order_and_repeated_video_ids() {
        let (dir, store) = temp_store();
        store.save_page("UC-account", "/playlist?list=PLone", &page(&[("a", "A"), ("b", "B")]), None).unwrap();
        store.save_page("UC-account", "/playlist?list=PLone", &page(&[("a", "A again")]), Some("token")).unwrap();
        let cached = store.load("UC-account", "/playlist?list=PLone").unwrap();
        assert_eq!(cached.sections[0].items.iter().map(|it| it.id.as_str()).collect::<Vec<_>>(), vec!["a", "b", "a"]);
        assert!(cached.sections[0].items.iter().all(|item| item.remove_token.is_none()));
        assert!(cached.continuation.is_none());
        assert!(store.load("UC-other", "/playlist?list=PLone").is_none());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn account_feed_links_only_own_saved_playlists() {
        let (dir, store) = temp_store();
        store.save_page("UC-a", "/playlist?list=PLone", &page(&[("x", "Unofficial remix")]), None).unwrap();
        store.save_page("UC-b", "/playlist?list=PLtwo", &page(&[("y", "Song")]), None).unwrap();
        let page = store.account_page("UC-a").unwrap();
        assert_eq!(page.sections[0].items.len(), 1);
        assert_eq!(page.sections[0].items[0].url.as_deref(), Some("/playlist?list=PLone"));
        assert!(store.account_page("UC-c").is_none());
        let _ = fs::remove_dir_all(dir);
    }
}
