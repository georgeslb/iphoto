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
import org.iphoto.repository.PersonRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final PhotoService photoService;
    private final PersonRecognitionService personRecognitionService;

    @Transactional
    public List<FaceDTO> getFacesForPhoto(UUID photoId) {
        return faceRepository.findByPhotoId(photoId)
                .stream()
                .map(this::mapToDTO)
                .toList();
    }

    private FaceDTO mapToDTO(FaceEntity faceEntity) {
        String base64Image = null;
        if (faceEntity.getFaceThumbnail() != null) {
            base64Image = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(faceEntity.getFaceThumbnail());
        }

        String personName = faceEntity.getPerson() != null ? faceEntity.getPerson().getName() : null;

        return new FaceDTO(
                faceEntity.getId(),
                faceEntity.getX(),
                faceEntity.getY(),
                faceEntity.getWidth(),
                faceEntity.getHeight(),
                base64Image,
                personName
        );
    }

    public Page<PersonDTO> getPersons(Pageable pageable) {
        return personRepository.findAll(pageable)
                .map(this::mapToPerson); // Pass user to mapToPerson
    }

    public PersonDTO getPerson(UUID id) {
        return personRepository.findById(id)
                .map(this::mapToPerson)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + id));
    }

    private PersonDTO mapToPerson(PersonEntity personEntity) { // Added UserEntity parameter
        FaceDTO coverPhoto = personEntity.getCoverFace() != null ? mapToDTO(personEntity.getCoverFace()) : null;
        int photoCount = (int) photoService.findByPerson(personEntity, Pageable.ofSize(1)).getTotalElements();
        return new PersonDTO(personEntity.getId(), personEntity.getName(), coverPhoto, photoCount);
    }

    public Page<FaceDTO> getPersonFaces(UUID id, Pageable pageable) {
        return faceRepository.findByPersonId(id, pageable)
                .map(this::mapToDTO);
    }

    public Page<Photo> getPersonPhotos(UUID id, Pageable pageable) {
        return personRepository.findById(id).map(personEntity -> photoService.findByPerson(personEntity, pageable)) // Pass user to photoService
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + id));
    }

    public void updatePerson(UUID id, PersonDTO personDTO) {
        personRepository.findById(id).ifPresent(personEntity -> { // Filter by user
            personEntity.setName(personDTO.name());
            personRepository.save(personEntity);
        });
    }

    public Optional<FaceEntity> findById(UserEntity user, UUID id) {
        return faceRepository.findById(id);
    }

    @Transactional
    public void deleteFace(UUID id) {
        FaceEntity face = faceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("no face found with id " + id));

        // If this was someone's cover face, pick a replacement immediately rather than leaving it
        // null — same pattern as FaceDetectionService.deleteFaces for a whole-photo deletion. The
        // face row hasn't been deleted yet at this point, so it must be removed from the person's
        // in-memory faces collection first or recalculateCoverFace would just re-pick it.
        personRepository.findByCoverFace(face).forEach(personEntity -> {
            personEntity.setCoverFace(null);
            personEntity.getFaces().remove(face);
            personRecognitionService.recalculateCoverFace(personEntity);
            personRepository.save(personEntity);
        });

        faceRepository.delete(face);
    }

    @Transactional
    public void unlinkPersonFromPhoto(UUID personId, UUID photoId) {
        PersonEntity person = personRepository.findById(personId)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + personId));

        List<FaceEntity> faces = faceRepository.findByPersonIdAndPhotoId(personId, photoId);
        if (faces.isEmpty()) {
            throw new NoSuchElementException("no face links person " + personId + " to photo " + photoId);
        }

        // Compare by id, not equals() — FaceEntity's @EqualsAndHashCode has no included fields.
        FaceEntity coverFace = person.getCoverFace();
        boolean coverFaceUnlinked = coverFace != null
                && faces.stream().anyMatch(face -> face.getId().equals(coverFace.getId()));

        faces.forEach(face -> {
            face.setPerson(null);
            person.getFaces().remove(face);
        });
        faceRepository.saveAll(faces);

        if (coverFaceUnlinked) {
            // Same pattern as deleteFace: pick a replacement immediately rather than leaving the
            // cover blank until some unrelated future face-add/delete happens to recalculate it.
            person.setCoverFace(null);
            personRecognitionService.recalculateCoverFace(person);
            personRepository.save(person);
        }
    }
}