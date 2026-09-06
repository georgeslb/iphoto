package org.iphoto.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.DeviceTokenEntity;
import org.iphoto.model.request.DeviceLoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// issueToken/listForUser/revoke are driven through DeviceAuthController's real endpoints (mobile
// /login is permitAll; /devices reuses a normal web session, same as the controller's own comment
// says any established authentication works for it). findActiveByRawToken/recordUsage have no
// endpoint of their own (they back DeviceTokenAuthenticationFilter, not exercised here) and hit no
// @MainSpaceLimited repository, so they're called directly.
class DeviceTokenServiceTest extends AbstractIntegrationTest {

    @Autowired
    private DeviceTokenService deviceTokenService;

    @Test
    void issueTokenViaLoginEndpointReturnsAUsableRawToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/mobile/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeviceLoginRequest("admin", "admin", "Test Phone"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(dto.get("token").asText()).isNotBlank();
        assertThat(dto.get("deviceName").asText()).isEqualTo("Test Phone");

        assertThat(deviceTokenService.findActiveByRawToken(dto.get("token").asText())).isPresent();
    }

    @Test
    void issueTokenWithWrongPasswordIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/mobile/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeviceLoginRequest("admin", "wrong-password", "Test Phone"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void findActiveByRawTokenIsEmptyForAnUnknownToken() {
        assertThat(deviceTokenService.findActiveByRawToken("not-a-real-token")).isEmpty();
    }

    @Test
    void recordUsageStampsLastUsedAt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/mobile/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeviceLoginRequest("admin", "admin", "Usage Phone"))))
                .andExpect(status().isOk())
                .andReturn();
        String rawToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        DeviceTokenEntity token = deviceTokenService.findActiveByRawToken(rawToken).orElseThrow();
        assertThat(token.getLastUsedAt()).isNull();

        deviceTokenService.recordUsage(token);

        assertThat(deviceTokenService.findActiveByRawToken(rawToken).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void listForUserAndRevokeWorkThroughTheDevicesEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/mobile/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeviceLoginRequest("admin", "admin", "Listed Phone"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID deviceId = UUID.fromString(objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("id").asText());

        MvcResult listResult = mockMvc.perform(get("/api/auth/mobile/devices").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode devices = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(devices).anyMatch(d -> d.get("id").asText().equals(deviceId.toString()));

        mockMvc.perform(delete("/api/auth/mobile/devices/{id}", deviceId).session(session))
                .andExpect(status().isOk());

        MvcResult afterRevoke = mockMvc.perform(get("/api/auth/mobile/devices").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode devicesAfter = objectMapper.readTree(afterRevoke.getResponse().getContentAsString());
        JsonNode revoked = null;
        for (JsonNode d : devicesAfter) {
            if (d.get("id").asText().equals(deviceId.toString())) revoked = d;
        }
        assertThat(revoked).isNotNull();
        assertThat(revoked.get("revoked").asBoolean()).isTrue();
    }

    @Test
    void revokeAnUnknownDeviceIdReturnsNotFound() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(delete("/api/auth/mobile/devices/{id}", UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
    }
}
