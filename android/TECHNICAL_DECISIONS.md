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

## ADR-005 : Vérification MD5 en tâche de fond au démarrage par lots de 50 (`check-hashes` + `HashCache`) et Double Hash MD5 (Avec / Sans Localisation)

### Contexte & Problème
1. Lorsqu'un utilisateur téléverse des photos/vidéos depuis le Web UI (ou réinstalle l'application mobile), l'application Android ne savait pas que ces fichiers locaux présents dans les dossiers de l'onglet **Sync** étaient déjà sur le serveur, et n'affichait donc pas l'icône Cloud (`Icons.Filled.CloudDone`).
2. **Rétrocompatibilité des fichiers téléversés avant `ACCESS_MEDIA_LOCATION`** : Certains fichiers locaux ont déjà été téléversés sur le serveur distant depuis le terminal **avant** l'ajout de `ACCESS_MEDIA_LOCATION`, c'est-à-dire **sans localisation** (avec les octets GPS mis à zéro par le `MediaProvider` d'Android). Sur le serveur distant, leur `file_hash` correspond donc au MD5 **sans localisation** (`redactedMd5`), tandis que les fichiers téléversés depuis le Web UI ou après l'activation de `ACCESS_MEDIA_LOCATION` ont pour `file_hash` le MD5 **réel** (`realMd5`).

### Décision Technique
1. **Calcul en une seule passe du double MD5 (`realMd5` + `redactedMd5` sans localisation) dans [`FileHasher.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/FileHasher.kt)** :
   - `FileHasher.computeHashes(...)` retourne un `AssetHashes(realMd5, redactedMd5)`.
   - Avant de lire le flux non masqué (`MediaStore.setRequireOriginal`), `FileHasher` identifie les plages d'octets exactes `[start, end)` correspondant aux métadonnées de localisation que le `MediaProvider` d'Android (`RedactionUtils`, `IsoInterface`, `XmpDataParser`) masque par des `0x00` :
     - **Images (JPEG, HEIC, WebP, TIFF, etc.)** : Les 31 tags EXIF GPS (`TAG_GPS_*`) via `ExifInterface.getAttributeRange(tag)` (API 29+) ainsi qu'un parseur TIFF/JPEG GPS IFD (`0x8825`) en Kotlin pur en repli.
     - **Vidéos ISO Base Media (MP4, MOV, 3GP)** : Les boîtes ISO de localisation `©xyz` (`0xa978797a`), `loci` (`0x6c6f6369`), `gps ` (`0x67707320`) et `gps0` (`0x67707330`) dans l'arbre `moov`/`udta`/`meta`/`ilst`.
     - **Métadonnées XMP** : Les balises `<exif:GPS*>` présentes dans les blocs XMP d'images ou de vidéos (`XMP_` / UUID `be7acfcb-97a9-42e8-9c71-999491e3afac`).
   - Le fichier n'est lu **qu'une seule fois** sur le stockage flash : au fil de la lecture du flux original, `realDigest` calcule le MD5 réel et, si des plages GPS existent, `redactedDigest` calcule simultanément le MD5 sans localisation en remplaçant ces plages d'octets par `0x00`. Si le fichier ne contient aucune métadonnée GPS, `redactedMd5` est égal à `realMd5` sans second `MessageDigest`.
2. **Persistance locale et nettoyage automatique des deux hashes MD5 ([`HashCache.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/HashCache.kt))** :
   - `HashCache` persiste `hash` (`realMd5`) et `redactedHash` (`redactedMd5`) dans `sync_hash_cache.json` indexé par `(uri, size, lastModified)`.
   - Les anciennes entrées de cache ne possédant pas encore `redactedHash` (`null`) sont automatiquement recalculées et mises à niveau lors du passage suivant.
   - **Suppression automatique du stockage MD5 à la suppression sur le téléphone** : Lorsqu'une photo/vidéo est supprimée sur le terminal (`removeAll`), supprimée en dehors de l'app (`retainForFolder`), ou lorsqu'un dossier est retiré (`removeForFolder`), ses entrées MD5 sont immédiatement purgées de `HashCache` et persistées sur disque.
3. **Vérification serveur par lots de 50 (`HASH_CHECK_BATCH_SIZE = 50`) sur les deux hashes** :
   - Côté serveur, [`PhotoController.java`](file:///C:/Users/georg/StudioProjects/iphoto/src/main/java/org/iphoto/controller/PhotoController.java) (`POST /api/photos/check-hashes`) et [`CheckHashesResponse.java`](file:///C:/Users/georg/StudioProjects/iphoto/src/main/java/org/iphoto/model/CheckHashesResponse.java) retournent `existingHashes` ainsi qu'une map `statuses: Map<String, Boolean>` donnant le statut exact (`true`/`false`) pour chaque hash demandé.
   - Côté Android, [`SyncRepository.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/SyncRepository.kt) (`verifyFolderAssetsHashesInBatches`) envoie à la fois `realMd5` et `redactedMd5` (lorsqu'ils diffèrent, via `AssetHashes.allDistinct()`) à `POST /api/photos/check-hashes`.
   - Si **l'un ou l'autre** des deux hashes (`statusByHash[realMd5] == true || statusByHash[redactedMd5] == true`) retourne `true`, la photo/vidéo est marquée comme présente sur le serveur dans [`SyncPrefs.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/data/SyncPrefs.kt) et affiche l'icône Cloud (`Icons.Filled.CloudDone`).

4. **Bouton de forçage manuel `check_hashes` et indicateur de progression dans l'onglet Sync** :
   - Dans [`SyncScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncScreen.kt), un bouton `Icons.Default.Sync` en haut à droite de la rangée de sélection des dossiers permet de relancer manuellement l'interrogation serveur `POST /api/photos/check-hashes` par paquets de 50 pour tous les dossiers sélectionnés.
   - Lors de ce forçage ([`SyncViewModel.forceCheckHashes`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncViewModel.kt)), les hashes MD5 déjà présents dans [`HashCache.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/HashCache.kt) sont réutilisés sans re-scanner inutilement le disque.
   - Tant que les vérifications serveur sont en cours (`state.isCheckingHashes == true`), l'icône tourne en boucle (`rotate(rotationAngle)`) et le bouton est désactivé pour éviter les déclenchements concurrents. L'exécution s'effectue intégralement en tâche de fond sans figer la grille d'assets.

---

## ADR-006 : Optimisation du chargement de l'onglet Sync, retour instantané depuis la Lightbox et icône Cloud dans la Lightbox locale

### Contexte & Problème
1. **Lenteur du premier accès à l'onglet Sync** : Lors du scan d'un dossier local (`LocalFolderScanner.scan`), chaque fichier vidéo ouvrait séquentiellement un `MediaMetadataRetriever` natif via IPC `ContentResolver` pour extraire `METADATA_KEY_DURATION`, prenant 5 à 15 secondes sur des dossiers contenant de nombreuses vidéos. De plus, l'entrée en composition de [`SyncScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncScreen.kt) déclenchait immédiatement un événement `Lifecycle.Event.ON_RESUME` qui relançait `viewModel.refreshAllFolders()` en doublon du scan déjà démarré par `SyncViewModel.init`.
2. **Lenteur et spinner au retour de la Lightbox locale ([`AssetDetailScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/AssetDetailScreen.kt))** :
   - `AssetDetailScreen` et `SyncScreen` sont deux destinations distinctes dans le `NavHost` ([`MainScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/nav/MainScreen.kt)). Ouvrir la Lightbox puis revenir en arrière (`popBackStack()`) faisait ré-entrer `SyncScreen` en composition et déclenchait `ON_RESUME` -> `viewModel.refreshAllFolders()`.
   - Or `refreshFolder` ajoutait inconditionnellement l'URI du dossier à `isLoadingFolderContents`, et `FolderContentGrid` testait `if (isLoading)` avant d'afficher `items`, remplaçant toute la grille de photos déjà en mémoire par un `CircularProgressIndicator` plein écran pendant tout le rescan du dossier.
3. **Indicateur Cloud dans la Lightbox locale** : L'utilisateur souhaite également voir l'icône Cloud (`Icons.Filled.CloudDone`) dans la Lightbox de l'onglet Sync lorsque la photo/vidéo affichée est déjà téléversée.

### Décision Technique
1. **Requête groupée `MediaStore` + cache mémoire des durées vidéo dans [`LocalFolderScanner.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/LocalFolderScanner.kt)** :
   - Au début de `LocalFolderScanner.scan(...)`, une seule requête groupée (`ContentResolver.query`) est effectuée sur `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` (`DISPLAY_NAME`, `SIZE`, `DURATION`) pour pré-charger toutes les durées vidéo en quelques millisecondes.
   - Un cache en mémoire `ConcurrentHashMap<String, Long>` (`videoDurationCache`, indexé par `"$uri|$size|$lastModified"`) évite tout appel `MediaMetadataRetriever` lors des scans suivants, `MediaMetadataRetriever` n'étant plus utilisé qu'en repli pour un fichier vidéo non encore indexé par `MediaStore`.
2. **Navigation instantanée et rafraîchissement non bloquant ([`SyncViewModel.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncViewModel.kt), [`SyncScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SyncScreen.kt), [`AssetDetailScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/AssetDetailScreen.kt))** :
   - Suppression du `DisposableEffect(lifecycleOwner)` redondant dans `AssetDetailScreen.kt`.
   - Dans `SyncScreen.kt`, l'observateur `DisposableEffect(lifecycleOwner)` ignore le premier `ON_RESUME` émis lors de l'entrée en composition (`isFirstResume`), de sorte que l'ouverture de l'onglet Sync ou le retour depuis `AssetDetailScreen` affiche instantanément la grille en mémoire sans relancer `refreshAllFolders()`.
   - Dans `SyncViewModel.refreshFolder`, `isLoadingFolderContents` n'est activé que lors du chargement initial (`!folderContents.containsKey(uri)`), et `FolderContentGrid` n'affiche le `CircularProgressIndicator` plein écran que si `isLoading && items.isNullOrEmpty()`.
   - `verifyFolderHashesInBackground` conserve le job de vérification MD5 en cours si la liste des signatures `(uri, size, lastModified)` du dossier n'a pas changé.
3. **Affichage de l'icône Cloud dans [`AssetDetailScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/AssetDetailScreen.kt)** :
   - Calcul réactif de `isCurrentAssetUploaded` pour la page courante du `HorizontalPager` à partir de `state.uploadedAssetKeys`, et affichage de `Icons.Filled.CloudDone` dans les `actions` de la `TopAppBar` de la Lightbox locale.

---

## ADR-007 : Restauration du bouton Édition de la Lightbox Sync et ré-alignement de la photo éditée sur l'original

### Contexte & Problème
1. **Régression** : le bouton **Edit** de la Lightbox locale ([`AssetDetailScreen.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/AssetDetailScreen.kt)) introduit par `652d392` a disparu lors des commits `1b13f0a` (suppression du bouton, du flux d'édition et de `SyncPrefs.defaultEditorPackage`) et `3d17202` (suppression de `SystemEditorHelper.kt`, `EditorPickerDialog.kt` et de leur test), basés sur un état de travail antérieur à la fonctionnalité.
2. Les éditeurs externes (Google Photos, Samsung Gallery…) enregistrent généralement la photo modifiée comme **nouveau fichier**, parfois dans un autre dossier (`Pictures/…`), avec une date de modification « maintenant » : la photo éditée n'apparaissait donc pas à côté de l'original dans l'onglet Sync et perdait ses propriétés.

### Décision Technique
1. Restauration de [`SystemEditorHelper.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/SystemEditorHelper.kt), [`EditorPickerDialog.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/ui/sync/EditorPickerDialog.kt), de `SyncPrefs.defaultEditorPackage` et du bouton **Edit** dans la barre inférieure de la Lightbox.
2. Nouveau [`EditedMediaRelocator.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/main/java/org/iphoto/mobile/sync/EditedMediaRelocator.kt) :
   - **Avant le lancement** : capture d'un `EditSnapshot` de l'original (`RELATIVE_PATH`, `DATE_TAKEN`, `DATE_MODIFIED`, taille, `lastModified` du fichier, tags EXIF date/heure/fuseau/appareil/GPS lus via le flux non masqué).
   - **Au retour de l'éditeur** : recherche dans `MediaStore` des fichiers ajoutés depuis le lancement. Pour supporter les éditeurs constructeurs (ex. ColorOS/Oppo `com.oplus.gallery` ou `com.coloros.gallery3d`, Samsung, Xiaomi) qui délèguent l'écriture finale à un service système ou `com.android.providers.media`, la vérification de `ownerPackage` accepte le package de l'éditeur lancé ainsi que les providers système/OEM.
   - **Écriture asynchrone** : pour compenser le fait que certains éditeurs ferment l'activité avant que l'encodage et l'indexation MediaStore soient achevés, `findEditedOutputs` sonde activement par paliers successifs (~6 secondes max) avant de passer le relais.
   - **Une seule confirmation système** `MediaStore.createWriteRequest` (API 30+) puis : copie des tags EXIF de l'original, mise à jour `RELATIVE_PATH` (déplacement dans le dossier de l'original), restauration simultanée de `DATE_TAKEN` et `DATE_MODIFIED` via `ContentValues`, restauration de la date de modification du fichier (`File.setLastModified`) et re-scan `MediaScannerConnection`.
   - La logique de décision (`isNewEditorOutput`, `isOriginalOverwritten`, `planFix`) est pure et couverte par [`EditedMediaRelocatorTest.kt`](file:///C:/Users/georg/StudioProjects/iphoto/android/app/src/test/java/org/iphoto/mobile/sync/EditedMediaRelocatorTest.kt).
