package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - DuplicationUniquenessService has no HTTP endpoint of its own, it's
// consumed internally by DuplicationService (excluded from this suite). Its repositories
// (Duplicate/PhotoDuplicate) are @MainSpaceLimited, so calls run through
// AbstractIntegrationTest#runAsAdmin to give the space-filter aspect a real authenticated user,
// the same as a real request would.
//
// consolidateDuplicates is @Transactional(REQUIRES_NEW) already, so it needs no extra transaction
// wrapping here - it commits on its own, exactly as it does when called for real. But
// undoGenerateUniqueHash carries no @Transactional of its own, and reads a lazy collection
// (DuplicateEntity.photoDuplicates) after its repository call returns - fine in production, where
// spring.jpa.open-in-view keeps one session open for the whole request, but not from a bare
// direct call in this JUnit thread. TransactionTemplate wraps just those calls in a real
// transaction (joined by the service method itself, default REQUIRED propagation) so the session
// stays open across the whole method body, the same effect open-in-view has around a request -
// deliberately NOT wrapped around the consolidateDuplicates calls, which must stay outside any
// ambient transaction so their own REQUIRES_NEW is a genuinely separate one, exactly as in
// production.
class DuplicationUniquenessServiceTest extends AbstractIntegrationTest {

    @Autowired
    private DuplicationUniquenessService duplicationUniquenessService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private PhotoEntity savePhoto(SpaceEntity space, UserEntity user, String hashSeed) {
        return photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID())
                .fileName(hashSeed + ".jpg")
                .fileHash(HexFormat.of().formatHex(hashSeed.getBytes(StandardCharsets.UTF_8)))
                .contentType("image/jpeg")
                .user(user)
                .space(space)
                .originalPath("/tmp/" + hashSeed + ".jpg")
                .isFavorite(false)
                .isGarbage(false)
                .build());
    }

    // Mirrors DuplicationUniquenessServiceImpl#generateUniqueHash exactly: SHA-256 of the
    // member photos' fileHash values, sorted, comma-joined - undoGenerateUniqueHash needs this
    // same hash back to look the group up.
    private static String groupHashFor(PhotoEntity... photos) throws Exception {
        String canonical = Stream.of(photos)
                .map(PhotoEntity::getFileHash)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private List<UUID> lookupGroup(String hash) {
        return transactionTemplate.execute(status -> {
            try {
                return runAsAdmin(() -> duplicationUniquenessService.undoGenerateUniqueHash(hash));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void consolidateDuplicatesCreatesANewGroupWhenNeitherPhotoHasOneYet() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity mainSpace = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(mainSpace, admin, "consolidate-primary");
        PhotoEntity duplicate = savePhoto(mainSpace, admin, "consolidate-secondary");

        TaskStatus status = runAsAdmin(() -> duplicationUniquenessService.consolidateDuplicates(photo, Set.of(duplicate)));

        assertThat(status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(lookupGroup(groupHashFor(photo, duplicate))).containsExactlyInAnyOrder(photo.getId(), duplicate.getId());
    }

    @Test
    void consolidateDuplicatesAddsToAnExistingGroupRatherThanCreatingASecondOne() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity mainSpace = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(mainSpace, admin, "grow-primary");
        PhotoEntity firstDuplicate = savePhoto(mainSpace, admin, "grow-secondary");
        PhotoEntity secondDuplicate = savePhoto(mainSpace, admin, "grow-tertiary");
        runAsAdmin(() -> duplicationUniquenessService.consolidateDuplicates(photo, Set.of(firstDuplicate)));

        TaskStatus status = runAsAdmin(() -> duplicationUniquenessService.consolidateDuplicates(secondDuplicate, Set.of(photo)));

        assertThat(status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(lookupGroup(groupHashFor(photo, firstDuplicate, secondDuplicate)))
                .containsExactlyInAnyOrder(photo.getId(), firstDuplicate.getId(), secondDuplicate.getId());
    }

    @Test
    void undoGenerateUniqueHashReturnsEmptyForAnUnknownHash() {
        assertThat(lookupGroup("no-such-hash")).isEmpty();
    }
}
