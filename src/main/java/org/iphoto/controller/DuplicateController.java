package org.iphoto.controller;

import org.iphoto.entities.UserEntity;
import org.iphoto.model.DuplicateGroupDTO;
import org.iphoto.model.request.FinishComparisonRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.DuplicationService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/photos/duplicates")
public class DuplicateController extends AbstractBaseController {

    private final DuplicationService duplicationService;

    public DuplicateController(DuplicationService duplicationService, UserRepository  userRepository) {
        super(userRepository);
        this.duplicationService = duplicationService;
    }

    @GetMapping
    public Slice<DuplicateGroupDTO> getDuplicates(@PageableDefault(size = 1) Pageable pageable) {
        UserEntity currentUser = getCurrentUser();
        return duplicationService.getDuplicates(currentUser, pageable);
    }

    @PutMapping("/finishChanges")
    public ResponseEntity<Void> finishChanges(@RequestBody FinishComparisonRequest payload) {
        UserEntity currentUser = getCurrentUser();
        String hash = payload.hash();
        if (hash != null) {
            duplicationService.closeDuplicates(currentUser, hash);
        }
        return ResponseEntity.ok().build();
    }
}