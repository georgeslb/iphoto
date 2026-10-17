# iPhoto Android Client — Architecture & Technical Decisions (ADR)

Ce document recense les choix techniques structurants de l'application Android **iPhoto**, les problématiques rencontrées (réseau, système de fichiers Android Scoped Storage, Media3 ExoPlayer, batterie/I/O) et les solutions retenues.

---

## ADR-001 : Lecture vidéo distante par blocs HTTP Range bornés (`ChunkedVideoDataSource`)

### Contexte & Problème
La lecture de vidéos distantes via `/api/photos/{id}/stream` sur le portail Web est fluide, tandis que sur l'application mobile Android (Media3 ExoPlayer), le chargement initial était très lent et la lecture s'arrêtait fréquemment :
1. **`OkHttpDataSource` par défaut utilise des requêtes ouvertes (`Range: bytes=start-`)** : Le serveur Spring Boot répond en plafonnant chaque réponse `206 Partial Content` à des chunks de `1 Mo` (`Content-Length: 1048576`). `OkHttpDataSource` interprétait la fin de ce premier chunk de 1 Mo comme la fin totale du flux (`C.RESULT_END_OF_INPUT`), provoquant un arrêt prématuré de la lecture.
2. **Allers-retours `moov` / `mdat` sur les MP4 non-*faststart*** : Les vidéos enregistrées par un smartphone placent souvent l'atome d'index `moov` à la **fin** du fichier. ExoPlayer lit l'en-tête (`0..`), saute à la fin pour lire `moov`, puis revient au début de `mdat` — et parfois alterne entre les pistes audio et vidéo si elles sont faiblement entrelacées, coupant et recréant des connexions TCP/TLS à chaque saut.
3. **Configuration `DefaultLoadControl` bloquante** : Avec `prioritizeTimeOverSizeThresholds = false`, ExoPlayer refusait de démarrer la lecture tant que `targetBufferBytes` n'était pas atteint pour les vidéos à haut débit (4K).
4. **Attente de `GET /api/photos/{id}` avant de monter `VideoPlayer`** : La requête de métadonnées partageait le pool HTTP des miniatures de la grille, retardant l'instanciation d'ExoPlayer.

### Décision Technique
1. **[`ChunkedVideoDataSource.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/network/ChunkedVideoDataSource.kt)** :
   - Envoie uniquement des requêtes HTTP `Range: bytes=start-end` **bornées** : probe initial de **256 Ko** (démarrage quasi-instantané), puis **1 Mo**, puis **2 Mo** en lecture séquentielle.
   - Extrait la taille totale réelle du fichier depuis l'en-tête `Content-Range: bytes start-end/total` et **chaîne automatiquement** les requêtes `Range` suivantes tant que `totalBytesRemaining > 0`, même si le serveur plafonne ses réponses `206`.
   - Intègre un **cache mémoire LRU de 32 Mo découpé en blocs alignés de 128 Ko (`VideoBlockCache`)** : les sauts `ftyp` $\rightarrow$ `moov` (fin de fichier) $\rightarrow$ `mdat` (début de fichier) et les micro-sauts audio/vidéo sont servis instantanément en mémoire (0 requête réseau).
   - Draine les fins de fenêtres $\le 256\text{ Ko}$ lors d'un `close()` au lieu de fermer brutalement le socket TCP (`cancel()`), préservant le *keep-alive* HTTP d'OkHttp.
2. **[`VideoPlayer.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/photo/VideoPlayer.kt)** :
   - `setPrioritizeTimeOverSizeThresholds(true)`, `bufferForPlaybackMs = 500`, `bufferForPlaybackAfterRebufferMs = 1500`, `backBufferDurationMs = 5000`.
   - Récupération automatique sur erreur réseau transitoire (`DefaultLoadErrorHandlingPolicy(10)` + `seekTo(currentPosition)` / `prepare()`).
3. **[`PhotoBrowsingContext.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/common/PhotoBrowsingContext.kt) & [`PhotoDetailViewModel.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/photo/PhotoDetailViewModel.kt)** :
   - Pré-remplissage immédiat de `PhotoDetailUiState.Loaded` avec l'objet `Photo` déjà en mémoire dans la grille afin que `VideoPlayer` démarre le buffering dès la 1ère frame.

---

## ADR-002 : Identité du hash MD5 entre Web UI et Android (`ACCESS_MEDIA_LOCATION`)

### Contexte & Problème
Lorsqu'une photo ou vidéo était téléversée depuis le Web UI puis téléversée à nouveau depuis l'application Android, le contrôle de doublon MD5 côté serveur ([`PhotoServiceImpl.java`](file:///C:/Users/georg/StudioProjects/iphoto/src/main/java/org/iphoto/service/PhotoServiceImpl.java)) ne reconnaissait pas toujours le fichier comme doublon, alors qu'un double envoi Web/Web ou App/App fonctionnait.

**Cause racine :**
Depuis **Android 10 (API 29 / Scoped Storage)**, le `MediaProvider` d'Android (`RedactingFileDescriptor` dans le système de fichiers FUSE) **masque automatiquement à la volée les coordonnées GPS** dans les métadonnées EXIF des images (JPEG, HEIC, WebP) et les atomes `©xyz`/`loci` des vidéos (MP4, MOV) en remplaçant leurs octets par `0x00` lors d'un appel à `ContentResolver.openInputStream(uri)`, **sauf si l'application détient la permission `android.permission.ACCESS_MEDIA_LOCATION`**.
- Sans cette permission, le flux envoyé par Android avait ses octets GPS mis à zéro (`MD5_masqué`), tandis que le fichier envoyé depuis un navigateur PC conservait ses octets GPS d'origine (`MD5_original`).

### Décision Technique
1. Déclaration de `<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />` dans [`AndroidManifest.xml`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/AndroidManifest.xml).
2. Demande de la permission à l'exécution dans [`SyncScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncScreen.kt).
3. Dans [`FileHasher.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/FileHasher.kt) (`openUnredactedInputStream`) et [`SyncRepository.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/SyncRepository.kt), ouverture du flux original non masqué via `MediaStore.setRequireOriginal(mediaStoreUri)` sur Android 10+ (avec repli transparent sur `contentResolver.openInputStream(uri)`).
4. **Avantage double :** Le hash MD5 calculé sur les octets reçus par le serveur est désormais strictement identique entre le Web UI et l'application Android, et le serveur conserve les coordonnées GPS des photos/vidéos téléversées depuis le mobile.

---

## ADR-003 : Messages de retour d'import, cycle de vie de l'état local (`SyncPrefs`) et badge Cloud en sélection

### Contexte & Problème
- Lorsque le serveur détectait un doublon à la fin de l'upload et renvoyait le statut de tâche `IGNORED` (ou `TRASHED`), les Toasts de [`SyncScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncScreen.kt) et [`AssetDetailScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/AssetDetailScreen.kt) affichaient `"Uploaded successfully"` au lieu du message du Web UI (`"Already imported"` / `"Already imported and in trash"`).
- Lorsqu'une photo était supprimée sur le téléphone, son état "envoyé sur le cloud" restait indéfiniment dans [`SyncPrefs.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/data/SyncPrefs.kt), et l'indicateur nuage (`CloudDone`) disparaissait visuellement dès qu'une vignette était sélectionnée.

### Décision Technique
1. **Alignement des messages utilisateur avec le Web UI (`UploadVerificationResult`)** :
   - `COMPLETED` $\rightarrow$ `"Uploaded successfully"` / `"Uploaded X photo(s)"`
   - `IGNORED` $\rightarrow$ `"Already imported"` (aligné avec le Web UI et [`UploadListDialog.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/UploadListDialog.kt))
   - `TRASHED` $\rightarrow$ `"Already imported and in trash"`
2. **Nettoyage automatique de l'état Cloud local à la suppression sur le terminal** :
   - Toute suppression locale d'un fichier (`onAssetsTrashed`, `trashAssetsDirectly`, `deleteSelectedAssets`, `trashSingleAsset`, `deletePermanently`, ou `upload...` avec `deleteAfter = true`) appelle `SyncPrefs.removeUploadedAsset(...)`.
   - Chaque rafraîchissement de dossier (`refreshFolder`) appelle `SyncPrefs.retainUploadedAssetsForFolder(...)` pour purger les clés des fichiers supprimés en dehors de l'application, et `removeFolder` purge toutes les clés du dossier retiré.
3. **Maintien de l'indicateur Cloud en mode sélection** :
   - Dans [`PhotoThumbnail.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/common/PhotoThumbnail.kt), le badge `Icons.Filled.CloudDone` (en haut à droite) reste affiché même lorsque `isSelectionMode == true` (la coche de sélection étant en haut à gauche).

---

## ADR-004 : Rafraîchissement automatique des Espaces et Albums, et tri des Albums par date de création

### Contexte & Problème
1. **Navigation Compose avec `saveState = true` / `restoreState = true`** : Dans [`MainScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/nav/MainScreen.kt), le changement d'onglet dans la barre de navigation restaure l'état des `ViewModel` (`SpacesViewModel`, `AlbumsViewModel`). Leur bloc `init` ne s'exécutait donc qu'à la toute première visite, laissant des listes ou compteurs (`photoCount`, `coverPhoto`) obsolètes après un ajout/retrait de photo ou une création depuis le Web UI.
2. **Tri des Albums** : Le backend ([`AlbumServiceImpl.java`](file:///C:/Users/georg/StudioProjects/iphoto/src/main/java/org/iphoto/service/AlbumServiceImpl.java)) retourne les albums triés par ordre alphabétique (`name`), alors que l'utilisateur souhaite voir les albums triés par **date de création** (du plus récent au plus ancien) dans l'onglet Albums et le sélecteur d'albums.

### Décision Technique
1. **Observation du cycle de vie `ON_RESUME` + compteurs de version dans [`AppContainer.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/AppContainer.kt)** :
   - Ajout d'un observateur `Lifecycle.Event.ON_RESUME` (en ignorant le premier `ON_RESUME` initial déjà couvert par `init`) et écoute de `container.libraryChangeVersion` dans [`SpacesScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/spaces/SpacesScreen.kt) pour recharger automatiquement les espaces à chaque clic sur l'onglet **Spaces** ou mutation de la bibliothèque.
   - Rafraîchissement explicite de la liste des espaces (`viewModel.refreshSpaces()`) à chaque ouverture de la modale de téléversement dans [`SyncScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncScreen.kt) et [`AssetDetailScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/AssetDetailScreen.kt).
   - Ajout de `albumsChangeVersion` / `notifyAlbumsChanged()` dans [`AppContainer.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/AppContainer.kt), déclenché par `addSelectionToAlbum`, `createAlbumAndAddSelection`, `removeSelectionFromAlbum` ([`PagedPhotoGridViewModel.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/common/PagedPhotoGridViewModel.kt)) ainsi que `addToAlbum`, `createAlbumAndAdd`, `removeFromAlbum` ([`PhotoDetailViewModel.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/photo/PhotoDetailViewModel.kt)).
   - Écoute de `ON_RESUME` et `albumsChangeVersion` dans [`AlbumsScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/albums/AlbumsScreen.kt) et [`AlbumDetailScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/albums/AlbumDetailScreen.kt).
2. **Tri par date de création (`createdAt` décroissant)** :
   - Dans [`AlbumsViewModel.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/albums/AlbumsViewModel.kt) et [`AlbumPickerDialog.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/common/AlbumPickerDialog.kt), tri des `AlbumDTO` via `.sortedByDescending { it.createdAt }` (chaîne ISO-8601 `LocalDateTime` comparable lexicographiquement).

---

## ADR-005 : Vérification MD5 en tâche de fond au démarrage par lots de 50 (`check-hashes` + `HashCache`)

### Contexte & Problème
Lorsqu'un utilisateur téléverse des photos/vidéos depuis le Web UI (ou réinstalle l'application mobile), l'application Android ne savait pas que ces fichiers locaux présents dans les dossiers de l'onglet **Sync** étaient déjà sur le serveur, et n'affichait donc pas l'icône Cloud (`Icons.Filled.CloudDone`).
Calculer tous les hashes à chaque affichage sans cache ou envoyer une requête HTTP par fichier serait trop coûteux.

### Décision Technique
1. **Persistance locale et nettoyage automatique des hashes MD5 ([`HashCache.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/HashCache.kt))** :
   - Chaque hash MD5 calculé via [`FileHasher.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/FileHasher.kt) (avec flux original non masqué `MediaStore.setRequireOriginal`) est mis en cache dans `sync_hash_cache.json` indexé par `(uri, size, lastModified)`.
   - Un fichier déjà connu dont la taille et la date de modification n'ont pas changé n'est **jamais relu sur le stockage flash** aux démarrages suivants (coût I/O et batterie quasi nul après le premier passage).
   - **Suppression automatique du stockage MD5 à la suppression sur le téléphone** : Lorsqu'une photo/vidéo est supprimée sur le terminal (`removeAll`), supprimée en dehors de l'app (détectée au scan via `retainForFolder`), ou lorsqu'un dossier est retiré (`removeForFolder`), ses entrées MD5 correspondantes sont immédiatement purgées de `HashCache` et persistées sur disque.
2. **Vérification serveur par lots de 50 (`HASH_CHECK_BATCH_SIZE = 50`)** :
   - Côté serveur, [`PhotoController.java`](file:///C:/Users/georg/StudioProjects/iphoto/src/main/java/org/iphoto/controller/PhotoController.java) (`POST /api/photos/check-hashes`) et [`CheckHashesResponse.java`](file:///C:/Users/georg/StudioProjects/iphoto/src/main/java/org/iphoto/model/CheckHashesResponse.java) retournent `existingHashes` ainsi qu'une map `statuses: Map<String, Boolean>` donnant le statut exact (`true`/`false`) pour chaque hash demandé en une seule requête SQL indexée (`SELECT DISTINCT p.fileHash FROM PhotoEntity p WHERE p.fileHash IN :hashes`).
   - Côté Android, au démarrage de l'application (`SyncViewModel.init` $\rightarrow$ `refreshAllFolders()` $\rightarrow$ `verifyFolderHashesInBackground`) et de manière séquentielle (protégée par un `Mutex` sur `Dispatchers.IO`), [`SyncRepository.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/SyncRepository.kt) (`verifyFolderAssetsHashesInBatches`) calcule/récupère les hashes MD5 par tranche de **50 fichiers** (ainsi que le reliquat final $< 50$), persiste immédiatement `HashCache` sur disque, et interroge `POST /api/photos/check-hashes`.
   - À chaque lot de 50 répondu, [`SyncPrefs.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/data/SyncPrefs.kt) (`applyBatchHashCheckStatuses`) et `SyncUiState.uploadedAssetKeys` sont mis à jour en temps réel, affichant automatiquement l'icône Cloud (`Icons.Filled.CloudDone`) sur toutes les photos/vidéos dont `check-hashes` retourne `true`.
