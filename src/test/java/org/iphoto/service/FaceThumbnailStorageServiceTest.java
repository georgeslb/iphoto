package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - FaceThumbnailStorageService has no HTTP endpoint of its own
// (FaceController#getThumbnail and FaceDetectionServiceImpl/FaceThumbnailMigrationService consume
// it directly). Storage root comes from AbstractIntegrationTest's @DynamicPropertySource (a fresh
// temp directory per test run), so this writes real files under that, never the app's real
// storage location.
class FaceThumbnailStorageServiceTest extends AbstractIntegrationTest {

    @Autowired
    private FaceThumbnailStorageService faceThumbnailStorageService;

    @Test
    void saveWritesTheFileUnderAYearMonthSubfolderAndReturnsItsPath() throws Exception {
        byte[] bytes = generateTestJpeg();
        LocalDateTime dateTaken = LocalDateTime.of(2024, 3, 15, 10, 30);

        String path = faceThumbnailStorageService.save(bytes, dateTaken);

        Path resolved = Path.of(path);
        assertThat(resolved.toString()).endsWith(".jpg");
        assertThat(resolved.getParent().toString()).endsWith("2024" + java.io.File.separator + "03");
        assertThat(Files.readAllBytes(resolved)).isEqualTo(bytes);
    }

    @Test
    void saveFallsBackToNowWhenDateTakenIsNull() throws Exception {
        String path = faceThumbnailStorageService.save(generateTestJpeg(), null);

        assertThat(Files.exists(Path.of(path))).isTrue();
    }

    @Test
    void deleteRemovesTheFile() throws Exception {
        String path = faceThumbnailStorageService.save(generateTestJpeg(), LocalDateTime.now());
        assertThat(Files.exists(Path.of(path))).isTrue();

        faceThumbnailStorageService.delete(path);

        assertThat(Files.exists(Path.of(path))).isFalse();
    }

    @Test
    void deleteIsANoOpForNullOrAlreadyMissingPaths() {
        faceThumbnailStorageService.delete(null);
        faceThumbnailStorageService.delete("/nonexistent/path/does-not-exist.jpg");
        // No exception thrown - that's the assertion.
    }
}
