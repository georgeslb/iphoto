# Android TODO

## 2026 dependency upgrade — follow-ups

Tracking work left over from the AGP 9 / Kotlin 2.4 / Gradle 9 / Compose BOM 2026.08 upgrade
(Sept 2026). The upgrade got the project building again, but these are known gaps, not just
version bumps:

- [ ] **Migrate off `androidx.security:security-crypto`** (`data/TokenStore.kt`). All of its APIs
      (`EncryptedSharedPreferences`, `MasterKey`) were deprecated upstream in favor of using the
      Android Keystore directly - the library still works on the current stable 1.1.0, but won't
      see further updates. This is a real rewrite of how the server URL/device token/token id are
      persisted, not a dependency-version change - do it as its own task.
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
- [ ] Double check exact dependency versions actually resolved during sync (`compose-bom`,
      `navigation-compose`, `retrofit`, `okhttp`, `coil3`, `androidx.work`) against what's pinned
      in `app/build.gradle.kts` - several were pinned from web lookups during the upgrade rather
      than verified against a live Gradle sync at the time they were written.

## Multi-select (garbage / album / date) - 2026-09-13

- [ ] **Lightbox has no "remove from album" action**, only the album-grid selection bar does.
      `PhotoDetailScreen`/`MainScreen`'s `photo/{photoId}` route has no album-context argument
      today, so the lightbox can't know it was opened from inside an album. Would need a nav
      argument (e.g. `photo/{photoId}?albumId={albumId}`) threaded from `AlbumDetailScreen`'s
      `onPhotoClick` through `MainScreen`'s NavHost.
- [ ] **Black image in the lightbox** (`PhotoDetailScreen`) - added `Log.e`/`Log.d` around the
      `AsyncImage` load (tag `PhotoDetailScreen`) logging the failing URL, contentType, and
      throwable. Leading hypotheses, unconfirmed: (a) the photo is a video and `/original` serves
      the raw video file, which Coil can't decode as an image - PhotoDetailScreen has no video
      playback path at all right now; (b) `photo.getOriginalPath()` file is missing on disk on the
      WSL2 backend (thumbnails are generated/stored separately from originals, so thumbnails
      working doesn't rule this out) -> `/original` 404s. Repro with Logcat filtered on
      `PhotoDetailScreen` to confirm which.
- [ ] If (a) above is confirmed: PhotoDetailScreen needs an actual video player (ExoPlayer/Media3)
      for `contentType` starting with "video" - there's currently no video playback anywhere in
      the app, only the play-icon badge on thumbnails.

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
first two, the newest and least-exercised code (today's multi-select feature). Not yet covered:
`SyncViewModel`/`SyncRepository` (needs a real or faked `Context`/`ContentResolver` for
`LocalFolderScanner`/SAF access - more setup than a plain ViewModel test), `AlbumsViewModel`,
`SpacesViewModel`, `PagedPhotoGridViewModel`'s `loadMore` pagination edge cases beyond the basics
tested so far.
