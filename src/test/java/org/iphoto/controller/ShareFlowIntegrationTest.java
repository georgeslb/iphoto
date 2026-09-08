package org.iphoto.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.model.request.AlbumCreateRequest;
import org.iphoto.model.request.AlbumPhotoRequest;
import org.iphoto.model.request.ShareRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.service.ShareService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End-to-end repro of the real guest-browsing flow behind /share/{userMd5}/{name}: an anonymous
// caller (its own fresh MockHttpSession, never logged in) hitting the exact sequence a browser
// makes - GET /api/share/{userMd5}/{name} to establish the ROLE_SHARE session, then the
// session-dependent follow-up calls (/api/albums/share/{id}/photos, /api/photos/{id}/thumbnail) -
// reusing the SAME session object across every call, the way a browser reuses its one cookie jar.
// Written to pin down whether the reported "should be public" failure is an application-code bug
// (this test would fail) or something outside this repo entirely - reverse-proxy cookie handling
// in front of upload.attieh.uk, which no JVM-level test can exercise.
public class ShareFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AlbumRepository albumRepository;
    @Autowired
    private PhotoRepository photoRepository;

    @Test
    void guestCanBrowseANonPasswordProtectedShareEndToEndOnASingleAnonymousSession() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();
        UUID spaceId = createSpace(adminSession, "share-flow-space-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(adminSession, spaceId);

        // Albums only hold references to Main Space photos (see specification.md) -
        // addPhotosToAlbum silently no-ops (checkPhotosAreInMainSpace against an empty
        // findByIdIn match, see below) for a photo left in a regular Space, so it must be moved
        // into Main first - mirrors AlbumServiceTest's own setup for this exact reason.
        mockMvc.perform(post("/api/photos/main")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());

        MvcResult albumResult = mockMvc.perform(post("/api/albums")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AlbumCreateRequest("share-flow-album", null, null))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID albumId = UUID.fromString(objectMapper.readTree(albumResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/albums/{id}/photos", albumId)
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AlbumPhotoRequest(List.of(photoId)))))
                .andExpect(status().isOk());

        AlbumEntity persistedAlbum = runAsAdmin(() -> albumRepository.findById(albumId).orElseThrow());
        long photosInAlbum = runAsAdmin(() -> photoRepository.countByIsGarbageFalseAndAlbums(Set.of(persistedAlbum)));
        assertThat(photosInAlbum).isEqualTo(1);

        String shareName = "share-flow-link-" + UUID.randomUUID();
        mockMvc.perform(post("/api/share")
                        .session(adminSession)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShareRequest(shareName, 7, null, albumId))))
                .andExpect(status().isCreated());

        // Resolve the admin's own userMd5 the same way ShareController does - not exposed by
        // the createShare response, so derive it directly via the admin-only user list.
        JsonNode users = objectMapper.readTree(
                mockMvc.perform(get("/api/users").session(adminSession))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        UUID adminUserId = null;
        for (JsonNode user : users) {
            if ("admin".equals(user.get("username").asText())) {
                adminUserId = UUID.fromString(user.get("id").asText());
                break;
            }
        }
        assertThat(adminUserId).isNotNull();
        String userMd5 = ShareService.computeUserMd5(adminUserId);

        // From here on, an entirely separate, never-authenticated session - the guest.
        MockHttpSession guestSession = new MockHttpSession();

        MvcResult statusResult = mockMvc.perform(get("/api/share/{userMd5}/{name}", userMd5, shareName)
                        .session(guestSession))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statusBody = objectMapper.readTree(statusResult.getResponse().getContentAsString());
        assertThat(statusBody.get("protected").asBoolean()).isFalse();
        UUID resolvedAlbumId = UUID.fromString(statusBody.get("albumId").asText());
        assertThat(resolvedAlbumId).isEqualTo(albumId);

        // The session MockMvc hands back after this call carries the ROLE_SHARE SecurityContext
        // that establishShareSession wrote into it - a real browser gets this back via the
        // Set-Cookie header on the same response and sends it back on the next request
        // automatically; reusing guestSession here is the MockMvc equivalent of that.
        MockHttpSession sessionAfterEstablish =
                (MockHttpSession) statusResult.getRequest().getSession(false);
        assertThat(sessionAfterEstablish).isNotNull();

        MvcResult guestPhotosResult = mockMvc.perform(get("/api/albums/share/{id}/photos", resolvedAlbumId)
                        .session(sessionAfterEstablish))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode guestPhotos = objectMapper.readTree(guestPhotosResult.getResponse().getContentAsString());
        assertThat(guestPhotos.get("content")).hasSize(1);
        assertThat(guestPhotos.get("content").get(0).get("id").asText()).isEqualTo(photoId.toString());

        mockMvc.perform(get("/api/albums/share/{id}/photos/{photoId}", resolvedAlbumId, photoId)
                        .session(sessionAfterEstablish))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/photos/{id}/thumbnail", photoId)
                        .session(sessionAfterEstablish))
                .andExpect(status().isOk());
    }
}
