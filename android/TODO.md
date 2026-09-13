# Android TODO

## SyncRepository test coverage + a real bug it found — 2026-09-13

Added `SyncRepositoryTest` (`app/src/test/java/org/iphoto/mobile/sync/`), covering
`computeDiff`'s full diff logic and the local-trash helpers (`trashLocalFilesForHashes`,
`restoreLocalAssets`, `removeFromSpace`, `restoreInSpace`). `LocalFolderScanner`/`LocalTrash` are
plain Kotlin `object`s, so `mockkObject` stubs their functions directly - no need to fake a whole
`DocumentFile`/SAF tree just to test `SyncRepository`'s own reconciliation logic. `HashCache.get`
is stubbed per-asset (keyed on a unique `size` per test asset, not on `Uri.toString()` - MockK
can't stub `toString()` on a mock via the normal `every {}` API, learned the hard way when the
first version of this test failed with "Missing mocked calls" purely from trying).

**Found and fixed a real bug while writing the "trashed file matching an active photo" test**: a
file sitting *only* in the local trash (not the folder root) that matches a still-*active* remote
photo - exactly the "restored from the web app while the file is still in `.iPhotoTrash`" case
`toLocalRestore` exists for - was *also* getting queued in `toRemove`, because that filter only
checked presence in the root scan, not the trash scan. One sync pass would garbage the photo *and*
restore the file to root; the next pass would then see the file back in root matching a (freshly
re-)garbaged photo and restore it *again* - a two-pass flap instead of settling in one.
`computeDiff` now excludes any remote-active photo whose hash is found in the trash scan from
`toRemove` (see its updated comment) - safe for the ordinary "photo actually garbaged, file already
moved to trash" path too, since a photo already in the Space's garbage list never reaches the
`toRemove` filter in the first place (it only iterates *active* `remotePhotos`).

**Verified for real**, same approach as the `TokenStore`/`AlbumsViewModel` work above: compiled and
ran the real `SyncRepository`/`LocalFolderScanner`/`LocalTrash`/`LocalAsset`/`FileHasher`/
`HashCache`/`PhotoApi` files (not stand-ins) plus the real test file, against real MockK/JUnit/
kotlinx-coroutines-test/retrofit/okhttp/gson jars from Maven Central and a real `android.jar`
(robolectric's `android-all`, for `Context`/`Uri`). The one thing that genuinely couldn't be
sourced anywhere on Maven Central is `androidx.documentfile:documentfile` itself (Google-Maven-only,
confirmed 404) - `LocalFolderScanner`/`LocalTrash` both import `DocumentFile`, so compiling them at
all needed a hand-written stand-in `DocumentFile` class matching the real one's public shape (never
exercised at runtime here, since `mockkObject` replaces both files' actual bodies entirely - it only
had to satisfy the compiler). All 14 tests passed for real, including the one that initially failed
and pointed at the bug above.

Still not covered: `SyncViewModel` itself (the WorkManager plumbing - `observeWork`,
`schedulePeriodic`, `confirmSync` - would need `androidx.work`'s real classes plus
`androidx.work:work-testing`'s fakes to test meaningfully, and both are Google-Maven-hosted, same
blocker as everywhere else `androidx.*` shows up in this file), and `uploadAsset`/
`waitForProcessing`'s chunk-upload loop (exercises real multipart/OkHttp request-building - doable,
just not done yet).

## Sync/Space fluidity — 2026-09-13

Improved how a synced Space and its device folder stay consistent, in both directions, without
needing a Garbage screen in the app (there still isn't one - see "Test coverage" below):

- **Automatic sync** (opt-in): `SyncPrefs.autoSyncEnabled` + `SyncWorker.schedulePeriodic`/
  `cancelPeriodic`, wired to a "Sync automatically" switch in `SyncScreen`. Runs a periodic
  `SyncWorker` (6h interval, network + battery-not-low constraints) under its own unique work name
  (`PERIODIC_WORK_NAME`), separate from the manual "Sync now" one-time work (`UNIQUE_WORK_NAME`) so
  neither blocks the other. Off by default, matching the feature's original design.
- **Sync status banner**: `SpaceDetailScreen` now shows a banner when the space it's showing is the
  one currently synced (`SyncPrefs.spaceId`/`folderUri`) - which folder, and when it last synced.
- **Garbage mirrored onto the phone**: moving a photo to the Garbage from a synced Space
  (`SpaceDetailScreen`'s selection action bar) now also moves the matching local file (by content
  hash) into `LocalTrash` (`sync/LocalTrash.kt`, a `.iPhotoTrash` subfolder inside the synced
  folder) right away - see `PagedPhotoGridViewModel.onPhotosMovedToGarbage` and
  `SyncRepository.trashLocalFilesForHashes`. This is an app-managed trash, not the OS/MediaStore
  trash: an arbitrary SAF-picked folder isn't necessarily MediaStore-indexed, and trashing via
  MediaStore needs a per-call user-consent intent, so a plain subfolder is what a general synced
  folder can actually support.
- **Restore, both directions**, handled passively by `SyncRepository.computeDiff`'s new
  `toRestore`/`toLocalRestore` (applied by `SyncWorker`):
  - A garbage photo's file reappearing in the synced folder root (e.g. the user drags it back out
    of `.iPhotoTrash` with a file manager) restores the photo via `POST /api/photos/garbage/restore`
    instead of re-uploading it as new (`toRestore`, matched against the new
    `GET /api/spaces/{spaceId}/garbage` endpoint).
  - A Space photo becoming active again while its file still sits in `.iPhotoTrash` (e.g. restored
    from the web app) moves the file back to the folder root (`toLocalRestore`).
  - Both only run on the next sync pass (manual or automatic) - there's no live filesystem watch.

Known gaps / not done:
- No Android build/test run in this session - no Android SDK in this container (no `ANDROID_HOME`,
  no cached Gradle wrapper distribution), unlike the machine that produced the "Fixed: sync never
  actually ran" entry below. Reviewed by hand instead; the backend half (`GET
  /api/spaces/{spaceId}/garbage`, `PhotoRepository`/`PhotoServiceImpl`) does compile (`mvn compile`
  passed). Needs a real `assembleDebug`/`testDebugUnitTest` pass before shipping.
- `LocalTrash` restores a file to the folder **root**, not its original subfolder - nested
  camera-roll layouts (`Camera/2024/06/...`) lose their subfolder on restore. Fine for now since
  sync only matches by content hash, not path, but worth fixing if it turns out to matter.
- Still no Garbage screen anywhere in the Android app, so a user can't trigger a restore from the
  app UI directly - `toRestore`/`toLocalRestore` only fire from file-system-level reconciliation
  (manually moving a file, or restoring via the web app) picked up on the next sync.
- ~~The reactive "also trash the local file" wiring only covers `SpaceDetailScreen`'s multi-select
  garbage action~~ - fixed, see "Multi-select (garbage / album / date)" below: the lightbox's own
  delete button now mirrors onto the local synced folder's trash too, via the same `spaceId` nav
  arg that also fixed "Lightbox has no 'remove from album' action".
- `trashLocalFilesForHashes`/local-trash moves are best-effort and silently swallow individual
  per-file failures (`LocalTrash.moveDocument` returns `false` rather than throwing) - if one is
  worth surfacing to the user, `SyncWorker`'s logs are the only place it currently shows up.

## 2026 dependency upgrade — follow-ups

Tracking work left over from the AGP 9 / Kotlin 2.4 / Gradle 9 / Compose BOM 2026.08 upgrade
(Sept 2026). The upgrade got the project building again, but these are known gaps, not just
version bumps:

- [x] **Migrate off `androidx.security:security-crypto`** (`data/TokenStore.kt`) - done, 2026-09-13.
      Replaced `EncryptedSharedPreferences`/`MasterKey` with a hand-rolled
      `KeystoreEncryptedPreferences` (a `SharedPreferences` decorator: AES-256-GCM key generated
      in/retained by `AndroidKeyStore`, only values encrypted, not key names - see its kdoc for why
      that's an acceptable trade for TokenStore's own non-secret key names). The actual
      encrypt/decrypt framing (IV handling, Base64, GCM tag) is split into its own object,
      `GcmCipherCodec`, specifically so it can be unit-tested against a plain in-memory `SecretKey`
      (`GcmCipherCodecTest` - round-trip, wrong-key, and tampered-ciphertext cases) without needing
      a real Android Keystore. `TokenStore` itself didn't change its public shape - it already
      gained an `internal` `SharedPreferences`-taking constructor before this migration
      specifically so `TokenStoreTest` (written first, against a hand-rolled `FakeSharedPreferences`
      double) could pin down its get/set/clear contract independent of whatever backs `prefs`; that
      same test suite passed unchanged after the swap, which is what actually gives confidence
      nothing broke. No data-migration path for values written under the old scheme - they simply
      fail to decrypt and behave like "nothing stored" (`KeystoreEncryptedPreferences#getString`
      catches and falls back), which for TokenStore just means logging in again; reasonable for a
      pre-release app, called out explicitly in case it ever isn't.
      **Verified without an Android SDK/emulator in this session**: no device/emulator was
      available, so this was checked by compiling+running `GcmCipherCodecTest` and `TokenStoreTest`
      standalone against a real `kotlinc` (2.4.20) and a real `android.jar` (pulled from
      `org.robolectric:android-all` on Maven Central, for genuine `SharedPreferences` interface
      signatures rather than hand-typed guesses) - all 11 tests passed for real, not just "looks
      right on review". What's still unverified: `KeystoreEncryptedPreferences.getOrCreateKey()`
      itself (needs a real `AndroidKeyStore` provider - doesn't exist outside a real device/
      emulator/Robolectric, so this one piece stays a manual-review-only change) and the app
      actually building via Gradle/AGP (no SDK here either - same gap as the rest of this file).
- [x] `androidx.work` artifact resolved: pinned to plain `work-runtime:2.11.2` (not `-ktx`) -
      confirmed by extracting the actual cached AARs (`~/.gradle/caches/.../androidx.work/`):
      `work-runtime-ktx:2.11.2`'s classes.jar is genuinely empty (version marker only, zero
      classes), and `getWorkInfosForUniqueWorkFlow` is a member method directly on the
      `WorkManager` class in `work-runtime-2.11.2.aar` - not a top-level Kotlin extension
      function, so it needs no import at all, just a direct call on the `WorkManager` instance.
      Removed the incorrect `import androidx.work.getWorkInfosForUniqueWorkFlow` in
      `SyncViewModel.kt` accordingly.
- [ ] Do one full **Rebuild Project** (not just a Gradle sync - sync only resolves dependencies,
      it doesn't run the Kotlin compiler) in Android Studio top to bottom. So far only the errors
      surfaced by individual file inspections have been fixed interactively - there's no confirmed
      green build of the whole module yet.
- [ ] Visually verify edge-to-edge / predictive-back behavior after the `targetSdk` 34 -> 36 jump.
      `MainActivity` already calls `enableEdgeToEdge()`, but worth confirming no clipping or
      layout regressions under the system bars on a real device/emulator.
- [ ] `@OptIn(ExperimentalMaterial3Api::class)` was only added to the 4 screens that surfaced the
      warning during this upgrade (`AlbumDetailScreen`, `MainScreen`, `PhotoDetailScreen`,
      `SpaceDetailScreen`). Check for other `TopAppBar`/experimental Material3 usages that may
      need the same annotation.
- [ ] Spot-check the Coil 2 -> 3 migration (`AppContainer.kt`, `PhotoThumbnail.kt`,
      `PhotoDetailScreen.kt`) against a running app - thumbnails and full-size photos should still
      authenticate via the shared `OkHttpClient` through `OkHttpNetworkFetcherFactory`.
- [x] Double check exact dependency versions - partially done, 2026-09-13, still no live Gradle
      sync (no SDK in this container either). Verified existence directly against each repo's real
      `maven-metadata.xml`/POM instead: everything hosted on Maven Central (`retrofit`/
      `converter-gson` 3.0.0, `okhttp`/`logging-interceptor` 5.1.0, `junit` 4.13.2,
      `kotlinx-coroutines-test` 1.10.2, `mockk` 1.14.11, `kotlin-gradle-plugin` 2.4.20,
      `io.coil-kt.coil3:coil-compose`/`coil-network-okhttp` 3.6.2) resolves exactly as pinned - all
      real, resolvable releases, not typos. Two have newer releases available (informational only,
      not broken): `okhttp` 5.1.0 pinned vs 5.5.0 latest, `kotlinx-coroutines-test` 1.10.2 pinned vs
      1.11.0 latest. **Could not check the Google-Maven-hosted half** (`compose-bom`,
      `activity-compose`, `lifecycle-runtime-ktx`/`lifecycle-viewmodel-compose`,
      `navigation-compose`, `androidx.work`, `androidx.documentfile`, AGP itself) - this session's
      egress proxy blocks `dl.google.com`/`maven.google.com` outright (org policy, confirmed via
      `curl`), which is also where Android Studio's own Gradle sync would need to reach. Needs
      either a real Gradle sync (as originally planned) or running from a network that can reach
      Google's Maven repo.

## Multi-select (garbage / album / date) - 2026-09-13

- [x] **Lightbox has no "remove from album" action** - fixed. `MainScreen`'s `photo/{photoId}`
      route now takes optional `albumId`/`spaceId` query-style nav args (`photo/{photoId}
      ?albumId={albumId}&spaceId={spaceId}`), threaded from `AlbumDetailScreen`'s and
      `SpaceDetailScreen`'s `onPhotoClick`; `LibraryScreen`'s `onPhotoClick` still navigates with
      neither, which the route's `nullable = true, defaultValue = null` args allow. `PhotoDetailScreen`
      shows a "remove from album" button (`PhotoDetailViewModel.removeFromAlbum`) whenever `albumId`
      is non-null, mirroring `PagedPhotoGridViewModel.removeSelectionFromAlbum`'s bulk-action
      behavior for the single-photo case. Same caveat as that bulk action already had: popping back
      to `AlbumDetailScreen` doesn't refresh its (separate, same-instance) `PagedPhotoGridViewModel`
      state, so the removed photo stays visible in the grid until it's reloaded some other way -
      pre-existing gap, not introduced here.
- [x] The new `spaceId` nav arg above also closes the sync-fluidity gap from the "Sync/Space
      fluidity" entry above: the lightbox's own delete button (`PhotoDetailViewModel.moveToGarbage`)
      now mirrors onto the local synced folder's trash too, the same way
      `SpaceDetailScreen`'s multi-select action already did - see `PhotoDetailScreen`'s
      `onPhotoMovedToGarbage` (same logic as `SpaceDetailScreen`'s, just for one photo instead of a
      selection). Not yet run through a real device/emulator - see "No Android build/test run in
      this session" above, still true.
- [ ] **Black image in the lightbox for photos** (`PhotoDetailScreen`) - the video half of this
      (below) is fixed; this is what's left. Confirmed via code review this session that it's *not*
      only videos - the still-open hypothesis is (b) `photo.getOriginalPath()` missing/corrupt on
      disk (thumbnails are generated/stored separately from originals, so thumbnails working
      doesn't rule this out), possibly combined with an original in a format the client can't
      decode (no HEIC/RAW transcoding exists anywhere in the app - `/original` always streams raw
      bytes, both on Android and in the web app's own Lightbox/DuplicateClusterModal/
      SharedLightbox/UploadPreviewModal). Blocked on a real repro: the `Log.e`/`Log.d` logging
      added around the `AsyncImage` load (tag `PhotoDetailScreen`, logs the failing URL,
      contentType, and throwable) needs a Logcat capture from an actual affected photo to go
      further - no device access this session.
- [x] **Video playback in the lightbox** - done, 2026-09-13. Confirmed by code review (not a
      hypothesis): `AsyncImage`/Coil has no video decode path in this project's dependencies, so
      every video hit this the same way, 100% of the time, distinct from whatever's separately
      wrong with some photos above. Added `androidx.media3:media3-exoplayer`/`media3-ui`/
      `media3-datasource-okhttp` (`1.5.1`, pinned from a web lookup - see the dependency-version
      entry above for why: `androidx.media3` is Google-Maven-hosted, unreachable from this
      session). `PhotoDetailScreen` now branches on `contentType?.startsWith("video")`: video goes
      to the new `VideoPlayer` composable (`ui/photo/VideoPlayer.kt`, an ExoPlayer wrapped in
      `AndroidView`/`PlayerView`), pointed at `GET /api/photos/{id}/stream` - the same
      range-request-capable endpoint (`PhotoController#streamVideo`) the web app's own
      `<video controls autoPlay>` element already uses, instead of `/original`. Authenticates via
      `AppContainer#videoDataSourceFactory` (`OkHttpDataSource` wrapping the same authenticated
      `OkHttpClient` Coil's `imageLoader` already shares) - the stream endpoint needs the same
      Bearer token header as everything else, which ExoPlayer's default HTTP stack wouldn't carry.
      **Not verified by an actual compile/run**: unlike the TokenStore migration above,
      `androidx.media3` isn't on Maven Central, so it couldn't be downloaded and checked against
      real signatures in this session either - this is reviewed-by-hand only, against Media3 APIs
      that have been stable for a long time (`ExoPlayer.Builder`, `MediaItem.fromUri`, `PlayerView`,
      `OkHttpDataSource.Factory`), but genuinely unverified. Needs a real `assembleDebug` +
      on-device tap-a-video check before trusting it.

## Fixed: sync never actually ran - 2026-09-13

`SyncWorker` always failed with `NoSuchMethodException` on its constructor, before `doWork()` ever
ran - confirmed via a real Logcat trace. Root cause: `IPhotoApplication` implements
`Configuration.Provider` to supply `SyncWorkerFactory` (which knows how to construct `SyncWorker`
with its `AppContainer` dependency), but nothing disabled WorkManager's *default* App Startup
auto-initializer. The two race to call `WorkManager.initialize()`; only the first call wins, and
the default one (using plain reflection, which only knows a `(Context, WorkerParameters)`
constructor) usually wins that race - so the custom factory was never actually used. Fixed with the
manifest `<meta-data tools:node="remove">` override this always requires (documented in
`AndroidManifest.xml` now) - confirmed the `WorkManagerInitializer` entry is actually gone from the
merged manifest, not just silently ignored.

Discovered this session that a local build is actually possible: Android Studio had already
downloaded a working Gradle 9.7.1 distribution to `~/.gradle/wrapper/dists/`, and the SDK +
Android Studio's bundled JDK (`...\Android Studio\jbr`) already exist on disk - the project's own
`gradlew`/`gradlew.bat`/`gradle-wrapper.jar` just didn't exist yet. Generated them this session
(`gradle wrapper`), so `.\gradlew.bat assembleDebug` / `testDebugUnitTest` now work directly.

## Test coverage - 2026-09-13

Added the first unit tests in the module (none existed before): JUnit4 + kotlinx-coroutines-test +
MockK, `app/src/test/java/org/iphoto/mobile/`. Covered `PagedPhotoGridViewModel`,
`PhotoDetailViewModel`, `AuthViewModel` - the ViewModels with the most business logic and, for the
first two, the newest and least-exercised code (today's multi-select feature).

`AlbumsViewModelTest`/`SpacesViewModelTest` added later the same day - both ViewModels are a thin
`init { load() }` + `load()` wrapper around one `suspend list()` call, so coverage is intentionally
small: loads on init, falls back to an error state (with and without a message) on failure, `load()`
reloads and replaces the previous list, and a previous error clears once a reload succeeds.
`SpacesViewModelTest` also pins that `deletionAllowed` survives the round trip unchanged (the Main
space's non-deletable flag - not filtered here, unlike `SyncViewModel`'s sync-target picker, but
something `SpacesScreen`'s own "delete" affordance is expected to read directly off this state).
Added `testAlbum`/`testSpace` fixtures to `TestFixtures.kt` alongside the existing `testPhoto`.

**Verified without a live Gradle sync**, same as the `TokenStore` migration and for the same
reason (`androidx.lifecycle` is Google-Maven-hosted, unreachable here): compiled and ran the real,
unmodified `AlbumsViewModelTest`/`SpacesViewModelTest` files against the real `AlbumApi`/`SpaceApi`/
`AlbumDTO`/`SpaceDTO` and real MockK/JUnit/kotlinx-coroutines-test jars, substituting only
`AlbumsViewModel`/`SpacesViewModel` themselves with a stand-in using a plain
`CoroutineScope(Dispatchers.Main)` instead of `androidx.lifecycle`'s `ViewModel`/`viewModelScope`
(identical behavior under `MainDispatcherRule`'s `Dispatchers.setMain`, just without the
unavailable dependency) - all 11 tests passed for real. The actual production `AlbumsViewModel`/
`SpacesViewModel` classes themselves were not recompiled this way, only reviewed by hand, but they
follow the exact same `MutableStateFlow` + `viewModelScope.launch { try/catch }` shape already
proven out by `AuthViewModel`/`PhotoDetailViewModel` elsewhere in this same module.

Still not covered: `SyncViewModel` itself (see the "SyncRepository test coverage" entry above -
`SyncRepository` now has real coverage, `SyncViewModel`'s WorkManager plumbing doesn't yet),
`PagedPhotoGridViewModel`'s `loadMore` pagination edge cases beyond the basics tested so far.
