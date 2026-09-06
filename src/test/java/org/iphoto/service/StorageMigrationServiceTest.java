package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.StorageMigrationSummaryDTO;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - StorageMigrationService has no HTTP endpoint of its own (only
// AdminService#migrateFlatFilesToOrganizedStorage, exposed via AdminController, wraps it - see
// AdminServiceTest for that). No @MainSpaceLimited repository involved (PhotoRepository doesn't
// use that aspect), so no authenticated context is needed.
class StorageMigrationServiceTest extends AbstractIntegrationTest {

    @Autowired
    private StorageMigrationService storageMigrationService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private IPhotoProperties iPhotoProperties;

    @Test
    void migrateAllFlatPhotosMovesAFlatlyStoredPhotoIntoItsYearMonthFolder() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity mainSpace = spaceRepository.findByTypeEqualsMainAndUser(admin);

        // Simulate a photo left over from before the year/month storage layout existed: its file
        // sits directly in the flat immich-temp root, not under {year}/{month}/.
        Path flatRoot = Path.of(iPhotoProperties.getStorage().getImmichTemp());
        Files.createDirectories(flatRoot);
        Path flatOriginal = flatRoot.resolve(UUID.randomUUID() + ".jpg");
        Files.write(flatOriginal, generateTestJpeg());

        PhotoEntity photo = photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID())
                .fileName("flat.jpg")
                .fileHash(HexFormat.of().formatHex(flatOriginal.toString().getBytes()))
                .contentType("image/jpeg")
                .user(admin)
                .space(mainSpace)
                .originalPath(flatOriginal.toString())
                .dateTaken(LocalDateTime.of(2020, 7, 4, 12, 0))
                .isFavorite(false)
                .isGarbage(false)
                .build());

        StorageMigrationSummaryDTO summary = storageMigrationService.migrateAllFlatPhotos();

        assertThat(summary.moved()).isGreaterThanOrEqualTo(1);
        PhotoEntity reloaded = photoRepository.findById(photo.getId()).orElseThrow();
        assertThat(reloaded.getOriginalPath()).contains("2020" + java.io.File.separator + "07");
        assertThat(Files.exists(Path.of(reloaded.getOriginalPath()))).isTrue();
        assertThat(Files.exists(flatOriginal)).isFalse();
    }
}
