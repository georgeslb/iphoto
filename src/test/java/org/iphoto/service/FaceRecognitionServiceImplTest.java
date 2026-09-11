package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.FaceStatus;
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
// groupFacesForUser's own fetches (ungrouped faces / existing people) are space-scoped by an
// explicit :spaceId parameter, NOT @MainSpaceLimited (that's a no-op on the heavy-task thread pool
// this runs on in production - see FaceRepository.findByPersonIsNullAndSpaceIdFetchPhoto's comment),
// so isolation from other test classes' data comes purely from each test using a freshly-created,
// uniquely-named non-admin user's brand-new default space.
//
// The grouping engine underneath it (PersonRecognitionServiceImpl.updatePerson/addPerson) does
// re-fetch a person via the now-@MainSpaceLimited PersonRepository.findById - a no-op on a real
// heavy-task thread in production, but on this bare JUnit thread it needs a principal to resolve a
// space from, so groupFaces() and the direct personRecognitionService calls here run inside
// runAs(testUser, ...). The test user's own space is where every fixture lives, so this filters to
// exactly the right set.
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
    @Autowired
    private PersonRecognitionService personRecognitionService;

    private UserEntity createTestUser() {
        return userService.createUser("face-grouping-" + UUID.randomUUID(), "password", "USER");
    }

    // Pushes `user` as the current principal for the duration of `action` - the grouping engine's
    // PersonRepository.findById re-fetch is @MainSpaceLimited and reads the principal off
    // SecurityContextHolder, which no security filter chain has populated on this bare test thread.
    private <T> T runAs(UserEntity user, Callable<T> action) throws Exception {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private void runAs(UserEntity user, Runnable action) throws Exception {
        runAs(user, () -> {
            action.run();
            return null;
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

    // Axis-aligned vectors (see CandidateClustererTest's own comment) - a same-direction vector
    // scores cosine similarity 1 against itself/other same-direction faces, and 0 against a
    // different axis, well clear of THRESHOLD (0.60) either way.
    private FaceEntity saveFace(PhotoEntity photo, float[] direction) {
        return faceRepository.save(FaceEntity.builder()
                .photo(photo).x(0).y(0).width(100).height(100)
                .embedding(EmbeddingCodec.toBytes(direction))
                .build());
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
        runAs(testUser, () -> faceRecognitionService.groupFaces());
        assertThat(faceRepository.findById(face1.getId()).orElseThrow().getPerson()).isNull();
        assertThat(faceRepository.findById(face2.getId()).orElseThrow().getPerson()).isNull();

        // A later run (MAX_UNGROUPED_FACES_PER_RUN's own comment: "a backlog beyond this is simply
        // picked up by later runs") picks up the backlog once a 3rd matching face exists.
        FaceEntity face3 = saveFace(savePhoto(space, testUser, t.plusMinutes(2)), direction);
        runAs(testUser, () -> faceRecognitionService.groupFaces());

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

        runAs(testUser, () -> faceRecognitionService.groupFaces());

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

    // Covers the FaceStatus.NEW/OLD split added to stop groupFacesForUser re-running the expensive
    // existing-person match (findBestMatchForFace, checked against every person) on the same
    // known-unmatched face every single run forever. A face already marked OLD (a past run already
    // checked it against every person and found nothing) must NOT get re-matched even when a person
    // that would now match it exists - only a genuinely NEW face gets that analysis. The OLD face
    // still isn't lost, though: it remains a person-null clustering candidate (see
    // aRealPersonAppearsOnlyOnceAThirdMatchingFaceExistsAndPicksUpTheBacklogOnALaterRun above for
    // that half of the contract) - this test only exercises the existing-person-matching skip.
    @Test
    void anOldFaceIsNeverReMatchedAgainstAnExistingPersonButANewOneStillIs() throws Exception {
        UserEntity testUser = createTestUser();
        SpaceEntity space = spaceService.getDefaultSpaceForUser(testUser);
        LocalDateTime t = LocalDateTime.of(2024, 8, 1, 12, 0);
        float[] direction = {0f, 0f, 1f};

        // A real person, built directly (not via groupFaces()) so it already exists before either
        // face below is ever analysed.
        FaceEntity personFace = saveFace(savePhoto(space, testUser, t), direction);
        runAs(testUser, () -> personRecognitionService.addPerson(List.of(personFace)));
        UUID personId = faceRepository.findById(personFace.getId()).orElseThrow().getPerson().getId();

        // Same direction as personFace - would clear THRESHOLD easily - but marked OLD up front, as
        // if a past run had already tried and failed to match it (before this person existed).
        FaceEntity oldFace = saveFace(savePhoto(space, testUser, t.plusMinutes(1)), direction);
        oldFace.setStatus(FaceStatus.OLD);
        faceRepository.save(oldFace);

        // Genuinely NEW, same direction, same person to match against.
        FaceEntity newFace = saveFace(savePhoto(space, testUser, t.plusMinutes(2)), direction);

        runAs(testUser, () -> faceRecognitionService.groupFaces());

        assertThat(faceRepository.findById(oldFace.getId()).orElseThrow().getPerson())
                .as("an OLD face must not be re-matched against an existing person")
                .isNull();
        assertThat(faceRepository.findById(newFace.getId()).orElseThrow().getPerson())
                .as("a NEW face must still be matched against an existing person")
                .isNotNull();
        assertThat(faceRepository.findById(newFace.getId()).orElseThrow().getPerson().getId()).isEqualTo(personId);
    }
}
