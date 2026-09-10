package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PersonMergeSuggestionEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.request.FaceRenameRequest;
import org.iphoto.model.request.PersonMergeRequest;
import org.iphoto.model.request.PersonRenameRequest;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonMergeSuggestionRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through FaceController's real endpoints - FaceRepository/PersonRepository's plain CRUD
// methods used by FaceServiceImpl aren't @MainSpaceLimited, but renamePerson/setFacePerson hit
// PersonRepository.findByNameIgnoreCase(AndIdNot) which IS, so a real authenticated request is
// used throughout for consistency. Faces are inserted directly via FaceRepository rather than
// through real face detection (FaceDetectionService is out of scope for this suite, and a
// synthetic solid-color test JPEG has no detectable face anyway).
class FaceServiceTest extends AbstractIntegrationTest {

    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private FaceRepository faceRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FaceService faceService;
    @Autowired
    private PersonMergeSuggestionRepository personMergeSuggestionRepository;

    private PhotoEntity savePhoto(SpaceEntity space, UserEntity user) {
        String seed = UUID.randomUUID().toString();
        return photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID())
                .fileName(seed + ".jpg")
                .fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("image/jpeg")
                .user(user)
                .space(space)
                .originalPath("/tmp/" + seed + ".jpg")
                .dateTaken(LocalDateTime.of(2024, 6, 1, 0, 0))
                .isFavorite(false)
                .isGarbage(false)
                .build());
    }

    private FaceEntity saveFace(PhotoEntity photo) {
        return faceRepository.save(FaceEntity.builder()
                .photo(photo).x(1).y(1).width(50).height(50)
                .embedding(EmbeddingCodec.toBytes(new float[]{0.1f, 0.2f}))
                .build());
    }

    @Test
    void getFacesForPhotoAndDeleteFaceWorkThroughTheirEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        FaceEntity face = saveFace(photo);

        MvcResult listResult = mockMvc.perform(get("/api/faces/photo/{id}", photo.getId()).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode faces = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(faces).anyMatch(f -> f.get("id").asText().equals(face.getId().toString()));

        mockMvc.perform(delete("/api/faces/{id}", face.getId()).session(session))
                .andExpect(status().isNoContent());
        assertThat(faceRepository.findById(face.getId())).isEmpty();
    }

    @Test
    void getPersonsGetPersonAndRenamePersonWorkThroughTheirEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        FaceEntity face = saveFace(photo);
        PersonEntity person = personRepository.save(PersonEntity.builder().space(space).name("Alice").faces(new ArrayList<>(List.of(face))).build());
        face.setPerson(person);
        faceRepository.save(face);

        MvcResult listResult = mockMvc.perform(get("/api/faces/persons").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode personsPage = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(personsPage.get("content")).anyMatch(p -> p.get("id").asText().equals(person.getId().toString()));

        mockMvc.perform(get("/api/faces/person/{id}", person.getId()).session(session))
                .andExpect(status().isOk());

        MvcResult renameResult = mockMvc.perform(put("/api/faces/person/{id}", person.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PersonRenameRequest("Alicia", false, null))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(renameResult.getResponse().getContentAsString()).get("name").asText()).isEqualTo("Alicia");
    }

    @Test
    void renamePersonToACollidingNameReturns409WithTheCandidates() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        PersonEntity existing = personRepository.save(PersonEntity.builder().space(space).name("Bob").faces(new ArrayList<>()).build());
        PersonEntity toRename = personRepository.save(PersonEntity.builder().space(space).name("Carl").faces(new ArrayList<>()).build());

        MvcResult result = mockMvc.perform(put("/api/faces/person/{id}", toRename.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PersonRenameRequest("Bob", false, null))))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode candidates = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(candidates).anyMatch(c -> c.get("id").asText().equals(existing.getId().toString()));

        // Confirmed retry, folding the candidate in - fixed direction (toRename is always kept).
        mockMvc.perform(put("/api/faces/person/{id}", toRename.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PersonRenameRequest("Bob", true, List.of(existing.getId())))))
                .andExpect(status().isOk());
        assertThat(personRepository.findById(existing.getId())).isEmpty();
        assertThat(personRepository.findById(toRename.getId())).isPresent();
    }

    @Test
    void personDaysAndPhotosForDayWorkThroughTheirEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        FaceEntity face = saveFace(photo);
        PersonEntity person = personRepository.save(PersonEntity.builder().space(space).name("Dana").faces(new ArrayList<>(List.of(face))).build());
        face.setPerson(person);
        faceRepository.save(face);

        MvcResult daysResult = mockMvc.perform(get("/api/faces/person/{id}/days", person.getId()).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode days = objectMapper.readTree(daysResult.getResponse().getContentAsString());
        assertThat(days).isNotEmpty();
        String day = days.get(0).get("day").asText();

        mockMvc.perform(get("/api/faces/person/{id}/photos/day/{day}", person.getId(), day).session(session))
                .andExpect(status().isOk());

        MvcResult facesResult = mockMvc.perform(get("/api/faces/person/{id}/faces", person.getId()).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode personFaces = objectMapper.readTree(facesResult.getResponse().getContentAsString());
        assertThat(personFaces.get("content")).anyMatch(f -> f.get("id").asText().equals(face.getId().toString()));
    }

    @Test
    void unlinkPersonFromPhotoAndUnlinkFaceFromPersonDetachWithoutDeletingTheFace() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        FaceEntity face = saveFace(photo);
        PersonEntity person = personRepository.save(PersonEntity.builder().space(space).name("Eve").faces(new ArrayList<>(List.of(face))).build());
        face.setPerson(person);
        faceRepository.save(face);

        mockMvc.perform(delete("/api/faces/person/{personId}/photo/{photoId}", person.getId(), photo.getId()).session(session))
                .andExpect(status().isNoContent());
        assertThat(faceRepository.findById(face.getId()).orElseThrow().getPerson()).isNull();

        // Re-link, then unlinkFaceFromPerson (single-face variant).
        face.setPerson(person);
        faceRepository.save(face);
        mockMvc.perform(delete("/api/faces/{id}/person", face.getId()).session(session))
                .andExpect(status().isNoContent());
        assertThat(faceRepository.findById(face.getId())).isPresent();
        assertThat(faceRepository.findById(face.getId()).orElseThrow().getPerson()).isNull();
    }

    @Test
    void setFacePersonRenamesOrMergesDependingOnCollision() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        FaceEntity face = saveFace(photo);

        // No existing person with this name yet - creates a brand-new one for just this face.
        MvcResult firstResult = mockMvc.perform(put("/api/faces/{id}/person", face.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Frank", false))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("personName").asText()).isEqualTo("Frank");

        PersonEntity otherPerson = personRepository.save(PersonEntity.builder().space(space).name("Gina").faces(new ArrayList<>()).build());
        FaceEntity secondFace = saveFace(savePhoto(space, admin));

        mockMvc.perform(put("/api/faces/{id}/person", secondFace.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Gina", false))))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/faces/{id}/person", secondFace.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Gina", true))))
                .andExpect(status().isOk());
        assertThat(faceRepository.findById(secondFace.getId()).orElseThrow().getPerson().getId()).isEqualTo(otherPerson.getId());
    }

    @Test
    void mergePersonsFoldsTheSmallerPersonIntoTheLarger() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        FaceEntity biggerFace1 = saveFace(photo);
        FaceEntity biggerFace2 = saveFace(photo);
        PersonEntity bigger = personRepository.save(PersonEntity.builder().space(space).name("Big").faces(new ArrayList<>(List.of(biggerFace1, biggerFace2))).build());
        biggerFace1.setPerson(bigger);
        biggerFace2.setPerson(bigger);
        faceRepository.saveAll(List.of(biggerFace1, biggerFace2));

        FaceEntity smallerFace = saveFace(photo);
        PersonEntity smaller = personRepository.save(PersonEntity.builder().space(space).name("Small").faces(new ArrayList<>(List.of(smallerFace))).build());
        smallerFace.setPerson(smaller);
        faceRepository.save(smallerFace);

        MvcResult result = mockMvc.perform(post("/api/faces/persons/merge").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PersonMergeRequest(List.of(bigger.getId(), smaller.getId())))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText()).isEqualTo(bigger.getId().toString());
        assertThat(personRepository.findById(smaller.getId())).isEmpty();
        // All three faces (biggerFace1, biggerFace2, smallerFace) live on the same single `photo` -
        // photoCount counts DISTINCT photos, so the merged person should show 1, not 3.
        assertThat(personRepository.findById(bigger.getId()).orElseThrow().getPhotoCount()).isEqualTo(1);
    }

    @Test
    void photoCountIsRecalculatedAsFacesAreAttachedAndDetached() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(space, admin);
        FaceEntity face = saveFace(photo);

        // setFacePerson's new-person branch: photoCount starts at 1 (one photo, one face).
        mockMvc.perform(put("/api/faces/{id}/person", face.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Hank", false))))
                .andExpect(status().isOk());
        // findByNameIgnoreCase is @MainSpaceLimited - needs an authenticated SecurityContextHolder
        // principal even for a direct repository call, not just the real mockMvc requests above.
        PersonEntity person = runAsAdmin(() -> personRepository.findByNameIgnoreCase("Hank").orElseThrow());
        assertThat(person.getPhotoCount()).isEqualTo(1);

        // unlinkFaceFromPerson detaches the face - photoCount drops back to 0.
        mockMvc.perform(delete("/api/faces/{id}/person", face.getId()).session(session))
                .andExpect(status().isNoContent());
        assertThat(personRepository.findById(person.getId()).orElseThrow().getPhotoCount()).isEqualTo(0);

        // Re-attach via setFacePerson's existing-person branch (confirmMerge=true - "Hank" already
        // exists from above, and the face is currently unassigned, so this is the same
        // isDifferentPerson/confirm-collision path setFacePersonRenamesOrMergesDependingOnCollision
        // exercises), then delete the face outright - photoCount must follow a face DELETION too,
        // not just an unlink.
        mockMvc.perform(put("/api/faces/{id}/person", face.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Hank", true))))
                .andExpect(status().isOk());
        assertThat(personRepository.findById(person.getId()).orElseThrow().getPhotoCount()).isEqualTo(1);

        mockMvc.perform(delete("/api/faces/{id}", face.getId()).session(session))
                .andExpect(status().isNoContent());
        assertThat(personRepository.findById(person.getId()).orElseThrow().getPhotoCount()).isEqualTo(0);
    }

    @Test
    void getPersonsSortsNamedFirstThenPhotoCountDescendingRegardlessOfRequestedSort() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);

        PersonEntity unnamedHighCount = personRepository.save(PersonEntity.builder().space(space).faces(new ArrayList<>()).photoCount(100).build());
        PersonEntity namedLowCount = personRepository.save(PersonEntity.builder().space(space).name("Zed").faces(new ArrayList<>()).photoCount(1).build());
        PersonEntity namedHighCount = personRepository.save(PersonEntity.builder().space(space).name("Amy").faces(new ArrayList<>()).photoCount(50).build());

        // Deliberately asks for the opposite sort via ?sort= - the server-side fixed order must win.
        MvcResult result = mockMvc.perform(get("/api/faces/persons").session(session).param("size", "500").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        List<String> orderedIds = new ArrayList<>();
        content.forEach(p -> orderedIds.add(p.get("id").asText()));

        int namedHighIdx = orderedIds.indexOf(namedHighCount.getId().toString());
        int namedLowIdx = orderedIds.indexOf(namedLowCount.getId().toString());
        int unnamedIdx = orderedIds.indexOf(unnamedHighCount.getId().toString());

        assertThat(namedHighIdx).isGreaterThanOrEqualTo(0);
        assertThat(namedLowIdx).isGreaterThanOrEqualTo(0);
        assertThat(unnamedIdx).isGreaterThanOrEqualTo(0);
        assertThat(namedHighIdx).isLessThan(namedLowIdx); // named, higher photoCount first
        assertThat(namedLowIdx).isLessThan(unnamedIdx); // named (even low count) before unnamed
    }

    // Regression test for the merge-suggestions endpoint being changed from one flat, unpaginated
    // list (every suggestion's two face thumbnails fetched up front, even though the popup only
    // ever shows one pair at a time - a real library turned this into a multi-megabyte,
    // multi-second response) to a cheap count endpoint plus a real size=1-paginated list.
    @Test
    void mergeSuggestionsAreCountedSeparatelyAndPagedOneAtATimeMostSimilarFirst() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PersonEntity personA = personRepository.save(PersonEntity.builder().space(space).name("Ann").faces(new ArrayList<>()).build());
        PersonEntity personB = personRepository.save(PersonEntity.builder().space(space).name("Ben").faces(new ArrayList<>()).build());
        PersonEntity personC = personRepository.save(PersonEntity.builder().space(space).name("Cid").faces(new ArrayList<>()).build());
        PersonEntity personD = personRepository.save(PersonEntity.builder().space(space).name("Dee").faces(new ArrayList<>()).build());

        PersonMergeSuggestionEntity lessSimilar = personMergeSuggestionRepository.save(PersonMergeSuggestionEntity.builder()
                .personA(personA).personB(personB).spaceId(space.getId()).similarity(0.78).build());
        PersonMergeSuggestionEntity moreSimilar = personMergeSuggestionRepository.save(PersonMergeSuggestionEntity.builder()
                .personA(personC).personB(personD).spaceId(space.getId()).similarity(0.91).build());

        MvcResult countResult = mockMvc.perform(get("/api/faces/persons/merge-suggestions/count").session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(countResult.getResponse().getContentAsString()).get("count").asInt()).isEqualTo(2);

        MvcResult firstPage = mockMvc.perform(get("/api/faces/persons/merge-suggestions").session(session)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(firstBody.get("content")).hasSize(1);
        assertThat(firstBody.get("content").get(0).get("id").asText()).isEqualTo(moreSimilar.getId().toString());
        assertThat(firstBody.get("totalElements").asInt()).isEqualTo(2);

        MvcResult secondPage = mockMvc.perform(get("/api/faces/persons/merge-suggestions").session(session)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondBody = objectMapper.readTree(secondPage.getResponse().getContentAsString());
        assertThat(secondBody.get("content")).hasSize(1);
        assertThat(secondBody.get("content").get(0).get("id").asText()).isEqualTo(lessSimilar.getId().toString());
    }

    @Test
    void findByIdFindsAFaceDirectly() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        FaceEntity face = saveFace(savePhoto(space, admin));

        Optional<FaceEntity> found = faceService.findById(admin, face.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(face.getId());
        assertThat(faceService.findById(admin, UUID.randomUUID())).isEmpty();
    }
}
