# iPhoto

A high-performance, self-hosted photo and video management platform featuring deep-learning face recognition, perceptual deduplication, chronological timeline navigation, interactive geolocation mapping, and a native Android synchronization client.

---

## Table of Contents

- [Overview & Purpose](#overview--purpose)
- [Architecture & Core Building Blocks](#architecture--core-building-blocks)
  - [1. Backend & Async Worker Engine](#1-backend--async-worker-engine)
  - [2. Web Application (SPA)](#2-web-application-spa)
  - [3. Native Android Mobile App](#3-native-android-mobile-app)
  - [4. Automated NAS Backup Sidecar](#4-automated-nas-backup-sidecar)
  - [5. Admin Back Office](#5-admin-back-office)
- [Tech Stack & Languages](#tech-stack--languages)
- [Exhaustive Features Breakdown](#exhaustive-features-breakdown)
  - [Core Organization: Spaces & Albums](#core-organization-spaces--albums)
  - [Library, Memories & Timeline Grid](#library-memories--timeline-grid)
  - [AI Face Detection & Person Clustering](#ai-face-detection--person-clustering)
  - [Perceptual Deduplication & Duplicate Clusters](#perceptual-deduplication--duplicate-clusters)
  - [Full-Featured Lightbox & Video Player](#full-featured-lightbox--video-player)
  - [Interactive Geolocation Map](#interactive-geolocation-map)
  - [Share Links & Guest Access](#share-links--guest-access)
  - [Drag-and-Drop Chronological Reordering](#drag-and-drop-chronological-reordering)
  - [Android Mobile App & Background Sync](#android-mobile-app--background-sync)
  - [Remote Photo Editing with External Apps](#remote-photo-editing-with-external-apps)
  - [Immich Migration Tool](#immich-migration-tool)
- [Quick Start & Deployment](#quick-start--deployment)
  - [Prerequisites](#prerequisites)
  - [1. Clone and Configure Environment](#1-clone-and-configure-environment)
  - [2. Start the Stack](#2-start-the-stack)
  - [3. Configure NAS Backup (Optional)](#3-configure-nas-backup-optional)
  - [4. Android Client Setup](#4-android-client-setup)
- [CI/CD & Automated Releases](#cicd--automated-releases)
- [Development & Testing](#development--testing)
- [License](#license)

---

## Overview & Purpose

**iPhoto** is an open-source, private photo management ecosystem designed to serve as a self-hosted alternative to proprietary cloud services (Google Photos, Apple Photos, Immich).

### Key Highlights
- **Absolute Privacy & True Ownership**: Hosted entirely on your own server or home infrastructure. No telemetry, third-party analytics, or vendor lock-in.
- **Non-Destructive Storage**: Original photos, videos, and EXIF/XMP metadata are preserved byte-for-byte. Derived assets (thumbnails, video preview clips, web-optimized previews) are stored separately.
- **Engineered for Scale**: Effortlessly manages collections of 100,000+ assets through day-indexed database partitioning, lazy-loading IntersectionObservers, and asynchronous background worker queues.
- **Local Machine Learning**: Performs face detection (MTCNN) and vector embeddings (ResNet-50) using on-device Deep Java Library (DJL) and PyTorch models without transmitting media to third-party APIs.
- **Dual Client Ecosystem**: Seamless web experience for desktop/mobile browsers coupled with an offline-first native Android app with delta synchronization.

---

## Architecture & Core Building Blocks

```
                      ┌───────────────────────────────────────────────┐
                      │              Client Interfaces                │
                      │   Web SPA (React 18)   │  Android (Compose)   │
                      └───────┬───────────────────────────────┬───────┘
                              │ REST / HTTP 206 Streaming     │ SAF / Retrofit
                              ▼                               ▼
                      ┌───────────────────────────────────────────────┐
                      │           iPhoto Backend (Spring Boot 25)     │
                      │  Controllers │ Services │ Repositories │ Auth │
                      └───────┬───────────────────────────────┬───────┘
                              │                               │
              ┌───────────────┴──────────────┐                │
              ▼                              ▼                ▼
  ┌───────────────────────┐      ┌─────────────────┐   ┌───────────────┐
  │  PostgreSQL Database  │      │  Async Workers  │   │  Disk Volume  │
  │ (Metadata, Indexing)  │      │  (DJL, PyTorch, │   │ (Originals,   │
  │                       │      │   OpenCV/FFmpeg)│   │  Thumbnails)  │
  └───────────────────────┘      └─────────────────┘   └───────┬───────┘
                                                               │
                                                               ▼ (Nightly rsync)
                                                       ┌───────────────┐
                                                       │  NAS Backup   │
                                                       │  (Synology /  │
                                                       │   TrueNAS SSH)│
                                                       └───────────────┘
```

The system is partitioned into four decoupled modules:

### 1. Backend & Async Worker Engine (`src/main/java/org/iphoto`)
- **Runtime**: Java 25 running on Spring Boot 3.5.
- **Database**: PostgreSQL 16 managed via Flyway versioned migrations.
- **Asynchronous Task Architecture (`TaskWorkerManager`)**: Heavy computation tasks are decoupled from API requests into queued tasks (`TaskEntity`):
  - `CALCULATE_HASH`: Computes SHA-256 signatures for exact duplicate detection.
  - `EXTRACT_METADATA`: Parses EXIF, GPS, camera model, lens, ISO, and shutter speed.
  - `GENERATE_THUMBNAILS`: Generates multi-scale responsive thumbnails via JavaCV.
  - `ISOLATE_FACES`: Runs MTCNN neural networks to extract bounding boxes for all detected faces.
  - `GENERATE_FACE_EMBEDDING`: Generates 512-dimension vector embeddings using ResNet-50.
  - `FACE_RECOGNITION`: Clusters face embeddings using cosine similarity into identifiable persons.
  - `CHECK_DUPLICATION`: Computes perceptual image hashes (ORB) and calculates Hamming distances.
  - `TRANSCODE_VIDEO`: Generates web-streamable MP4 clips and animated hover previews.

### 2. Web Application (SPA) (`src/main/resources/static/src`)
- **Framework**: React 18 with Vite bundling.
- **Grid Rendering**: Responsive masonry grid where each row dynamically calculates flex-basis from aspect ratios while maintaining a unified row height.
- **Virtualization & Look-Ahead**: Day-indexed pagination loaded via a shared `IntersectionObserver` that pre-fetches two days ahead of the viewport.
- **Navigation & Inspection**: Full-screen responsive Lightbox, pan/zoom canvas, interactive Leaflet world map, and timeline scrubber.

### 3. Native Android Mobile App (`android/`)
- **Framework**: Kotlin with 100% Jetpack Compose and Material 3 design.
- **Storage Access Framework (SAF)**: Selectively pair local device directories (Camera, WhatsApp, Screenshots) to specific cloud Spaces without broad storage permissions.
- **Offline-First Delta Sync**: Powered by AndroidX WorkManager. Compares local SHA-256 hashes against remote index records before uploading, saving bandwidth and battery.
- **Single-Request Fast Timeline**: Real-time fast-scroll scrubber jumps directly to any historical date in a single HTTP request without fetching intermediary pages.
- **Remote Editing**: Allows editing server-stored photos in external editors (Snapseed, Lightroom, Google Photos) and atomically saving modified versions back to the server.

### 4. Automated NAS Backup Sidecar (`nas-backup/`)
- **Container**: Independent Alpine-based backup container (`iphoto-nas-backup`).
- **Nightly Synchronization**: Runs at 02:00 every night over encrypted SSH.
- **Asset Integrity**: Performs non-destructive atomic `rsync` of the entire `/data/iphoto` volume to Synology/TrueNAS storage. Existing remote assets are never deleted.
- **Database Dump Pruning**: Automates `pg_dump` of the PostgreSQL database with configurable retention pruning (`DB_DUMP_RETENTION_DAYS`, default 30 days).

### 5. Admin Back Office
- **Task Management (`/admin/tasks`)**: Live inspection of every background worker job, showing execution duration (`durationMs`), state (`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `PARKED`), and stack traces.
- **Task Controls**: Ability to park/unpark jobs, batch retry failed tasks, or trigger full pipeline re-processing (re-extract faces, re-calculate duplicates, or purge and re-cluster persons).
- **User Management (`/admin/users`)**: Create, inspect, activate, and deactivate user accounts and assign security roles (`ROLE_USER`, `ROLE_ADMIN`).

---

## Tech Stack & Languages

| Component | Language | Technologies & Frameworks | Key Dependencies |
| :--- | :--- | :--- | :--- |
| **Backend API** | Java 25 | Spring Boot 3.5, Spring Security, Spring Data JPA | Deep Java Library (DJL), PyTorch Engine, JavaCV (OpenCV 4.11, FFmpeg 7.1), Flyway, Jackson, Lombok |
| **Database** | SQL / PLpgSQL | PostgreSQL 16 (Alpine) | Testcontainers (Integration Testing) |
| **Web Frontend** | JavaScript (ES6+), JSX | React 18, Vite | Lucide React, Leaflet, React-Cropper, Vitest, Testing Library |
| **Android Mobile** | Kotlin | Jetpack Compose, Material 3, AndroidX | WorkManager, Retrofit 2, OkHttp 4, Coil, Navigation Compose |
| **NAS Backup** | Bash / Shell | Alpine Linux, OpenSSH, Crond | rsync, postgresql-client (pg_dump) |
| **DevOps / CI** | YAML, Dockerfile | Docker, Docker Compose, GitHub Actions | GitHub Container Registry (GHCR), multi-stage Docker build |

---

## Exhaustive Features Breakdown

### Core Organization: Spaces & Albums

iPhoto maintains a clean architectural boundary between photo **ownership** and photo **organization**:

- **Spaces (Ownership)**: Every photo belongs to exactly one Space at any time.
  - *Main Space*: The primary user vault whose assets populate the master Library timeline.
  - *Custom Spaces*: Isolated photo containers (e.g. Work, Private, Archive). Photos can be moved freely between spaces.
  - *Space Sync Locks*: Spaces can be paired with an Android device folder. When locked, sync operations prevent collision.
- **Albums (Virtual Collections)**:
  - Albums contain *references* to photos that reside in the Main Space.
  - A single photo can belong to multiple albums without duplicating on-disk storage.
  - Deleting an album removes the virtual grouping; original photos remain safe in the Main Space.
- **Two-Stage Garbage & Cascading Deletion**:
  - Individual photos moved to Garbage can be restored or permanently purged.
  - Entire custom Spaces can be soft-deleted into Garbage.
  - Restoring a Space restores its photos; permanently deleting a Space cascades and permanently purges all contained assets. The Main Space cannot be deleted.

### Library, Memories & Timeline Grid

- **Day-Indexed Infinite Grid**: Photos are chronologically sectioned by day (`yyyy-MM-dd`). The frontend queries `/api/photos/days` to obtain the full collection timeline, then dynamically fetches individual day buckets as the user scrolls.
- **Look-Ahead Prefetching**: When scrolling reaches day $N$, day $N+2$ is pre-fetched in the background, eliminating blank screen flickering.
- **Timeline Scrubber**: A vertical scrubbing bar on the right margin with year indicators. Scrubbing displays a floating date pill and jumps immediately to the selected day without sequential intermediate page requests.
- **Memories Bar**: Automatically cycles through past moments on configurable anniversaries (e.g., 1 month ago, 1 year ago, 5 years ago) with configurable window tolerances (e.g. `6-2d+3d`).
- **Recently Added Strip**: An expandable horizontal carousel at the top of Library and Space views displaying the most recently *uploaded* (rather than taken) assets.

### AI Face Detection & Person Clustering

- **Local Machine Learning**: Built-in MTCNN face detection pipeline detects faces from photos upon upload.
- **ResNet-50 Embeddings**: Generates a 512-dimension mathematical vector representing facial geometry.
- **Automatic Clustering**: Cosine similarity clustering groups recurring faces into unique `PersonEntity` records.
- **Person Management**: Name people, assign primary avatar covers, hide individuals from view, or unlink false detections.
- **Merge Suggestion Flow**: The system continuously analyzes proximity between person clusters and proposes candidates for one-click merges.

### Perceptual Deduplication & Duplicate Clusters

- **Multi-Level Detection**:
  1. *Exact Duplicates*: Computed via SHA-256 bitwise hash comparison.
  2. *Perceptual Duplicates*: Computed using OpenCV ORB feature extraction and Hamming distance comparison to catch resized, re-compressed, or cropped copies.
- **Collapsed Cluster Badges**: Duplicate photos are grouped behind the primary asset in the grid with a `×N similar` badge.
- **Flicker Comparison Modal**: A dedicated modal allowing instant back-and-forth comparison between candidate duplicates, side-by-side EXIF inspection, and survivor promotion (choosing which copy to keep and which to trash).

### Full-Featured Lightbox & Video Player

- **Full-Resolution Inspection**: Smooth pan and zoom canvas up to 500% zoom with edge clamping.
- **Non-Destructive In-Browser Editing**: Rotate, crop with preset aspect ratios, and update EXIF capture timestamps directly from the interface.
- **Metadata Sidebar**: Comprehensive EXIF breakdown (camera manufacturer, lens model, aperture, shutter speed, ISO, focal length, file size, dimensions).
- **HTTP 206 Video Streaming**: Native video player supporting partial content byte-range requests for instantaneous seeking without downloading the full video.
- **Face Bounding Box Overlay**: Inspect detected faces with interactive bounding boxes and person tags directly over the photo.

### Interactive Geolocation Map

- **World Map View**: Leaflet-powered global map rendered using OpenStreetMap tiles.
- **EXIF GPS Parsing**: Automatically extracts latitude, longitude, and altitude from image EXIF metadata.
- **Cluster Markers**: Intelligently groups nearby photos into zoom-dependent clusters with thumbnail previews.
- **Direct Navigation**: Clicking any marker opens the asset directly in the Lightbox.

### Share Links & Guest Access

- **Public & Protected Links**: Create shareable links for individual albums with optional password protection and expiration dates.
- **Guest Experience**: Clean, unauthenticated responsive viewer allowing recipients to view photos, play videos, and download original files without creating an account.

### Drag-and-Drop Chronological Reordering

- **Visual Reorder Handle**: Located on the top-right corner of photo cards.
- **Dynamic Insertion Bar**: Shows an animated green indicator line (`#4CAF50`, 4px wide) on the exact drop boundary.
- **Timestamp Cascade Math**: Dropping a photo between neighbors $P$ and $Q$ calculates an exact intermediate timestamp, persisting chronological order without altering unrelated dates.

### Android Mobile App & Background Sync

- **Material 3 Native Design**: Complete support for dynamic colors, dark theme, and edge-to-edge layouts.
- **SAF Directory Pairing**: Grants iphoto access strictly to chosen folders (e.g. `DCIM/Camera`) using Android's Storage Access Framework.
- **Delta Sync Algorithm**:
  1. Scans local storage and computes SHA-256 hashes.
  2. Queries the server for missing hashes.
  3. Uploads new assets in chunks via background `SyncWorker`.
- **System Media Provider Integration**: Sync operations cleanly handle files moved to the Android system trash without throwing security exceptions.

### Remote Photo Editing with External Apps

- **External App Integration**: In the Android app lightbox, select **Edit with external app** to open any cloud-hosted photo in tools like Snapseed, Google Photos, or Adobe Lightroom.
- **Atomic Cloud Update**: Once editing is complete in the third-party application, the modified asset is uploaded back to the iphoto server with an option to either overwrite the original or save as a new copy.

### Immich Migration Tool

- **Direct Server-to-Server Migration**: Enter your Immich server URL and API key in the admin settings to import your existing library, assets, albums, and metadata directly into iphoto.

---

## Quick Start & Deployment

### Prerequisites
- Docker Engine 24.0+ and Docker Compose v2.20+
- At least 4GB of RAM allocated to Docker
- Ports `8080` (Web UI/API) open on the host

### 1. Clone and Configure Environment

```bash
git clone https://github.com/georgeslb/iphoto.git
cd iphoto

# Create production environment file
cp .env.example .env
```

Edit `.env` and configure your credentials:

```ini
# PostgreSQL Database Password
POSTGRES_PASSWORD=your_secure_postgres_password

# Admin account password (username is "admin")
IPHOTO_ADMIN_PASSWORD=your_secure_admin_password

# Security key for persistent login cookies (generate with `openssl rand -hex 32`)
IPHOTO_REMEMBER_ME_KEY=e834b9cf8d123...

# Published host port
IPHOTO_PORT=8080
```

### 2. Start the Stack

```bash
# Pull images and launch containers in the background
docker compose up -d
```

Once started:
- Access the Web UI at: `http://localhost:8080` (or `http://your-server-ip:8080`)
- Sign in with username: `admin` and the password configured in `IPHOTO_ADMIN_PASSWORD`.

### 3. Configure NAS Backup (Optional)

If you have a Synology or TrueNAS server on your local network:
1. Generate an SSH keypair:
   ```bash
   ssh-keygen -t ed25519 -N "" -f nas-backup/secrets/id_ed25519
   ```
2. Copy `nas-backup/secrets/id_ed25519.pub` to the backup user's `~/.ssh/authorized_keys` on the NAS.
3. Configure `NAS_HOST`, `NAS_USER`, and `NAS_ASSETS_PATH` in `.env`.
4. Launch the backup container:
   ```bash
   docker compose up -d iphoto-nas-backup
   ```

### 4. Android Client Setup

1. Open the `android/` project directory in **Android Studio Hedgehog (2023.1.1)** or newer.
2. Build and install the debug or release APK on your device:
   ```bash
   cd android
   ./gradlew installDebug
   ```
3. Open the app, enter your server URL (`http://your-server-ip:8080`), log in with your credentials, and select your local camera directory in the **Sync** tab to initiate automatic background backup.

---

## CI/CD & Automated Releases

The repository utilizes an automated GitHub Actions pipeline (`.github/workflows/docker-image.yml`):

- **Smart Path Filtering**: Pushes or pull requests that modify only documentation (`*.md`), Android source code (`android/**`), or backup scripts (`nas-backup/**`) automatically bypass backend and Docker build runs.
- **Automated Testing**: Every code push runs both Java 25 integration tests (PostgreSQL via Testcontainers) and the full React Vitest suite (220+ tests).
- **Automated Versioned Releases on Merge**: Merging any PR into `master` automatically builds the production container, publishes it to the GitHub Container Registry (`ghcr.io/georgeslb/iphoto`), and creates a versioned GitHub Release and Git Tag (`v1.0.<run_number>`) with auto-generated release notes.
- **Reproducible Tag Builds**: Pushing a manual git tag (`git tag v1.0.1 && git push origin v1.0.1`) triggers a dedicated build that pins and publishes the Docker image to that exact tag.

---

## Development & Testing

### Backend (Java / Spring Boot)
- **Requirements**: JDK 25, Maven 3.9+, Docker (for Testcontainers).
- Run test suite:
  ```bash
  mvn clean test
  ```
- Run locally with Spring Boot:
  ```bash
  mvn spring-boot:run
  ```

### Web Frontend (React / Vite)
- **Requirements**: Node.js 22 LTS, npm 10+.
- Navigate to the frontend directory:
  ```bash
  cd src/main/resources/static
  npm install
  ```
- Run tests with Vitest:
  ```bash
  npm test
  ```
- Run Vite local development server:
  ```bash
  npm run dev
  ```

### Android Client
- **Requirements**: Android Studio, Android SDK 34+, JDK 17 or 21.
- Run unit tests:
  ```bash
  cd android
  ./gradlew testDebugUnitTest
  ```

---

## License

This project is licensed under the [MIT License](LICENSE).
Original media files and database dumps remain the exclusive property of the deployment administrator.
