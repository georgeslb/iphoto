package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through AdminController's real endpoints - every AdminService public method maps 1:1
// onto one of its routes.
class AdminServiceTest extends AbstractIntegrationTest {

    @Test
    void heavyTasksCanBeToggledOnAndOff() throws Exception {
        MockHttpSession session = loginAsAdmin();
        // Not assuming a specific starting value - ACTIVATE_HEAVY_TASKS is exactly one global row,
        // updated in place (see AdminService's own comment), so another test class in this shared
        // suite (e.g. TaskWorkerManagerTest) may have already flipped it before this one runs.
        // Establish a known starting state explicitly instead.
        mockMvc.perform(post("/api/admin/heavyTasks").session(session).contentType(APPLICATION_JSON)
                        .content("{\"activate\":false}"))
                .andExpect(status().isOk());
        MvcResult before = mockMvc.perform(get("/api/admin/heavyTasks").session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(before.getResponse().getContentAsString()).get("activate").asBoolean()).isFalse();

        mockMvc.perform(post("/api/admin/heavyTasks")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content("{\"activate\":true}"))
                .andExpect(status().isOk());

        MvcResult after = mockMvc.perform(get("/api/admin/heavyTasks").session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(after.getResponse().getContentAsString()).get("activate").asBoolean()).isTrue();
    }

    @Test
    void updateImageTaskToDoDoesNotFailWithAnEmptyLibrary() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(get("/api/admin/updateImageTaskToDo").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void migrateFlatFilesToOrganizedStorageReturnsAScanSummary() throws Exception {
        MockHttpSession session = loginAsAdmin();

        MvcResult result = mockMvc.perform(post("/api/admin/storage/migrate-flat-files").session(session))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode summary = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(summary.has("scanned")).isTrue();
    }
}
