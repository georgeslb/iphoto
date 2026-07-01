package org.iphoto.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_img_hash.AverageHash;
import org.bytedeco.opencv.opencv_img_hash.PHash;
import org.iphoto.entities.DuplicateEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.DuplicateGroupDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.DuplicateRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;
import static org.iphoto.model.DuplicateStatus.CLOSED;
import static org.iphoto.model.DuplicateStatus.OPEN;

@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicationService {

    private final PhotoRepository photoRepository;
    private final TaskManagerService taskManagerService;
    private final ObjectMapper objectMapper;
    private final DuplicateRepository duplicateRepository;
    private final PhotoMapper photoMapper;
    private final DuplicationUniquenessService duplicateUniquenessService;
    private static final int MAX_SIZE = 1000;


    public void processMovingToGarbage(Set<PhotoEntity> photosDeleted) { // Added UserEntity parameter
        List<UUID> photoIds = photosDeleted.stream().map(PhotoEntity::getId).collect(Collectors.toList());
        List<DuplicateEntity> duplicateEntities = duplicateRepository.findByPhotosIdIn(photoIds); // Filter by user
        duplicateEntities.forEach(duplicateEntity -> {
            if (duplicateEntity.getStatus() == OPEN && duplicateEntity.getPhotos()
                    .stream().filter(photoEntity -> !photoEntity.getIsGarbage()).count() < 2) {
                duplicateEntity.setStatus(CLOSED);
            }
        });
    }

    public void restoreFromGarbage(List<PhotoEntity> photosRestored) { // Added UserEntity parameter
        List<UUID> photoIds = photosRestored.stream().map(PhotoEntity::getId).collect(Collectors.toList());
        List<DuplicateEntity> duplicateEntities = duplicateRepository.findByPhotosIdIn(photoIds); // Filter by user
        duplicateEntities.forEach(duplicateEntity -> {
            if (duplicateEntity.getStatus() == CLOSED && duplicateEntity.getPhotos()
                    .stream().filter(photoEntity -> !photoEntity.getIsGarbage()).count() > 1) {
                duplicateEntity.setStatus(OPEN);
            }
        });
    }

    public void hashImage(TaskEntity task) { // Added UserEntity parameter
        photoRepository
                .findById(task.getPhotoId()).ifPresent(photo -> { // Filter by user
                    try {
                        if (photo.getContentType().contains("image")) {
                            log.info("creating Perceptive Hash for photoId: {}", photo.getId());
                            preprocess(photo);
                            photoRepository.save(photo);
                            taskManagerService.createTaskEntity(TaskType.CHECK_DUPLICATION, photo.getId(), null, LocalDateTime.now().plusMinutes(1));
                        }
                    } catch (Exception e) {
                        log.warn(e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                });
    }

    public void closeDuplicates (UserEntity user, String hash) { // Added UserEntity parameter
        duplicateRepository.findByUserAndHash(user, hash).ifPresent(duplicate -> { // Filter by user
            duplicate.setStatus(CLOSED);
            duplicateRepository.save(duplicate);
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

        try (AverageHash  averageHash = AverageHash.create()) {
            Mat hash = new Mat();

            averageHash.compute(image, hash);

            byte[] hashBytes = new byte[(int) hash.total()];

            hash.data().get(hashBytes);

            photo.setMhash(hashBytes);

            hash.release();
        }
        rotatedImage.release();
        resizedImage.release();
        image.release();
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

    public void checkDuplication(TaskEntity task) { // Added UserEntity parameter
        photoRepository
                .findById(task.getPhotoId()).ifPresent(photo -> { // Filter by user
                    Pageable pageable = PageRequest.of(0, 100);
                    Slice<PhotoEntity> photosSlice;
                    Set<PhotoEntity> duplicatesPhotos = new HashSet<>();
                    do {
                        photosSlice =
                                photoRepository.findByDateTakenBetweenAndPhashNotNullAndMhashNotNullAndIsGarbageFalse(photo.getDateTaken().minusDays(1),
                                        photo.getDateTaken().plusDays(1), pageable); // Filter by user

                        List<PhotoEntity> duplicatePhotosForIteration = compare(photo, photosSlice.getContent());
                        if (!duplicatePhotosForIteration.isEmpty()) {
                            duplicatesPhotos.addAll(duplicatePhotosForIteration);
                        }
                        pageable = photosSlice.nextPageable();

                    } while (photosSlice.hasNext());

                    if (!duplicatesPhotos.isEmpty()) {
                        try {
                            TaskStatus taskStatus = duplicateUniquenessService.consolidateDuplicates(photo, duplicatesPhotos); // Pass user
                            if (taskStatus == TaskStatus.COMPLETED || taskStatus == TaskStatus.FAILED) {
                                task.setStatus(taskStatus);
                            } else {
                                task.setStatus(TaskStatus.PENDING);
                            }
                        } catch (Exception e) {
                            task.setStatus(TaskStatus.PENDING);
                            task.setExecuteAt(LocalDateTime.now().plusMinutes(1));
                        }
                        return;
                    }
                    task.setStatus(TaskStatus.COMPLETED);
                });
    }


    private List<PhotoEntity> compare(PhotoEntity photo, List<PhotoEntity> photos) {
        return photos.stream()
                .filter(targetPhoto -> targetPhoto.getContentType().contains("image"))
                .filter(targetPhoto -> !targetPhoto.getId().equals(photo.getId()))
                .map(targetPhoto -> {
                    log.info("testing similarity between photo with id: {} and photo with id: {}", photo.getId(), targetPhoto.getId());
                    int pHashDistance = calculateHammingDistance(photo.getPhash(), targetPhoto.getPhash());
                    int averageHashDistance = calculateHammingDistance(photo.getMhash(), targetPhoto.getMhash());
                    if (pHashDistance <= 12 && averageHashDistance <=10) {
                        log.info("find similarity between photo with id: {} and photo with id: {} and score {}/{} ", photo.getId(), targetPhoto.getId(), pHashDistance,  averageHashDistance);
                        return targetPhoto;
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public Slice<DuplicateGroupDTO> getDuplicates(UserEntity user, Pageable pageable) { // Added UserEntity parameter
        // Pageable is equals to 1, we will see only one DuplicateEntity
        Slice<DuplicateEntity> hashesSlice = duplicateRepository.findByUserAndStatus(user, OPEN, pageable); // Filter by user
        List<DuplicateEntity> duplicateEntities = hashesSlice.getContent();

        if (duplicateEntities.isEmpty()) {
            return new SliceImpl<>(Collections.emptyList(), pageable, hashesSlice.hasNext());
        }
        List<Photo> photos = duplicateEntities.getFirst().getPhotos().stream()
                .filter(photoEntity -> !photoEntity.getIsGarbage())
                .map(photoMapper::photoEntityToPhoto).toList();

        List<DuplicateGroupDTO> result = Collections.emptyList();
        if (photos.size() > 1) {
            result = duplicateEntities.stream()
                    .map(hash -> new DuplicateGroupDTO(duplicateEntities.getFirst().getId().toString(), photos))
                    .collect(Collectors.toList());
        }

        return new SliceImpl<>(result, pageable, hashesSlice.hasNext());
    }

    /**
     * Calculates the Hamming Distance between two 8-byte (64-bit) pHashes.
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
}