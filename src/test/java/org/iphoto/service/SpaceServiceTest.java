package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.MainSpaceDeletionConfigurationException;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Mixes MockMvc-driven coverage (SpaceController exposes createSpace/getAllSpaces/
// getSpaceByIdForUser/markSpaceForDeletion/getGarbageSpaces/restoreSpacesFromGarbage) with direct
// calls for the handful of methods with no HTTP endpoint of their own (getDefaultSpaceForUser/
// getSpaceById/createDefaultSpace/increaseSpacePhotoCount/decreaseSpacePhotoCount/deleteSpace) -
// none of SpaceRepository's methods are @MainSpaceLimited, so those need no authenticated context.
class SpaceServiceTest extends AbstractIntegrationTest {

    @Autowired
    private SpaceService spaceService;
    @Autowired
    private UserRepository userRepository;

    @Test
    void getDefaultSpaceForUserReturnsTheBootstrappedMainSpace() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();

        SpaceEntity main = spaceService.getDefaultSpaceForUser(admin);

        assertThat(main.getName()).isEqualTo("MAIN");
    }

    @Test
    void getSpaceByIdFindsAnExistingSpaceAndIsEmptyForAnUnknownOne() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity main = spaceService.getDefaultSpaceForUser(admin);

        assertThat(spaceService.getSpaceById(main.getId())).isPresent();
        assertThat(spaceService.getSpaceById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void createDefaultSpaceBuildsAFreshMainTypeSpaceForANewUser() {
        UserEntity throwawayUser = userRepository.save(UserEntity.builder()
                .username("space-test-user-" + UUID.randomUUID())
                .password("irrelevant")
                .role("USER")
                .build());

        SpaceEntity created = spaceService.createDefaultSpace(throwawayUser);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("MAIN");
        assertThat(created.getPhotoCount()).isZero();
    }

    @Test
    void increaseAndDecreaseSpacePhotoCountAdjustTheCounterAtomically() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity main = spaceService.getDefaultSpaceForUser(admin);
        int before = spaceService.getSpaceById(main.getId()).orElseThrow().getPhotoCount();

        spaceService.increaseSpacePhotoCount(main);
        assertThat(spaceService.getSpaceById(main.getId()).orElseThrow().getPhotoCount()).isEqualTo(before + 1);

        spaceService.decreaseSpacePhotoCount(main);
        assertThat(spaceService.getSpaceById(main.getId()).orElseThrow().getPhotoCount()).isEqualTo(before);

        spaceService.increaseSpacePhotoCount(main, 5);
        assertThat(spaceService.getSpaceById(main.getId()).orElseThrow().getPhotoCount()).isEqualTo(before + 5);
        spaceService.increaseSpacePhotoCount(main, -5);
    }

    @Test
    void createSpaceViaHttpThenListsItAndFetchesItById() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String name = "Trip-" + UUID.randomUUID();

        MvcResult createResult = mockMvc.perform(post("/api/spaces")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SpaceCreateRequest(name))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID spaceId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

        MvcResult listResult = mockMvc.perform(get("/api/spaces").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode spaces = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(spaces).anyMatch(s -> s.get("id").asText().equals(spaceId.toString()));

        mockMvc.perform(get("/api/spaces/{id}", spaceId).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void creatingASpaceWithADuplicateNameConflicts() throws Exception {
        MockHttpSession session = loginAsAdmin();
        String name = "Duplicate-" + UUID.randomUUID();
        mockMvc.perform(post("/api/spaces").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SpaceCreateRequest(name))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/spaces").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SpaceCreateRequest(name))))
                .andExpect(status().isConflict());
    }

    @Test
    void markSpaceForDeletionRejectsTheMainSpaceButAllowsAnOrdinarySpace() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity main = spaceService.getDefaultSpaceForUser(admin);
        assertThatThrownBy(() -> spaceService.markSpaceForDeletion(admin, main.getId()))
                .isInstanceOf(MainSpaceDeletionConfigurationException.class);

        MockHttpSession session = loginAsAdmin();
        MvcResult createResult = mockMvc.perform(post("/api/spaces").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SpaceCreateRequest("ToDelete-" + UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID spaceId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(delete("/api/spaces/{id}", spaceId).session(session))
                .andExpect(status().isNoContent());

        MvcResult garbageResult = mockMvc.perform(get("/api/spaces/garbage").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode garbagePage = objectMapper.readTree(garbageResult.getResponse().getContentAsString());
        assertThat(garbagePage.get("content")).anyMatch(s -> s.get("id").asText().equals(spaceId.toString()));
    }

    @Test
    void restoreSpacesFromGarbageMovesThemOutOfTheGarbageList() throws Exception {
        MockHttpSession session = loginAsAdmin();
        MvcResult createResult = mockMvc.perform(post("/api/spaces").session(session).contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SpaceCreateRequest("Restorable-" + UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID spaceId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(delete("/api/spaces/{id}", spaceId).session(session)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/spaces/garbage/restore")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(spaceId))))
                .andExpect(status().isOk());

        MvcResult listResult = mockMvc.perform(get("/api/spaces").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode spaces = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(spaces).anyMatch(s -> s.get("id").asText().equals(spaceId.toString()));
    }

    @Test
    void getPhotoSpaceAndGetSpaceByIdForUserReturnTheOwningSpace() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "PhotoSpace-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();

        SpaceDTO photoSpace = spaceService.getPhotoSpace(admin, photoId);
        assertThat(photoSpace.id()).isEqualTo(spaceId);

        Optional<SpaceDTO> byIdForUser = spaceService.getSpaceByIdForUser(spaceId, admin);
        assertThat(byIdForUser).isPresent();
        assertThat(byIdForUser.get().id()).isEqualTo(spaceId);
    }

    @Test
    void deleteSpaceRemovesTheRowOutright() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity created = spaceService.createDefaultSpace(userRepository.save(UserEntity.builder()
                .username("delete-space-user-" + UUID.randomUUID()).password("x").role("USER").build()));

        spaceService.deleteSpace(created.getId());

        assertThat(spaceService.getSpaceById(created.getId())).isEmpty();
    }
}
