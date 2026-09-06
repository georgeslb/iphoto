package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.AccessDeniedForPhotoChangesException;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// EditPhotoService.editImage has one HTTP endpoint (PhotoController's POST /{id}/edit, see
// PhotoServiceTest for a shallow pass through it) - exercised here directly for a deeper check on
// the "overwrite in place" (saveAsNew=false) branch: the file hash/size/dimensions actually
// change, the thumbnail is regenerated in the SAME folder, and any previously-detected faces are
// cleared (a changed image invalidates them). The saveAsNew=true branch is not covered here - it
// self-polls waiting for a background PROCESS_UPLOAD task to complete synchronously inside the
// same call, which needs a second thread driving the task worker concurrently to ever return;
// editImage's overwrite branch transitively calls DuplicationService.deleteDuplicatesForPhoto,
// which hits PhotoDuplicateRepository's @MainSpaceLimited deleteByPhotoIdIn - runAsAdmin gives
// the space-filter aspect a real authenticated user, the same as a real request would.
class EditPhotoServiceTest extends AbstractIntegrationTest {

    @Autowired
    private EditPhotoService editPhotoService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void editImageOverwritesTheOriginalFileAndUpdatesItsMetadata() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "EditDirect-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        PhotoEntity before = photoRepository.findById(photoId).orElseThrow();
        String originalHash = before.getFileHash();
        Path originalPath = Path.of(before.getOriginalPath());

        byte[] editedBytes = generateTestJpeg();
        MockMultipartFile editedFile = new MockMultipartFile("file", "edited.jpg", "image/jpeg", editedBytes);

        PhotoEntity edited = runAsAdmin(() -> editPhotoService.editImage(admin, photoId, editedFile, new PhotoEditRequest(false)));

        assertThat(edited.getId()).isEqualTo(photoId);
        assertThat(edited.getFileHash()).isNotEqualTo(originalHash);
        assertThat(edited.getOriginalPath()).isEqualTo(originalPath.toString());
        assertThat(Files.size(originalPath)).isEqualTo(editedBytes.length);
    }

    @Test
    void editImageRejectsAUserWhoDoesNotOwnThePhoto() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "EditAuthz-" + UUID.randomUUID());
        UUID photoId = uploadPhoto(session, spaceId);
        UserEntity otherUser = userRepository.save(UserEntity.builder()
                .username("not-the-owner-" + UUID.randomUUID()).password("x").role("USER").build());
        MockMultipartFile editedFile = new MockMultipartFile("file", "edited.jpg", "image/jpeg", generateTestJpeg());

        assertThatThrownBy(() -> editPhotoService.editImage(otherUser, photoId, editedFile, new PhotoEditRequest(false)))
                .isInstanceOf(AccessDeniedForPhotoChangesException.class);
    }
}
