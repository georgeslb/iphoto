package org.iphoto.service;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.model.TaskStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface DuplicationUniquenessService {

    TaskStatus consolidateDuplicates(PhotoEntity photo, Set<PhotoEntity> duplicatesPhotos);

    List<UUID> undoGenerateUniqueHash(String hexHash);
}
