package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - PhotoDeletionService has no HTTP endpoint of its own, it's the
// internal row-deletion step PhotoService.permanentDeleteAssets delegates to (see
// PhotoServiceTest for that full flow, file-deletion included). Its only branch that isn't a
// plain PhotoRepository call - removing the photo from any album it belongs to - hits
// AlbumRepository's @MainSpaceLimited findById, so that scenario runs through runAsAdmin.
class PhotoDeletionServiceTest extends AbstractIntegrationTest {

    @Autowired
    private PhotoDeletionService photoDeletionService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private AlbumRepository albumRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private PhotoEntity savePhoto(SpaceEntity space, UserEntity user) {
        String seed = UUID.randomUUID().toString();
        return photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID()).fileName(seed + ".jpg").fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("image/jpeg").user(user).space(space).originalPath("/tmp/" + seed + ".jpg")
                .isFavorite(false).isGarbage(false).build());
    }

    @Test
    void permanentDeleteAssetsRemovesThePhotoRow() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);

        photoDeletionService.permanentDeleteAssets(photo.getId());

        assertThat(photoRepository.findById(photo.getId())).isEmpty();
    }

    @Test
    void permanentDeleteAssetsAlsoRemovesThePhotoFromAnyAlbumItBelongsTo() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        AlbumEntity album = albumRepository.save(AlbumEntity.builder()
                .space(space).name("PhotoDeletion-Album-" + UUID.randomUUID()).photoCount(1)
                .photos(new HashSet<>(Set.of(photo))).build());

        runAsAdmin(() -> {
            photoDeletionService.permanentDeleteAssets(photo.getId());
            return null;
        });

        assertThat(photoRepository.findById(photo.getId())).isEmpty();
        // A real transaction here (unlike the bare direct call above - see other tests' own
        // comments on this pattern) keeps a session open long enough to read AlbumEntity.photos,
        // a lazy collection, right after the findById that loads it; findById is
        // @MainSpaceLimited too, so this also needs runAsAdmin's authenticated context.
        int remainingAlbumPhotos = transactionTemplate.execute(status -> {
            try {
                return runAsAdmin(() -> albumRepository.findByIdInMainSpace(album.getId()).orElseThrow().getPhotos().size());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(remainingAlbumPhotos).isZero();
    }
}
