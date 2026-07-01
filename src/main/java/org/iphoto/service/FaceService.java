package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.Photo;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PeopleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceService {

    private final FaceRepository faceRepository;
    private final PeopleRepository peopleRepository;
    private final PhotoService photoService;

    @Transactional
    public List<FaceDTO> getFacesForPhoto(UserEntity user, UUID photoId) {
        return faceRepository.findAllByUserAndPhotoId(user, photoId)
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

    public Page<PersonDTO> getPersons(UserEntity user, Pageable pageable) {
        return peopleRepository.findByUser(user, pageable) // Assuming findAllByUser exists in PeopleRepository
                .map(personEntity -> mapToPerson(user, personEntity)); // Pass user to mapToPerson
    }

    private PersonDTO mapToPerson(UserEntity user, PersonEntity personEntity) { // Added UserEntity parameter
        return new PersonDTO(personEntity.getId(), personEntity.getName(), mapToDTO(personEntity.getCoverFace()), null);
    }

    public Page<FaceDTO> getPersonFaces(UserEntity user, UUID id, Pageable pageable) {
        return faceRepository.findByUserAndPersonId(user, id, pageable)
                .map(this::mapToDTO);
    }

    public Page<Photo> getPersonPhotos(UserEntity user, UUID id, Pageable pageable) {
        return peopleRepository.findByIdAndUser(id, user).map(personEntity -> photoService.findByPerson(user, personEntity, pageable)) // Pass user to photoService
                .orElseThrow(() -> new RuntimeException("no person found with id " + id));
    }

    public void updatePerson(UserEntity user, UUID id, PersonDTO personDTO) {
        peopleRepository.findByIdAndUser(id, user).ifPresent(personEntity -> { // Filter by user
            personEntity.setName(personDTO.name());
            peopleRepository.save(personEntity);
        });
    }

    public Optional<FaceEntity> findById(UserEntity user, UUID id) {
        return faceRepository.findByIdAndUser(id, user);
    }
}