package org.iphoto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iphoto.model.request.AlbumCreateRequest;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.service.TaskWorkerManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// One flowing end-to-end scenario, driven entirely through the real HTTP API (MockMvc, real
// Spring Security filter chain, real Postgres via AbstractIntegrationTest) rather than calling
// services directly: log in -> create a Space -> upload a photo straight into that Space ->
// move it to Main (required before it can join an album, see AlbumService.addPhotosToAlbum's
// MAIN-space check) -> create an Album and add the photo to it -> move the photo to Garbage ->
// permanently delete it. Covers Photo/Album/Space/Garbage together, matching how a real user
// would actually exercise the app rather than testing each feature in isolation.
class PhotoLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TaskWorkerManager taskWorkerManager;
    @Autowired
    private PhotoRepository photoRepository;

    @Test
    void uploadAddToAlbumAddToSpaceGarbageAndPermanentDelete() throws Exception {
        MockHttpSession session = loginAsAdmin();

        // 1. Create a Space - photos are only ever placed into a non-Main space at upload time
        // (there is no "move an existing Main-space photo into an arbitrary space" endpoint;
        // only the reverse, space -> Main, exists - see PhotoController/moveAssetsToMain).
        UUID tripSpaceId = createSpace(session, "Trip");

        // 2. Upload a photo directly into that Space.
        UUID photoId = uploadPhoto(session, tripSpaceId);

        // Photo really landed in the Trip space, not Main.
        assertSpaceName(session, photoId, "Trip");

        // 3. Move it to Main - a precondition for adding it to an album.
        mockMvc.perform(post("/api/photos/main")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        assertSpaceName(session, photoId, "MAIN");

        // 4. Create an Album and add the photo to it.
        UUID albumId = createAlbum(session, "Vacation");
        MvcResult addToAlbumResult = mockMvc.perform(post("/api/albums/{id}/photos", albumId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("photoIds", List.of(photoId)))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode albumAfterAdd = objectMapper.readTree(addToAlbumResult.getResponse().getContentAsString());
        assertThat(albumAfterAdd.get("photoCount").asInt()).isEqualTo(1);

        // Album really contains the photo.
        MvcResult albumPhotosResult = mockMvc.perform(get("/api/albums/{id}/photos", albumId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode albumPhotos = objectMapper.readTree(albumPhotosResult.getResponse().getContentAsString());
        assertThat(albumPhotos.get("content")).anyMatch(p -> p.get("id").asText().equals(photoId.toString()));

        // 5. Move the photo to Garbage.
        mockMvc.perform(post("/api/photos/garbage")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        assertThat(photoRepository.findById(photoId)).isPresent();
        assertThat(photoRepository.findById(photoId).orElseThrow().getIsGarbage()).isTrue();

        Path originalPath = Path.of(photoRepository.findById(photoId).orElseThrow().getOriginalPath());
        assertThat(Files.exists(originalPath)).isTrue();

        // 6. Permanently delete it - the photo row and its files must both be gone.
        mockMvc.perform(delete("/api/photos/garbage/permanent")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isNoContent());
        assertThat(photoRepository.findById(photoId)).isEmpty();
        assertThat(Files.exists(originalPath)).isFalse();
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/login")
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private UUID createSpace(MockHttpSession session, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/spaces")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SpaceCreateRequest(name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createAlbum(MockHttpSession session, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/albums")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AlbumCreateRequest(name, "Integration test album", null))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void assertSpaceName(MockHttpSession session, UUID photoId, String expectedSpaceName) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/photos/{id}/space", photoId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode space = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(space.get("name").asText()).isEqualTo(expectedSpaceName);
    }

    // Uploads a single-chunk JPEG straight into targetSpaceId, forces the async PROCESS_UPLOAD
    // task to run immediately (rather than waiting on TaskWorkerManager's 5s @Scheduled poll -
    // same code path, just not idling for the timer), and waits for it to complete.
    private UUID uploadPhoto(MockHttpSession session, UUID targetSpaceId) throws Exception {
        byte[] jpegBytes = generateTestJpeg();
        String uploadId = UUID.randomUUID().toString();

        MvcResult uploadResult = mockMvc.perform(multipart("/api/upload/chunk")
                        .file(new MockMultipartFile("chunk", "test-photo.jpg", "image/jpeg", jpegBytes))
                        .param("uploadId", uploadId)
                        .param("fileName", "test-photo.jpg")
                        .param("fileSize", String.valueOf(jpegBytes.length))
                        .param("chunkIndex", "0")
                        .param("totalChunks", "1")
                        .param("contentType", "image/jpeg")
                        .param("spaceId", targetSpaceId.toString())
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        UUID taskId = UUID.fromString(response.get("task").get("id").asText());
        UUID photoId = UUID.fromString(response.get("task").get("photoId").asText());

        waitForTaskCompletion(session, taskId);
        return photoId;
    }

    private void waitForTaskCompletion(MockHttpSession session, UUID taskId) throws Exception {
        taskWorkerManager.pollPendingTasks(); // dispatch immediately instead of waiting on the timer
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/tasks/{id}", taskId).session(session))
                    .andExpect(status().isOk())
                    .andReturn();
            String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
            if ("COMPLETED".equals(status)) return;
            if ("FAILED".equals(status)) {
                throw new AssertionError("Upload task " + taskId + " failed");
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Upload task " + taskId + " did not complete in time");
    }

    private static byte[] generateTestJpeg() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
