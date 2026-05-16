package org.iphoto.controller;

import lombok.AllArgsConstructor;
import org.iphoto.model.DuplicateGroupDTO;
import org.iphoto.model.FinishComparisonRequest;
import org.iphoto.service.DuplicationService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/photos/duplicates")
@AllArgsConstructor
public class DuplicateController {

    private final DuplicationService duplicationService;

    @GetMapping
    public Slice<DuplicateGroupDTO> getDuplicates(@PageableDefault(size = 1) Pageable pageable) {
        return duplicationService.getDuplicates(pageable);
    }

    @PutMapping("/finishChanges")
    public ResponseEntity<Void> finishChanges(@RequestBody FinishComparisonRequest payload) {
        String hash = payload.hash();
        if (hash != null) {
            duplicationService.closeDuplicates(hash);
        }
        return ResponseEntity.ok().build();
    }
}
