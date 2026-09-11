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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through FaceController's real endpoints. Faces and people are per-user, scoped to each
// user's own MAIN space (PersonEntity.space / FaceServiceImpl.getPersons(user) /
// PersonRepository.findById is @MainSpaceLimited) - the admin account is an ops account that
// deliberately never touches this feature, so every test here runs as a FRESH, dedicated
// non-admin user (newUser()) and puts its fixtures in THAT user's own MAIN space. Direct
// repository reads made from the bare test thread for assertions go through runAs(user, ...) so
// the @MainSpaceLimited aspect has a principal to resolve a space from (the same reason
// AbstractIntegrationTest.runAsAdmin exists). Faces are inserted directly via FaceRepository
// rather than through real face detection (FaceDetectionService is out of scope for this suite,
// and a synthetic solid-color test JPEG has no detectable face anyway).
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
    private UserService userService;
    @Autowired
    private FaceService faceService;
    @Autowired
    private PersonMergeSuggestionRepository personMergeSuggestionRepository;

    // A fresh regular user, with its own private MAIN space (userService.createUser creates one) -
    // brand new, so its people/faces are fully isolated from every other test class's leftovers in
    // the shared test database.
    private UserEntity newUser() {
        return userService.createUser("face-test-" + UUID.randomUUID(), "password", "USER");
    }

    private MockHttpSession sessionFor(UserEntity user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/login")
                        .param("username", user.getUsername())
                        .param("password", "password"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // Pushes `user` as the current principal for the duration of `action`, then restores whatever
    // was there - lets a direct call to a @MainSpaceLimited repository method (which reads the
    // principal off SecurityContextHolder to pick a space) work from the bare test thread, where
    // no security filter chain has run. Mirrors AbstractIntegrationTest.runAsAdmin.
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
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        PhotoEntity photo = savePhoto(space, user);
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
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        PhotoEntity photo = savePhoto(space, user);
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
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
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
        assertThat(runAs(user, () -> personRepository.findByIdInMainSpace(existing.getId()))).isEmpty();
        assertThat(runAs(user, () -> personRepository.findByIdInMainSpace(toRename.getId()))).isPresent();
    }

    @Test
    void personDaysAndPhotosForDayWorkThroughTheirEndpoints() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        PhotoEntity photo = savePhoto(space, user);
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
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        PhotoEntity photo = savePhoto(space, user);
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
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        PhotoEntity photo = savePhoto(space, user);
        FaceEntity face = saveFace(photo);

        // No existing person with this name yet - creates a brand-new one for just this face.
        MvcResult firstResult = mockMvc.perform(put("/api/faces/{id}/person", face.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Frank", false))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("personName").asText()).isEqualTo("Frank");

        PersonEntity otherPerson = personRepository.save(PersonEntity.builder().space(space).name("Gina").faces(new ArrayList<>()).build());
        FaceEntity secondFace = saveFace(savePhoto(space, user));

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
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        PhotoEntity photo = savePhoto(space, user);
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
        assertThat(runAs(user, () -> personRepository.findByIdInMainSpace(smaller.getId()))).isEmpty();
        // All three faces (biggerFace1, biggerFace2, smallerFace) live on the same single `photo` -
        // photoCount counts DISTINCT photos, so the merged person should show 1, not 3.
        assertThat(runAs(user, () -> personRepository.findByIdInMainSpace(bigger.getId())).orElseThrow().getPhotoCount()).isEqualTo(1);
    }

    @Test
    void photoCountIsRecalculatedAsFacesAreAttachedAndDetached() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        PhotoEntity photo = savePhoto(space, user);
        FaceEntity face = saveFace(photo);

        // setFacePerson's new-person branch: photoCount starts at 1 (one photo, one face).
        mockMvc.perform(put("/api/faces/{id}/person", face.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Hank", false))))
                .andExpect(status().isOk());
        // findByNameIgnoreCase / findById are @MainSpaceLimited - a direct repository call from
        // this bare test thread still needs a principal to resolve the space from.
        PersonEntity person = runAs(user, () -> personRepository.findByNameIgnoreCase("Hank").orElseThrow());
        assertThat(person.getPhotoCount()).isEqualTo(1);

        // unlinkFaceFromPerson detaches the face - photoCount drops back to 0.
        mockMvc.perform(delete("/api/faces/{id}/person", face.getId()).session(session))
                .andExpect(status().isNoContent());
        assertThat(runAs(user, () -> personRepository.findByIdInMainSpace(person.getId())).orElseThrow().getPhotoCount()).isEqualTo(0);

        // Re-attach via setFacePerson's existing-person branch (confirmMerge=true - "Hank" already
        // exists from above, and the face is currently unassigned, so this is the same
        // isDifferentPerson/confirm-collision path setFacePersonRenamesOrMergesDependingOnCollision
        // exercises), then delete the face outright - photoCount must follow a face DELETION too,
        // not just an unlink.
        mockMvc.perform(put("/api/faces/{id}/person", face.getId()).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FaceRenameRequest("Hank", true))))
                .andExpect(status().isOk());
        assertThat(runAs(user, () -> personRepository.findByIdInMainSpace(person.getId())).orElseThrow().getPhotoCount()).isEqualTo(1);

        mockMvc.perform(delete("/api/faces/{id}", face.getId()).session(session))
                .andExpect(status().isNoContent());
        assertThat(runAs(user, () -> personRepository.findByIdInMainSpace(person.getId())).orElseThrow().getPhotoCount()).isEqualTo(0);
    }

    @Test
    void getPersonsSortsNamedFirstThenPhotoCountDescendingRegardlessOfRequestedSort() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);

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
        // Scoped to this user's own space: another test class's leftover people never leak in.
        assertThat(orderedIds).containsExactlyInAnyOrder(
                namedHighCount.getId().toString(), namedLowCount.getId().toString(), unnamedHighCount.getId().toString());
    }

    // Regression test for the merge-suggestions endpoint being changed from one flat, unpaginated
    // list (every suggestion's two face thumbnails fetched up front, even though the popup only
    // ever shows one pair at a time - a real library turned this into a multi-megabyte,
    // multi-second response) to a cheap count endpoint plus a real size=1-paginated list.
    @Test
    void mergeSuggestionsAreCountedSeparatelyAndPagedOneAtATimeMostSimilarFirst() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
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
        UserEntity user = newUser();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        FaceEntity face = saveFace(savePhoto(space, user));

        Optional<FaceEntity> found = faceService.findById(user, face.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(face.getId());
        assertThat(faceService.findById(user, UUID.randomUUID())).isEmpty();
    }
}
