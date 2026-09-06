package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.CoverFaceOrigin;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEmbeddingBucketEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonEmbeddingBucketRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - PersonRecognitionService has no HTTP endpoint of its own (it's
// consumed internally by FaceRecognitionService/FaceService/FaceDetectionService, all excluded or
// tested elsewhere). None of PersonRepository/FaceRepository/PersonEmbeddingBucketRepository's
// methods used here are @MainSpaceLimited (only PersonRepository's findAll/findByNameIgnoreCase*
// overloads are, not used by this service), so no authenticated context is needed.
class PersonRecognitionServiceTest extends AbstractIntegrationTest {

    @Autowired
    private PersonRecognitionService personRecognitionService;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private FaceRepository faceRepository;
    @Autowired
    private PersonEmbeddingBucketRepository personEmbeddingBucketRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

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
    void updatePersonAttachesTheFaceAndCreatesAnEmbeddingBucket() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin, LocalDateTime.of(2024, 1, 1, 0, 0));
        FaceEntity face = saveFace(photo, 1.0f);
        PersonEntity person = personRepository.save(PersonEntity.builder().space(space).faces(new ArrayList<>()).build());

        personRecognitionService.updatePerson(person, face);

        FaceEntity reloadedFace = faceRepository.findById(face.getId()).orElseThrow();
        assertThat(reloadedFace.getPerson().getId()).isEqualTo(person.getId());
        PersonEntity reloadedPerson = personRepository.findById(person.getId()).orElseThrow();
        assertThat(reloadedPerson.getCoverFace()).isNotNull();
        List<PersonEmbeddingBucketEntity> buckets = personEmbeddingBucketRepository.findByPersonId(person.getId());
        assertThat(buckets).hasSize(1);
        assertThat(buckets.getFirst().getFaceCount()).isEqualTo(1);
    }

    @Test
    void addPersonCreatesABrandNewPersonOwningEveryGivenFace() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin, LocalDateTime.of(2024, 2, 2, 0, 0));
        FaceEntity face1 = saveFace(photo, 2.0f);
        FaceEntity face2 = saveFace(photo, 2.1f);

        personRecognitionService.addPerson(admin, List.of(face1, face2));

        FaceEntity reloaded1 = faceRepository.findById(face1.getId()).orElseThrow();
        FaceEntity reloaded2 = faceRepository.findById(face2.getId()).orElseThrow();
        assertThat(reloaded1.getPerson()).isNotNull();
        assertThat(reloaded1.getPerson().getId()).isEqualTo(reloaded2.getPerson().getId());
        assertThat(personEmbeddingBucketRepository.findByPersonId(reloaded1.getPerson().getId())).hasSize(1);
    }

    @Test
    void mergePersonsFoldsTheSmallerIntoTheLargerAndDeletesTheSource() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin, LocalDateTime.of(2024, 3, 1, 0, 0));

        FaceEntity biggerFace1 = saveFace(photo, 3.0f);
        FaceEntity biggerFace2 = saveFace(photo, 3.1f);
        personRecognitionService.addPerson(admin, List.of(biggerFace1, biggerFace2));
        UUID biggerPersonId = faceRepository.findById(biggerFace1.getId()).orElseThrow().getPerson().getId();

        FaceEntity smallerFace = saveFace(photo, 3.2f);
        personRecognitionService.addPerson(admin, List.of(smallerFace));
        UUID smallerPersonId = faceRepository.findById(smallerFace.getId()).orElseThrow().getPerson().getId();

        PersonEntity target = personRecognitionService.mergePersons(biggerPersonId, smallerPersonId);

        assertThat(target.getId()).isEqualTo(biggerPersonId);
        assertThat(personRepository.findById(smallerPersonId)).isEmpty();
        assertThat(faceRepository.findById(smallerFace.getId()).orElseThrow().getPerson().getId()).isEqualTo(biggerPersonId);
    }

    @Test
    void mergeIntoAlwaysKeepsTheExplicitTargetRegardlessOfFaceCount() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin, LocalDateTime.of(2024, 4, 1, 0, 0));

        FaceEntity targetFace = saveFace(photo, 4.0f);
        personRecognitionService.addPerson(admin, List.of(targetFace));
        UUID targetId = faceRepository.findById(targetFace.getId()).orElseThrow().getPerson().getId();

        FaceEntity sourceFace1 = saveFace(photo, 4.1f);
        FaceEntity sourceFace2 = saveFace(photo, 4.2f);
        personRecognitionService.addPerson(admin, List.of(sourceFace1, sourceFace2));
        UUID sourceId = faceRepository.findById(sourceFace1.getId()).orElseThrow().getPerson().getId();

        // Source has MORE faces than target here - mergeInto must still keep target as the keeper.
        PersonEntity result = personRecognitionService.mergeInto(targetId, sourceId);

        assertThat(result.getId()).isEqualTo(targetId);
        assertThat(personRepository.findById(sourceId)).isEmpty();
    }

    @Test
    void recalculateCoverFacePicksTheMostRecentDatedFaceAndNeverOverridesAManualChoice() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity olderPhoto = savePhoto(space, admin, LocalDateTime.of(2020, 1, 1, 0, 0));
        PhotoEntity newerPhoto = savePhoto(space, admin, LocalDateTime.of(2023, 1, 1, 0, 0));
        FaceEntity olderFace = saveFace(olderPhoto, 5.0f);
        FaceEntity newerFace = saveFace(newerPhoto, 5.1f);
        PersonEntity person = personRepository.save(PersonEntity.builder()
                .space(space).faces(new ArrayList<>(List.of(olderFace, newerFace))).build());
        olderFace.setPerson(person);
        newerFace.setPerson(person);
        faceRepository.saveAll(List.of(olderFace, newerFace));

        // recalculateCoverFace carries no @Transactional of its own (fine in production, where
        // spring.jpa.open-in-view keeps a session open for the whole request) - it reads
        // face.getPhoto().getDateTaken() off each of the person's faces, and those `photo`
        // references come back as lazy proxies once reloaded fresh here, so this whole check
        // needs one real transaction/session spanning the reload + the call, the same role
        // open-in-view plays around a real request.
        transactionTemplate.executeWithoutResult(status -> {
            PersonEntity reloaded = personRepository.findById(person.getId()).orElseThrow();
            personRecognitionService.recalculateCoverFace(reloaded);
            assertThat(reloaded.getCoverFace().getId()).isEqualTo(newerFace.getId());
            assertThat(reloaded.getCoverFaceOrigin()).isEqualTo(CoverFaceOrigin.CALCULATED);

            // A manual choice must never be silently overridden by a later automatic recalculation.
            reloaded.setCoverFace(olderFace);
            reloaded.setCoverFaceOrigin(CoverFaceOrigin.MANUAL);
            personRecognitionService.recalculateCoverFace(reloaded);
            assertThat(reloaded.getCoverFace().getId()).isEqualTo(olderFace.getId());
        });
    }

    @Test
    void addAndRemoveFaceToBucketMaintainTheRunningAverageAndCleanUpWhenEmpty() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin, LocalDateTime.of(2024, 5, 1, 0, 0));
        FaceEntity face = saveFace(photo, 6.0f);
        PersonEntity person = personRepository.save(PersonEntity.builder().space(space).faces(new ArrayList<>()).build());

        personRecognitionService.addFaceToBucket(person, face);
        List<PersonEmbeddingBucketEntity> afterAdd = personEmbeddingBucketRepository.findByPersonId(person.getId());
        assertThat(afterAdd).hasSize(1);
        assertThat(afterAdd.getFirst().getFaceCount()).isEqualTo(1);

        personRecognitionService.removeFaceFromBucket(person, face);
        assertThat(personEmbeddingBucketRepository.findByPersonId(person.getId())).isEmpty();
    }
}
