package org.iphoto.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.model.FaceDTO;
import org.iphoto.service.FaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/faces")
@AllArgsConstructor
@Slf4j
public class FaceController {

    private final FaceService faceService;


    @GetMapping("/photo/{id}")
    public ResponseEntity<List<FaceDTO>> getFacesForPhoto(@PathVariable String id) {
        return ResponseEntity.ok(faceService.getFacesForPhoto(UUID.fromString(id)));
    }
}
