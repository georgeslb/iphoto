package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.model.request.ShareRequest;
import org.iphoto.model.request.ShareUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven entirely through ShareController's real endpoints - createShare/getAllShares/
// getShareById/updateSharePassword/deleteShare all read/write through ShareRepository's
// @MainSpaceLimited queries (see ShareServiceImpl), so they need a real authenticated request
// rather than a bare direct call; getShareDetails/getShareDetailsById/getSharePassword are the
// deliberately-unfiltered guest/public lookups (see ShareRepository), reached here through the
// public share-link endpoints. computeUserMd5 (static) is exercised indirectly - every created
// share's userMd5 is computed by it.
class ShareServiceTest extends AbstractIntegrationTest {

    private UUID createAlbumForShare(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/albums")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new org.iphoto.model.request.AlbumCreateRequest("Share-Album-" + UUID.randomUUID(), "desc", null))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void createShareThenListsAndFetchesItById() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID albumId = createAlbumForShare(session);
        String shareName = "share-" + UUID.randomUUID();

        MvcResult createResult = mockMvc.perform(post("/api/share")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShareRequest(shareName, 7, null, albumId))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID shareId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("isPasswordProtected").asBoolean()).isFalse();

        MvcResult listResult = mockMvc.perform(get("/api/share/my-shares").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode shares = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(shares).anyMatch(s -> s.get("id").asText().equals(shareId.toString()));

        mockMvc.perform(get("/api/share/{id}", shareId).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void creatingAShareWithADuplicateNameConflicts() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID albumId = createAlbumForShare(session);
        String shareName = "dup-share-" + UUID.randomUUID();
        mockMvc.perform(post("/api/share").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShareRequest(shareName, 0, null, albumId))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/share").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShareRequest(shareName, 0, null, albumId))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateSharePasswordThenGuestLoginRequiresIt() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID albumId = createAlbumForShare(session);
        String shareName = "password-share-" + UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/api/share").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShareRequest(shareName, 0, null, albumId))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID shareId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());
        String userMd5 = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("userMd5").asText();

        MvcResult updateResult = mockMvc.perform(put("/api/share/{id}", shareId).session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShareUpdateRequest("guest-password"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(updateResult.getResponse().getContentAsString()).get("isPasswordProtected").asBoolean()).isTrue();

        // getShareDetails (guest-facing, public endpoint) - reports protected:true, no session
        // needed at all.
        MvcResult detailsResult = mockMvc.perform(get("/api/share/{userMd5}/{name}", userMd5, shareName))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(detailsResult.getResponse().getContentAsString()).get("protected").asBoolean()).isTrue();

        // getShareDetailsById/getSharePassword (also guest-facing) - wrong password rejected...
        mockMvc.perform(post("/api/share/{shareId}/login", shareId)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "wrong"))))
                .andExpect(status().isUnauthorized());
        // ...right password accepted.
        mockMvc.perform(post("/api/share/{shareId}/login", shareId)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "guest-password"))))
                .andExpect(status().isOk());
    }

    @Test
    void deleteShareRemovesIt() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID albumId = createAlbumForShare(session);
        MvcResult createResult = mockMvc.perform(post("/api/share").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShareRequest("deletable-" + UUID.randomUUID(), 0, null, albumId))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID shareId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(delete("/api/share/{id}", shareId).session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/share/{id}", shareId).session(session))
                .andExpect(status().isNotFound());
    }
}
