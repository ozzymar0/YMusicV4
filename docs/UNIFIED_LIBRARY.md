# Unified YouTube library — custom fork implementation

This document describes the code on `feature/unified-offline-library`. The branch remains a draft PR and must not be merged until tests and platform builds pass.

## Existing cloud identity

A YouTube playlist entry is a video ID within a specific YouTube playlist. The authenticated YouTube engine already retrieves playlists and supports adding videos and creating playlists on supported account sessions; it uses ordinary YouTube video IDs, not a YouTube Music-only track list. This implementation does not substitute local playlists for YouTube playlists.

A video can appear in many cloud playlists. Its device-local audio belongs to the **video ID**, not to an individual playlist membership. Downloading it never removes or duplicates the cloud entry. The new `CloudPlaylistMembership` and `CloudVideoKey` model this distinction; the app's existing cloud feed remains authoritative.

## Device-local downloads

- **Kotlin (Android/iOS/shared UI)**: Completed rows in the existing SQLDelight download database already store `videoId`, `state`, `format`, `fileUri`, and update time. `indexCompletedAudioDownloads` picks the newest readable M4A/MP3/Opus file per video ID, ignoring failed, paused, video-only, missing, and zero-byte files. No download table migration is required.
- **iOS**: `MainViewController.kt` now creates the native `DownloadService` and SQLDelight download driver. Finished M4A files are published under the application's Application Support `ymusic/downloads` folder, with scratch bytes under `ymusic/download-work`. These are not cache directories and survive normal app upgrades. iOS has no FFmpeg executable: the existing downloader publishes original AAC-in-M4A audio without transcoding. It uses the existing signed-in cookie session and MSE player for resolving downloads.
- **Windows Rust**: `ym-store::MediaStore::audio_copies_for_youtube_video` uses existing `youtubeVid` metadata. The new `download_index.rs` separately reads the existing `downloads.db` on a Tokio worker and maintains a by-video-ID map of completed audio files. The player checks that map before the media-store candidates, so a valid download can play even if media tag scanning has not completed. Every load verifies that the file actually exists and is nonempty before falling back to a YouTube stream. Existing downloads and media tables are preserved.

All stored paths remain on their original devices. This fork does not attempt to sync audio bytes across devices or share their download-state database.

## Playback and indicators

The Kotlin `PlaybackCoordinator` selects a verified local copy before constructing a playlist, single-video, or radio queue. Next playlist pages are adapted through a `QueueSource` wrapper. Local and online entries keep the same `videoId`, title, artwork and playlist ordering. The Rust desktop similarly retains the cloud video ID when it changes a queue item's playback source.

The shared `AvailableOffline` indicator reads both the media store (when present) and verified download records. Windows also merges verified media-store files with verified completed download tasks, and listens for download completion/removal. A missing or zero-byte file is not marked offline just because the database mentions it.

On iOS, **Remove from disk** invokes `DownloadService.deleteFinishedFile`. It only deletes completed downloads immediately inside the current app download directory, then removes that download row. It does not modify a YouTube playlist. The older **Remove** (download-history-only) action in other parts of the UI remains separate to avoid unexpectedly destroying existing user files.

## Previously visited playlists while offline (Kotlin/iOS)

A successful YouTube playlist detail fetch writes the playable video entries (video IDs, titles, artwork references and order) to an account-scoped JSON snapshot in Application Support. Continuation pages append to that snapshot. Failed network fetches fall back to a matching saved playlist; previously saved playlists are also listed in the Account tab if the startup account probe cannot connect. The account ID is the last confirmed YouTube channel ID, cleared on explicit sign-out or account switch. Cached mutation tokens are never used, and an online fetch remains authoritative.

Windows Rust also saves successfully loaded playlist pages as JSON under the application's existing data directory. Its account feed can fall back to locally stored playlist links when YouTube is unreachable; the playlist detail feed can fall back to saved ordered song entries. The cache follows the same per-channel-ID isolation and is cleared from account access on explicit logout or account switch. The Rust store preserves duplicate video entries, never persists server-side removal tokens, and does not move audio files.

Both iOS and Windows have an explicit **Save complete playlist list offline** action. It fetches all available continuation pages, keeps repeated entries in their original order, and publishes the combined snapshot only after every page succeeds. If fetching fails partway through, the existing snapshot is retained. This action saves metadata, not audio bytes: to play fully offline, download the desired videos separately. Successful cloud playlist item removal updates the offline metadata on that device without deleting an audio download.

This is **device-local metadata caching**, not a synchronized offline database. For offline access, open the playlist at least once or use the complete-save action while connected on each device. Cloud membership itself stays on YouTube, so signed-in devices see edits upon online refresh; downloaded audio remains strictly device-local.

## Data-preservation rules

No existing `downloads.db`, local `media-store.db`, cookie jar or playlist has been reset, rewritten or migrated. No downloaded file is moved at application startup. The new iOS files use the app's established Application Support area; the existing Rust and Kotlin local libraries remain at their prior locations. The added code does not write to YouTube playlists on download or removal.

## Known limitations — not yet implemented

1. **Offline playlist browsing needs a prior visit or explicit save.** Previously opened playlist pages persist, and the complete-save action can fetch all continuation pages. Playlists never saved/fetched on that device remain unavailable offline. Snapshots do not sync automatically between devices; YouTube is authoritative whenever reachable.
2. **Full bidirectional playlist editing has not been verified.** Existing authentication/engine code supports cloud feed retrieval, add and create operations. Delete, reorder, rename and multi-device propagation still need integration and live-account tests.
3. **Network reachability is not yet part of queue source selection.** A missing local copy falls back to the YouTube player, whose network error handling applies even when fully offline.
4. **Kotlin queues resolve existing downloaded files at construction/page-load time, not before every next-track load.** A file removed during an active queue may cause a local file-missing error. Rust re-resolves on each load.
5. **iOS still has no local media scanner or traditional local Library database in the UI.** Its new device downloads are indexed via the download DB; it does not yet support importing arbitrary user files.
6. **Sideload and playback on a physical iPhone have not been validated.** An ARM64 unsigned IPA for the modified app compiled and passed artifact checks on the previous feature commit; current changes still need their own CI run. Install with Sideloadly and test with an actual signed-in account.
7. Upstream's existing YouTube/media downloader may be constrained by availability, account session, or provider behavior. Local media permission and YouTube Terms may affect playback and downloading.

## Validation

CI workflows on this branch:

- `.github/workflows/ios-unsigned-ipa.yml`: macOS 26/Xcode 26.4.1, JDK 21, Node 22, pinned FFmpeg static libraries, Kotlin Multiplatform framework and Swift Xcode app, unsigned `iphoneos` ARM64 IPA. Packaging validates `Payload/iosApp.app`, `YMusicApp.framework`, `Info.plist` and ARM64 Mach-O slices. This is an **unsigned artifact**, not an App Store or Sideloadly-signed IPA.
- `.github/workflows/library-checks.yml`: shared Kotlin JVM tests on Ubuntu plus a native MinGW/GCC Windows x64 job. Windows builds the pinned FFmpeg dependencies and JavaScript bundles, runs media-store, local-source, offline-snapshot and download-index Rust tests, checks the desktop application, then packages a portable production `YMusic.exe` with `ffmpeg.exe` and required DLLs into the `YMusic-Windows-x64-unified-offline-portable` artifact.
- `.github/workflows/upstream-updates.yml` on `master`: scheduled/manual upstream proposal using a disposable branch and a draft PR; no automatic merge. Upstream workflow-file changes are separately reported when the Actions token cannot push them.

Tests in `UnifiedOfflineSelectionTest` cover playlist identity, device-local copy priority, stream fallback, unavailable-offline policy, completed-vs-paused/video-only records, missing copies, deduplication and post-removal membership. Kotlin snapshot tests cover order, repeated video IDs, continuation appending, replacement on server refresh, and account isolation. Rust playback and snapshot tests cover cloud identity, missing-file fallback, cached playlist order and isolation. Tests may not pass until CI compilation and runtime dependencies are confirmed.

**CI status is determined by GitHub Actions, not by this document.** Do not mark the modified app as working until both relevant jobs and the final IPA artifact pass.

## Installation after a successful Windows build

1. Open the successful `Verify unified library (Kotlin and Windows)` Actions run on this branch.
2. Download the `YMusic-Windows-x64-unified-offline-portable` artifact and extract the directory.
3. Launch `YMusic.exe` on Windows x64, with `ffmpeg.exe` and the supplied DLLs in the same directory. This is a portable unsigned build, not an installer.
4. Sign into the same YouTube account as the iOS app. Confirm that a shared cloud playlist is fetched on both platforms, downloaded status is device-local, audio plays from a verified local file when available, and the playlist remains after deleting that downloaded file.

## Installation after a successful iOS build

1. Open the successful `Build unsigned iOS IPA` run under **Actions** in this fork.
2. Download the artifact `YMusic-unsigned-ios-arm64` and extract `YMusic-unsigned-ios-arm64.ipa` from the artifact ZIP.
3. Use Sideloadly on Windows to sign and install with your own Apple account or certificate locally. Never provide signing credentials to this repository or chat.
4. Verify authenticated playlist loading, M4A playlist downloading, playback offline from the existing playlist page, delete-from-disk behavior and return-to-streaming online.
