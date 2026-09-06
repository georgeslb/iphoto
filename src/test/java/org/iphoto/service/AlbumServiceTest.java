package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.model.request.AlbumCreateRequest;
import org.iphoto.model.request.AlbumUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through AlbumController/AlbumShareController's real endpoints - AlbumRepository's
// @MainSpaceLimited queries (see AlbumServiceImpl) mean createAlbum/getAllAlbums/getAlbumById/
// updateAlbum/deleteAlbum/addPhotosToAlbum/removePhotosFromAlbum/getAlbumPhotos/
// getAlbumDailyPhotoCounts/getAlbumPhotosForDay all need a real authenticated request rather than
// a bare direct call. getAlbumPhotosForShare/getAlbumPhotoForShare are the deliberately-unfiltered
// guest-facing lookups, exercised here with no session at all. convertToDto/updateAlbum(entity)
// have no endpoint of their own but are exercised on every AlbumDTO response above (every one of
// them is built through those two methods), so no separate test is needed for them.
class AlbumServiceTest extends AbstractIntegrationTest {

    private UUID createAlbum(MockHttpSession session, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/albums")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AlbumCreateRequest(name, "desc", null))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void createAlbumThenListsAndFetchesItById() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String name = "Album-" + UUID.randomUUID();

        UUID albumId = createAlbum(session, name);

        MvcResult listResult = mockMvc.perform(get("/api/albums").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode albums = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(albums).anyMatch(a -> a.get("id").asText().equals(albumId.toString()));

        mockMvc.perform(get("/api/albums/{id}", albumId).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void creatingAnAlbumWithADuplicateNameConflicts() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String name = "Dup-Album-" + UUID.randomUUID();
        createAlbum(session, name);

        mockMvc.perform(post("/api/albums").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AlbumCreateRequest(name, "desc", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateAlbumRenamesItAndDeleteAlbumRemovesIt() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID albumId = createAlbum(session, "Original-" + UUID.randomUUID());
        String newName = "Renamed-" + UUID.randomUUID();

        MvcResult updateResult = mockMvc.perform(put("/api/albums/{id}", albumId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AlbumUpdateRequest(newName, null, null))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(updateResult.getResponse().getContentAsString()).get("name").asText()).isEqualTo(newName);

        mockMvc.perform(delete("/api/albums/{id}", albumId).session(session))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/albums/{id}", albumId).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void addAndRemovePhotosFromAlbumUpdateThePhotoCountAndDayIndex() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "AlbumPhotos-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        mockMvc.perform(post("/api/photos/main").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        UUID albumId = createAlbum(session, "PhotoAlbum-" + UUID.randomUUID());

        MvcResult addResult = mockMvc.perform(post("/api/albums/{id}/photos", albumId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("photoIds", List.of(photoId)))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(addResult.getResponse().getContentAsString()).get("photoCount").asInt()).isEqualTo(1);

        mockMvc.perform(get("/api/albums/{id}/photos", albumId).session(session))
                .andExpect(status().isOk());

        MvcResult daysResult = mockMvc.perform(get("/api/albums/{id}/days", albumId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode days = objectMapper.readTree(daysResult.getResponse().getContentAsString());
        assertThat(days).isNotEmpty();
        String day = days.get(0).get("day").asText();

        mockMvc.perform(get("/api/albums/{id}/photos/day/{day}", albumId, day).session(session))
                .andExpect(status().isOk());

        MvcResult removeResult = mockMvc.perform(delete("/api/albums/{id}/photos", albumId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("photoIds", List.of(photoId)))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(removeResult.getResponse().getContentAsString()).get("photoCount").asInt()).isZero();
    }

    @Test
    void getAlbumPhotosForShareAndGetAlbumPhotoForShareUseTheUnfilteredGuestLookup() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "ShareAlbumPhotos-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        mockMvc.perform(post("/api/photos/main").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
        UUID albumId = createAlbum(session, "GuestAlbum-" + UUID.randomUUID());
        mockMvc.perform(post("/api/albums/{id}/photos", albumId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("photoIds", List.of(photoId)))))
                .andExpect(status().isOk());

        // /api/albums/share/** itself still requires SOME authenticated principal (Spring
        // Security's anyRequest().authenticated() - only /api/share/** is permitAll, see
        // SecurityConfig) - a real guest would carry a ROLE_SHARE session established by
        // ShareController's own login flow instead of this admin one, but
        // getAlbumPhotosForShare/getAlbumPhotoForShare are what's under test here, and neither
        // one filters by the caller's identity (that's the whole point being verified).
        MvcResult sharePhotosResult = mockMvc.perform(get("/api/albums/share/{id}/photos", albumId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sharePhotos = objectMapper.readTree(sharePhotosResult.getResponse().getContentAsString());
        assertThat(sharePhotos.get("content")).anyMatch(p -> p.get("id").asText().equals(photoId.toString()));

        mockMvc.perform(get("/api/albums/share/{id}/photos/{photoId}", albumId, photoId).session(session))
                .andExpect(status().isOk());
    }
}
