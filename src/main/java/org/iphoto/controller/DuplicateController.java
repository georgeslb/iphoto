package org.iphoto.controller;

import lombok.AllArgsConstructor;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.DuplicateGroupDTO;
import org.iphoto.model.FinishComparisonRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.DuplicationService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/photos/duplicates")
@AllArgsConstructor
public class DuplicateController {

    private final DuplicationService duplicationService;
    private final UserRepository userRepository; // Inject UserRepository

    private UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
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