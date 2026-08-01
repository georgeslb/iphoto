package org.iphoto.controller;

import org.iphoto.entities.UserEntity;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController extends AbstractBaseController {

    private final AdminService adminService;

    public AdminController(AdminService adminService, UserRepository userRepository) {
        super(userRepository);
        this.adminService = adminService;
    }


    @GetMapping("/activateFaceRecognition")
    public void activateFaceRecognition() {
      adminService.activateFaceRecognition();
    }

    @GetMapping("/updateImageTaskToDo")
    public void updateImageTaskToDo() {
        UserEntity currentUser = getCurrentUser();
        adminService.updateImageTaskToDo();
    }
}