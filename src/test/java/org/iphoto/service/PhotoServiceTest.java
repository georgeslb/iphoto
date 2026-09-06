package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.*;
import org.iphoto.model.Photo;
import org.iphoto.model.request.CheckHashesRequest;
import org.iphoto.model.request.MenuSelectionRequest;
import org.iphoto.model.request.PhotoSelectionRequest;
import org.iphoto.model.request.UpdatePhotoDateRequest;
import org.iphoto.repository.*;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven mostly through PhotoController's real endpoints - a couple of methods with no endpoint
// of their own (findByPerson/getPersonDailyPhotoCounts/getPersonPhotosForDay, delegated to by
// FaceService/FaceController - see FaceServiceTest for the same flow exercised end-to-end through
// its own endpoints; permanentDeleteAssets(List<UUID>) - "called only from system", used by
// TaskWorkerManager's PERMANENT_DELETE handling) are called directly. PhotoRepository has no
// @MainSpaceLimited methods, so direct calls need no authenticated context either.
class PhotoServiceTest extends AbstractIntegrationTest {

    @Autowired
    private PhotoService photoService;
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

    @Test
    void getPhotosGetDaysAndGetPhotosForDayReflectAnUploadedPhoto() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "Feed-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);

        MvcResult photosResult = mockMvc.perform(get("/api/photos/space/{spaceId}", spaceId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode photos = objectMapper.readTree(photosResult.getResponse().getContentAsString());
        assertThat(photos.get("content")).anyMatch(p -> p.get("id").asText().equals(photoId.toString()));

        MvcResult daysResult = mockMvc.perform(get("/api/photos/space/{spaceId}/days", spaceId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode days = objectMapper.readTree(daysResult.getResponse().getContentAsString());
        assertThat(days).isNotEmpty();
        String day = days.get(0).get("day").asText();

        MvcResult dayPhotosResult = mockMvc.perform(get("/api/photos/space/{spaceId}/day/{day}", spaceId, day).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode dayPhotos = objectMapper.readTree(dayPhotosResult.getResponse().getContentAsString());
        assertThat(dayPhotos.get("content")).anyMatch(p -> p.get("id").asText().equals(photoId.toString()));

        mockMvc.perform(get("/api/photos/{id}", photoId).session(session)).andExpect(status().isOk());
    }

    @Test
    void getMemoriesReturnsAPhotoTakenExactlyOneMonthAgo() throws Exception {
        MockHttpSession session = loginAsAdmin();
        // getMemories is scoped to the caller's MAIN space only (see PhotoController#getMemories) -
        // a photo left in the space it was uploaded into would never show up here.
        UUID spaceId = createSpace(session, "Memories-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        mockMvc.perform(post("/api/photos/main").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        photoService.updatePhotoDate(photoId, LocalDateTime.now().minusMonths(1));

        MvcResult result = mockMvc.perform(get("/api/photos/memories").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode memories = objectMapper.readTree(result.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode block : memories) {
            for (JsonNode photo : block.get("photos")) {
                if (photo.get("id").asText().equals(photoId.toString())) found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void moveToGarbageRestoreAndPermanentlyDeleteGoThroughTheirEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "GarbageFlow-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);

        mockMvc.perform(post("/api/photos/garbage").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        assertThat(photoRepository.findById(photoId).orElseThrow().getIsGarbage()).isTrue();

        MvcResult garbageDaysResult = mockMvc.perform(get("/api/photos/garbage/days").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode garbageDays = objectMapper.readTree(garbageDaysResult.getResponse().getContentAsString());
        assertThat(garbageDays).isNotEmpty();
        String day = garbageDays.get(0).get("day").asText();
        MvcResult garbageDayPhotosResult = mockMvc.perform(get("/api/photos/garbage/day/{day}", day).session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(garbageDayPhotosResult.getResponse().getContentAsString()).get("content"))
                .anyMatch(p -> p.get("id").asText().equals(photoId.toString()));

        mockMvc.perform(post("/api/photos/garbage/restore").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        assertThat(photoRepository.findById(photoId).orElseThrow().getIsGarbage()).isFalse();

        mockMvc.perform(delete("/api/photos/garbage/permanent").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isNoContent());
        assertThat(photoRepository.findById(photoId)).isEmpty();
    }

    @Test
    void uploadingTheSameContentTwiceReportsADuplicate() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "DupUpload-" + UUID.randomUUID());
        byte[] jpegBytes = generateTestJpeg();

        String firstUploadId = UUID.randomUUID().toString();
        MvcResult firstResult = mockMvc.perform(multipart("/api/upload/chunk")
                        .file(new MockMultipartFile("chunk", "a.jpg", "image/jpeg", jpegBytes))
                        .param("uploadId", firstUploadId).param("fileName", "a.jpg")
                        .param("fileSize", String.valueOf(jpegBytes.length)).param("chunkIndex", "0")
                        .param("totalChunks", "1").param("contentType", "image/jpeg")
                        .param("spaceId", spaceId.toString()).session(session))
                .andExpect(status().isOk())
                .andReturn();
        UUID firstTaskId = UUID.fromString(objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("task").get("id").asText());
        waitForTaskCompletion(session, firstTaskId);

        String secondUploadId = UUID.randomUUID().toString();
        MvcResult secondResult = mockMvc.perform(multipart("/api/upload/chunk")
                        .file(new MockMultipartFile("chunk", "a.jpg", "image/jpeg", jpegBytes))
                        .param("uploadId", secondUploadId).param("fileName", "a.jpg")
                        .param("fileSize", String.valueOf(jpegBytes.length)).param("chunkIndex", "0")
                        .param("totalChunks", "1").param("contentType", "image/jpeg")
                        .param("spaceId", spaceId.toString()).session(session))
                .andExpect(status().isOk())
                .andReturn();
        UUID secondTaskId = UUID.fromString(objectMapper.readTree(secondResult.getResponse().getContentAsString()).get("task").get("id").asText());

        // Not waitForTaskCompletion - its terminal state here is IGNORED (a content-duplicate),
        // never COMPLETED, so that helper would just spin until its own timeout.
        taskWorkerManager.pollPendingTasks();
        String status = "PENDING";
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult taskResult = mockMvc.perform(get("/api/tasks/{id}", secondTaskId).session(session))
                    .andExpect(status().isOk())
                    .andReturn();
            status = objectMapper.readTree(taskResult.getResponse().getContentAsString()).get("status").asText();
            if ("IGNORED".equals(status)) break;
            Thread.sleep(250);
        }
        assertThat(status).isEqualTo("IGNORED");
    }

    @Test
    void updatePhotoDateThenEditPhotoInPlaceGoThroughTheirEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "EditFlow-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);

        mockMvc.perform(patch("/api/photos/{id}/date", photoId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdatePhotoDateRequest("2022-05-01T00:00"))))
                .andExpect(status().isOk());
        // The Photo DTO only carries human-formatted date strings (date/fullDateTime/groupDate,
        // see model.Photo), not a raw dateTaken field - check the actual persisted value instead.
        assertThat(photoRepository.findById(photoId).orElseThrow().getDateTaken()).isEqualTo(LocalDateTime.parse("2022-05-01T00:00"));

        byte[] newBytes = generateTestJpeg();
        mockMvc.perform(multipart("/api/photos/{id}/edit", photoId)
                        .file(new MockMultipartFile("file", "edited.jpg", "image/jpeg", newBytes))
                        .param("saveAsNew", "false")
                        .session(session))
                .andExpect(status().isOk());
    }

    @Test
    void getAlbumsByPhotoAndGetSelectionAndCheckExistingHashesWorkThroughTheirEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "Misc-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        mockMvc.perform(post("/api/photos/main").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        UUID albumId = createAlbum(session);
        mockMvc.perform(post("/api/albums/{id}/photos", albumId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("photoIds", List.of(photoId)))))
                .andExpect(status().isOk());

        MvcResult albumsResult = mockMvc.perform(get("/api/photos/{id}/albums", photoId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(albumsResult.getResponse().getContentAsString()))
                .anyMatch(a -> a.get("id").asText().equals(albumId.toString()));

        MvcResult selectionResult = mockMvc.perform(post("/api/photos/selected").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PhotoSelectionRequest(new MenuSelectionRequest("lightbox", List.of(), List.of()), List.of(photoId)))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(selectionResult.getResponse().getContentAsString()).get("canBeModified").asBoolean()).isTrue();

        String hash = photoRepository.findById(photoId).orElseThrow().getFileHash();
        MvcResult hashesResult = mockMvc.perform(post("/api/photos/check-hashes").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CheckHashesRequest(List.of(hash, "not-a-real-hash")))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode existingHashes = objectMapper.readTree(hashesResult.getResponse().getContentAsString()).get("existingHashes");
        assertThat(existingHashes).extracting(JsonNode::asText).contains(hash).doesNotContain("not-a-real-hash");
    }

    @Test
    void moveAssetsToMainMovesAPhotoOutOfItsOriginalSpace() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "MoveToMain-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);

        mockMvc.perform(post("/api/photos/main").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());

        MvcResult spaceResult = mockMvc.perform(get("/api/photos/{id}/space", photoId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(spaceResult.getResponse().getContentAsString()).get("name").asText()).isEqualTo("MAIN");
    }

    @Test
    void permanentDeleteAssetsSystemVariantDeletesRegardlessOfOwner() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        String seed = UUID.randomUUID().toString();
        PhotoEntity photo = photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID()).fileName(seed + ".jpg").fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("image/jpeg").user(admin).space(space).originalPath("/tmp/does-not-exist-" + seed + ".jpg")
                .isFavorite(false).isGarbage(false).build());

        photoService.permanentDeleteAssets(List.of(photo.getId()));

        assertThat(photoRepository.findById(photo.getId())).isEmpty();
    }

    @Test
    void findByPersonAndPersonDailyPhotoCountsAndPersonPhotosForDayFindThePhotoThroughItsFace() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        String seed = UUID.randomUUID().toString();
        PhotoEntity photo = photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID()).fileName(seed + ".jpg").fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("image/jpeg").user(admin).space(space).originalPath("/tmp/" + seed + ".jpg")
                .dateTaken(LocalDateTime.of(2024, 8, 1, 0, 0)).isFavorite(false).isGarbage(false).build());
        FaceEntity face = faceRepository.save(FaceEntity.builder().photo(photo).x(0).y(0).width(10).height(10)
                .embedding(EmbeddingCodec.toBytes(new float[]{1f})).build());
        PersonEntity person = personRepository.save(PersonEntity.builder().space(space).name("Henry").faces(new ArrayList<>(List.of(face))).build());
        face.setPerson(person);
        faceRepository.save(face);

        Page<Photo> byPerson = photoService.findByPerson(person, Pageable.ofSize(10));
        assertThat(byPerson.getContent()).anyMatch(p -> p.id().equals(photo.getId().toString()));

        assertThat(photoService.getPersonDailyPhotoCounts(person)).anyMatch(d -> d.day().equals(java.time.LocalDate.of(2024, 8, 1)));

        Page<Photo> photosForDay = photoService.getPersonPhotosForDay(person, java.time.LocalDate.of(2024, 8, 1), Pageable.ofSize(10));
        assertThat(photosForDay.getContent()).anyMatch(p -> p.id().equals(photo.getId().toString()));
    }

    @Test
    void deletePhotoInSpaceRemovesEveryPhotoInIt() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "SpaceWipe-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();

        photoService.deletePhotoInSpace(admin, spaceId);

        assertThat(photoRepository.findById(photoId)).isEmpty();
    }

    private UUID createAlbum(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/albums")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new org.iphoto.model.request.AlbumCreateRequest("PhotoTest-Album-" + UUID.randomUUID(), "desc", null))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
