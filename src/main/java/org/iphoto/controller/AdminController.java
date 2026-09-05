package org.iphoto.controller;

import org.iphoto.entities.UserEntity;
import org.iphoto.model.StorageMigrationSummaryDTO;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
}