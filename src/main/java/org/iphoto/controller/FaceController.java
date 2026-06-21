package org.iphoto.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.Photo;
import org.iphoto.service.FaceService;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/faces")
@AllArgsConstructor
@Slf4j
public class FaceController {

    private final FaceService faceService;


    @GetMapping("/photo/{id}")
    public ResponseEntity<List<FaceDTO>> getFacesForPhoto(@PathVariable String id) {
        return ResponseEntity.ok(faceService.getFacesForPhoto(UUID.fromString(id)));
    }

    @GetMapping("/persons")
    public ResponseEntity<Page<PersonDTO>> getPersons(@PageableDefault(size = 50, sort = "name", direction = Sort.Direction.DESC)  Pageable pageable) {
        return ResponseEntity.ok(faceService.getPersons(pageable));
    }

    @PutMapping("/person/{id}")
    public void getPersonFaces(@PathVariable UUID id, @RequestBody PersonDTO personDTO) {
        faceService.updatePerson(id, personDTO);
    }

    @GetMapping("/person/{id}/faces")
    public ResponseEntity<Page<FaceDTO>> getPersonFaces(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC)  Pageable pageable) {
        return ResponseEntity.ok(faceService.getPersonFaces(id, pageable));
    }

    @GetMapping("/person/{id}/photos")
    public ResponseEntity<Page<Photo>> getPersonPhotos(@PathVariable UUID id, @PageableDefault(size = 50, sort = "dateTaken", direction = Sort.Direction.DESC) Pageable pageable) {
        try {
            Page<Photo> photos = faceService.getPersonPhotos(id, pageable);
            return ResponseEntity.ok(photos);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}
