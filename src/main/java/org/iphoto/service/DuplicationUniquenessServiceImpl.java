package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.iphoto.entities.DuplicateEntity;
import org.iphoto.entities.PhotoDuplicateEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.DuplicateRepository;
import org.iphoto.repository.PhotoDuplicateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

import static org.iphoto.model.DuplicateStatus.IN_GROUP;

@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicationUniquenessServiceImpl implements DuplicationUniquenessService {

    private final DuplicateRepository duplicateRepository;
    private final PhotoDuplicateRepository photoDuplicateRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskStatus consolidateDuplicates(PhotoEntity photo, Set<PhotoEntity> duplicatesPhotos) {
        try {
            List<UUID> ids = duplicatesPhotos.stream().map(PhotoEntity::getId).toList();
            List<PhotoDuplicateEntity> photoDuplicateEntities = photoDuplicateRepository.findByPhotoIdIn(ids);
            Set<DuplicateEntity> existingDuplicates = photoDuplicateEntities.stream().map(PhotoDuplicateEntity::getDuplicate).collect(Collectors.toSet());
            if (!existingDuplicates.isEmpty()) {
                for (DuplicateEntity duplicateEntity : existingDuplicates) {
                    Set<PhotoDuplicateEntity> existingDuplicatePhotosInEntity = duplicateEntity.getPhotoDuplicates();
                    // photo may already be a member of this group (e.g. this task is a retry, or
                    // another of its candidate duplicates already pulled it in during this same
                    // pass) - only add it once. PhotoDuplicateEntity's own equals/hashCode
                    // (photo+duplicate) would normally make the Set.add below a safe no-op on its
                    // own, but that relies on the lazy `photo` proxy comparing equal to the fresh
                    // one built here, which needs PhotoEntity's own equals/hashCode to cooperate -
                    // an explicit id check here doesn't depend on that.
                    boolean alreadyInGroup = existingDuplicatePhotosInEntity.stream()
                            .anyMatch(pd -> pd.getPhoto().getId().equals(photo.getId()));
                    if (alreadyInGroup) {
                        continue;
                    }
                    existingDuplicatePhotosInEntity.add(buildPhotoDuplicate(photo, duplicateEntity));
                    duplicateEntity.setHash(generateUniqueHash(existingDuplicatePhotosInEntity));
                    duplicateRepository.save(duplicateEntity);
                }
            } else {
                // create a new duplicate group
                DuplicateEntity duplicateEntity = DuplicateEntity.builder()
                        .id(UUID.randomUUID())
                        .space(photo.getSpace())
                        .build();
                //FIXME les duplicate sont ajouté plusieurs fois
                Set<PhotoDuplicateEntity> duplicatesPhotosToAdd = duplicatesPhotos.stream().map(p -> buildPhotoDuplicate(p, duplicateEntity)).collect(Collectors.toSet());
                duplicatesPhotosToAdd.add(buildPhotoDuplicate(photo, duplicateEntity));
                duplicateEntity.setHash(generateUniqueHash(duplicatesPhotosToAdd));
                duplicateEntity.setPhotoDuplicates(duplicatesPhotosToAdd);
                duplicateRepository.save(duplicateEntity);
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

    private PhotoDuplicateEntity buildPhotoDuplicate(PhotoEntity photo, DuplicateEntity duplicateEntity) {
        return PhotoDuplicateEntity.builder().photo(photo).duplicate(duplicateEntity).status(IN_GROUP).build();
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

    private String generateUniqueHash(Set<PhotoDuplicateEntity> photoDuplicates){
        MessageDigest digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            String canonical = photoDuplicates.stream()
                    .map(PhotoDuplicateEntity::getPhoto)
                    .sorted(Comparator.comparing(PhotoEntity::getFileHash))
                    .map(PhotoEntity::getFileHash)
                    .collect(Collectors.joining(","));

            return HexFormat.of()
                    .formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public List<UUID> undoGenerateUniqueHash(String hexHash) {
        Optional<DuplicateEntity> duplicateEntityOptional = duplicateRepository.findByHash(hexHash);
        return duplicateEntityOptional.map(duplicateEntity ->
                duplicateEntity.getPhotoDuplicates().stream()
                        .map(PhotoDuplicateEntity::getPhoto)
                        .map(PhotoEntity::getId)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }
}
