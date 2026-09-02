package org.iphoto;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Base for real Spring Boot integration tests: full application context, real Spring Security
// filter chain (MockMvc, not @WebMvcTest slices), and a real Postgres instance via Testcontainers
// (@ServiceConnection auto-wires spring.datasource.* from the container - no manual property
// wiring needed for the datasource itself). H2/an in-memory DB was deliberately not used: this
// app is Postgres-only in practice (native window-function queries, BYTEA columns, Postgres
// dialect hardcoded in application.yml) and relies on ddl-auto=update for schema creation (no
// Flyway/Liquibase in this project), so a real Postgres is both more representative and no more
// setup than any alternative.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

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
}
