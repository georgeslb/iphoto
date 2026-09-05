package org.iphoto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.service.TaskWorkerManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Base for real Spring Boot integration tests: full application context, real Spring Security
// filter chain (MockMvc, not @WebMvcTest slices), and a real Postgres instance via Testcontainers
// (@ServiceConnection auto-wires spring.datasource.* from the container - no manual property
// wiring needed for the datasource itself). H2/an in-memory DB was deliberately not used: this
// app is Postgres-only in practice (native window-function queries, BYTEA columns, Postgres
// dialect hardcoded in application.yml) and relies on ddl-auto=update for schema creation (no
// Flyway/Liquibase in this project), so a real Postgres is both more representative and no more
// setup than any alternative.
//
// Also carries the login/space/upload/task-wait helpers shared by every subclass's flowing
// end-to-end scenario (originally written for PhotoLifecycleIntegrationTest, hoisted here once a
// second scenario - ImmichAlbumImportIntegrationTest - needed the exact same "log in, upload a
// real photo, force the async task poller instead of waiting on its timer" setup).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected TaskWorkerManager taskWorkerManager;

    // Every test run gets its own throwaway storage directory - tests must never write into the
    // real iphoto.storage.* location from application.yml, which is the user's actual photo
    // library on disk. A plain Files.createTempDirectory here (rather than JUnit's @TempDir on a
    // static field) avoids any ambiguity about extension ordering vs. @DynamicPropertySource,
    // which runs very early during context preparation.
    @DynamicPropertySource
    static void overrideStorageProperties(DynamicPropertyRegistry registry) throws IOException {
        Path storageRoot = Files.createTempDirectory("iphoto-it-storage-");
        registry.add("iphoto.storage.base", storageRoot::toString);
        registry.add("iphoto.storage.photos", () -> storageRoot.resolve("originals").toString());
        registry.add("iphoto.storage.thumbnails", () -> storageRoot.resolve("thumbnails").toString());
        registry.add("iphoto.storage.temp", () -> storageRoot.resolve("temp").toString());
        registry.add("iphoto.storage.chunks", () -> storageRoot.resolve("chunks").toString());
    }

    protected MockHttpSession loginAsAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/login")
                        .param("username", "admin")
                        .param("password", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    protected UUID createSpace(MockHttpSession session, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/spaces")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SpaceCreateRequest(name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // Uploads a single-chunk JPEG straight into targetSpaceId, forces the async PROCESS_UPLOAD
    // task to run immediately (rather than waiting on TaskWorkerManager's 5s @Scheduled poll -
    // same code path, just not idling for the timer), and waits for it to complete.
    protected UUID uploadPhoto(MockHttpSession session, UUID targetSpaceId) throws Exception {
        byte[] jpegBytes = generateTestJpeg();
        String uploadId = UUID.randomUUID().toString();

        MvcResult uploadResult = mockMvc.perform(multipart("/api/upload/chunk")
                        .file(new MockMultipartFile("chunk", "test-photo.jpg", "image/jpeg", jpegBytes))
                        .param("uploadId", uploadId)
                        .param("fileName", "test-photo.jpg")
                        .param("fileSize", String.valueOf(jpegBytes.length))
                        .param("chunkIndex", "0")
                        .param("totalChunks", "1")
                        .param("contentType", "image/jpeg")
                        .param("spaceId", targetSpaceId.toString())
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        UUID taskId = UUID.fromString(response.get("task").get("id").asText());
        UUID photoId = UUID.fromString(response.get("task").get("photoId").asText());

        waitForTaskCompletion(session, taskId);
        return photoId;
    }

    protected void waitForTaskCompletion(MockHttpSession session, UUID taskId) throws Exception {
        taskWorkerManager.pollPendingTasks(); // dispatch immediately instead of waiting on the timer
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/tasks/{id}", taskId).session(session))
                    .andExpect(status().isOk())
                    .andReturn();
            String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
            if ("COMPLETED".equals(status)) return;
            if ("FAILED".equals(status)) {
                throw new AssertionError("Task " + taskId + " failed");
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Task " + taskId + " did not complete in time");
    }

    protected static byte[] generateTestJpeg() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
