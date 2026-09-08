package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

// Integration-level coverage for FaceRecognitionServiceImpl's new slice-based groupFacesForUser -
// see TODO.md's "qualif-server item 5" entry, test plan bullets 4 and 6. Drives the REAL
// groupFaces()/groupFacesForUser() entry point end-to-end (unlike CandidateClustererTest, which
// covers the extracted matching/promotion logic in isolation without a database).
//
// The entry's own bullet 6 documents an EARLIER attempt at this exact kind of test running into a
// real, pre-existing, out-of-scope limitation: FaceRepository.findByPersonIsNullFetchPhoto and
// PersonRepository.findAll() are @MainSpaceLimited, which needs an authenticated
// SecurityContextHolder principal to scope its query - with none pushed, or with the thread renamed
// to bypass the filter entirely (production's own real bypass mechanism, see
// MainSpaceAspectConfiguration), matching becomes global across this whole shared-database test
// suite and collides with unrelated leftover people from other test classes.
//
// This uses a DIFFERENT approach than that earlier attempt: a freshly-created, uniquely-named
// NON-ADMIN user (see userService.createUser below), with that SAME user's own Authentication
// pushed for the duration of the call (not admin's, and not a thread rename). Since
// @MainSpaceLimited scopes by the CURRENT principal's own default space id
// (MainSpaceAspectConfiguration), and this fresh user's own default space is brand new and private
// to it, every @MainSpaceLimited query below is scoped to a space nothing else in this suite ever
// writes into - fully isolated from other test classes' leftover data without needing global/
// thread-rename bypass at all.
class FaceRecognitionServiceImplTest extends AbstractIntegrationTest {

    @Autowired
    private FaceRecognitionService faceRecognitionService;
    @Autowired
    private UserService userService;
    @Autowired
    private SpaceService spaceService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private FaceRepository faceRepository;

    private UserEntity createTestUser() {
        return userService.createUser("face-grouping-" + UUID.randomUUID(), "password", "USER");
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

    // Axis-aligned vectors (see CandidateClustererTest's own comment) - a same-direction vector
    // scores cosine similarity 1 against itself/other same-direction faces, and 0 against a
    // different axis, well clear of THRESHOLD (0.60) either way.
    private FaceEntity saveFace(PhotoEntity photo, float[] direction) {
        return faceRepository.save(FaceEntity.builder()
                .photo(photo).x(0).y(0).width(100).height(100)
                .embedding(EmbeddingCodec.toBytes(direction))
                .build());
    }

    // Mirrors AbstractIntegrationTest.runAsAdmin, but for an arbitrary (non-admin) user - needed so
    // @MainSpaceLimited queries below scope to THIS test's own fixtures' space, not admin's shared
    // one (see this class's own header comment).
    private <T> T runAs(UserEntity user, Callable<T> action) throws Exception {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    @Test
    void aRealPersonAppearsOnlyOnceAThirdMatchingFaceExistsAndPicksUpTheBacklogOnALaterRun() throws Exception {
        UserEntity testUser = createTestUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(testUser);
        LocalDateTime t = LocalDateTime.of(2024, 6, 1, 12, 0);
        float[] direction = {1f, 0f, 0f};

        FaceEntity face1 = saveFace(savePhoto(space, testUser, t), direction);
        FaceEntity face2 = saveFace(savePhoto(space, testUser, t.plusMinutes(1)), direction);

        // Only 2 matching faces exist so far - MIN_NEW_PERSON_CLUSTER_SIZE (3) isn't reached, so
        // neither should get a person yet.
        runAs(testUser, () -> {
            faceRecognitionService.groupFaces();
            return null;
        });
        assertThat(faceRepository.findById(face1.getId()).orElseThrow().getPerson()).isNull();
        assertThat(faceRepository.findById(face2.getId()).orElseThrow().getPerson()).isNull();

        // A later run (MAX_UNGROUPED_FACES_PER_RUN's own comment: "a backlog beyond this is simply
        // picked up by later runs") picks up the backlog once a 3rd matching face exists.
        FaceEntity face3 = saveFace(savePhoto(space, testUser, t.plusMinutes(2)), direction);
        runAs(testUser, () -> {
            faceRecognitionService.groupFaces();
            return null;
        });

        UUID personId = faceRepository.findById(face1.getId()).orElseThrow().getPerson().getId();
        assertThat(personId).isNotNull();
        assertThat(faceRepository.findById(face2.getId()).orElseThrow().getPerson().getId()).isEqualTo(personId);
        assertThat(faceRepository.findById(face3.getId()).orElseThrow().getPerson().getId()).isEqualTo(personId);
    }

    // Test plan bullet 4: a face that arrives AFTER a CandidateCluster promotion within the same
    // run must attach to that brand-new person via the normal existing-person path
    // (personRecognitionService.updatePerson), not seed a second, duplicate candidate cluster/
    // person for the same subject. Deliberately not forced across a literal CLUSTERING_SLICE_SIZE
    // (200) DB-page boundary - see this class's own header comment and CandidateClusterer's: the
    // mid-run-visibility code under test here (persons/bucketsByPerson mutated in place,
    // read by the same per-face loop body on every later iteration) is instantiated once per user
    // run, OUTSIDE groupFacesForUser's own slice-fetch loop, so it behaves identically regardless of
    // which physical DB page a later matching face's row happens to land in - only the page boundary
    // would differ, not the logic this test exercises.
    @Test
    void aFaceMatchingAPersonPromotedEarlierInTheSameRunAttachesToItInsteadOfSeedingADuplicate() throws Exception {
        UserEntity testUser = createTestUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(testUser);
        LocalDateTime t = LocalDateTime.of(2024, 7, 1, 12, 0);
        float[] direction = {0f, 1f, 0f};

        FaceEntity face1 = saveFace(savePhoto(space, testUser, t), direction);
        FaceEntity face2 = saveFace(savePhoto(space, testUser, t.plusMinutes(1)), direction);
        FaceEntity face3 = saveFace(savePhoto(space, testUser, t.plusMinutes(2)), direction); // triggers promotion
        FaceEntity face4 = saveFace(savePhoto(space, testUser, t.plusMinutes(3)), direction); // must join the SAME person

        runAs(testUser, () -> {
            faceRecognitionService.groupFaces();
            return null;
        });

        UUID personId = faceRepository.findById(face1.getId()).orElseThrow().getPerson().getId();
        assertThat(personId).isNotNull();
        assertThat(faceRepository.findById(face2.getId()).orElseThrow().getPerson().getId()).isEqualTo(personId);
        assertThat(faceRepository.findById(face3.getId()).orElseThrow().getPerson().getId()).isEqualTo(personId);
        // The interesting assertion: if mid-run visibility were broken, face4 would either stay
        // personless (seeded its own new, never-promoted 1-member candidate cluster) or - if this
        // test fed enough faces to promote a second cluster - end up under a DIFFERENT person id.
        assertThat(faceRepository.findById(face4.getId()).orElseThrow().getPerson())
                .as("face4 must attach to the person promoted from face1-3 in this same run, not stay unmatched")
                .isNotNull();
        assertThat(faceRepository.findById(face4.getId()).orElseThrow().getPerson().getId()).isEqualTo(personId);
    }
}
