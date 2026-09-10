package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.FaceStatus;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.iphoto.repository.TaskRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Driven through AdminController's real endpoints - every AdminService public method maps 1:1
// onto one of its routes.
class AdminServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private FaceRepository faceRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private UserRepository userRepository;

    private TaskEntity saveTask(TaskType taskType, TaskStatus status, String errorMessage) {
        TaskEntity task = new TaskEntity();
        task.setTaskType(taskType);
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setExecuteAt(LocalDateTime.now());
        return taskRepository.save(task);
    }

    private PhotoEntity savePhoto(SpaceEntity space, UserEntity user) {
        String seed = UUID.randomUUID().toString();
        return photoRepository.save(PhotoEntity.builder()
                .id(UUID.randomUUID())
                .fileName(seed + ".jpg")
                .fileHash(HexFormat.of().formatHex(seed.getBytes()))
                .contentType("image/jpeg")
                .user(user)
                .space(space)
                .originalPath("/tmp/" + seed + ".jpg")
                .dateTaken(LocalDateTime.of(2024, 6, 1, 0, 0))
                .isFavorite(false)
                .isGarbage(false)
                .build());
    }

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

    @Test
    void tasksCanBeListedAndFilteredByStatusAndType() throws Exception {
        MockHttpSession session = loginAsAdmin();
        TaskEntity failedHash = saveTask(TaskType.HASH_IMAGE, TaskStatus.FAILED, "boom");
        saveTask(TaskType.CHECK_DUPLICATION, TaskStatus.COMPLETED, null);

        MvcResult unfiltered = mockMvc.perform(get("/api/admin/tasks").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode unfilteredBody = objectMapper.readTree(unfiltered.getResponse().getContentAsString());
        assertThat(unfilteredBody.get("content").size()).isGreaterThanOrEqualTo(2);

        MvcResult filtered = mockMvc.perform(get("/api/admin/tasks")
                        .session(session)
                        .param("status", "FAILED")
                        .param("taskType", "HASH_IMAGE"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode filteredContent = objectMapper.readTree(filtered.getResponse().getContentAsString()).get("content");
        assertThat(filteredContent).hasSize(1);
        assertThat(filteredContent.get(0).get("id").asText()).isEqualTo(failedHash.getId().toString());
        assertThat(filteredContent.get(0).get("errorMessage").asText()).isEqualTo("boom");
    }

    @Test
    void restartTaskResetsItToPendingAndClearsItsError() throws Exception {
        MockHttpSession session = loginAsAdmin();
        TaskEntity failed = saveTask(TaskType.HASH_IMAGE, TaskStatus.FAILED, "boom");

        MvcResult result = mockMvc.perform(post("/api/admin/tasks/{id}/restart", failed.getId()).session(session))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("errorMessage").isNull()).isTrue();
        TaskEntity reloaded = taskRepository.findById(failed.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(reloaded.getErrorMessage()).isNull();
    }

    @Test
    void restartTaskReturnsNotFoundForAnUnknownId() throws Exception {
        MockHttpSession session = loginAsAdmin();

        mockMvc.perform(post("/api/admin/tasks/{id}/restart", UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void bulkRestartResetsEveryRequestedTaskAndReportsHowManyWereRestarted() throws Exception {
        MockHttpSession session = loginAsAdmin();
        TaskEntity failed = saveTask(TaskType.HASH_IMAGE, TaskStatus.FAILED, "boom");
        TaskEntity trashed = saveTask(TaskType.PROCESS_UPLOAD, TaskStatus.TRASHED, null);

        MvcResult result = mockMvc.perform(post("/api/admin/tasks/restart")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content("{\"taskIds\":[\"" + failed.getId() + "\",\"" + trashed.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("restarted").asInt()).isEqualTo(2);
        assertThat(taskRepository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskRepository.findById(trashed.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void reprocessFacesResetsEveryIsolateFacesTaskAndLeavesOtherTypesAlone() throws Exception {
        MockHttpSession session = loginAsAdmin();
        TaskEntity failedIsolate = saveTask(TaskType.ISOLATE_FACES, TaskStatus.FAILED, "boom");
        TaskEntity completedIsolate = saveTask(TaskType.ISOLATE_FACES, TaskStatus.COMPLETED, null);
        TaskEntity unrelated = saveTask(TaskType.CHECK_DUPLICATION, TaskStatus.COMPLETED, null);

        MvcResult result = mockMvc.perform(post("/api/admin/reprocess/faces").session(session))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("tasksReset").asInt())
                .isGreaterThanOrEqualTo(2);
        assertThat(taskRepository.findById(failedIsolate.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskRepository.findById(completedIsolate.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskRepository.findById(failedIsolate.getId()).orElseThrow().getErrorMessage()).isNull();
        // A different task type is untouched.
        assertThat(taskRepository.findById(unrelated.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void reprocessDuplicatesResetsEveryCheckDuplicationTaskAndLeavesOtherTypesAlone() throws Exception {
        MockHttpSession session = loginAsAdmin();
        TaskEntity failedDup = saveTask(TaskType.CHECK_DUPLICATION, TaskStatus.FAILED, "boom");
        TaskEntity unrelated = saveTask(TaskType.ISOLATE_FACES, TaskStatus.COMPLETED, null);

        MvcResult result = mockMvc.perform(post("/api/admin/reprocess/duplicates").session(session))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("tasksReset").asInt())
                .isGreaterThanOrEqualTo(1);
        assertThat(taskRepository.findById(failedDup.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskRepository.findById(unrelated.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void reprocessPersonsDeletesPeopleDetachesFacesAndKicksFaceRecognition() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        SpaceEntity mainSpace = spaceRepository.findByTypeEqualsMainAndUser(admin);
        PhotoEntity photo = savePhoto(mainSpace, admin);

        FaceEntity face = faceRepository.save(FaceEntity.builder()
                .photo(photo).x(1).y(1).width(50).height(50)
                .status(FaceStatus.OLD)
                .embedding(EmbeddingCodec.toBytes(new float[]{0.1f, 0.2f}))
                .build());
        PersonEntity person = personRepository.save(PersonEntity.builder().space(mainSpace).name("ToWipe").build());
        face.setPerson(person);
        faceRepository.save(face);

        MvcResult result = mockMvc.perform(post("/api/admin/reprocess/persons").session(session))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("personsDeleted").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("facesReset").asInt()).isGreaterThanOrEqualTo(1);

        assertThat(personRepository.findById(person.getId())).isEmpty();
        FaceEntity reloadedFace = faceRepository.findById(face.getId()).orElseThrow();
        assertThat(reloadedFace.getPerson()).isNull();
        assertThat(reloadedFace.getStatus()).isEqualTo(FaceStatus.NEW);

        TaskEntity faceRecognition = taskRepository
                .findFirstByTaskTypeOrderByCreatedAtDesc(TaskType.FACE_RECOGNITION).orElseThrow();
        assertThat(faceRecognition.getStatus()).isEqualTo(TaskStatus.PENDING);
    }
}
