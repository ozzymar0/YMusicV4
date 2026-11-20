# Unified YouTube library — custom fork implementation

This document describes the code on `feature/unified-offline-library`. The branch remains a draft PR and must not be merged until tests and platform builds pass.

## Existing cloud identity

A YouTube playlist entry is a video ID within a specific YouTube playlist. The authenticated YouTube engine already retrieves playlists and supports adding videos and creating playlists on supported account sessions; it uses ordinary YouTube video IDs, not a YouTube Music-only track list. This implementation does not substitute local playlists for YouTube playlists.

A video can appear in many cloud playlists. Its device-local audio belongs to the **video ID**, not to an individual playlist membership. Downloading it never removes or duplicates the cloud entry. The new `CloudPlaylistMembership` and `CloudVideoKey` model this distinction; the app's existing cloud feed remains authoritative.

## Device-local downloads

- **Kotlin (Android/iOS/shared UI)**: Completed rows in the existing SQLDelight download database already store `videoId`, `state`, `format`, `fileUri`, and update time. `indexCompletedAudioDownloads` picks the newest readable M4A/MP3/Opus file per video ID, ignoring failed, paused, video-only, missing, and zero-byte files. No download table migration is required.
- **iOS**: `MainViewController.kt` now creates the native `DownloadService` and SQLDelight download driver. Finished M4A files are published under the application's Application Support `ymusic/downloads` folder, with scratch bytes under `ymusic/download-work`. These are not cache directories and survive normal app upgrades. iOS has no FFmpeg executable: the existing downloader publishes original AAC-in-M4A audio without transcoding. It uses the existing signed-in cookie session and MSE player for resolving downloads.
- **Windows Rust**: `ym-store::MediaStore::audio_copies_for_youtube_video` uses the existing indexed `youtubeVid` metadata. No media-database rewrite is needed. The Rust player's `load_current` chooses a nonempty existing local audio file when available, otherwise keeps the YouTube stream. It rechecks on each load rather than permanently rewriting a cloud membership.

All stored paths remain on their original devices. This fork does not attempt to sync audio bytes across devices or share their download-state database.

## Playback and indicators

The Kotlin `PlaybackCoordinator` selects a verified local copy before constructing a playlist, single-video, or radio queue. Next playlist pages are adapted through a `QueueSource` wrapper. Local and online entries keep the same `videoId`, title, artwork and playlist ordering. The Rust desktop similarly retains the cloud video ID when it changes a queue item's playback source.

The shared `AvailableOffline` indicator reads both the media store (when present) and verified download records. A file removed from disk is not considered downloaded merely because a database row exists.

On iOS, **Remove from disk** invokes `DownloadService.deleteFinishedFile`. It only deletes completed downloads immediately inside the current app download directory, then removes that download row. It does not modify a YouTube playlist. The older **Remove** (download-history-only) action in other parts of the UI remains separate to avoid unexpectedly destroying existing user files.

## Previously visited playlists while offline (Kotlin/iOS)

A successful YouTube playlist detail fetch writes the playable video entries (video IDs, titles, artwork references and order) to an account-scoped JSON snapshot in Application Support. Continuation pages append to that snapshot. Failed network fetches fall back to a matching saved playlist; previously saved playlists are also listed in the Account tab if the startup account probe cannot connect. The account ID is the last confirmed YouTube channel ID, cleared on explicit sign-out or account switch. Cached mutation tokens are never used, and an online fetch remains authoritative.

This is **device-local metadata caching**, not a synchronized offline database. Playlists must have been opened online at least once on each device to be available offline. Windows Rust still needs an equivalent persistent offline playlist browser; its current custom code only provides local-first playback for entries in a loaded cloud playlist.

## Data-preservation rules

No existing `downloads.db`, local `media-store.db`, cookie jar or playlist has been reset, rewritten or migrated. No downloaded file is moved at application startup. The new iOS files use the app's established Application Support area; the existing Rust and Kotlin local libraries remain at their prior locations. The added code does not write to YouTube playlists on download or removal.

## Known limitations — not yet implemented

1. **Offline playlist browsing is partial.** The Kotlin/iOS app caches previously visited playlist entries and exposes those snapshots when YouTube cannot be reached, but Windows Rust has no corresponding persistent playlist-browser cache yet. Unvisited playlists cannot be opened offline.
2. **Full bidirectional playlist editing has not been verified.** Existing authentication/engine code supports cloud feed retrieval, add and create operations. Delete, reorder, rename and multi-device propagation still need integration and live-account tests.
3. **Network reachability is not yet part of queue source selection.** A missing local copy falls back to the YouTube player, whose network error handling applies even when fully offline.
4. **Kotlin queues resolve existing downloaded files at construction/page-load time, not before every next-track load.** A file removed during an active queue may cause a local file-missing error. Rust re-resolves on each load.
5. **iOS still has no local media scanner or traditional local Library database in the UI.** Its new device downloads are indexed via the download DB; it does not yet support importing arbitrary user files.
6. **Sideload and playback on a physical iPhone have not been validated.** These need a successful unsigned IPA build followed by user signing/installing on their device.
7. Upstream's existing YouTube/media downloader may be constrained by availability, account session, or provider behavior. Local media permission and YouTube Terms may affect playback and downloading.

## Validation

CI workflows on this branch:

- `.github/workflows/ios-unsigned-ipa.yml`: macOS 26/Xcode 26.4.1, JDK 21, Node 22, pinned FFmpeg static libraries, Kotlin Multiplatform framework and Swift Xcode app, unsigned `iphoneos` ARM64 IPA. Packaging validates `Payload/iosApp.app`, `YMusicApp.framework`, `Info.plist` and ARM64 Mach-O slices. This is an **unsigned artifact**, not an App Store or Sideloadly-signed IPA.
- `.github/workflows/library-checks.yml`: Kotlin shared tests on Ubuntu, Rust media store tests and Windows desktop compilation on Windows 2025.
- `.github/workflows/upstream-updates.yml` on `master`: scheduled/manual upstream proposal using a disposable branch and a draft PR; no automatic merge. Upstream workflow-file changes are separately reported when the Actions token cannot push them.

Tests in `UnifiedOfflineSelectionTest` cover playlist identity, device-local copy priority, stream fallback, unavailable-offline policy, completed-vs-paused/video-only records, missing copies, deduplication and post-removal membership. Rust playback tests cover preserving the video ID and stream fallback.

**CI status is determined by GitHub Actions, not by this document.** Do not mark the modified app as working until both relevant jobs and the final IPA artifact pass.

## Installation after a successful iOS build

1. Open the successful `Build unsigned iOS IPA` run under **Actions** in this fork.
2. Download the artifact `YMusic-unsigned-ios-arm64` and extract `YMusic-unsigned-ios-arm64.ipa` from the artifact ZIP.
3. Use Sideloadly on Windows to sign and install with your own Apple account or certificate locally. Never provide signing credentials to this repository or chat.
4. Verify authenticated playlist loading, M4A playlist downloading, playback offline from the existing playlist page, delete-from-disk behavior and return-to-streaming online.
