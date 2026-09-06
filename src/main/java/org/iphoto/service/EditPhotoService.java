package org.iphoto.service;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.request.PhotoEditRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

public interface EditPhotoService {

    PhotoEntity editImage(
            UserEntity user,
            UUID id,
            MultipartFile file,
            PhotoEditRequest request
    ) throws IOException;
}
