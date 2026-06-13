package org.iphoto.controller;

import lombok.RequiredArgsConstructor;
import org.iphoto.model.*;
import org.iphoto.service.AdminService;
import org.iphoto.service.AlbumService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;


    @GetMapping("/activateFaceRecognition")
    public void activateFaceRecognition() {
      adminService.activateFaceRecognition();
    }

    @GetMapping("/updateImageTaskToDo")
    public void updateImageTaskToDo() {
        adminService.updateImageTaskToDo();
    }
}
