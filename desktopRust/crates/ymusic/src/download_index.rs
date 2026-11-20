//! A read-only device-local index of completed downloads by YouTube video ID. Separate from
//! cloud playlist membership, which remains authoritative on YouTube. This also covers downloads
//! whose media-store tag scan has not run yet or could not extract tags.
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};

use tokio::runtime::Handle;
use tokio::sync::broadcast;
use ym_common::log::Logger;
use ym_download::{DownloadChange, DownloadService, DownloadState, DownloadTask};

pub type AudioIndex = Arc<RwLock<HashMap<String, Vec<PathBuf>>>>;

/// Rebuild after terminal state or removals. This avoids doing SQLite work on the GPUI thread,
/// and keeps a task registered without any destructive migration of the previous downloads.db.
pub fn start(service: DownloadService, tokio: &Handle, log: Logger) -> AudioIndex {
    let index: AudioIndex = Arc::default();
    let shared = index.clone();
    let mut changes = service.subscribe();
    tokio.spawn(async move {
        loop {
            match service.snapshot().await {
                Ok(tasks) => {
                    let next = index_tasks(tasks);
                    if let Ok(mut guard) = shared.write() {
                        *guard = next;
                    }
                }
                Err(error) => log.warn("readable downloads could not be indexed", Some(&error)),
            }
            loop {
                match changes.recv().await {
                    Ok(DownloadChange::Completed { .. }
                       | DownloadChange::Removed { .. }
                       | DownloadChange::StateChanged { .. }
                       | DownloadChange::FormatChanged { .. }) => break,
                    Ok(_) => {}
                    Err(broadcast::error::RecvError::Lagged(_)) => break,
                    Err(broadcast::error::RecvError::Closed) => return,
                }
            }
        }
    });
    index
}

pub fn index_tasks(mut tasks: Vec<DownloadTask>) -> HashMap<String, Vec<PathBuf>> {
    tasks.sort_by_key(|task| std::cmp::Reverse(task.updated_at));
    let mut result = HashMap::<String, Vec<PathBuf>>::new();
    for task in tasks {
        if task.state != DownloadState::Completed || task.format.is_video() || task.video_id.is_empty() {
            continue;
        }
        let Some(path) = task.file_uri.as_deref()
            .and_then(|uri| url::Url::parse(uri).ok())
            .and_then(|uri| uri.to_file_path().ok())
        else { continue };
        result.entry(task.video_id).or_default().push(path);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use ym_download::{AudioQuality, DownloadFormat};

    fn task(id: &str, video: &str, format: DownloadFormat, state: DownloadState, updated: i64) -> DownloadTask {
        DownloadTask {
            id: id.into(), video_id: video.into(), title: "test".into(), artist: None,
            thumbnail_url: None, format, requested_height: None, state,
            downloaded_bytes: 42, total_bytes: 42,
            file_uri: Some(format!("file:///C:/music/{id}.m4a")), error: None, created_at: 1,
            updated_at: updated, file_name: None, audio_track_id: None, captions: None,
            audio_language: None, caption_language: None, audio_quality: AudioQuality::Best,
            audio_stream: None, video_stream: None, loudness_gain_db: None,
        }
    }

    #[test]
    fn duplicates_are_one_cloud_video_with_newest_download_first() {
        let next = index_tasks(vec![
            task("older", "youtube-a", DownloadFormat::M4a, DownloadState::Completed, 1),
            task("newer", "youtube-a", DownloadFormat::M4a, DownloadState::Completed, 3),
            task("unfinished", "youtube-a", DownloadFormat::M4a, DownloadState::Paused, 6),
            task("video-only", "youtube-b", DownloadFormat::VideoMp4, DownloadState::Completed, 7),
        ]);
        assert_eq!(next.len(), 1);
        assert_eq!(next["youtube-a"].len(), 2);
        assert!(next["youtube-a"][0].to_string_lossy().contains("newer"));
    }
}
