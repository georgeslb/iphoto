package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.model.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through TaskController's only route (GET /api/tasks/{id}) - TaskService's single public
// method maps 1:1 onto it.
class TaskServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TaskManagerService taskManagerService;

    @Test
    void getTaskReturnsTheTaskDtoForAnExistingTask() throws Exception {
        MockHttpSession session = loginAsAdmin();
        var created = taskManagerService.createTaskEntityImmediately(TaskType.HASH_IMAGE, UUID.randomUUID(), null, null);

        MvcResult result = mockMvc.perform(get("/api/tasks/{id}", created.getId()).session(session))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode dto = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(dto.get("id").asText()).isEqualTo(created.getId().toString());
        assertThat(dto.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void getTaskReturns404ForAnUnknownId() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(get("/api/tasks/{id}", UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
    }
}
