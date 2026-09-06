package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.request.FaceRenameRequest;
import org.iphoto.model.request.PersonMergeRequest;
import org.iphoto.model.request.PersonRenameRequest;
import org.iphoto.repository.FaceRepository;
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
