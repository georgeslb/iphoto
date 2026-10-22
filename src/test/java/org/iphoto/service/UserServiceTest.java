package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.request.UserCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through UserController (findAll/createUser/deleteUser/resetPassword all map 1:1 onto its
// routes, gated by ROLE_ADMIN - the bootstrapped admin account satisfies that) plus
// updateImmichSettings/clearImmichSettings/resolveImmichCredentials/requireImmichCredentials
// (no controller of their own here - see ImmichImportServiceTest-equivalent coverage; Immich
// itself is out of scope for this suite, but the plumbing on UserService is not). UserRepository
// has no @MainSpaceLimited methods, so no authenticated context is needed for direct calls either.
class UserServiceTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Test
    void findAllAndCreateUserWorkThroughTheAdminEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String username = "new-user-" + UUID.randomUUID();

        MvcResult createResult = mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserCreateRequest() {{
                            setUsername(username);
                            setPassword("password123");
                            setRole("USER");
                        }})))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        assertThat(created.get("username").asText()).isEqualTo(username);
        assertThat(created.get("duplicationRegrouping").asBoolean()).isTrue();

        MvcResult listResult = mockMvc.perform(get("/api/users").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode users = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(users).anyMatch(u -> u.get("username").asText().equals(username) && u.get("duplicationRegrouping").asBoolean());
    }

    @Test
    void duplicationRegroupingCanBeToggledByAdminAndCurrentUser() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String username = "toggle-user-" + UUID.randomUUID();

        // Create user (defaults to duplicationRegrouping = true)
        MvcResult createResult = mockMvc.perform(post("/api/users")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserCreateRequest() {{
                            setUsername(username);
                            setPassword("password123");
                            setRole("USER");
                        }})))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());
        assertThat(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("duplicationRegrouping").asBoolean()).isTrue();

        // Toggle to false via admin endpoint
        MvcResult updateResult = mockMvc.perform(put("/api/users/{id}/duplication-regrouping", userId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("duplicationRegrouping", false))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(updateResult.getResponse().getContentAsString()).get("duplicationRegrouping").asBoolean()).isFalse();

        // Toggle back to true via admin endpoint
        MvcResult toggleBackResult = mockMvc.perform(put("/api/users/{id}/duplication-regrouping", userId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("duplicationRegrouping", true))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(toggleBackResult.getResponse().getContentAsString()).get("duplicationRegrouping").asBoolean()).isTrue();

        // Admin can also toggle their own via /api/user/me/duplication-regrouping
        mockMvc.perform(put("/api/user/me/duplication-regrouping")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("duplicationRegrouping", false))))
                .andExpect(status().isOk());
        MvcResult meResult = mockMvc.perform(get("/api/user/me").session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(meResult.getResponse().getContentAsString()).get("duplicationRegrouping").asBoolean()).isFalse();

        // Restore admin setting to true
        mockMvc.perform(put("/api/user/me/duplication-regrouping")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("duplicationRegrouping", true))))
                .andExpect(status().isOk());
    }

    @Test
    void resetPasswordThenDeleteUserWorkThroughTheAdminEndpoints() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String username = "reset-user-" + UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/api/users")
                        .session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserCreateRequest() {{
                            setUsername(username);
                            setPassword("old-password");
                            setRole("USER");
                        }})))
                .andExpect(status().isOk())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/users/{id}/reset-password", userId)
                        .session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newPassword", "new-password"))))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/users/{id}", userId).session(session))
                .andExpect(status().isNoContent());

        MvcResult listResult = mockMvc.perform(get("/api/users").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode users = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(users).noneMatch(u -> u.get("id").asText().equals(userId.toString()));
    }

    @Test
    void findByIdAndFindByUsernameFindTheBootstrappedAdmin() {
        Optional<UserEntity> byUsername = userService.findByUsername("admin");
        assertThat(byUsername).isPresent();

        Optional<UserEntity> byId = userService.findById(byUsername.get().getId());
        assertThat(byId).isPresent();
        assertThat(byId.get().getUsername()).isEqualTo("admin");

        assertThat(userService.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void immichSettingsCanBeSetResolvedAndCleared() {
        UserEntity user = userService.createUser("immich-user-" + UUID.randomUUID(), "password", "USER");

        assertThat(userService.resolveImmichCredentials(user)).isEmpty();

        UserEntity updated = userService.updateImmichSettings(user.getId(), "https://immich.example.com", "api-key-123");
        Optional<UserService.ImmichCredentials> creds = userService.resolveImmichCredentials(updated);
        assertThat(creds).isPresent();
        assertThat(creds.get().baseUrl()).isEqualTo("https://immich.example.com");
        assertThat(creds.get().apiKey()).isEqualTo("api-key-123");
        assertThat(userService.requireImmichCredentials(updated).apiKey()).isEqualTo("api-key-123");

        UserEntity cleared = userService.clearImmichSettings(user.getId());
        assertThat(userService.resolveImmichCredentials(cleared)).isEmpty();
    }
}
