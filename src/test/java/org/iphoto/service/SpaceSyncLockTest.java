package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.request.DeviceLoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End-to-end coverage for the block/unblock-on-sync feature (SpaceEntity.syncLockedByDevice,
// SpaceService#lockForSync/unlockFromSync/assertSyncWritable), driven through the real endpoints
// exactly the way the Android app and the web frontend would hit them - a session-cookie request
// for "the web app", a Bearer device-token request (POST /api/auth/mobile/login, same as
// DeviceTokenServiceTest) for "the Android app". Each test uses a fresh, dedicated non-admin user
// (own MAIN space + this test's own Space) so fixtures never collide with another test class.
class SpaceSyncLockTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    private UserEntity newUser() {
        return userService.createUser("sync-lock-" + UUID.randomUUID(), "password", "USER");
    }

    private MockHttpSession sessionFor(UserEntity user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/login")
                        .param("username", user.getUsername())
                        .param("password", "password"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // Bearer token for a "device" logged in as `user` - the app's own auth path
    // (DeviceTokenAuthenticationFilter), independent of the web session above.
    private String deviceTokenFor(UserEntity user, String deviceName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/mobile/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeviceLoginRequest(user.getUsername(), "password", deviceName))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private UUID mainSpaceId(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/spaces").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode spaces = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode space : spaces) {
            if (space.get("albumAllowed").asBoolean()) {
                return UUID.fromString(space.get("id").asText());
            }
        }
        throw new AssertionError("no MAIN space found");
    }

    @Test
    void lockingRequiresDeviceAuthAndOnlyThatDeviceCanWriteToTheLockedSpace() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        UUID spaceId = createSpace(session, "camera-roll-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        String deviceToken = deviceTokenFor(user, "Pixel");

        // A web session has no device identity to lock to - rejected before it ever reaches the service.
        mockMvc.perform(post("/api/spaces/{id}/sync-lock", spaceId).session(session))
                .andExpect(status().isForbidden());

        MvcResult lockResult = mockMvc.perform(post("/api/spaces/{id}/sync-lock", spaceId)
                        .header("Authorization", "Bearer " + deviceToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode locked = objectMapper.readTree(lockResult.getResponse().getContentAsString());
        assertThat(locked.get("syncLocked").asBoolean()).isTrue();
        assertThat(locked.get("syncLockedByDeviceName").asText()).isEqualTo("Pixel");

        // Confirmed on a plain GET too, not just the lock response itself.
        MvcResult getResult = mockMvc.perform(get("/api/spaces/{id}", spaceId).session(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(getResult.getResponse().getContentAsString()).get("syncLocked").asBoolean()).isTrue();

        // The web session - "outside the app" - is rejected on a write while locked.
        mockMvc.perform(post("/api/photos/garbage").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isLocked());

        // The exact device that owns the lock can still write - "the app" itself is never blocked.
        mockMvc.perform(post("/api/photos/garbage").header("Authorization", "Bearer " + deviceToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
    }

    @Test
    void aDifferentDeviceCannotWriteOrUnlockButTheWebSessionEscapeHatchCan() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        UUID spaceId = createSpace(session, "camera-roll-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        String deviceA = deviceTokenFor(user, "Pixel");
        String deviceB = deviceTokenFor(user, "Second Phone");

        mockMvc.perform(post("/api/spaces/{id}/sync-lock", spaceId).header("Authorization", "Bearer " + deviceA))
                .andExpect(status().isOk());

        // A second device of the SAME user is still "outside" this specific sync pairing.
        mockMvc.perform(post("/api/photos/garbage").header("Authorization", "Bearer " + deviceB)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isLocked());
        mockMvc.perform(delete("/api/spaces/{id}/sync-lock", spaceId).header("Authorization", "Bearer " + deviceB))
                .andExpect(status().isLocked());

        // The owning user's web session is the "lost my phone" escape hatch - always allowed to unlock.
        MvcResult unlockResult = mockMvc.perform(delete("/api/spaces/{id}/sync-lock", spaceId).session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(unlockResult.getResponse().getContentAsString()).get("syncLocked").asBoolean()).isFalse();

        // Now unlocked, the web session can write again.
        mockMvc.perform(post("/api/photos/garbage").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(photoId))))
                .andExpect(status().isOk());
    }

    @Test
    void theMainSpaceCannotBeLockedForSync() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        UUID mainSpaceId = mainSpaceId(session);
        String deviceToken = deviceTokenFor(user, "Pixel");

        mockMvc.perform(post("/api/spaces/{id}/sync-lock", mainSpaceId).header("Authorization", "Bearer " + deviceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokingTheOwningDeviceAutomaticallyUnlocksItsSpace() throws Exception {
        UserEntity user = newUser();
        MockHttpSession session = sessionFor(user);
        UUID spaceId = createSpace(session, "camera-roll-" + UUID.randomUUID());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/mobile/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeviceLoginRequest(user.getUsername(), "password", "Pixel"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String deviceToken = loginBody.get("token").asText();
        UUID deviceId = UUID.fromString(loginBody.get("id").asText());

        mockMvc.perform(post("/api/spaces/{id}/sync-lock", spaceId).header("Authorization", "Bearer " + deviceToken))
                .andExpect(status().isOk());

        // "I lost my phone" - revoking the device from Manage Devices, not stopping sync from the
        // app itself (which isn't possible any more once it's lost).
        mockMvc.perform(delete("/api/auth/mobile/devices/{id}", deviceId).session(session))
                .andExpect(status().isOk());

        MvcResult getResult = mockMvc.perform(get("/api/spaces/{id}", spaceId).session(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(getResult.getResponse().getContentAsString()).get("syncLocked").asBoolean()).isFalse();
    }
}
