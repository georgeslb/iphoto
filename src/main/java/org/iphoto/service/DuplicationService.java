package org.iphoto.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_features2d.BFMatcher;
import org.bytedeco.opencv.opencv_features2d.ORB;
import org.bytedeco.opencv.opencv_img_hash.AverageHash;
import org.bytedeco.opencv.opencv_img_hash.PHash;
import org.iphoto.entities.*;
import org.iphoto.exception.ServiceNotAvailableException;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.DuplicateRepository;
import org.iphoto.repository.PhotoDuplicateRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.bytedeco.opencv.global.opencv_calib3d.RANSAC;
import static org.bytedeco.opencv.global.opencv_calib3d.findHomography;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.iphoto.model.DuplicateStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DuplicationService {

    private PhotoRepository photoRepository;
    private DuplicateRepository duplicateRepository;
    private PhotoDuplicateRepository photoDuplicateRepository;
    private DuplicationUniquenessService duplicateUniquenessService;
    private TaskMapper taskMapper;
    private ReentrantLock checkDuplicationLock = new ReentrantLock();

    private static final int MAX_SIZE = 1000;

    // Global hash distances (out of 64 bits) tight enough to confirm a duplicate on their own.
    private static final int STRONG_PHASH_MAX = 12;
    private static final int STRONG_MHASH_MAX = 10;
    // Looser band: hashes alone are inconclusive (could be a crop/edit/recompression),
    // so fall back to local feature matching instead of a whole-image hash comparison.
    private static final int CANDIDATE_PHASH_MAX = 24;
    private static final int CANDIDATE_MHASH_MAX = 20;

    private static final int ORB_MAX_FEATURES = 500;
    // ORB descriptors are 256-bit; Hamming distance below this is considered a "good" match.
    private static final int ORB_MAX_MATCH_DISTANCE = 64;
    private static final int ORB_MIN_GOOD_MATCHES = 15;
    // Minimum number of good matches that must also agree on a single geometric transform
    // (via RANSAC homography), which filters out coincidental matches on repetitive textures.
    private static final int ORB_MIN_INLIERS = 10;
    // RANSAC's job is to find the LARGEST coincidental consensus set, so on two unrelated photos
    // with enough "good" matches (e.g. both containing grass, wood grain, or other repetitive
    // texture), it will often still find 10+ points that happen to agree on some transform even
    // though the images are unrelated. An absolute inlier count alone can't tell that apart from a
    // genuine match, so also require a minimum fraction of the good matches to be inliers — a real
    // duplicate/crop/rotation typically has most of its good matches agree on one transform, while
    // a coincidental match on repeated texture produces a small, low-ratio consensus.
    private static final double ORB_MIN_INLIER_RATIO = 0.5;
    // Bytes per ORB descriptor row, fixed by ORB.create(..., WTA_K=2, ...) below (256-bit descriptor).
    private static final int ORB_DESCRIPTOR_COLS = 32;


    public void deleteDuplicatesForPhoto(PhotoEntity photo) {
        photoDuplicateRepository.deleteByPhotoIdIn(Collections.singletonList(photo.getId()));
    }

    public void processMovingToGarbage(Set<PhotoEntity> photosMovedToGarbage) {
        List<UUID> photoIds = photosMovedToGarbage.stream().map(PhotoEntity::getId).collect(Collectors.toList());
        List<PhotoDuplicateEntity> photoDuplicateEntities = photoDuplicateRepository.findByPhotoIdIn(photoIds);
        // in case we moved a photo to garbage, we don't remove photo from its duplication group
        // in case only one photo stands in duplication group, remove duplication group
        photoDuplicateEntities.forEach(pd -> {
            if (pd.getStatus() == IN_GROUP) {
                pd.setStatus(MOVED_TO_GARBAGE);
            } else if (pd.getStatus() == UNLINKED) {
                pd.setStatus(UNLINKED_BUT_MOVED_TO_GARBAGE);
            }
        });
        photoDuplicateRepository.saveAll(photoDuplicateEntities);
    }

    public void restoreFromGarbage(List<PhotoEntity> photosRestored) {
        List<UUID> photoIds = photosRestored.stream().map(PhotoEntity::getId).collect(Collectors.toList());
        List<PhotoDuplicateEntity> photoDuplicateEntities = photoDuplicateRepository.findByPhotoIdIn(photoIds);
        // in case we moved a photo to garbage, we don't remove photo from its duplication group
        // in case only one photo stands in duplication group, remove duplication group
        photoDuplicateEntities.forEach(pd -> {
            if (pd.getStatus() == MOVED_TO_GARBAGE) {
                pd.setStatus(IN_GROUP);
            } else if (pd.getStatus() == UNLINKED_BUT_MOVED_TO_GARBAGE) {
                pd.setStatus(UNLINKED);
            }
        });
        photoDuplicateRepository.saveAll(photoDuplicateEntities);
    }

    public void hashImage(TaskEntity task) {
        photoRepository
                .findById(task.getPhotoId()).ifPresent(photo -> { // Filter by user
                    try {
                        if (photo.getContentType().contains("image")) {
                            log.info("creating Perceptive Hash for photoId: {}", photo.getId());
                            preprocess(photo);
                            photoRepository.save(photo);
                            if (photo.getSpace().getType() == SpaceType.MAIN) {
                                taskMapper.updateTasksToAction(TaskMapper.Action.AFTER_PHOTO_HASH, photo.getId());
                            }
                        }
                    } catch (Exception e) {
                        log.warn(e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                });
    }

    // ---------------------------------------------------
    // PREPROCESS
    // ---------------------------------------------------

    private void preprocess(PhotoEntity photo) {

        String imagePath = photo.getOriginalPath();

        Mat image = imread(imagePath);

        if (image.empty()) {
            return;
        }

        // 1. auto rotate from metadata
        Mat rotatedImage = autoRotate(imagePath, image);
        if (rotatedImage != image) {
            image.release();
            image = rotatedImage;
        }

        // 2. resize huge images
        Mat resizedImage = resizeIfNeeded(image);
        if (resizedImage != image) {
            image.release();
            image = resizedImage;
        }

        try (PHash pHash = PHash.create()) {
            Mat hash = new Mat();

            pHash.compute(image, hash);

            byte[] hashBytes = new byte[(int) hash.total()];

            hash.data().get(hashBytes);

            photo.setPhash(hashBytes);

            hash.release();
        }

        try (AverageHash averageHash = AverageHash.create()) {
            Mat hash = new Mat();

            averageHash.compute(image, hash);

            byte[] hashBytes = new byte[(int) hash.total()];

            hash.data().get(hashBytes);

            photo.setMhash(hashBytes);

            hash.release();
        }

        computeAndStoreOrbFeatures(photo, image);

        rotatedImage.release();
        resizedImage.release();
        image.release();
    }

    // ---------------------------------------------------
    // ORB FEATURE EXTRACTION (computed once at upload time, persisted alongside phash/mhash)
    // ---------------------------------------------------

    private void computeAndStoreOrbFeatures(PhotoEntity photo, Mat image) {
        Mat gray = new Mat();
        cvtColor(image, gray, COLOR_BGR2GRAY);
        try (ORB orb = ORB.create(ORB_MAX_FEATURES, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20)) {
            KeyPointVector keypoints = new KeyPointVector();
            Mat descriptors = new Mat();
            orb.detectAndCompute(gray, new Mat(), keypoints, descriptors);

            if (!descriptors.empty()) {
                photo.setOrbDescriptors(matToBytes(descriptors));
                photo.setOrbKeypoints(keypointsToBytes(keypoints));
            }
            descriptors.release();
        } finally {
            gray.release();
        }
    }

    private byte[] matToBytes(Mat mat) {
        byte[] bytes = new byte[(int) (mat.total() * mat.elemSize())];
        mat.data().get(bytes);
        return bytes;
    }

    private byte[] keypointsToBytes(KeyPointVector keypoints) {
        int n = (int) keypoints.size();
        ByteBuffer buffer = ByteBuffer.allocate(n * 8);
        for (int i = 0; i < n; i++) {
            Point2f pt = keypoints.get(i).pt();
            buffer.putFloat(pt.x());
            buffer.putFloat(pt.y());
            pt.close();
        }
        return buffer.array();
    }

    // ---------------------------------------------------
    // AUTO ROTATE
    // ---------------------------------------------------

    private Mat autoRotate(
            String imagePath,
            Mat image) {

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(imagePath)) {
            grabber.start();
            String rotateMeta =
                    grabber.getVideoMetadata("rotate");
            if (rotateMeta == null ||
                    rotateMeta.isBlank()) {
                rotateMeta =
                        grabber.getMetadata("rotate");
            }

            if (rotateMeta == null) {
                return image;
            }

            int angle =
                    Integer.parseInt(rotateMeta);

            // IMPORTANT:
            // reverse direction to match players

            switch (angle) {

                case 90:
                    Mat rotated90 = new Mat();
                    rotate(
                            image,
                            rotated90,
                            ROTATE_90_COUNTERCLOCKWISE
                    );
                    return rotated90;

                case 180:
                    Mat rotated180 = new Mat();
                    rotate(
                            image,
                            rotated180,
                            ROTATE_180
                    );
                    return rotated180;

                case 270:
                    Mat rotated270 = new Mat();
                    rotate(
                            image,
                            rotated270,
                            ROTATE_90_CLOCKWISE
                    );
                    return rotated270;

                default:
                    return image;
            }
        } catch (Exception ignored) {
            return image;
        }
    }

    // ---------------------------------------------------
    // RESIZE LARGE IMAGES
    // ---------------------------------------------------

    private Mat resizeIfNeeded(Mat image) {

        int width = image.cols();
        int height = image.rows();

        int maxDimension =
                Math.max(width, height);

        if (maxDimension <= MAX_SIZE) {
            return image;
        }

        double scale =
                (double) MAX_SIZE / maxDimension;

        int newWidth =
                (int) (width * scale);

        int newHeight =
                (int) (height * scale);

        Mat resized = new Mat();

        resize(
                image,
                resized,
                new Size(newWidth, newHeight)
        );


        return resized;
    }

    public void checkDuplication(TaskEntity task) {
        if (!checkDuplicationLock.tryLock()) {
            throw new ServiceNotAvailableException("Duplication is busy with another task");
        }
        try {
            checkDuplicationLock.lock();
            if (photoAlreadyDuplicated(task.getPhotoId())) {
                task.setStatus(TaskStatus.COMPLETED);
                return;
            }

            photoRepository
                    .findById(task.getPhotoId()).ifPresent(photo -> {

                        Pageable pageable = PageRequest.of(0, 100);
                        Slice<PhotoEntity> photosSlice;
                        Set<PhotoEntity> duplicatesPhotos = new HashSet<>();
                        log.info("checkDuplication photo={} spaceId={} dateTaken={}: scanning candidates in [{}, {}]",
                                photo.getId(), photo.getSpace().getId(), photo.getDateTaken(),
                                photo.getDateTaken().minusHours(2), photo.getDateTaken().plusHours(2));
                        do {
                            photosSlice =
                                    photoRepository.findBySpaceIdAndDateTakenBetweenAndPhashNotNullAndMhashNotNullAndIsGarbageFalse(photo.getSpace().getId(), photo.getDateTaken().minusHours(2),
                                            photo.getDateTaken().plusHours(2), pageable);
                            log.info("checkDuplication photo={}: candidate page with {} photo(s)", photo.getId(), photosSlice.getContent().size());
                            List<PhotoEntity> duplicatePhotosForIteration = compare(photo, photosSlice.getContent());
                            if (!duplicatePhotosForIteration.isEmpty()) {
                                duplicatesPhotos.addAll(duplicatePhotosForIteration);
                            }
                            pageable = photosSlice.nextPageable();

                        } while (photosSlice.hasNext());

                        log.info("checkDuplication photo={}: found {} duplicate(s): {}",
                                photo.getId(), duplicatesPhotos.size(),
                                duplicatesPhotos.stream().map(PhotoEntity::getId).toList());

                        if (!duplicatesPhotos.isEmpty()) {
                            try {
                                if (!duplicatesPhotos.contains(photo)) {
                                    TaskStatus taskStatus = duplicateUniquenessService.consolidateDuplicates(photo, duplicatesPhotos);
                                    if (taskStatus == TaskStatus.COMPLETED || taskStatus == TaskStatus.FAILED) {
                                        task.setStatus(taskStatus);
                                    } else {
                                        task.setStatus(TaskStatus.PENDING);
                                    }
                                } else {
                                    // photo already exists in group no need to add it again
                                    task.setStatus(TaskStatus.COMPLETED);
                                }

                            } catch (Exception e) {
                                task.setStatus(TaskStatus.PENDING);
                                task.setExecuteAt(LocalDateTime.now().plusMinutes(1));
                            }
                            return;
                        }
                        task.setStatus(TaskStatus.COMPLETED);
                    });
        } finally {
            checkDuplicationLock.unlock();
        }
    }

    private boolean photoAlreadyDuplicated(UUID photoId) {
        return !photoDuplicateRepository.findByPhotoIdIn(List.of(photoId)).isEmpty();
    }


    private List<PhotoEntity> compare(PhotoEntity photo, List<PhotoEntity> photos) {
        return photos.stream()
                .filter(targetPhoto -> targetPhoto.getContentType().contains("image"))
                .filter(targetPhoto -> !targetPhoto.getId().equals(photo.getId()))
                .map(targetPhoto -> evaluatePair(photo, targetPhoto))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Optional<PhotoEntity> evaluatePair(PhotoEntity photo, PhotoEntity targetPhoto) {
        int pHashDistance = calculateHammingDistance(photo.getPhash(), targetPhoto.getPhash());
        int averageHashDistance = calculateHammingDistance(photo.getMhash(), targetPhoto.getMhash());
        log.debug("evaluatePair photo={} target={}: pHashDistance={} (strong<={}, candidate<={}), mHashDistance={} (strong<={}, candidate<={})",
                photo.getId(), targetPhoto.getId(), pHashDistance, STRONG_PHASH_MAX, CANDIDATE_PHASH_MAX,
                averageHashDistance, STRONG_MHASH_MAX, CANDIDATE_MHASH_MAX);

        if (pHashDistance <= STRONG_PHASH_MAX && averageHashDistance <= STRONG_MHASH_MAX) {
            log.debug("DUPLICATE via strong hash match: photo={} target={} pHashDistance={} mHashDistance={}",
                    photo.getId(), targetPhoto.getId(), pHashDistance, averageHashDistance);
            return Optional.of(targetPhoto);
        }

        boolean plausibleByHash = pHashDistance <= CANDIDATE_PHASH_MAX || averageHashDistance <= CANDIDATE_MHASH_MAX;
        if (!plausibleByHash) {
            log.debug("NOT duplicate: photo={} target={} outside candidate hash band, skipping feature matching",
                    photo.getId(), targetPhoto.getId());
            return Optional.empty();
        }

        log.debug("candidate hash band: photo={} target={} falling back to ORB feature matching", photo.getId(), targetPhoto.getId());
        if (confirmWithFeatureMatching(photo, targetPhoto)) {
            log.debug("DUPLICATE via feature matching: photo={} target={}", photo.getId(), targetPhoto.getId());
            return Optional.of(targetPhoto);
        }
        log.debug("NOT duplicate: photo={} target={} feature matching did not confirm", photo.getId(), targetPhoto.getId());
        return Optional.empty();
    }

    // ---------------------------------------------------
    // FEATURE-BASED CONFIRMATION (ORB + RANSAC homography)
    // ---------------------------------------------------
    // Global hashes (pHash/averageHash) summarize the whole image as one signature, so they
    // miss crops, rotations beyond what EXIF corrects, and localized edits entirely. ORB
    // matches local keypoints instead, and the homography check confirms the matches agree on
    // a single consistent geometric transform (protects against coincidental matches on
    // repetitive textures like foliage or brick walls).

    private boolean confirmWithFeatureMatching(PhotoEntity photo, PhotoEntity targetPhoto) {
        byte[] descriptorBytes1 = photo.getOrbDescriptors();
        byte[] descriptorBytes2 = targetPhoto.getOrbDescriptors();
        if (descriptorBytes1 == null || descriptorBytes1.length == 0
                || descriptorBytes2 == null || descriptorBytes2.length == 0) {
            log.debug("feature matching photo={} target={}: no stored ORB descriptors (not yet hashed?)",
                    photo.getId(), targetPhoto.getId());
            return false;
        }

        Mat descriptors1 = null;
        Mat descriptors2 = null;
        try (BFMatcher matcher = BFMatcher.create(NORM_HAMMING, true)) {
            descriptors1 = bytesToDescriptorMat(descriptorBytes1);
            descriptors2 = bytesToDescriptorMat(descriptorBytes2);

            DMatchVector matches = new DMatchVector();
            matcher.match(descriptors1, descriptors2, matches);

            List<DMatch> goodMatches = new ArrayList<>();
            for (long i = 0; i < matches.size(); i++) {
                DMatch match = matches.get(i);
                if (match.distance() <= ORB_MAX_MATCH_DISTANCE) {
                    goodMatches.add(match);
                }
            }

            log.debug("feature matching photo={} target={}: keypoints1={} keypoints2={} totalMatches={} goodMatches={} (min required {})",
                    photo.getId(), targetPhoto.getId(), descriptors1.rows(), descriptors2.rows(), matches.size(), goodMatches.size(), ORB_MIN_GOOD_MATCHES);

            if (goodMatches.size() < ORB_MIN_GOOD_MATCHES) {
                return false;
            }

            float[] points1 = bytesToPoints(photo.getOrbKeypoints());
            float[] points2 = bytesToPoints(targetPhoto.getOrbKeypoints());
            int inliers = countHomographyInliers(goodMatches, points1, points2);
            double inlierRatio = (double) inliers / goodMatches.size();
            log.debug("feature matching photo={} target={}: homography inliers={}/{} ratio={} (min count {}, min ratio {})",
                    photo.getId(), targetPhoto.getId(), inliers, goodMatches.size(), inlierRatio, ORB_MIN_INLIERS, ORB_MIN_INLIER_RATIO);
            return inliers >= ORB_MIN_INLIERS && inlierRatio >= ORB_MIN_INLIER_RATIO;
        } catch (Exception e) {
            log.warn("feature matching failed between photo {} and {}: {}", photo.getId(), targetPhoto.getId(), e.getMessage());
            return false;
        } finally {
            if (descriptors1 != null) {
                descriptors1.release();
            }
            if (descriptors2 != null) {
                descriptors2.release();
            }
        }
    }

    private Mat bytesToDescriptorMat(byte[] descriptorBytes) {
        int rows = descriptorBytes.length / ORB_DESCRIPTOR_COLS;
        Mat mat = new Mat(rows, ORB_DESCRIPTOR_COLS, CV_8UC1);
        mat.data().put(descriptorBytes);
        return mat;
    }

    private float[] bytesToPoints(byte[] keypointBytes) {
        if (keypointBytes == null) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(keypointBytes);
        float[] points = new float[keypointBytes.length / 4];
        for (int i = 0; i < points.length; i++) {
            points[i] = buffer.getFloat();
        }
        return points;
    }

    private int countHomographyInliers(List<DMatch> goodMatches, float[] points1, float[] points2) {
        int n = goodMatches.size();
        Mat srcPoints = new Mat(n, 1, CV_32FC2);
        Mat dstPoints = new Mat(n, 1, CV_32FC2);
        Mat mask = new Mat();
        Mat homography = null;
        try (FloatIndexer srcIdx = srcPoints.createIndexer(); FloatIndexer dstIdx = dstPoints.createIndexer()) {
            for (int i = 0; i < n; i++) {
                DMatch match = goodMatches.get(i);
                int queryIdx = match.queryIdx();
                int trainIdx = match.trainIdx();
                srcIdx.put(i, 0, 0, points1[queryIdx * 2]);
                srcIdx.put(i, 0, 1, points1[queryIdx * 2 + 1]);
                dstIdx.put(i, 0, 0, points2[trainIdx * 2]);
                dstIdx.put(i, 0, 1, points2[trainIdx * 2 + 1]);
            }
            homography = findHomography(srcPoints, dstPoints, mask, RANSAC, 5.0);
            if (homography.empty()) {
                return 0;
            }
            return countNonZero(mask);
        } finally {
            srcPoints.release();
            dstPoints.release();
            mask.release();
            if (homography != null) {
                homography.release();
            }
        }
    }
    /**
     * Calculates the Hamming Distance between two 8-byte (64-bit) pHashes.
     *
     * @return 0 if identical, higher numbers mean less similar.
     */
    private static int calculateHammingDistance(byte[] hash1, byte[] hash2) {
        if (hash1 == null || hash2 == null || hash1.length != 8 || hash2.length != 8) {
            throw new IllegalArgumentException("Hashes must be non-null and exactly 8 bytes (64 bits) long.");
        }

        // Convert the 8-byte arrays into a 64-bit Java 'long'
        long long1 = ByteBuffer.wrap(hash1).getLong();
        long long2 = ByteBuffer.wrap(hash2).getLong();

        // XOR sets bits to 1 only where the two numbers differ
        long xorResult = long1 ^ long2;

        // Long.bitCount uses an optimized hardware instruction (popcount) to count the 1s
        return Long.bitCount(xorResult);
    }

    public Set<PhotoEntity> getDuplicates(PhotoEntity photo) {
        return photoDuplicateRepository.findByPhotoIdIn(List.of(photo.getId()))
                .stream()
                .filter(pd -> pd.getStatus() == IN_GROUP)
                .map(PhotoDuplicateEntity::getDuplicate)
                .map(DuplicateEntity::getPhotoDuplicates)
                .flatMap(Collection::stream)
                .filter(pd -> pd.getStatus() == IN_GROUP)
                .map(PhotoDuplicateEntity::getPhoto)
                .filter(photoEntity -> !photoEntity.getId().equals(photo.getId()))
                .filter(p -> !p.getIsGarbage())
                .collect(Collectors.toSet());

    }

    @Transactional
    public void unlinkPhotoFromGroup(UUID photoId, Set<UUID> photosId) {
        duplicateRepository.findByPhotoDuplicatesPhotoIdIn(List.of(photoId)).forEach(duplicate -> {
            Set<UUID> includedPhotoInDuplicateGroup = duplicate.getPhotoDuplicates().stream().filter(pd -> pd.getStatus() == IN_GROUP)
                    .map(PhotoDuplicateEntity::getPhoto).map(PhotoEntity::getId).collect(Collectors.toSet());
            if (includedPhotoInDuplicateGroup.containsAll(photosId)) {
                duplicate.getPhotoDuplicates().stream().filter(pd -> pd.getPhoto().getId().equals(photoId))
                        .findAny().ifPresent(pd -> pd.setStatus(UNLINKED));
            }
        });
    }
}