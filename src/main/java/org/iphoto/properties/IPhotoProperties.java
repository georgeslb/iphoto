package org.iphoto.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "iphoto")
@Data
public class IPhotoProperties {
    Storage storage;
    Memories memories = new Memories();
    HeavyTasks heavyTasks = new HeavyTasks();

    @Data
    public static class Storage {
        String immichTemp;
        String photos;
        String thumbnails;
        String faceThumbnails;
        String temp;
        String chunks;
    }

    // Library's "N months/years ago" bar (see PhotoService.getMemories) - one candidate window per
    // configured offset, searched today minus that many months. Configurable here rather than
    // hardcoded so the set of offsets can change without a code change.
    @Data
    public static class Memories {
        // Each entry is a plain integer ("6" - exact day, no tolerance) or that integer plus one
        // or both signed day-tolerance tokens in either order ("6-2d+3d" == "6+3d-2d"): "-Nd"
        // widens the search N days earlier than the anchor day, "+Nd" widens it N days later -
        // see MemoryOffset#parse for the exact grammar. Useful for an anchor day that rarely has a
        // photo taken on it exactly, without needing an exact hit every time.
        List<String> offsets = List.of("1", "6", "12", "36", "60", "120");
        // Up to this many random photos per offset, auto-cycled in the frontend's one block
        // instead of a single static photo - fewer than this if the window has fewer photos.
        int photosPerBlock = 5;
    }

    // Immich connection info moved to a per-user setting (see UserEntity.immichBaseUrl/
    // immichApiKeyEncrypted, UserService#resolveImmichCredentials) - no more global
    // env-var-configured server here.

    // Each heavy task type (CHECK_DUPLICATION/ISOLATE_FACES/FACE_RECOGNITION) gets its own
    // dedicated, fixed-size thread pool (see ThreadPoolConfiguration/TaskWorkerManager) instead of
    // all three sharing one pool whose size was dynamically resized based on light-task load. That
    // combination used to fight itself: each service also held its own internal lock capping ITS
    // task type to 1 concurrent run regardless of how many threads the shared pool actually gave
    // it, so a face-detection burst never got the parallelism the pool size implied, and (a real
    // bug found while fixing this) DuplicationService's lock was acquired twice but released only
    // once per call, permanently jamming CHECK_DUPLICATION after its very first run. Splitting into
    // per-type pools sized exactly to each service's real concurrency ceiling and deleting the
    // internal locks fixes both at once - the pool itself is now the only concurrency limiter.
    @Data
    public static class HeavyTasks {
        // CHECK_DUPLICATION: kept at 1 - DuplicationService's hash-based consolidation reads and
        // writes DuplicateEntity rows across the candidate set non-atomically; two photos' checks
        // running at once could race on the same duplicate group.
        int duplicationThreads = 1;
        // FACE_RECOGNITION: kept at 1 - groupFaces() reprocesses/regroups ALL users' ungrouped
        // faces in one run; two overlapping runs would race on the same PersonEntity clusters.
        int faceRecognitionThreads = 1;
        // HASH_IMAGE (perceptual-hashing prep for duplicate detection) and ISOLATE_FACES (face
        // detection) share ONE dedicated "middle" pool (TaskWorkerManager#pollMiddleTasks,
        // ThreadPoolConfiguration#middleThreadPoolExecutor) - unlike duplicationThreads/
        // faceRecognitionThreads above, this isn't gated by the admin heavy-tasks toggle and polls
        // on the same always-on cadence as the light pool, since both task types are routine
        // per-upload processing (needed promptly after every upload), not an optional maintenance
        // pass. They used to run ON the light pool itself, but each call takes ~20s of real
        // CPU/native-model work (OpenCV hashing, ONNX Runtime face detection - see
        // FaceDetectionConfiguration), so a burst of uploads could stack up dozens of these
        // concurrently on the light pool's much higher virtual-thread concurrency ceiling and
        // starve the CPU; this dedicated, fixed-size pool caps that regardless of upload burst
        // size. ISOLATE_FACES's own DJL/ONNX Predictor is safe to call concurrently (each
        // invocation opens its own Predictor - FaceDetectionService#detectAndSaveFaces - never a
        // shared instance), so sharing this one pool with HASH_IMAGE is fine.
        int middleTaskThreads = 5;
        // Shared across every dedicated pool above (including the middle one) - how many PENDING
        // tasks of a given type/pool can be queued waiting for a free thread before its poll method
        // stops fetching more.
        int queueCapacity = 10;
    }
}
