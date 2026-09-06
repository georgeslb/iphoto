package org.iphoto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.service.TaskWorkerManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Base for real Spring Boot integration tests: full application context, real Spring Security
// filter chain (MockMvc, not @WebMvcTest slices), and an in-memory H2 database instead of a real
// Postgres (no Testcontainers/Docker dependency for the test suite). Run in PostgreSQL
// compatibility mode (MODE=PostgreSQL below) since the app's own queries are written against
// Postgres (a native `ORDER BY random() LIMIT` query, `BYTEA` columnDefinitions) - the Hibernate
// dialect is still overridden to H2Dialect (not PostgreSQLDialect, which assumes a real Postgres
// server on the wire). ddl-auto=update (inherited from application.yml) builds the schema the
// same way the real app does, no separate test-only migration setup needed.
//
// DB_CLOSE_DELAY=-1 keeps this in-memory database alive for the life of the JVM regardless of
// connection pool churn (the pool's own max-lifetime/idle-timeout are tuned short for a real
// server, see application.yml) - without it, H2 would drop the whole database the moment the
// last pooled connection closes. The same fixed URL across every subclass means Spring reuses one
// cached application context (and this one shared database) for the whole test run, rather than
// starting a new one per test class - test isolation instead comes from each test using its own
// randomly-generated names/ids, the same assumption the previous Testcontainers-per-JVM setup
// already relied on (one container, shared and reused across every test class in the same run).
//
// Also carries the login/space/upload/task-wait helpers shared by every subclass's flowing
// end-to-end scenario (originally written for PhotoLifecycleIntegrationTest, hoisted here once
// other scenarios needed the exact same "log in, upload a real photo, force the async task poller
// instead of waiting on its timer" setup).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:iphototest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

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

    // A handful of service methods with no HTTP endpoint of their own still route through a
    // @MainSpaceLimited-annotated repository query (see MainSpaceAspectConfiguration) that reads
    // the current user off SecurityContextHolder - normally populated by the security filter
    // chain around a real request, but empty in a bare JUnit test thread calling a service
    // directly. Pushes a real "admin" Authentication for the duration of `action`, then restores
    // whatever was there before, mirroring what the filter chain does around a real request.
    protected <T> T runAsAdmin(Callable<T> action) throws Exception {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    // A random fill color per call, not a fixed image - PhotoRepository.findByFileHash enforces
    // file-hash uniqueness GLOBALLY (a deliberate dedup feature, not a bug - see its own comment),
    // and this whole test suite shares one H2 database across every test class/method (see this
    // class's own header comment). A byte-identical JPEG on every call would make every upload
    // after the very first one in the whole run collide as a content-duplicate (uploadAsset
    // returns DUPLICATED, the task never reaches COMPLETED) - reproduced live while writing
    // AlbumServiceTest, once enough other tests already calling this existed earlier in the run.
    protected static byte[] generateTestJpeg() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(java.util.concurrent.ThreadLocalRandom.current().nextInt(0x1000000)));
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
