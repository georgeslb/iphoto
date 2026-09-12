package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.*;
import org.iphoto.model.Photo;
import org.iphoto.model.request.CheckHashesRequest;
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

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
    void isVideoIsDerivedFromContentTypeOnSaveAndUpdate() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(admin);
        String seed = UUID.randomUUID().toString();

        PhotoEntity image = photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID()).fileName(seed + ".jpg").fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("image/jpeg").user(admin).space(space).originalPath("/tmp/" + seed + ".jpg")
                .isFavorite(false).isGarbage(false).build());
        assertThat(image.getIsVideo()).isFalse();

        String videoSeed = UUID.randomUUID().toString();
        PhotoEntity video = photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID()).fileName(videoSeed + ".mp4").fileHash(HexFormat.of().formatHex(videoSeed.getBytes()))
                .contentType("video/mp4").user(admin).space(space).originalPath("/tmp/" + videoSeed + ".mp4")
                .isFavorite(false).isGarbage(false).build());
        assertThat(video.getIsVideo()).isTrue();

        // onUpdate re-derives it too, not just onCreate - flipping the stored contentType later
        // (e.g. a corrected upload) must not leave a stale isVideo behind.
        video.setContentType("image/png");
        PhotoEntity updated = photoRepository.save(video);
        assertThat(updated.getIsVideo()).isFalse();
    }

    @Test
    void typeFilterOnSpaceDaysAndDayEndpointsOnlyReturnsMatchingMediaType() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "TypeFilter-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        PhotoEntity photoEntity = photoRepository.findById(photoId).orElseThrow();

        // A video sibling landing in the exact same day bucket, persisted directly (skipping the
        // real upload/thumbnail pipeline, which the isVideo derivation itself doesn't need).
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findById(spaceId).orElseThrow();
        String seed = UUID.randomUUID().toString();
        PhotoEntity video = photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID()).fileName(seed + ".mp4").fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("video/mp4").user(admin).space(space).originalPath("/tmp/" + seed + ".mp4")
                .dateTaken(photoEntity.getDateTaken()).isFavorite(false).isGarbage(false).build());
        String day = photoEntity.getGroupedDay().toString();

        MvcResult photosOnly = mockMvc.perform(get("/api/photos/space/{spaceId}/day/{day}", spaceId, day)
                        .param("type", "photo").session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode photosOnlyContent = objectMapper.readTree(photosOnly.getResponse().getContentAsString()).get("content");
        assertThat(photosOnlyContent).anyMatch(p -> p.get("id").asText().equals(photoId.toString()));
        assertThat(photosOnlyContent).noneMatch(p -> p.get("id").asText().equals(video.getId().toString()));

        MvcResult videosOnly = mockMvc.perform(get("/api/photos/space/{spaceId}/day/{day}", spaceId, day)
                        .param("type", "video").session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode videosOnlyContent = objectMapper.readTree(videosOnly.getResponse().getContentAsString()).get("content");
        assertThat(videosOnlyContent).anyMatch(p -> p.get("id").asText().equals(video.getId().toString()));
        assertThat(videosOnlyContent).noneMatch(p -> p.get("id").asText().equals(photoId.toString()));

        // No type param at all - both still show up (pre-filter behavior unaffected).
        MvcResult unfiltered = mockMvc.perform(get("/api/photos/space/{spaceId}/day/{day}", spaceId, day).session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode unfilteredContent = objectMapper.readTree(unfiltered.getResponse().getContentAsString()).get("content");
        assertThat(unfilteredContent).anyMatch(p -> p.get("id").asText().equals(photoId.toString()));
        assertThat(unfilteredContent).anyMatch(p -> p.get("id").asText().equals(video.getId().toString()));

        // The day-count also respects the filter, not just the per-day content.
        MvcResult videoDays = mockMvc.perform(get("/api/photos/space/{spaceId}/days", spaceId)
                        .param("type", "video").session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode videoDaysContent = objectMapper.readTree(videoDays.getResponse().getContentAsString());
        JsonNode matchingDay = null;
        for (JsonNode d : videoDaysContent) {
            if (d.get("day").asText().equals(day)) matchingDay = d;
        }
        assertThat(matchingDay).describedAs("expected the video-only day count to include " + day).isNotNull();
        assertThat(matchingDay.get("count").asLong()).isEqualTo(1L);
    }

    @Test
    void typeFilterOnGarbageDaysAndDayEndpointsOnlyReturnsMatchingMediaType() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "GarbageTypeFilter-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        PhotoEntity photoEntity = photoRepository.findById(photoId).orElseThrow();

        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity space = spaceRepository.findById(spaceId).orElseThrow();
        String seed = UUID.randomUUID().toString();
        PhotoEntity video = photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID()).fileName(seed + ".mp4").fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("video/mp4").user(admin).space(space).originalPath("/tmp/" + seed + ".mp4")
                .dateTaken(photoEntity.getDateTaken()).isFavorite(false).isGarbage(false).build());

        mockMvc.perform(post("/api/photos/garbage").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId, video.getId()))))
                .andExpect(status().isOk());
        String day = photoEntity.getGroupedDay().toString();

        MvcResult videosOnly = mockMvc.perform(get("/api/photos/garbage/day/{day}", day)
                        .param("type", "video").session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode videosOnlyContent = objectMapper.readTree(videosOnly.getResponse().getContentAsString()).get("content");
        assertThat(videosOnlyContent).anyMatch(p -> p.get("id").asText().equals(video.getId().toString()));
        assertThat(videosOnlyContent).noneMatch(p -> p.get("id").asText().equals(photoId.toString()));

        MvcResult photosOnly = mockMvc.perform(get("/api/photos/garbage/day/{day}", day)
                        .param("type", "photo").session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode photosOnlyContent = objectMapper.readTree(photosOnly.getResponse().getContentAsString()).get("content");
        assertThat(photosOnlyContent).anyMatch(p -> p.get("id").asText().equals(photoId.toString()));
        assertThat(photosOnlyContent).noneMatch(p -> p.get("id").asText().equals(video.getId().toString()));
    }

    @Test
    void recentEndpointsSortByUploadTimeNewestFirstAndPaginateAtTwenty() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "Recent-" + UUID.randomUUID());
        UUID firstUploaded = uploadPhoto(session, spaceId);
        UUID secondUploaded = uploadPhoto(session, spaceId);

        MvcResult recentResult = mockMvc.perform(get("/api/photos/space/{spaceId}/recent", spaceId).session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode recent = objectMapper.readTree(recentResult.getResponse().getContentAsString());
        assertThat(recent.get("pageSize").asInt()).isEqualTo(20);
        // Newest-uploaded-first: the second upload must come before the first in content order.
        List<String> ids = new ArrayList<>();
        recent.get("content").forEach(p -> ids.add(p.get("id").asText()));
        assertThat(ids.indexOf(secondUploaded.toString())).isLessThan(ids.indexOf(firstUploaded.toString()));

        // Library's own /recent (Main space) - just confirm it's reachable and shaped the same way;
        // the actual sort/pagination behavior is already covered above.
        mockMvc.perform(get("/api/photos/recent").session(session)).andExpect(status().isOk());
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
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        photoService.updatePhotoDate(admin, photoId, LocalDateTime.now().minusMonths(1));

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
        taskWorkerManager.pollLightPendingTasks();
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

        // Regression: PhotoController's `df` was widened from a fixed "yyyy-MM-dd'T'HH:mm" pattern
        // to DateTimeFormatter.ISO_LOCAL_DATE_TIME so the drag-to-reorder feature can send
        // second/millisecond-precision timestamps through this same endpoint - confirm that still
        // parses correctly (and didn't just start silently truncating back to the minute again).
        mockMvc.perform(patch("/api/photos/{id}/date", photoId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdatePhotoDateRequest("2022-05-01T00:00:10.500"))))
                .andExpect(status().isOk());
        assertThat(photoRepository.findById(photoId).orElseThrow().getDateTaken()).isEqualTo(LocalDateTime.parse("2022-05-01T00:00:10.500"));

        byte[] newBytes = generateTestJpeg();
        mockMvc.perform(multipart("/api/photos/{id}/edit", photoId)
                        .file(new MockMultipartFile("file", "edited.jpg", "image/jpeg", newBytes))
                        .param("saveAsNew", "false")
                        .session(session))
                .andExpect(status().isOk());
    }

    @Test
    void getAlbumsByPhotoAndCheckExistingHashesWorkThroughTheirEndpoints() throws Exception {
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
    void findByPersonAndPersonDailyPhotoCountsAndPersonPhotosForDayFindThePhotoThroughItsFace() throws Exception {
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

        // getPersonPhotosForDay -> PhotoRepository.findByFacesPersonAndGroupedDay is @MainSpaceLimited
        // now; a direct call from this bare test thread needs a principal to resolve a space from.
        // The fixture person lives in admin's own MAIN space, so runAsAdmin filters to the right set.
        Page<Photo> photosForDay = runAsAdmin(() ->
                photoService.getPersonPhotosForDay(person, java.time.LocalDate.of(2024, 8, 1), Pageable.ofSize(10)));
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

    @Test
    void downloadSingleAndMultiplePhotosSetContentDispositionAndZipEntries() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "Download-" + UUID.randomUUID());
        UUID firstPhotoId = uploadPhoto(session, spaceId);
        UUID secondPhotoId = uploadPhoto(session, spaceId);

        // Single download - a separate route from /original (see PhotoController's own note on
        // why), so this must carry Content-Disposition: attachment while /original still doesn't.
        MvcResult singleResult = mockMvc.perform(get("/api/photos/{id}/download", firstPhotoId).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();
        assertThat(singleResult.getResponse().getContentAsByteArray().length).isGreaterThan(0);

        mockMvc.perform(get("/api/photos/{id}/original", firstPhotoId).session(session))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Disposition"));

        // Multiple - a zip containing one entry per (owned) selected photo.
        // PhotoController#downloadMultiple returns ResponseEntity<StreamingResponseBody>, which
        // Spring MVC processes as an ASYNC request - MockMvc's own perform() returns as soon as
        // that async processing STARTS, not once the streaming body has actually finished writing.
        // Without the asyncDispatch() round-trip below, reading the response content races the
        // background thread that writes the zip: harmless when that thread wins quickly (an idle
        // machine running just this one test), but under real CPU contention (a full `mvn test`
        // run with many other tests' background threads/schedulers competing for CPU) the async
        // write can still be in progress - or not yet started - when the content is read, silently
        // producing a truncated or empty zip. This was root-caused (2026-09-11) by reproducing it
        // on demand: hammering this same request pattern under artificial busy-thread CPU load
        // reliably produced "0 entries" without asyncDispatch, and 0 failures across 40 iterations
        // once it was added - confirming the flakiness was a test bug (missing the standard
        // MockMvc pattern for async controller methods), not an application bug in the upload/
        // download/task pipeline itself (which several earlier investigation passes, recorded in
        // TODO.md, had already spent real effort trying to root-cause from that angle).
        MvcResult zipDispatch = mockMvc.perform(post("/api/photos/download").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(firstPhotoId, secondPhotoId))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult zipResult = mockMvc.perform(asyncDispatch(zipDispatch))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();

        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(zipResult.getResponse().getContentAsByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }
        assertThat(entryNames).hasSize(2);
    }

    @Test
    void findOwnedByIdsIsTheAuthorizationBoundaryBehindBulkDownload() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "DownloadAuthz-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        UserEntity otherUser = userRepository.save(UserEntity.builder()
                .username("download-other-" + UUID.randomUUID()).password("x").role("USER").build());

        // The actual authorization boundary bulk download relies on (PhotoController#
        // downloadMultiple) - a caller must never be able to read another user's files just by
        // passing their photo ids.
        assertThat(photoService.findOwnedByIds(otherUser, List.of(photoId))).isEmpty();
        assertThat(photoService.findOwnedByIds(userRepository.findByUsername("admin").orElseThrow(), List.of(photoId)))
                .hasSize(1);

        mockMvc.perform(get("/api/photos/{id}/download", UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
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
