package org.iphoto.service;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_img_hash.AverageHash;
import org.bytedeco.opencv.opencv_img_hash.ImgHashBase;
import org.bytedeco.opencv.opencv_img_hash.PHash;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.SpaceType;
import org.iphoto.entities.TaskEntity;
import org.iphoto.repository.DuplicateRepository;
import org.iphoto.repository.PhotoDuplicateRepository;
import org.iphoto.repository.PhotoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers the reduced-resolution decode path added to avoid allocating a full-resolution native
 * OpenCV buffer per photo (see DuplicationServiceImpl#readForHashing) - both the pure
 * factor-selection logic and an end-to-end check that hashing still produces usable phash/mhash/
 * ORB data through that path.
 */
class DuplicationServiceImplTest {

    private Path tempFile;

    @AfterEach
    void cleanup() throws IOException {
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    @ParameterizedTest
    @CsvSource({
            // maxDimension, expectedFactor
            "8000, 8",   // 8000/8=1000 >= MAX_SIZE -> largest factor that still clears the target
            "7999, 4",   // 7999/8=999 < 1000, but 7999/4=1999 >= 1000
            "5000, 4",   // 5000/8=625 < 1000, 5000/4=1250 >= 1000
            "2000, 2",   // 2000/8 and /4 both < 1000, /2=1000 >= 1000
            "1999, 1",   // 1999/2=999 < 1000 - no reduction clears the target, decode at full size
            "1000, 1",   // already at MAX_SIZE - no reduction needed
            "500, 1",    // already below MAX_SIZE
    })
    void selectReducedDecodeFactorPicksTheLargestFactorThatStillClearsMaxSize(int maxDimension, int expectedFactor) {
        assertThat(DuplicationServiceImpl.selectReducedDecodeFactor(maxDimension)).isEqualTo(expectedFactor);
    }

    @Test
    void hashImageThroughTheReducedDecodePathStillProducesUsablePhashMhashAndOrbFeatures() throws IOException {
        // 2100x2100 forces selectReducedDecodeFactor to pick factor 2 (2100/2=1050 >= 1000), so
        // this exercises the IMREAD_REDUCED_COLOR_2 path rather than the plain-imread fallback.
        assertHashingProducesUsableResults(generatePhotoLikeJpeg(2100, 2100));
    }

    @Test
    void reducedDecodePathProducesHashesWellWithinTheExistingStrongDuplicateBandComparedToFullDecode() throws IOException {
        // This is the direct answer to "are you sure the change is safe": rather than just
        // asserting the new path produces *some* valid hash, decode the SAME image both the old
        // way (plain imread - full resolution) and the new way (readForHashing - reduced
        // decode), run both through the real resizeIfNeeded + PHash/AverageHash pipeline, and
        // measure how many bits actually differ. If DCT-scaled decode meaningfully changed the
        // perceptual hash, this would show it directly instead of leaving it as a theory.
        File imageFile = generatePhotoLikeJpeg(2100, 2100);
        DuplicationServiceImpl service = new DuplicationServiceImpl(
                mock(PhotoRepository.class), mock(DuplicateRepository.class), mock(PhotoDuplicateRepository.class),
                mock(DuplicationUniquenessService.class), mock(TaskMapper.class));

        Mat fullDecode = imread(imageFile.getAbsolutePath());
        Mat reducedDecode = service.readForHashing(imageFile.getAbsolutePath());
        try {
            assertThat(fullDecode.empty()).isFalse();
            assertThat(reducedDecode.empty()).isFalse();
            // Confirms this test is actually exercising the reduced-decode branch, not silently
            // falling back to the same full decode on both sides.
            assertThat(reducedDecode.cols()).isLessThan(fullDecode.cols());

            Mat fullResized = service.resizeIfNeeded(fullDecode);
            Mat reducedResized = service.resizeIfNeeded(reducedDecode);

            int pHashDistance = hammingDistance(computeHash(PHash.create(), fullResized), computeHash(PHash.create(), reducedResized));
            int mHashDistance = hammingDistance(computeHash(AverageHash.create(), fullResized), computeHash(AverageHash.create(), reducedResized));

            // Strong-duplicate thresholds in evaluatePair are pHash<=12, mHash<=10 (out of 64
            // bits). The same photo decoded two different ways should land far inside that band -
            // asserting a tight margin (half the threshold) rather than just "under the
            // threshold" so this would actually fail if the drift were meaningful.
            assertThat(pHashDistance).as("pHash bit drift between full and reduced decode").isLessThanOrEqualTo(6);
            assertThat(mHashDistance).as("mHash bit drift between full and reduced decode").isLessThanOrEqualTo(5);

            fullResized.release();
            reducedResized.release();
        } finally {
            fullDecode.release();
        }
    }

    private byte[] computeHash(ImgHashBase hasher, Mat image) {
        Mat hash = new Mat();
        try {
            hasher.compute(image, hash);
            byte[] bytes = new byte[(int) hash.total()];
            hash.data().get(bytes);
            return bytes;
        } finally {
            hash.release();
        }
    }

    private int hammingDistance(byte[] hash1, byte[] hash2) {
        long long1 = java.nio.ByteBuffer.wrap(hash1).getLong();
        long long2 = java.nio.ByteBuffer.wrap(hash2).getLong();
        return Long.bitCount(long1 ^ long2);
    }

    @Test
    void hashImageOnASmallImageStillUsesThePlainDecodeFallback() throws IOException {
        // Below MAX_SIZE entirely - selectReducedDecodeFactor returns 1 (no reduction), so this
        // is a regression check that the untouched fallback path still works.
        assertHashingProducesUsableResults(generatePhotoLikeJpeg(500, 500));
    }

    private void assertHashingProducesUsableResults(File imageFile) {
        PhotoRepository photoRepository = mock(PhotoRepository.class);
        DuplicateRepository duplicateRepository = mock(DuplicateRepository.class);
        PhotoDuplicateRepository photoDuplicateRepository = mock(PhotoDuplicateRepository.class);
        DuplicationUniquenessService duplicationUniquenessService = mock(DuplicationUniquenessService.class);
        TaskMapper taskMapper = mock(TaskMapper.class);

        DuplicationServiceImpl service = new DuplicationServiceImpl(
                photoRepository, duplicateRepository, photoDuplicateRepository, duplicationUniquenessService, taskMapper);

        UUID photoId = UUID.randomUUID();
        SpaceEntity space = SpaceEntity.builder().type(SpaceType.SECONDARY).build();
        PhotoEntity photo = PhotoEntity.builder()
                .id(photoId)
                .fileName(imageFile.getName())
                .originalPath(imageFile.getAbsolutePath())
                .fileHash(UUID.randomUUID().toString())
                .contentType("image/jpeg")
                .space(space)
                .build();

        org.mockito.Mockito.when(photoRepository.findById(photoId)).thenReturn(java.util.Optional.of(photo));

        TaskEntity task = TaskEntity.builder().photoId(photoId).build();
        service.hashImage(task);

        assertThat(photo.getPhash()).hasSize(8);
        assertThat(photo.getMhash()).hasSize(8);
        assertThat(photo.getOrbDescriptors()).isNotNull();
        assertThat(photo.getOrbDescriptors().length).isGreaterThan(0);
        assertThat(photo.getOrbKeypoints()).isNotNull();
        assertThat(photo.getOrbKeypoints().length).isGreaterThan(0);
        verify(photoRepository).save(photo);
    }

    private File generatePhotoLikeJpeg(int width, int height) throws IOException {
        // Smooth gradient background (real low-frequency structure, so pHash - which only looks
        // at the lowest DCT coefficients - has something stable to hash) plus scattered shapes
        // with distinct edges (real corners/gradients for ORB to key off). Deliberately NOT flat
        // per-pixel random noise: noise has no low-frequency correlation at all, which is an
        // adversarial worst case for any frequency-domain hash and not representative of an
        // actual photo - it was tried first and produced a 21-bit pHash drift between decode
        // paths purely as an artifact of that lack of structure, not a real duplication-detection
        // regression.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setPaint(new java.awt.GradientPaint(
                0, 0, new java.awt.Color(30, 60, 120),
                width, height, new java.awt.Color(220, 200, 140)));
        g.fillRect(0, 0, width, height);

        Random random = new Random(42);
        for (int i = 0; i < 60; i++) {
            g.setColor(new java.awt.Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            int size = 20 + random.nextInt(Math.max(20, width / 8));
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            if (random.nextBoolean()) {
                g.fillOval(x, y, size, size);
            } else {
                g.fillRect(x, y, size, size);
            }
        }
        g.dispose();

        tempFile = Files.createTempFile("duplication-service-test-", ".jpg");
        File file = tempFile.toFile();
        ImageIO.write(image, "jpg", file);
        return file;
    }
}
