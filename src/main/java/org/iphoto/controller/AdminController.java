package org.iphoto.controller;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.*;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.AdminService;
import org.iphoto.service.AlbumService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final UserRepository userRepository; // Inject UserRepository

    private UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    @GetMapping("/activateFaceRecognition")
    public void activateFaceRecognition() {
      UserEntity currentUser = getCurrentUser();
      adminService.activateFaceRecognition(currentUser);
    }

    @GetMapping("/updateImageTaskToDo")
    public void updateImageTaskToDo() {
        UserEntity currentUser = getCurrentUser();
        adminService.updateImageTaskToDo(currentUser);
    }
}