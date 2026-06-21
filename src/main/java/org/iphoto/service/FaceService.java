package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.FaceEntity;
import org.iphoto.model.FaceDTO;
import org.iphoto.repository.FaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceService {

    private final FaceRepository faceRepository;

    @Transactional
    public List<FaceDTO> getFacesForPhoto(UUID photoId) {
        return faceRepository.findAllByPhotoId(photoId)
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    private FaceDTO mapToDTO(FaceEntity faceEntity) {
        String base64Image = null;
        if (faceEntity.getFaceThumbnail() != null) {
            base64Image = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(faceEntity.getFaceThumbnail());
        }

        return new FaceDTO(
                faceEntity.getX(),
                faceEntity.getY(),
                faceEntity.getWidth(),
                faceEntity.getHeight(),
                base64Image
        );
    }
}
