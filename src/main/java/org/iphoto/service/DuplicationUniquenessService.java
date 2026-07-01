package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.iphoto.entities.DuplicateEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.DuplicateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

import static org.iphoto.model.DuplicateStatus.OPEN;

@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicationUniquenessService {

    private final DuplicateRepository duplicateRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskStatus consolidateDuplicates(PhotoEntity photo, Set<PhotoEntity> duplicatesPhotos) { // Added UserEntity parameter
        try {
            List<UUID> ids = duplicatesPhotos.stream().map(PhotoEntity::getId).toList();
            List<DuplicateEntity> existingDuplicates =  duplicateRepository.findByPhotosIdIn(ids); // Filter by user
            boolean addNewDuplicatePhoto = true;
            for (DuplicateEntity duplicate : existingDuplicates) {
                if (duplicate.getPhotos().stream().map(PhotoEntity::getId)
                        .filter(id -> !id.equals(photo.getId()))
                        .allMatch(ids::contains) && duplicate.getPhotos().size() - 1 >= ids.size()) {
                    // photo should be added to duplicate group
                    duplicate.getPhotos().add(photo);
                    duplicateRepository.saveAndFlush(duplicate);
                    addNewDuplicatePhoto = false;
                    break;
                }
            }
            if (addNewDuplicatePhoto) {
                // create a new duplicate group
                Set<PhotoEntity> duplicatesPhotosToAdd = new HashSet<>(duplicatesPhotos);
                duplicatesPhotosToAdd.add(photo);
                DuplicateEntity duplicateEntity = DuplicateEntity.builder()
                        .id(UUID.randomUUID())
                        .user(photo.getUser()) // Set the user for the new duplicate group
                        .photos(duplicatesPhotosToAdd)
                        .status(OPEN)
                        .hash(generateUniqueHash(duplicatesPhotosToAdd))
                        .build();
                duplicateRepository.saveAndFlush(duplicateEntity);
            }
           return TaskStatus.COMPLETED;
        } catch (Exception e) {
            if (!isUniqueConstraintViolation(e)) {
                log.error("DuplicationUniquenessService consolidateDuplicates failed", e);
                return TaskStatus.FAILED;
            }
        }
        return TaskStatus.PENDING;
    }

    private boolean isUniqueConstraintViolation(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                return cause.getMessage().contains("hash");
            }

            cause = cause.getCause();
        }
        return false;
    }

    private String generateUniqueHash(Set<PhotoEntity> photos){
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            String canonical = photos.stream()
                    .distinct()
                    .sorted(Comparator.comparing(PhotoEntity::getFileHash))
                    .map(PhotoEntity::getFileHash)
                    .collect(Collectors.joining(","));

            return HexFormat.of()
                    .formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

    }

}