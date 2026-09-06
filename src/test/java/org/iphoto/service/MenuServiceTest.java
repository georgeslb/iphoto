package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through the real endpoint (MenuController's only route, POST /api/menu/selected) rather
// than autowiring MenuService directly - unlike most services in this suite, its single public
// method maps 1:1 onto a controller action with nothing else worth calling it directly for.
class MenuServiceTest extends AbstractIntegrationTest {

    @Test
    void getSelectionForTheAlbumsMenuAllowsCreatingButNotDeletingWhenAnAlbumIsSelected() throws Exception {
        MockHttpSession session = loginAsAdmin();

        MvcResult result = mockMvc.perform(post("/api/menu/selected")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"menu":"albums","spaceIds":[],"albumIds":["11111111-1111-1111-1111-111111111111"]}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(dto.get("canCreateAlbum").asBoolean()).isTrue();
        assertThat(dto.get("canDeleteAlbum").asBoolean()).isFalse(); // an album is selected
        assertThat(dto.get("canUpload").asBoolean()).isFalse();
    }

    @Test
    void getSelectionForTheLibraryMenuAllowsUpload() throws Exception {
        MockHttpSession session = loginAsAdmin();

        MvcResult result = mockMvc.perform(post("/api/menu/selected")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new org.iphoto.model.request.MenuSelectionRequest("library", List.of(), List.of()))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(dto.get("canUpload").asBoolean()).isTrue();
    }
}
