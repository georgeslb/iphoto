package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.TaskType;
import org.iphoto.model.UploadStatus;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// processNewAsset has no HTTP endpoint of its own - PhotoController/UploadController go through
// PhotoService.uploadAsset instead, which only calls this on a genuinely new (non-duplicate) file
// hash (see PhotoLifecycleIntegrationTest/PhotoServiceTest for that full flow) - exercised here
// directly with a hand-built payload, the same shape UploadController assembles. No
// @MainSpaceLimited repository involved.
class PhotoUploadServiceTest extends AbstractIntegrationTest {

    @Autowired
    private PhotoUploadService photoUploadService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void processNewAssetPersistsThePhotoInTheRequestedSpace() throws Exception {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        byte[] jpegBytes = generateTestJpeg();
        Path tempFile = Files.createTempFile("upload-source", ".jpg");
        Files.write(tempFile, jpegBytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(jpegBytes));

        UUID photoId = UUID.randomUUID();
        TaskEntity task = TaskEntity.builder().id(UUID.randomUUID()).photoId(photoId).taskType(TaskType.PROCESS_UPLOAD).build();
        Map<String, String> payload = new HashMap<>();
        payload.put("originalFileName", "direct-upload.jpg");
        payload.put("contentType", "image/jpeg");

        UploadStatus status = photoUploadService.processNewAsset(task, admin.getId(), payload, tempFile.toFile(), hash);

        assertThat(status).isEqualTo(UploadStatus.SUCCESS);
        assertThat(photoRepository.findById(photoId)).isPresent();
        assertThat(photoRepository.findById(photoId).orElseThrow().getFileName()).isEqualTo("direct-upload.jpg");
    }

    @Test
    void extractBestCreationDateFallsBackToTheFilenamePatternWhenThereIsNoExif() throws Exception {
        File file = Files.createTempFile("IMG_20230615_143022", ".jpg").toFile();
        Files.write(file.toPath(), generateTestJpeg());

        LocalDateTime date = photoUploadService.extractBestCreationDate(file, file.getName());

        assertThat(date).isEqualTo(LocalDateTime.of(2023, 6, 15, 14, 30, 22));
    }

    @Test
    void extractBestCreationDateOverloadTakingAPhotoEntityDelegatesToItsFileName() throws Exception {
        File file = Files.createTempFile("no-date-pattern-here", ".jpg").toFile();
        Files.write(file.toPath(), generateTestJpeg());
        var photo = org.iphoto.entities.PhotoEntity.builder().fileName("IMG_20211231_235959.jpg").build();

        LocalDateTime date = photoUploadService.extractBestCreationDate(file, photo);

        assertThat(date).isEqualTo(LocalDateTime.of(2021, 12, 31, 23, 59, 59));
    }
}
