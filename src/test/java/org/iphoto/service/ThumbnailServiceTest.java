package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - ThumbnailService has no HTTP endpoint of its own, it's consumed
// internally by PhotoUploadService/EditPhotoService/ImmichImportService. No @MainSpaceLimited
// repository involved, so no authenticated context is needed either.
class ThumbnailServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ThumbnailService thumbnailService;

    @Test
    void generateThumbnailWritesA400pxJpegIntoTheDateBasedFolder() throws Exception {
        Path original = Files.createTempFile("thumb-source", ".jpg");
        Files.write(original, generateTestJpeg());
        String fileId = UUID.randomUUID().toString();

        String thumbnailPath = thumbnailService.generateThumbnail(original.toString(), fileId, "image/jpeg", LocalDateTime.of(2024, 5, 1, 0, 0));

        assertThat(thumbnailPath).endsWith(fileId + "_thumb.jpg");
        assertThat(Files.exists(Path.of(thumbnailPath))).isTrue();
        assertThat(Path.of(thumbnailPath).getParent().toString()).contains("2024" + File.separator + "05");

        Files.deleteIfExists(original);
    }

    @Test
    void regenerateThumbnailInPlaceWritesBackIntoTheExistingThumbnailsFolder() throws Exception {
        Path original = Files.createTempFile("thumb-source", ".jpg");
        Files.write(original, generateTestJpeg());
        String fileId = UUID.randomUUID().toString();
        String firstThumbnailPath = thumbnailService.generateThumbnail(original.toString(), fileId, "image/jpeg", LocalDateTime.of(2021, 1, 1, 0, 0));

        String regeneratedPath = thumbnailService.regenerateThumbnailInPlace(original.toString(), fileId, "image/jpeg", firstThumbnailPath);

        assertThat(Path.of(regeneratedPath).getParent()).isEqualTo(Path.of(firstThumbnailPath).getParent());
        assertThat(Files.exists(Path.of(regeneratedPath))).isTrue();

        Files.deleteIfExists(original);
    }
}
