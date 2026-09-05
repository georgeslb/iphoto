package org.iphoto;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.model.request.AlbumCreateRequest;
import org.iphoto.repository.PhotoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

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
}
