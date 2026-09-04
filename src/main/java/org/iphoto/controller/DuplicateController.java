package org.iphoto.controller;

import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.iphoto.model.DuplicateGroupDTO;
import org.iphoto.model.request.UnlinkPhotoDuplicate;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.DuplicationService;
import org.iphoto.service.SpaceService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/photos/duplicates")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DuplicateController extends AbstractBaseController {

    private DuplicationService duplicationService;

    public DuplicateController(DuplicationService duplicationService, UserRepository  userRepository, SpaceService spaceService) {
        super(userRepository);
        this.duplicationService = duplicationService;
    }

    @GetMapping
    @Deprecated
    public Slice<DuplicateGroupDTO> getDuplicates(@PageableDefault(size = 1) Pageable pageable) {
        return duplicationService.getDuplicates(pageable);
    }

    @PutMapping("/unlink")
    public ResponseEntity<Void> finishChanges(@RequestBody UnlinkPhotoDuplicate payload) {
        duplicationService.unlinkPhotoFromGroup(payload.unlinkedPhotoId(), payload.duplicatesId());
        return ResponseEntity.ok().build();
    }
}