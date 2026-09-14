# Android TODO

## Fixed: sync schedule went stale after code changes; video/sync connection contention — 2026-09-13

Two separate fixes from the same debugging session:

- **Periodic sync was never actually running.** `dumpsys jobscheduler` on a real device showed
  the scheduled job's `Minimum latency` at over an hour, and zero `SyncWorker` log lines had ever
  been emitted. Root cause: `enqueueUniquePeriodicWork` only applies new parameters (interval,
  the content-uri trigger) when actually called again - the user had toggled auto-sync on once,
  *before* this session changed the interval from 6h to 15min and added the content trigger, and
  had no reason to re-toggle it afterward, so the device was silently stuck on the stale schedule.
  Fixed by having `IPhotoApplication.onCreate()` re-assert the schedule on every process start
  when auto-sync is enabled, not only when the switch is flipped - self-healing against this
  exact class of drift for any future scheduling change. Verified for real: force-restarted the
  app, confirmed via `dumpsys jobscheduler` both jobs now have correct constraints, and watched
  real log output of it finding and working through an actual 748-item backlog.
- **Video playback competing with sync uploads for OkHttp's connection budget.** `videoDataSourceFactory`
  and sync's chunk uploads shared the same `OkHttpClient` (`ApiClient.httpClient`), and OkHttp
  defaults to 5 concurrent requests per host *shared across everything using that client*. With
  sync uploading up to 3 chunks concurrently, video's own buffering range requests were competing
  for what was left. Added `ApiClient.videoHttpClient` - a genuinely separate client (own
  `Dispatcher`/`ConnectionPool`, not `okHttpClient.newBuilder()`, which would have copied the same
  underlying shared instances and defeated the point) - so video's request budget no longer
  depends on how busy sync happens to be.

## Reverted: Main Space is not a valid sync target — 2026-09-13

Follow-up to (and partial revert of) "Main Space sync" further below: that entry removed
`SyncViewModel.loadSpaces()`'s `deletionAllowed` filter on the theory that nothing backend-side
stops Main Space's photos from mirroring a device folder. Explicit product call made this session:
Main Space should stay off the sync-target list after all - it's the app's default landing space
(also where Albums' references live, and the destination of "Move to Main Space"), not a
user-created collection meant to own a one-to-one device-folder mirror the way a regular Space
does. Restored the `.filter { it.deletionAllowed }` in `loadSpaces()` (`deletionAllowed` is false
for exactly, and only, the Main Space) so it drops back out of the Sync screen's space picker.
Backend untouched - this is a client-side policy choice, not a capability the API lacks.

No test file exercises `SyncViewModel.loadSpaces()` (`SyncViewModel` itself was never covered by
real compilation - see "Main Space sync" below - `androidx.work` needs a real Gradle/AGP resolve
this environment still doesn't have), so nothing else needed updating.

## Confirm before removing the device file on "move to Main" — 2026-09-13

`PagedPhotoGridViewModel.moveSelectionToMain` gained an `alsoRemoveFromDevice` param (default
`false`, unused change for every existing caller). `PhotoGridContent`'s "move to Main" action now
shows a confirmation dialog first, but only when `canExcludeFromSync` is true (i.e. this Space is
the one currently synced to a device folder - the only case a matching local file could even
exist). "Remove from device" reuses `onPhotosMovedToGarbage` - the exact same local-trash mirror
`moveSelectionToGarbage` already uses - since "move this file to `.iPhotoTrash`" is the identical
operation either way, just triggered from a different action. Not wired into the single-photo
lightbox - there's no "move to Main" action there at all yet, grid-only.

## Content-URI trigger for near-instant sync, even with the app not running — 2026-09-13

The 15-minute periodic poll alone meant sync only ever happened while the app was already
running/recently running - not the "sync when a photo is added, even if the app isn't running at
all" the user actually needed. Added `SyncWorker.scheduleContentTrigger` using
`Constraints.addContentUriTrigger(folderUri, true)` - backed by `JobInfo.TriggerContentUri`, this
is watched by the system JobScheduler itself, not any in-process listener, so it wakes the app for
a file change in the synced folder even from a fully dead process.

- Content-uri-trigger jobs are inherently one-shot (JobScheduler: can't combine with
  `setPeriodic()`/`setPersisted()`) - `SyncWorker.doWork()` re-arms it again at the end of every
  run (success or failure), and `SyncViewModel` also (re-)arms it explicitly when auto-sync is
  turned on and whenever the synced folder changes.
- **Kept the 15-minute periodic poll running as a fallback, not a replacement** - reliability of
  the content trigger depends on the specific SAF `DocumentsProvider` behind the chosen folder
  actually calling `notifyChange()`. The built-in local-storage/SD-card provider does (backed by a
  real `FileObserver`); a third-party provider (e.g. a cloud-storage app's own SAF picker) might
  never fire it, which is exactly what the periodic fallback covers.
- [ ] Not yet verified on-device with the app fully killed (not just backgrounded) - the mechanism
  is confirmed against the actual API docs/JobScheduler behavior, not observed end-to-end here.

## "Move to Main Space" for a synced Space, tracked so it isn't re-imported — 2026-09-13

Follow-up to "exclude/include from sync" below: "when connecting to a space different from main
space, user can move some assets to main space. In this case they should not be imported a second
time from device. [...] keep a list of excluded assets and a list of moved to main assets. Both
should not be synchronized again."

There was no "Move to Main Space" action anywhere in the Android app yet (the backend endpoint,
`POST /api/photos/main`, already existed and is used by the web frontend - see specification.md -
just never wired up on mobile). Added it, plus the sync-side bookkeeping the request specifically
called out:

- `PhotoApi.moveToMain` (new retrofit call).
- `PagedPhotoGridViewModel.moveSelectionToMain` + a new `canMoveToMain` flag (parallel to
  `albumId`/`canExcludeFromSync`) so `PhotoGridContent`/`SelectionActionBar` only offer the button
  (home icon) inside a Space - never Library or an Album, where "move to Main" is either already
  true or meaningless. Unlike exclude, this button isn't conditional on the Space being synced -
  "move to Main" is a general Space feature, not a sync one (see specification.md).
- `SyncPrefs`: the existing `ExcludedAsset` data class is now `TrackedAsset` (used by both lists) -
  added a second `movedToMainAssets`/`movedToMainHashes`/`markMovedToMain` set alongside the
  existing excluded one, same hand-rolled ``-delimited encoding, same reasoning: kept
  *separate* from the excluded list (as asked) since they're conceptually different actions even
  though `SyncRepository.computeDiff` treats both identically.
- `SyncRepository.computeDiff`'s third parameter is renamed `excludedHashes` -> `skipHashes` (still
  defaults to empty, still just "don't touch this hash's local file's upload/restore treatment") -
  callers now pass `syncPrefs.excludedHashes + syncPrefs.movedToMainHashes`, the union of both
  lists. Without this, a photo moved to Main Space is indistinguishable from a genuinely new local
  file on the next sync pass (it's in neither the Space's active list nor its garbage list anymore -
  it just belongs to a different Space now) - computeDiff would queue it right back up for upload
  and duplicate it.
- Only set for a synced Space, exactly like the exclude bookkeeping: `SpaceDetailScreen`'s
  `onPhotosMovedToMain` callback (`canMoveToMain` itself stays true regardless, per above).
- No "undo"/browse UI for the moved-to-main list this time, unlike excluded assets - the photo
  isn't hidden in garbage, it's sitting right there in Library (Main Space) same as always, so
  there's somewhere to find it already. If a "move back out of Main" feature ever gets built, this
  list is what it'd need to also clear via a new `SyncPrefs` method (there isn't one yet - only
  `markMovedToMain` adds to it).

**Verified for real**, same approach as the section below: recompiled and ran `SyncPrefsTest` (6
new tests, 14 total), `SyncRepositoryTest` (1 new test - a moved-to-main hash is never re-uploaded -
19 total), and `PagedPhotoGridViewModelTest` (5 new tests - `canMoveToMain`, `moveSelectionToMain`
with/without a callback set, its error and no-op paths - 30 total), against the same real
dependency jars. All passed. `SpaceDetailScreen`/`SelectionActionBar`/`PhotoGridContent`'s actual
Compose rendering (the new button) is reviewed by hand only, same Compose-UI caveat as always.

## Removed manual "Sync now", batched/throttled auto-sync, live space refresh — 2026-09-13

- **Removed "Sync now"** entirely (`SyncScreen`/`SyncViewModel`) - a one-off tap could push an
  unbounded number of assets in a single burst. The opt-in periodic job is now the only way sync
  ever runs.
- **Periodic interval 6h -> 15min**: asked for 10 minutes, but `PeriodicWorkRequest` has a hard
  15-minute floor - no WorkManager configuration goes lower (confirmed against the API, not
  assumed). Went with the plain 15-minute `PeriodicWorkRequestBuilder` rather than a
  self-rescheduling one-time-work chain that could hit exactly 10 - simpler and more robust
  (the OS guarantees the reschedule, not app code), per explicit confirmation.
- **Per-run upload cap**: `SyncRepository.selectUploadBatch` caps one run to a 200MB cumulative
  budget (`MAX_UPLOAD_BYTES_PER_RUN`) instead of draining the whole backlog - a large backlog now
  spreads across several 15-minute runs. Always includes at least one asset even if it alone
  exceeds the budget, so a single large file can't stall everything behind it forever.
- **Live space refresh**: `SpaceDetailScreen` now refreshes on `ON_RESUME` (returning to an
  already-open screen, or the app itself resuming) and, while a sync is actively `RUNNING` against
  the space currently being viewed, polls every 8s for new thumbnails - both via a new
  `PagedPhotoGridViewModel.refreshQuietly()` that swaps in the new page without flipping
  `isLoading` (a plain `loadFirstPage()` would blank/flash the grid every 8 seconds, which would
  have been bad UX for something running that often).
- [ ] **Not extended to Library/Main Space**: this only covers `SpaceDetailScreen`. Main Space
  sync is supported server-side and by `SyncWorker`, but `LibraryScreen` doesn't currently know its
  own backing space id, so it can't check "is *this* the space being synced" the way
  `SpaceDetailScreen` does. Worth adding if Main Space sync sees real use.

## Fixed: window froze on tapping "Log in" — 2026-09-13

`AuthRepository.login()`/`logout()` had no dispatcher switch at all, and `AuthViewModel` calls them
from `viewModelScope.launch { }` (defaults to `Dispatchers.Main.immediate`) - every
`tokenStore.xxx = ...` write runs through `KeystoreEncryptedPreferences`, which does real Android
Keystore crypto (`Cipher.doFinal()`, and on the very first call ever, a blocking
`KeyGenerator.generateKey()` against the Keystore). All of that was running directly on the UI
thread. Fixed by wrapping both methods' bodies in `withContext(Dispatchers.IO)`. Verified with a
real `assembleDebug` + `testDebugUnitTest` run, both green, and installed to the connected device.

- [ ] **Related, not yet fixed**: `AuthViewModel.resumeSession()` (called synchronously from
  `init {}`) reads `repository.isLoggedIn` / `repository.rememberMe` directly, outside any
  coroutine - same class of bug (a decrypt on the Keystore-backed store, blocking whatever thread
  constructs the ViewModel), just smaller/less noticeable since decrypt is faster than the
  first-ever key generation `login()` could hit. Worth moving these reads onto IO too, but that
  means either making `AuthRepository.isLoggedIn`/`rememberMe` suspend (touches every call site,
  including `LoginScreen`'s initial value wiring) or restructuring `resumeSession()` - didn't want
  to expand this fix's blast radius beyond the actual reported bug (the login button freezing).

## Main Space sync, exclude/include from sync, and "Remember me" — 2026-09-13

Three separate asks in one request: "user can't sync main space", "user can exclude/include some
asset from space [and excluding should] not [be] moved to garbage on device, only moving to
garbage in [the] distant server", and a "remember me" button on login.

- **Main Space sync**: `SyncViewModel.loadSpaces()` used to filter out any Space with
  `deletionAllowed = false`, which is exactly (and only) the Main Space, on the theory it "isn't a
  valid sync target". There's no backend reason it can't work - `/api/spaces/{id}/photos` and
  `/api/spaces/{id}/garbage` are already generic across every Space, Main included. Removed the
  filter.

- **Exclude/include from sync** (a brand new action - nothing like it existed before):
  `SpaceDetailScreen`'s selection bar gets an "Exclude from sync" button (eye-off icon), shown only
  for the Space currently synced to a device folder. Exclude garbages the photo on the server right
  away (same API call as "Move to garbage") but deliberately never mirrors it into `LocalTrash` -
  the request was explicit that an excluded photo should stay on the phone untouched, only garbaged
  server-side. `SyncPrefs.excludedAssets` (hash + title + photoId, a hand-rolled ``-delimited
  encoding rather than pulling in a JSON dependency this module otherwise has no need for) tracks
  which hashes are excluded; `SyncRepository.computeDiff` takes a new `excludedHashes` param and
  skips them entirely - without this, the *next* sync pass would either re-upload the still-present
  local file or auto-restore the Space photo (a garbage entry whose hash matches a present local
  file is normally exactly what `toRestore` is for), undoing the exclusion within one cycle.
  `SyncScreen` shows an "Excluded from sync" list with an "Include" button per entry (restores the
  photo server-side, drops it from the excluded set) - since there's still no Garbage screen (see
  "Test coverage" below), an excluded photo would otherwise be unreachable to undo.
  - Design call made without checking back first: with no Garbage/browse-garbage screen anywhere in
    the app yet, and the request not specifying where "include" should live, the undo list went on
    the Sync tab rather than building a whole Garbage screen. Worth moving there instead if a real
    Garbage screen ever gets built.

- **"Remember me" on login**: `TokenStore` already always persisted the session - there was no way
  to opt out. Added `TokenStore.rememberMe` (defaults `true`, so existing installs keep behaving
  exactly as before) plus a checkbox on `LoginScreen`. Unchecking it doesn't stop the token from
  being written (a background sync mid-flight shouldn't get logged out from under itself) - instead
  `AuthViewModel#resumeSession`, which only ever runs once per app process (the ViewModel survives
  config changes; only a real process restart recreates it), treats an unremembered session as
  expired on the *next* cold start and clears it via the new `AuthRepository#forgetUnrememberedSession`
  - no server-side revoke, the device token itself is still valid, this device just stops holding it.

**Verified for real**, same approach as the sections below: this environment still has no
gradle/AGP access (`dl.google.com` returns a 403 through the proxy, confirmed again this session -
`./gradlew :app:compileDebugKotlin` fails to resolve the Android Gradle Plugin itself, before
touching any app code). Compiled and ran the real changed files against real MockK/JUnit/
kotlinx-coroutines-test/retrofit/okhttp/gson jars from Maven Central plus the
`com.google.android:android:4.1.1.4` stub jar, with small hand-written compile-time stand-ins for
`androidx.lifecycle.ViewModel`/`viewModelScope` and `android.security.keystore.*` (same idea as the
existing `DocumentFile` stand-in below - Google-Maven-only, never exercised at runtime, only needed
so the real files typecheck):
- `SyncPrefsTest` (new, 8 tests) - the exclude/include encode/decode round trip, including a title
  containing the delimiter character.
- `SyncRepositoryTest` (3 new tests added to the existing 15) - `excludedHashes` suppresses both
  re-upload and auto-restore, and leaves non-excluded hashes alone.
- `PagedPhotoGridViewModelTest` (4 new tests added) - `excludeSelectionFromSync` garbages the
  selection and invokes only the exclude callback, never the move-to-garbage/local-trash one; plus
  its no-op/error paths.
- `TokenStoreTest` (3 new tests) + `AuthViewModelTest` (updated for the new `login` param, plus 2
  new tests) - `rememberMe` persistence, and `resumeSession` forgetting an unremembered session on
  a cold start without ever calling `currentUser()`.

All passed except one pre-existing, unrelated failure: `AuthViewModelTest`'s "login surfaces the
failure message" throws inside the old `com.google.android:android:4.1.1.4` stub's `Log.e` (a bare
`RuntimeException: Stub!` - that particular stub jar throws instead of no-op'ing, unlike the real
Gradle test config's `isReturnDefaultValues = true`). That `Log.e` call was already there,
unmodified by this change - a known limitation of this stand-in jar, not a regression.

Not covered by real compilation: `SyncViewModel` itself (still needs `androidx.work`, same
Google-Maven blocker as always) and the two touched Compose screens' actual rendering
(`SpaceDetailScreen`'s new button, `SyncScreen`'s new list/checkbox) - reviewed by hand only, same
caveat as every other Compose UI change in this file.

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

`SyncRepositoryUploadTest` added right after, covering `uploadAsset`'s chunking/multipart-building
and `waitForProcessing`'s polling loop: single-chunk and multi-chunk (asset larger than the 1 MiB
chunk size) uploads, exact multipart field values (`fileName`/`fileSize`/`chunkIndex`/
`totalChunks`/`contentType`/`spaceId`) and exact per-chunk byte content, `openInputStream` returning
null throwing before any network call, polling until `COMPLETED`, throwing on `FAILED`, and never
polling at all when the final chunk's response carries no task. A custom `StingyInputStream` test
double deliberately returns small reads (≤4 KiB) regardless of the requested length, to prove
`readFully`'s accumulation loop assembles a full 1 MiB chunk correctly even when the real
`ContentResolver` stream doesn't fill the buffer in one call - the exact scenario `readFully`'s own
comment says it exists for.

**Hit, and got past, a sharper verification-environment gotcha than the entries above**: mocking a
real `android.content.ContentResolver` via `robolectric:android-all` (the jar used to verify
everything above) crashed with `UnsatisfiedLinkError` in `Build.<clinit>` - that jar ships
Android's *actual platform implementation*, whose static initializers call real native methods
that only exist on a real device/emulator. The project's actual Gradle unit tests never hit this,
because AGP compiles/runs them against the plain Android SDK **stub** jar
(`unitTests.isReturnDefaultValues = true` in `app/build.gradle.kts`, empty-or-default method
bodies, no such static initializers) - `android-all` was a real-but-imperfect stand-in for that,
close enough for everything tested before this file but not for `ContentResolver`. Swapped in
`com.google.android:android:4.1.1.4` (an old but real community mirror of that same style of stub
jar, on Maven Central) for this one file instead, which is actually the *more* faithful match to
how the real project's tests run than `android-all` was - and all 20 tests across both
`SyncRepositoryTest` and `SyncRepositoryUploadTest` passed against it.

Not covered: `waitForProcessing`'s timeout path specifically (documented in the test file itself -
its deadline check uses real `System.currentTimeMillis()` while its retry backoff uses
`kotlinx.coroutines.delay()`, which only advances *virtual* time under `runTest`; actually reaching
the 5-minute deadline in a test would take 5 real minutes, not a virtual-time skip), and
`SyncViewModel` itself (the WorkManager plumbing - `observeWork`, `schedulePeriodic`, `confirmSync`
- would need `androidx.work`'s real classes plus `androidx.work:work-testing`'s fakes to test
meaningfully, and both are Google-Maven-hosted, same blocker as everywhere else `androidx.*` shows
up in this file).

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
- ~~`LocalTrash` restores a file to the folder root, not its original subfolder~~ - fixed,
  2026-09-13. `LocalAsset` gained a `relativeDir` field (e.g. `"Camera/2024/06"`, empty for a
  root-level file), populated by `LocalFolderScanner`/`LocalTrash.scan` as they walk (both already
  tracked depth for `MAX_DEPTH`, just didn't record the path). `LocalTrash.moveToTrash` now mirrors
  that same relative path under `.iPhotoTrash` (`resolveOrCreateDir`, walking/creating each path
  segment) instead of flattening everything into one directory, and `restoreFromTrash` recreates
  that path back under the synced root - a file that lived at `root/Camera/2024/06/IMG.jpg` now
  round-trips through `root/.iPhotoTrash/Camera/2024/06/IMG.jpg` and back to the same place instead
  of landing at `root/IMG.jpg`. `moveToTrash`/`restoreFromTrash`/`SyncRepository.restoreLocalAssets`
  /`trashLocalFilesForHashes` now take the whole `LocalAsset` instead of just its `Uri`, so
  `relativeDir` travels with it.
  Added `LocalTrashTest` (new file) directly exercising `resolveOrCreateDir` - now `internal` for
  exactly this reason - against a small hand-built in-memory `DocumentFile` tree: empty relativeDir
  resolves to the start dir itself, a single segment creates one missing directory, a nested path
  creates/traverses every segment in order, and resolving twice reuses the existing directories
  rather than creating duplicates. `moveToTrash`/`restoreFromTrash` themselves aren't covered by a
  test - they also depend on `DocumentFile.fromTreeUri`/`fromSingleUri`'s static resolution and
  `ContentResolver`-based byte copying, which would need a much larger fake-SAF harness than this
  fix's actual risk (the path math in `resolveOrCreateDir`) justified. Verified for real, same
  approach as the other `sync/` test files above: compiled and ran `LocalTrashTest` plus the updated
  `SyncRepositoryTest`/`SyncRepositoryUploadTest` (26 tests total) against real MockK/JUnit/
  coroutines-test/retrofit/okhttp/gson jars and the `com.google.android:android:4.1.1.4` stub jar -
  all passed.
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

## Battery usage audit — 2026-09-13

Went looking for the concrete things that actually drain battery on Android, rather than guessing:
wake locks, unbounded background polling, background-work scheduling constraints, and media
playback lifecycle. Already fine, no change needed:
- `SyncWorker`'s periodic auto-sync already gates on `CONNECTED` + `setRequiresBatteryNotLow(true)`
  (see "Sync/Space fluidity" above) at a 6h interval, not tighter.
- `HashCache` persists once per sync run, not per file (see its own kdoc).
- No `WakeLock`/`PowerManager`/`AlarmManager`/`Handler.postDelayed`/`Timer` usage anywhere in the
  module (checked directly - none exist).
- `SyncRepository`'s only polling loop (`waitForProcessing`, 1s interval / 5min cap) only runs
  during an active, user-triggered or already-battery-gated upload, not on a standing timer.
- OkHttp's logging interceptor is `if (BuildConfig.DEBUG)`-gated - zero overhead in release builds.
- Coil's `AsyncImage` decode size is constrained by Compose layout size resolution by default (no
  full-resolution decode for grid thumbnails) - nothing to tune here.

**Found and fixed a real one**: `VideoPlayer` (lightbox video playback, see the entry above) set
`playWhenReady = true` once and only ever released the player in `DisposableEffect`'s `onDispose`,
which fires when the composable leaves *composition* (navigating to a different photo) - not when
the app is merely backgrounded. Locking the screen or switching apps mid-video left ExoPlayer
decoding and playing indefinitely in the background: real, needless battery *and* data drain, for
exactly the kind of app where video playback is the single most power-hungry thing it does. Added a
`LifecycleEventObserver` (via `LocalLifecycleOwner`) that calls `exoPlayer.pause()` on
`ON_PAUSE`, alongside the existing release-on-dispose - deliberately no matching resume on
`ON_START`/`ON_RESUME`: coming back to a paused video and tapping play again via `PlayerView`'s own
controls is a simpler, safer contract than tracking "was this actually playing before it got
paused". **Not verified by a compile/run** - same `androidx.media3`/Google-Maven blocker as the
rest of the video-playback work, plus this specifically needs `LocalLifecycleOwner`
(`androidx.compose.ui.platform`), which has a real (if in this case low-probability) version-drift
risk: newer Compose/Lifecycle releases introduced a parallel `androidx.lifecycle.compose.
LocalLifecycleOwner` in `lifecycle-runtime-compose` (not currently a dependency here), and this
session has no way to confirm which one `compose-bom:2026.08.00` actually resolves `LocalLifecycleOwner`
to, or whether the old one still compiles against it. Needs the same real `assembleDebug` +
on-device check as the rest of the video player before trusting it - if it doesn't compile, the fix
is a one-line import swap to `androidx.lifecycle.compose.LocalLifecycleOwner` (adding
`lifecycle-runtime-compose` as a dependency), not a redesign.

Not otherwise pursued without being asked: restricting periodic auto-sync to `NetworkType.UNMETERED`
(Wi-Fi only) would save additional battery (cellular radio draws more power per byte transferred
than Wi-Fi) at the cost of auto-sync silently not running at all away from Wi-Fi - a real
product/data-usage trade-off, not a pure bugfix, so left as `CONNECTED` unless asked to change it.

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

`PagedPhotoGridViewModel.loadMore` edge cases added later still - `loadFirstPage` already had
coverage, `loadMore` had none. Covers: appends the next page and advances the page counter/
`endReached`; a no-op once `endReached`; a no-op while the first page is still loading
(`isLoading`) or while another `loadMore` is already in flight (`isLoadingMore`) - both exercised
by suspending the fake `loadPage` on a manually-completed `CompletableDeferred` so the guard can
actually be observed mid-flight, rather than racing a dispatcher that runs coroutines eagerly; a
failure preserves the existing photos/`endReached` and doesn't advance the page (so a retry
re-requests the *same* page, not the next one); and a successful retry after a failure.

**Found and fixed a real bug via that last test**: `loadMore`'s success branch built its new state
with `.copy(...)` and never touched `error`, unlike `loadFirstPage`, which always constructs a
fresh `PagedPhotosUiState()` (so `error` defaults back to null every time). That meant a failed
`loadMore` followed by a *successful* retry left the old error message sitting in state forever -
harmless today only because `PhotoGridContent` only ever renders `state.error` when
`state.photos.isEmpty()`, which is never true right after a successful `loadMore` appended more.
Added `error = null` to the success branch's `copy(...)` so a successful retry actually clears it,
matching every other action in this class.

Still not covered: `SyncViewModel` itself (see the "SyncRepository test coverage" entry above -
`SyncRepository` now has real coverage, `SyncViewModel`'s WorkManager plumbing doesn't yet).

**Verified for real**, same stand-in approach as `AlbumsViewModel`/`SpacesViewModel` above (`androidx.lifecycle`
unreachable here): compiled and ran the real, unmodified `PagedPhotoGridViewModelTest` (all 21 tests,
old and new) against the real `AlbumApi`/`PhotoApi`/`Photo`/etc., substituting only
`PagedPhotoGridViewModel` with a `CoroutineScope(Dispatchers.Main)` stand-in - all passed, including
after the `error = null` fix above.
