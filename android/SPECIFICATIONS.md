
# iPhoto Android Client - Specifications

## 1. Overview & Architecture

The **iPhoto Android application** is the native mobile client for a self-hosted personal media storage platform. It connects to an iPhoto backend instance over HTTP/HTTPS (including local private network IP addresses) to provide cloud media browsing, album organization, media management, and manual folder-based uploading/syncing from the device.

### 1.1 Technical Stack
- **Platform & Language:** Kotlin 2.4, Android SDK (minSdk 26 / Android 8.0, targetSdk 36 / Android 16, compileSdk 37).
- **UI Framework:** Jetpack Compose (BOM 2026.08.00) with Material 3, Edge-to-Edge display support.
- **Architecture Pattern:** MVVM (Model-View-ViewModel) with Unidirectional Data Flow (`StateFlow`), Kotlin Coroutines, and Channels.
- **Dependency Injection:** Hand-rolled application container (`AppContainer`) provided via `CompositionLocalProvider(LocalAppContainer)`.
- **Networking:** Retrofit 3.0 + OkHttp 5.5 + Gson.
  - Dedicated `apiClient.httpClient` for REST API and Coil image loading.
  - Independent `apiClient.videoHttpClient` for video streaming playback.
- **Image & Video Rendering:**
  - Remote and local image loading: Coil 3 (`coil-compose`, `coil-network-okhttp`, `coil-video`).
  - Interactive pan/zoom viewer: Telephoto (`me.saket.telephoto:zoomable-image-coil3:0.19.0`).
  - Video streaming playback: AndroidX Media3 ExoPlayer (`media3-exoplayer:1.5.1`, `media3-ui:1.5.1`, `media3-datasource-okhttp:1.5.1`) via bounded HTTP range requests (`/api/photos/{id}/stream`) through `ChunkedVideoDataSource` (adaptive 256 KB–2 MB range chunks, automatic range chaining across partial 206 responses, and a 32 MB 128 KB-block LRU cache for zero-network MP4 `moov`/`mdat` and audio/video track seeks) and local URI playback. Tuned with low-latency startup buffer (0.5s), fast rebuffer recovery (1.5s), time-prioritized load control, and automated buffering indicator.
- **Background Upload Service:** Dedicated Android Foreground Service (`UploadForegroundService`) with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, ongoing status notification with progress bar, and partial `WakeLock`. Ensures active uploads continue without disruption when the device screen sleeps or the user switches applications (preventing Doze mode network cutoff and "server unreachable" socket errors).
- **Storage & File System:** Storage Access Framework (SAF) via `DocumentFile` (`SafDir`) for folder selection and reading; MediaStore API 30+ (`MediaStore.createTrashRequest`) for native device trash.
- **Security & Keystore:** Android Keystore AES-256-GCM encryption (`KeystoreEncryptedPreferences` + `GcmCipherCodec`) for storing server URLs and device bearer tokens.

---

## 2. Core Principles & Synchronization Model

### 2.1 No Background Sync & No Space Locking
- **No Background Synchronization:** There is no automatic background sync worker, periodic polling, or system JobScheduler content-URI watcher running in the background. Media transfer is strictly initiated by user action.
- **No Space Locking:** Distant Spaces are not locked or paired exclusively to any device. Any Space can receive uploads at any time without locking constraints.
- **Decoupled Local & Remote Lifecycle:** Remote spaces and local device directories are completely independent. Deleting or moving a photo in a remote Space never mutates local storage on the phone. "Exclude from sync" and bidirectional mirror diffing are not present.

### 2.2 Manual Sync Workflow via the Sync Tab
All device synchronization and uploading occurs solely within the dedicated **Sync tab**:
1. **Folder Selection:** The user selects one or more device folders using the Android Storage Access Framework (SAF). Selected folders are persisted and displayed as tabs.
2. **Local Media Browsing:** Media files in each folder are grouped chronologically by day.
3. **Lightbox / Fullscreen Viewer:** Tapping any photo or video opens it in fullscreen lightbox mode (pan/zoom for photos, playback for videos).
4. **Three Manual Actions:**
   - **Upload (Téléverser):** Upload selected local media to a target Space selected by the user.
   - **Upload & Delete (Téléverser et supprimer):** Upload selected local media to a target Space, verify server-side completion, and move the local files to device trash.
   - **Delete (Supprimer):** Move selected local files directly to the device trash (system trash confirmation dialog via MediaStore on API 30+, falling back to `.iPhotoTrash`).

---

## 3. Domain Models

### 3.1 Photo (`Photo`)
- `id: String`: Unique server-side identifier.
- `title: String`: File name or title.
- `url: String`: Path for thumbnail/image asset.
- `date: String`, `fullDateTime: String`, `yearMonth: String`, `groupDate: String`: Temporal metadata.
- `size: String?`: File size string.
- `resolution: String`: Pixel resolution (e.g. `"4032x3024"`).
- `width: Int?`, `height: Int?`: Dimensions.
- `location: String?`, `camera: String?`: EXIF metadata.
- `contentType: String?`: MIME type.
- `fileHash: String?`: Content SHA-256 hash.
- `duration: Long?`: Video duration in milliseconds.
- `duplicates: List<Photo>?`: Clustered duplicate entries.

### 3.2 Space (`SpaceDTO`)
- `id: String`: Unique space identifier.
- `name: String`: Display name.
- `photoCount: Int?`: Total photos in the space.
- `albumAllowed: Boolean`: Whether albums can be created in/assigned to this space.
- `allowCopyToMain: Boolean`: Whether items can be moved to the Main Space.
- `deletionAllowed: Boolean`: Whether this space can be deleted (Main Space has `deletionAllowed = false`).

### 3.3 Album (`AlbumDTO`)
- `id: String`: Unique album identifier.
- `name: String`, `description: String?`.
- `coverPhoto: Photo?`: Cover photo object.
- `photoCount: Int?`: Item count.

### 3.4 Share (`ShareResponse`)
- `id: String`, `name: String`, `albumId: String`.
- `isPasswordProtected: Boolean`, `expired: Boolean`, `userMd5: String`.

### 3.5 Local Asset (`LocalAsset`)
- `uri: Uri`: SAF content URI.
- `name: String`, `size: Long`, `lastModified: Long`, `mimeType: String`.
- `relativeDir: String`: Relative directory path inside chosen root folder.

---

## 4. Navigation & Screens

```
NavHost
├── "library" (Destination.Library)
│     └── "photo/{photoId}"
├── "albums" (Destination.Albums)
│     └── "albums/{albumId}"
│           └── "photo/{photoId}?albumId={albumId}"
├── "spaces" (Destination.Spaces)
│     └── "spaces/{spaceId}"
│           └── "photo/{photoId}?spaceId={spaceId}"
├── "shares" (Destination.Shares)
└── "sync" (Destination.Sync)
      ├── Local Asset Lightbox Viewer
      └── Dialog: "UploadListDialog"
```

---

## 5. Functional Screen Specifications

### 5.1 Authentication (`LoginScreen`, `AuthViewModel`, `TokenStore`)
- Arbitrary server URL input (cleartext permitted for private LAN IPs).
- Username and password input.
- **Password Visibility Toggle:** Eye icon button integrated into the password field toggles masked/plain text.
- **Remember me:** If checked (default), token persists across app restarts; if unchecked, session is cleared on cold start.
- Communicates via `POST /api/auth/mobile/login` and `DELETE /api/auth/mobile/devices/{id}`.

### 5.2 Library (`LibraryScreen`, `PagedPhotoGridViewModel`, `PhotoGridContent`)
- Chronological reverse view of Main Space media (`GET /api/photos`).
- Infinite scrolling pagination.
- Day headers grouping media; tap header to select/deselect the entire day.
- Multi-selection bar actions:
  - **Edit Date:** Change timestamp via `PATCH /api/photos/{id}/date`.
  - **Add to Album:** Assign selected photos to an album (`POST /api/albums/{id}/photos`).
  - **Move to Garbage:** Move remote photos to server garbage (`POST /api/photos/garbage`).

### 5.3 Albums (`AlbumsScreen`, `AlbumDetailScreen`, `AlbumPickerDialog`)
- Browse user albums (`GET /api/albums`), sorted by **creation date descending** (`createdAt` newest first) in both the **Albums** tab and the **Add to Album** picker dialog.
- Automatically refreshes the albums list whenever the user navigates to/resumes the **Albums** tab, opens the Album Picker dialog, or adds/removes photos to/from an album (`AppContainer.albumsChangeVersion`).
- Create album (`POST /api/albums`).
- View album photos (`GET /api/albums/{id}/photos`), refreshing quietly on resume.
- Album multi-selection bar actions:
  - **Remove from Album:** Detach selected photos from album (`DELETE /api/albums/{id}/photos`) and notify album observers.
  - **Share Album:** Generate public or passworded link (`POST /api/share`).

### 5.4 Spaces (`SpacesScreen`, `SpaceDetailScreen`)
- Browse all spaces (`GET /api/spaces`).
- Automatically refreshes the spaces list whenever the user navigates to/resumes the **Spaces** tab, when library/space changes occur (`AppContainer.libraryChangeVersion`), or when opening the Space Picker dialog to upload media from the **Sync** tab.
- View space photos (`GET /api/spaces/{spaceId}/photos`).
- Multi-selection bar actions:
  - **Move to Main Space:** Relocate selected photos to Main Space (`POST /api/photos/main`).
  - **Move to Garbage:** Move selected photos to server garbage (`POST /api/photos/garbage`).
- *Note:* No space locking, no sync status banner, and no local device file mirroring.

### 5.5 Remote Media Lightbox (`PhotoDetailScreen`)
- High-definition rendering with zoom/pan (`ZoomableAsyncImage`) and video playback (`VideoPlayer`).
- Responsive orientation:
  - *Portrait:* Standard top and bottom app bars.
  - *Landscape:* Fullscreen immersive mode with floating overlay toolbar (toggleable on tap).
- Sibling navigation via left/right chevrons following grid order.
- Contextual actions:
  - Edit Date / Time.
  - Add to Album.
  - Remove from Album (if opened from album).
  - Move to Main Space (if opened from space).
  - Move to Garbage (server garbage only).

### 5.6 Sync Tab (`SyncScreen`, `AssetDetailScreen`, `SyncViewModel`, `UploadManager`, `UploadForegroundService`)
- **Unredacted Media Access (`ACCESS_MEDIA_LOCATION`):**
  - Requests `android.permission.ACCESS_MEDIA_LOCATION` on Android 10+ (API 29+) and opens media streams via `MediaStore.setRequireOriginal` (`FileHasher.openUnredactedInputStream`) so Android's Scoped Storage `MediaProvider` does not zero out EXIF/MP4 GPS metadata bytes on the fly. This preserves GPS coordinates and guarantees that the server-side MD5 hash matches uploads from the Web UI.
- **Startup Background Batched (50) MD5 Verification (`HashCache` + `POST /api/photos/check-hashes`):**
  - At app startup (and when scanning Sync folders), `SyncViewModel` scans the configured Sync folders in the background (`Dispatchers.IO`).
  - Computes MD5 hashes for local photos/videos via `FileHasher.md5` and persists them locally in `HashCache` (`sync_hash_cache.json`, keyed by `(uri, size, lastModified)`) so unchanged files are never re-read from flash storage on subsequent launches.
  - Queries the backend endpoint `POST /api/photos/check-hashes` in batches of **50 hashes** (plus the final remainder $< 50$). The backend returns per-hash existence statuses (`statuses: Map<String, Boolean>`).
  - Updates `SyncPrefs` (`applyBatchHashCheckStatuses`) and `SyncUiState.uploadedAssetKeys` after each batch so the cloud indicator badge (`Icons.Filled.CloudDone`) automatically appears on any photo/video already present on the server (including media uploaded via the Web UI).
- **Folder Tabs:**
  - Add folder via SAF tree picker (`OpenDocumentTree`).
  - Close/remove folder tab (automatically cleans up persisted uploaded-asset keys for that folder in `SyncPrefs`).
  - Persisted folder list across launches.
- **Grid View:**
  - 4-column thumbnail grid grouped by day headers.
  - Uploaded indicator badge (`Icons.Filled.CloudDone`, top-right) on thumbnails already uploaded to the server; remains visible even when thumbnails are selected or in multi-selection mode.
  - Long press on thumbnail or tap day header to enter multi-selection mode.
- **Local Lightbox Viewer (`AssetDetailScreen`):**
  - Fullscreen zoomable image viewer or inline video player for local files.
  - Horizontal swipe pager to navigate through folder assets.
- **Parallel Uploads & Background Persistence:**
  - Concurrent upload engine: up to 3 files uploaded simultaneously in parallel to saturate available bandwidth.
  - Screen-off & Background Reliability: `UploadForegroundService` promotes process to `dataSync` foreground service with persistent notification and partial `WakeLock`. Prevents Android Doze mode from shutting down radio, dropping sockets, or causing DNS "server unreachable" errors when the device screen turns off.
- **The 3 Manual Actions (Available in Multi-Selection & Lightbox):**
  1. **Upload (Téléverser):**
     - Opens Space Picker dialog (automatically refreshing the remote Spaces list).
     - Uploads selected asset(s) concurrently in 1 MB chunks to `/api/upload/chunk`.
     - Tracks upload progress in the background `UploadManager` queue and polls `/api/tasks/{id}`.
     - Displays status feedback aligned with the Web UI: `"Uploaded X photo(s)"` / `"Uploaded successfully"` (`COMPLETED`), `"Already imported"` (`IGNORED` duplicate MD5), or `"Already imported and in trash"` (`TRASHED` duplicate MD5).
     - Updates local uploaded indicator badge (`SyncPrefs`) once confirmed by server.
  2. **Upload & Trash (Téléverser et supprimer):**
     - Opens confirmation dialog, then Space Picker dialog (refreshing the Spaces list).
     - Uploads asset(s) concurrently and polls backend processing task (`/api/tasks/{id}`).
     - Upon verified server response (`COMPLETED`, `IGNORED`, or `TRASHED`), moves local file(s) to device trash and removes their uploaded state from `SyncPrefs`.
  3. **Delete / Move to Trash (Supprimer):**
     - Confirms deletion.
     - Requests native OS trash confirmation via `MediaStore.createTrashRequest` (API 30+).
     - Fallback to hidden `.iPhotoTrash` directory in folder root for non-MediaStore URIs or API < 30.
     - Automatically removes the deleted file's uploaded-to-cloud state from `SyncPrefs` and purges its cached MD5 hash from `HashCache` (`sync_hash_cache.json`) on the device (also pruned during folder scans if deleted externally or when a folder tab is closed).

---

## 6. Catalog of User Actions

| Location | Action Name | Description & Target |
| :--- | :--- | :--- |
| **Login Screen** | Toggle Password Visibility | Toggles between obscured password and plain text |
| **Global / Top Bar** | Log Out | Clears session token and returns to login screen |
| **Global / Top Bar** | View Upload Queue | Opens `UploadListDialog` modal showing active/queued/finished uploads |
| **Library & Grid** | Day Header Tap | Toggles selection of all photos for that day |
| **Library & Grid** | Long-press Thumbnail | Enters multi-selection mode and selects target photo |
| **Selection Bar (Library)** | Edit Date | Opens date/time picker to update timestamp for selected items |
| **Selection Bar (Library)** | Add to Album | Opens album picker to assign selected photos to an album |
| **Selection Bar (Library)** | Move to Garbage | Moves items to server garbage |
| **Selection Bar (Album)** | Remove from Album | Removes selected photos from current album |
| **Selection Bar (Space)** | Move to Main Space | Transfers selected photos to Main Space |
| **Lightbox (Remote)** | Chevron Left / Right | Navigates to previous / next sibling item in grid order |
| **Lightbox (Remote)** | Fullscreen Toggle | (Landscape only) Tap image to toggle visibility of floating controls |
| **Lightbox (Remote)** | Edit Date | Opens date/time picker for single item |
| **Lightbox (Remote)** | Add to Album | Assigns single item to an album |
| **Lightbox (Remote)** | Remove from Album | Removes single item from album (Album view only) |
| **Lightbox (Remote)** | Move to Main Space | Moves single item to Main Space (Space view only) |
| **Lightbox (Remote)** | Move to Garbage | Deletes single item to remote garbage |
| **Albums Screen** | Create Album | Dialog to create a new named album |
| **Album Detail** | Share Album | Opens dialog to create public or passworded share link |
| **Sync Screen** | Add Folder | Opens SAF folder picker to register a local directory tab |
| **Sync Screen** | Remove Folder | Closes and unregisters local directory tab |
| **Sync Screen** | Upload Selection | Uploads selected local files to chosen Space |
| **Sync Screen** | Upload & Trash Selection | Uploads to chosen Space, then moves local files to trash |
| **Sync Screen** | Trash Local Selection | Moves selected local files to device trash |
| **Sync Screen** | Tap Thumbnail | Opens local asset in Lightbox Viewer |
| **Lightbox (Local Asset)** | Swipe Left / Right | Pagers to previous / next media in local folder |
| **Lightbox (Local Asset)** | Upload | Uploads current local file to chosen Space |
| **Lightbox (Local Asset)** | Upload & Trash | Uploads to chosen Space, then moves current file to trash |
| **Lightbox (Local Asset)** | Move to Trash | Moves current local file to device trash |
