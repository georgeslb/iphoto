package org.iphoto;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.model.immich.ImmichAlbumSummaryDTO;
import org.iphoto.model.immich.ImmichTimeBucketDTO;
import org.iphoto.model.request.ImmichSettingsRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.service.immich.ImmichClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// One flowing end-to-end scenario for the Immich album-import feature (see
// ImmichAlbumImportService/TaskType.IMPORT_IMMICH_ALBUM), driven through the real HTTP API just
// like PhotoLifecycleIntegrationTest, with one deliberate substitution: ImmichClient - the only
// thing in this app that actually talks to a real Immich server over HTTP - is mocked out. There
// is no live Immich server reachable from a test run, and the point here is verifying iphoto's
// OWN logic (task queuing, the in-flight dedup guard, existsById/linkable resolution,
// addPhotosToAlbum, the polled status list), not re-verifying Immich's wire format, which nothing
// in this environment can do against a real server anyway.
class ImmichAlbumImportIntegrationTest extends AbstractIntegrationTest {

    private static final String IMMICH_ALBUM_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TIME_BUCKET = "2024-01-01";

    @MockitoBean
    private ImmichClient immichClient;

    @org.springframework.beans.factory.annotation.Autowired
    private AlbumRepository albumRepository;

    @Test
    void queuesOneTaskPerAlbumAndLinksOnlyPhotosThatExistLocally() throws Exception {
        MockHttpSession session = loginAsAdmin();

        // 1. Connect this user's (fake) Immich settings - never actually dialed, ImmichClient is
        // mocked below, but requireImmichCredentials still needs baseUrl+apiKey stored to not
        // reject the request.
        mockMvc.perform(put("/api/immich/settings")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImmichSettingsRequest("http://fake-immich.test", "test-api-key"))))
                .andExpect(status().isOk());

        // 2. Upload one real photo into Main, so it exists locally under a known id - the whole
        // point of this test is confirming only THIS asset (not the second, never-imported one
        // below) ends up linked.
        UUID spaceId = createSpace(session, "Trip");
        UUID photoId = uploadPhoto(session, spaceId);
        mockMvc.perform(post("/api/photos/main")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());

        String neverImportedAssetId = UUID.randomUUID().toString();

        // 3. Stub the Immich album this user "owns": one album, one time bucket, two assets - the
        // real photo just uploaded, and one that was never imported (must be skipped, not linked,
        // and must not blow up existsById/UUID parsing).
        when(immichClient.listAlbums(any(), eq(true)))
                .thenReturn(List.of(new ImmichAlbumSummaryDTO(IMMICH_ALBUM_ID, "Test Album", "desc", 2)));
        when(immichClient.getAlbumTimeBuckets(any(), eq(IMMICH_ALBUM_ID)))
                .thenReturn(List.of(new ImmichTimeBucketDTO(TIME_BUCKET, 2L)));
        when(immichClient.getAlbumBucketAssetIds(any(), eq(IMMICH_ALBUM_ID), eq(TIME_BUCKET)))
                .thenReturn(List.of(photoId.toString(), neverImportedAssetId));
        when(immichClient.listSharedLinks(any())).thenReturn(List.of());

        // 4. Trigger album import - must return immediately (queues a task, doesn't fetch/link
        // inline) and must not queue a second task for the same album on an immediate re-trigger,
        // since the first one hasn't run yet (the in-flight dedup guard - see
        // ImmichAlbumImportService#queueAlbumImport).
        MvcResult firstTrigger = mockMvc.perform(post("/api/immich/albums/import").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstResult = objectMapper.readTree(firstTrigger.getResponse().getContentAsString());
        assertThat(firstResult.get("albumsQueued").asInt()).isEqualTo(1);
        assertThat(firstResult.get("albumsAlreadyInFlight").asInt()).isEqualTo(0);

        MvcResult secondTrigger = mockMvc.perform(post("/api/immich/albums/import").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondResult = objectMapper.readTree(secondTrigger.getResponse().getContentAsString());
        assertThat(secondResult.get("albumsQueued").asInt()).isEqualTo(0);
        assertThat(secondResult.get("albumsAlreadyInFlight").asInt()).isEqualTo(1);

        // 5. Let the background task actually run, then confirm the polled status list reflects
        // it: COMPLETED, exactly one photo linked (not two - the never-imported asset must have
        // been skipped, not counted or linked).
        waitForAlbumTaskCompletion(session, IMMICH_ALBUM_ID);

        MvcResult statusResult = mockMvc.perform(get("/api/immich/albums").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode albums = objectMapper.readTree(statusResult.getResponse().getContentAsString());
        JsonNode albumRow = findByImmichAlbumId(albums, IMMICH_ALBUM_ID);
        assertThat(albumRow.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(albumRow.get("photoCount").asInt()).isEqualTo(1);

        // 6. And the photo is really linked in the database, not just reflected in the DTO.
        UUID localAlbumId = UUID.fromString(albumRow.get("albumId").asText());
        assertThat(albumRepository.findById(localAlbumId)).isPresent();
        MvcResult albumPhotosResult = mockMvc.perform(get("/api/albums/{id}/photos", localAlbumId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode albumPhotos = objectMapper.readTree(albumPhotosResult.getResponse().getContentAsString());
        assertThat(albumPhotos.get("content")).hasSize(1);
        assertThat(albumPhotos.get("content").get(0).get("id").asText()).isEqualTo(photoId.toString());
    }

    private void waitForAlbumTaskCompletion(MockHttpSession session, String immichAlbumId) throws Exception {
        taskWorkerManager.pollPendingTasks(); // dispatch immediately instead of waiting on the timer
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/immich/albums").session(session))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode albums = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode row = findByImmichAlbumId(albums, immichAlbumId);
            if (row != null) {
                String status = row.get("status").asText();
                if ("COMPLETED".equals(status)) return;
                if ("FAILED".equals(status)) {
                    throw new AssertionError("Album import task for " + immichAlbumId + " failed: " + row.get("errorMessage"));
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Album import task for " + immichAlbumId + " did not complete in time");
    }

    private static JsonNode findByImmichAlbumId(JsonNode albums, String immichAlbumId) {
        for (JsonNode album : albums) {
            if (immichAlbumId.equals(album.get("immichAlbumId").asText())) return album;
        }
        return null;
    }
}
