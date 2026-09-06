package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - PhotoStoragePathResolver has no HTTP endpoint of its own, it's a
// pure path-computation helper consumed by PhotoUploadService/StorageMigrationService/etc.
// Storage roots come from AbstractIntegrationTest's @DynamicPropertySource (a fresh temp
// directory per test run), so this writes real directories under that, never the app's real
// storage location.
class PhotoStoragePathResolverTest extends AbstractIntegrationTest {

    @Autowired
    private PhotoStoragePathResolver photoStoragePathResolver;

    @Test
    void resolveOriginalPathCreatesAYearMonthSubfolderAndReturnsAFileNamedAfterThePhotoId() throws Exception {
        UUID photoId = UUID.randomUUID();
        LocalDateTime dateTaken = LocalDateTime.of(2024, 3, 15, 10, 30);

        Path resolved = photoStoragePathResolver.resolveOriginalPath(photoId, "jpg", dateTaken);

        assertThat(resolved.toString()).endsWith(photoId + ".jpg");
        assertThat(resolved.getParent().toString()).endsWith("2024" + java.io.File.separator + "03");
        assertThat(Files.exists(resolved.getParent())).isTrue();
    }

    @Test
    void resolveThumbnailDirCreatesAndReturnsTheYearMonthFolder() throws Exception {
        LocalDateTime dateTaken = LocalDateTime.of(2023, 11, 1, 0, 0);

        Path dir = photoStoragePathResolver.resolveThumbnailDir(dateTaken);

        assertThat(dir.toString()).endsWith("2023" + java.io.File.separator + "11");
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void isAlreadyOrganizedIsTrueOnlyWhenTheFileSitsInItsDateExpectedFolder() throws Exception {
        LocalDateTime dateTaken = LocalDateTime.of(2022, 6, 1, 0, 0);
        Path storageRoot = photoStoragePathResolver.resolveThumbnailDir(dateTaken).getParent().getParent();
        Path organized = photoStoragePathResolver.resolveThumbnailDir(dateTaken).resolve("photo.jpg");
        Path misplaced = Files.createTempFile("misplaced", ".jpg");

        assertThat(photoStoragePathResolver.isAlreadyOrganized(organized, storageRoot, dateTaken)).isTrue();
        assertThat(photoStoragePathResolver.isAlreadyOrganized(misplaced, storageRoot, dateTaken)).isFalse();

        Files.deleteIfExists(misplaced);
    }
}
