package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.FaceThumbnailMigrationSummaryDTO;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - FaceThumbnailMigrationService has no HTTP endpoint of its own (only
// AdminService#migrateFaceThumbnailsToFilesystem, exposed via AdminController, wraps it).
class FaceThumbnailMigrationServiceTest extends AbstractIntegrationTest {

    @Autowired
    private FaceThumbnailMigrationService faceThumbnailMigrationService;
    @Autowired
    private FaceRepository faceRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        // Clear any leftover legacy blob thumbnails from other tests without violating foreign keys
        faceRepository.findAll().forEach(f -> {
            if (f.getFaceThumbnail() != null) {
                f.setFaceThumbnail(null);
                faceRepository.save(f);
            }
        });
    }

    private PhotoEntity savePhoto(SpaceEntity space, UserEntity user, LocalDateTime dateTaken) {
        String seed = UUID.randomUUID().toString();
        return photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID())
                .fileName(seed + ".jpg")
                .fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("image/jpeg")
                .user(user)
                .space(space)
                .originalPath("/tmp/" + seed + ".jpg")
                .dateTaken(dateTaken)
                .isFavorite(false)
                .isGarbage(false)
                .build());
    }

    @Test
    void migrateAllMovesALegacyBlobThumbnailToDiskAndClearsTheColumn() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity mainSpace = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(mainSpace, admin, LocalDateTime.of(2021, 5, 10, 0, 0));
        FaceEntity legacyFace = faceRepository.save(FaceEntity.builder()
                .photo(photo).x(1).y(1).width(50).height(50)
                .faceThumbnail(generateTestJpeg())
                .build());

        FaceThumbnailMigrationSummaryDTO summary = faceThumbnailMigrationService.migrateAll();

        assertThat(summary.scanned()).isGreaterThanOrEqualTo(1);
        assertThat(summary.migrated()).isGreaterThanOrEqualTo(1);
        assertThat(summary.failed()).isEqualTo(0);

        FaceEntity reloaded = faceRepository.findById(legacyFace.getId()).orElseThrow();
        assertThat(reloaded.getThumbnailPath()).isNotNull();
        assertThat(reloaded.getFaceThumbnail()).isNull();
        assertThat(Files.exists(Path.of(reloaded.getThumbnailPath()))).isTrue();
        assertThat(reloaded.getThumbnailPath()).contains("2021" + java.io.File.separator + "05");
    }

    @Test
    void migrateAllIsANoOpWhenNothingNeedsMigrating() {
        FaceThumbnailMigrationSummaryDTO summary = faceThumbnailMigrationService.migrateAll();

        assertThat(summary.scanned()).isEqualTo(0);
        assertThat(summary.migrated()).isEqualTo(0);
        assertThat(summary.failed()).isEqualTo(0);
    }

    @Test
    void migrateAllLeavesAnUnreadableBlobInPlaceAndCountsItAsFailed() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity mainSpace = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(mainSpace, admin, LocalDateTime.of(2021, 5, 10, 0, 0));
        FaceEntity corruptFace = faceRepository.save(FaceEntity.builder()
                .photo(photo).x(1).y(1).width(50).height(50)
                .faceThumbnail(new byte[]{1, 2, 3})
                .build());

        FaceThumbnailMigrationSummaryDTO summary = faceThumbnailMigrationService.migrateAll();

        assertThat(summary.failed()).isGreaterThanOrEqualTo(1);
        FaceEntity reloaded = faceRepository.findById(corruptFace.getId()).orElseThrow();
        // Left untouched - still eligible for a retry later (e.g. once the underlying issue is
        // otherwise addressed), never silently dropped.
        assertThat(reloaded.getThumbnailPath()).isNull();
        assertThat(reloaded.getFaceThumbnail()).isNotNull();
    }
}
