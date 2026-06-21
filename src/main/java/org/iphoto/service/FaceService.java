package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.Photo;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PeopleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceService {

    private final FaceRepository faceRepository;
    private final PeopleRepository peopleRepository;
    private final PhotoService photoService;

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

    public Page<PersonDTO> getPersons(Pageable pageable) {
        return peopleRepository.findAll(pageable)
                .map(this::mapToPerson);
    }

    private PersonDTO mapToPerson(PersonEntity personEntity) {
        return new PersonDTO(personEntity.getId(), personEntity.getName(), mapToDTO(personEntity.getCoverFace()), null);
    }

    public Page<FaceDTO> getPersonFaces(UUID id, Pageable pageable) {
        return faceRepository.findByPersonId(id, pageable)
                .map(this::mapToDTO);
    }

    public Page<Photo> getPersonPhotos(UUID id, Pageable pageable) {
        return peopleRepository.findById(id).map(personEntity -> photoService.findByPerson(personEntity, pageable))
                .orElseThrow(() -> new RuntimeException("no person found with id " + id));
    }

    public void updatePerson(UUID id, PersonDTO personDTO) {
        peopleRepository.findById(id).ifPresent(personEntity -> {
            personEntity.setName(personDTO.name());
            peopleRepository.save(personEntity);
        });
    }
}
