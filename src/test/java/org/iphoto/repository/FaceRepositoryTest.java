package org.iphoto.repository;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// FaceRecognitionServiceImpl's new slice-based groupFacesForUser depends on this query returning a
// user's WHOLE ungrouped-faces backlog in chronological (photo.dateTaken ascending) order across
// every slice/page fetched, not just within one page - see TODO.md's "qualif-server item 5" entry
// (test plan bullet 5b) and CandidateClusterer's own class comment for why that ordering matters (a
// cluster's up-to-3 held representatives get picked up as the timeline progresses instead of
// whichever faces happen to come back first, narrowing the transitive-chaining loss inherent to a
// capped representative set).
//
// findByPersonIsNullFetchPhoto is @MainSpaceLimited, so this runs as admin (runAsAdmin) with its
// fixtures in admin's own main space, same as FaceServiceTest/PersonRecognitionServiceTest. This
// shared H2 database also holds OTHER test classes' own person-null faces in admin's space by the
// time this runs, so the assertion below filters the returned content down to just this test's own
// 3 known face ids (by id, not by list equality) rather than asserting on the full returned list -
// their RELATIVE order among each other is what's under test here, regardless of interleaving.
class FaceRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private FaceRepository faceRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;

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

    private FaceEntity saveFace(PhotoEntity photo, float value) {
        return faceRepository.save(FaceEntity.builder()
                .photo(photo).x(0).y(0).width(100).height(100)
                .embedding(EmbeddingCodec.toBytes(new float[]{value, value, value}))
                .build());
    }

    @Test
    void returnsUngroupedFacesOrderedByPhotoDateTakenAscendingRegardlessOfInsertionOrder() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);

        // Inserted deliberately out of calendar order - the most recent one first.
        PhotoEntity newestPhoto = savePhoto(space, admin, LocalDateTime.of(2024, 9, 1, 0, 0));
        FaceEntity newestFace = saveFace(newestPhoto, 91.0f);
        PhotoEntity oldestPhoto = savePhoto(space, admin, LocalDateTime.of(2020, 1, 1, 0, 0));
        FaceEntity oldestFace = saveFace(oldestPhoto, 92.0f);
        PhotoEntity middlePhoto = savePhoto(space, admin, LocalDateTime.of(2022, 5, 1, 0, 0));
        FaceEntity middleFace = saveFace(middlePhoto, 93.0f);

        Set<UUID> ourFaceIds = Set.of(oldestFace.getId(), middleFace.getId(), newestFace.getId());

        // Pages through admin's whole person-null backlog (same page size groupFacesForUser itself
        // uses) until all 3 of our own faces have turned up, rather than guessing one big-enough
        // page size for however many OTHER tests' leftover person-null faces already share this
        // suite's database by the time this test runs.
        List<UUID> ourFacesInReturnedOrder = new ArrayList<>();
        int pageNumber = 0;
        boolean hasNext;
        do {
            int currentPage = pageNumber;
            Slice<FaceEntity> slice = runAsAdmin(() -> faceRepository.findByPersonIsNullFetchPhoto(PageRequest.of(currentPage, 200)));
            slice.getContent().stream()
                    .filter(face -> ourFaceIds.contains(face.getId()))
                    .map(FaceEntity::getId)
                    .forEach(ourFacesInReturnedOrder::add);
            hasNext = slice.hasNext();
            pageNumber++;
        } while (hasNext && ourFacesInReturnedOrder.size() < ourFaceIds.size());

        assertThat(ourFacesInReturnedOrder)
                .containsExactly(oldestFace.getId(), middleFace.getId(), newestFace.getId());
    }
}
