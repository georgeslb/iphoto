package org.iphoto.controller;

import org.iphoto.entities.UserEntity;
import org.iphoto.model.PageResponse;
import org.iphoto.model.StorageMigrationSummaryDTO;
import org.iphoto.model.TaskAdminDTO;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.model.request.TaskRestartRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.AdminService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController extends AbstractBaseController {

    private final AdminService adminService;

    public AdminController(AdminService adminService, UserRepository userRepository) {
        super(userRepository);
        this.adminService = adminService;
    }

    // Replaces the old one-shot /activateFaceRecognition button - see AdminService for why.
    @GetMapping("/heavyTasks")
    public Map<String, Boolean> getHeavyTasksActivated() {
        return Map.of("activate", adminService.isHeavyTasksActivated());
    }

    @PostMapping("/heavyTasks")
    public void setHeavyTasksActivated(@RequestBody Map<String, Boolean> body) {
        adminService.activateHeavyTasks(Boolean.TRUE.equals(body.get("activate")));
    }

    @GetMapping("/updateImageTaskToDo")
    public void updateImageTaskToDo() {
        UserEntity currentUser = getCurrentUser();
        adminService.updateImageTaskToDo();
    }

    // Optional one-time backfill moving pre-existing flat-stored photos/thumbnails into the
    // year/month storage layout - see StorageMigrationService. Safe to call more than once.
    @PostMapping("/storage/migrate-flat-files")
    public StorageMigrationSummaryDTO migrateFlatFilesToOrganizedStorage() {
        return adminService.migrateFlatFilesToOrganizedStorage();
    }

    // Admin > Tasks page (TaskManagement.jsx) - status/taskType are both optional filters; either
    // or both may be omitted for no filtering on that field. Sorted newest-first by default, same
    // as every other admin-facing list in this app.
    @GetMapping("/tasks")
    public PageResponse<TaskAdminDTO> getTasks(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskType taskType,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(adminService.getTasks(status, taskType, pageable));
    }

    // Per-row "Restart" button - resets one task back to PENDING regardless of its current status
    // (see AdminServiceImpl#resetToPending for why).
    @PostMapping("/tasks/{id}/restart")
    public ResponseEntity<TaskAdminDTO> restartTask(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(adminService.restartTask(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Mass-selection "Restart Selected" button - one round trip for however many rows are checked.
    @PostMapping("/tasks/restart")
    public Map<String, Integer> restartTasks(@RequestBody TaskRestartRequest request) {
        return Map.of("restarted", adminService.restartTasks(request.taskIds()));
    }

    // Admin > Tasks page "Reprocessing" panel - re-run a whole pipeline stage from scratch. See
    // AdminService for exactly what each one resets.
    @PostMapping("/reprocess/faces")
    public Map<String, Integer> reprocessFaces() {
        return Map.of("tasksReset", adminService.restartFaceExtraction());
    }

    @PostMapping("/reprocess/duplicates")
    public Map<String, Integer> reprocessDuplicates() {
        return Map.of("tasksReset", adminService.restartDuplicationDetection());
    }

    // Destructive: deletes every person and detaches every face before re-running grouping (the
    // faces themselves are kept). The frontend confirms before calling this.
    @PostMapping("/reprocess/persons")
    public Map<String, Integer> reprocessPersons() {
        return adminService.restartPersonRecognition();
    }
}