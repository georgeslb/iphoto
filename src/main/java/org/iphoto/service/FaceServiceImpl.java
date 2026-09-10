package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.FaceLinkExclusionEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.exception.PersonNameCollisionException;
import org.iphoto.exception.PersonNameCollisionsException;
import org.iphoto.model.DayBucketDTO;
import org.iphoto.model.FaceDTO;
import org.iphoto.model.PersonDTO;
import org.iphoto.model.PersonMergeSuggestionDTO;
import org.iphoto.model.Photo;
import org.iphoto.repository.FaceLinkExclusionRepository;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceServiceImpl implements FaceService {

    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final PhotoService photoService;
    private final PersonRecognitionService personRecognitionService;
    private final FaceLinkExclusionRepository faceLinkExclusionRepository;
    private final PersonMergeSuggestionService personMergeSuggestionService;
    private final SpaceService spaceService;

    @Override
    @Transactional
    public List<FaceDTO> getFacesForPhoto(UserEntity user, UUID photoId) {
        PhotoEntity photo = photoService.findById(photoId)
                .filter(p -> p.getSpace().getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new NoSuchElementException("no photo found with id " + photoId));

        return faceRepository.findByPhotoId(photo.getId())
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

    @Override
    public Page<PersonDTO> getPersons(Pageable pageable) {
        // Named-first/photoCount-DESC is the fixed, non-negotiable default (see
        // PersonRepository.findAllOrderByNamedFirstThenPhotoCountDesc) - strip any Sort the caller
        // asked for via ?sort= so there's nothing for Spring Data to append after that query's own
        // ORDER BY, only page/size survive here.
        Pageable pageSizeOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return personRepository.findAllOrderByNamedFirstThenPhotoCountDesc(pageSizeOnly)
                .map(this::mapToPerson); // Pass user to mapToPerson
    }

    @Override
    public PersonDTO getPerson(UUID id) {
        return personRepository.findById(id)
                .map(this::mapToPerson)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + id));
    }

    private PersonDTO mapToPerson(PersonEntity personEntity) { // Added UserEntity parameter
        FaceDTO coverPhoto = personEntity.getCoverFace() != null ? mapToDTO(personEntity.getCoverFace()) : null;
        // photoCount is now a persisted, incrementally-maintained column (see
        // PersonRecognitionService.recalculatePhotoCount) rather than a live per-request count -
        // reading it directly here also avoids the N+1 query-per-person this used to do on every
        // Persons-page fetch.
        int photoCount = personEntity.getPhotoCount() == null ? 0 : personEntity.getPhotoCount();
        return new PersonDTO(personEntity.getId(), personEntity.getName(), coverPhoto, photoCount);
    }

    @Override
    public Page<FaceDTO> getPersonFaces(UUID id, Pageable pageable) {
        return faceRepository.findByPersonId(id, pageable)
                .map(this::mapToDTO);
    }

    @Override
    public List<DayBucketDTO> getPersonDays(UUID id) {
        return personRepository.findById(id).map(photoService::getPersonDailyPhotoCounts)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + id));
    }

    @Override
    public Page<Photo> getPersonPhotosForDay(UUID id, LocalDate day, Pageable pageable) {
        return personRepository.findById(id).map(personEntity -> photoService.getPersonPhotosForDay(personEntity, day, pageable))
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + id));
    }

    @Override
    @Transactional
    public PersonDTO renamePerson(UUID personId, String name, boolean confirmed, List<UUID> mergeWithPersonIds) {
        PersonEntity person = personRepository.findById(personId)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + personId));

        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String trimmed = name.trim();

        if (!confirmed) {
            List<PersonEntity> collisions = personRepository.findByNameIgnoreCaseAndIdNot(trimmed, personId);
            if (!collisions.isEmpty()) {
                throw new PersonNameCollisionsException(collisions.stream().map(PersonEntity::getId).toList());
            }
            person.setName(trimmed);
            personRepository.save(person);
            return mapToPerson(person);
        }

        person.setName(trimmed);
        personRepository.save(person);
        if (mergeWithPersonIds != null) {
            for (UUID sourceId : mergeWithPersonIds) {
                if (sourceId.equals(personId)) {
                    continue; // Defensive: never merge the target into itself.
                }
                personRecognitionService.mergeInto(personId, sourceId);
            }
        }

        return mapToPerson(personRepository.findById(personId)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + personId)));
    }

    @Override
    public List<PersonDTO> getPersons(List<UUID> ids) {
        return personRepository.findAllById(ids).stream().map(this::mapToPerson).toList();
    }

    @Override
    public Optional<FaceEntity> findById(UserEntity user, UUID id) {
        return faceRepository.findById(id);
    }

    @Override
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

        // Must happen before the face row is actually deleted below - removeFaceFromBucket still
        // needs to read this face's own embedding to subtract it back out of its person's bucket.
        PersonEntity person = face.getPerson();
        if (person != null) {
            personRecognitionService.removeFaceFromBucket(person, face);
        }

        faceRepository.delete(face);

        // photoCount must be recalculated AFTER the face row is actually gone, not before - it
        // counts DISTINCT photos still reachable through this person's remaining faces.
        if (person != null) {
            personRecognitionService.recalculatePhotoCount(person);
            personRepository.save(person);
        }
    }

    @Override
    @Transactional
    public void unlinkPersonFromPhoto(UUID personId, UUID photoId) {
        PersonEntity person = personRepository.findById(personId)
                .orElseThrow(() -> new NoSuchElementException("no person found with id " + personId));

        List<FaceEntity> faces = faceRepository.findByPersonIdAndPhotoId(personId, photoId);
        if (faces.isEmpty()) {
            throw new NoSuchElementException("no face links person " + personId + " to photo " + photoId);
        }

        faces.forEach(face -> detachFaceFromPerson(face, person));
        faceRepository.saveAll(faces);
        personRepository.save(person);
    }

    @Override
    @Transactional
    public void unlinkFaceFromPerson(UUID faceId) {
        FaceEntity face = faceRepository.findById(faceId)
                .orElseThrow(() -> new NoSuchElementException("no face found with id " + faceId));

        PersonEntity person = face.getPerson();
        if (person == null) {
            return; // Already unassigned — nothing to do.
        }

        detachFaceFromPerson(face, person);
        faceRepository.save(face);
        personRepository.save(person);
    }

    @Override
    @Transactional
    public FaceDTO setFacePerson(UUID faceId, String name, boolean confirmMerge) {
        FaceEntity face = faceRepository.findById(faceId)
                .orElseThrow(() -> new NoSuchElementException("no face found with id " + faceId));

        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("name must not be blank");
        }

        PersonEntity currentPerson = face.getPerson();
        Optional<PersonEntity> existingPersonOpt = personRepository.findByNameIgnoreCase(name.trim());

        boolean isDifferentPerson = existingPersonOpt.isPresent()
                && (currentPerson == null || !existingPersonOpt.get().getId().equals(currentPerson.getId()));

        if (isDifferentPerson) {
            PersonEntity targetPerson = existingPersonOpt.get();
            if (!confirmMerge) {
                throw new PersonNameCollisionException(targetPerson.getId());
            }
            if (currentPerson != null) {
                detachFaceFromPerson(face, currentPerson);
                personRepository.save(currentPerson);
            }
            attachFaceToPerson(face, targetPerson);
            personRepository.save(targetPerson);
        } else if (currentPerson != null) {
            currentPerson.setName(name.trim());
            personRepository.save(currentPerson);
        } else {
            PersonEntity newPerson = PersonEntity.builder()
                    .space(face.getPhoto().getSpace())
                    .name(name.trim())
                    .faces(new ArrayList<>())
                    .photoCount(0) // NOT NULL column - attachFaceToPerson below recalculates the real value
                    .build();
            // Must be persisted BEFORE attachFaceToPerson runs, not after: that method points
            // face.person at this object, and its exclusion cleanup issues a @Modifying bulk
            // query, which forces Hibernate to auto-flush the session first — flushing while
            // face.person still references a transient PersonEntity throws "persistent instance
            // references an unsaved transient instance" (same family of issue
            // PersonRecognitionService.addPerson already documents, different trigger).
            PersonEntity savedPerson = personRepository.saveAndFlush(newPerson);
            attachFaceToPerson(face, savedPerson);
            personRepository.save(savedPerson);
        }

        faceRepository.save(face);
        return mapToDTO(face);
    }

    // Removes the face from its person (recording an exclusion so automatic re-matching never
    // reattaches this exact pair — see FaceLinkExclusionEntity) and, if it was that person's cover
    // face, picks a replacement immediately. Does not save either entity — callers do that once
    // they're done making all their changes.
    private void detachFaceFromPerson(FaceEntity face, PersonEntity person) {
        if (!faceLinkExclusionRepository.existsByFaceIdAndExcludedPersonId(face.getId(), person.getId())) {
            faceLinkExclusionRepository.save(
                    FaceLinkExclusionEntity.builder()
                            .face(face)
                            .excludedPerson(person)
                            .build()
            );
        }

        boolean wasCoverFace = person.getCoverFace() != null && person.getCoverFace().getId().equals(face.getId());
        person.getFaces().remove(face);
        personRecognitionService.removeFaceFromBucket(person, face);
        face.setPerson(null);
        if (wasCoverFace) {
            person.setCoverFace(null);
            personRecognitionService.recalculateCoverFace(person);
        }
        personRecognitionService.recalculatePhotoCount(person);
    }

    // Attaches the face to a (possibly brand-new) person and recalculates its cover face.
    // Clears any stale exclusion for this exact pair — an explicit user action here should win
    // over a past manual unlink. Does not save either entity — callers do that afterward.
    private void attachFaceToPerson(FaceEntity face, PersonEntity person) {
        face.setPerson(person);
        person.getFaces().add(face);
        personRecognitionService.addFaceToBucket(person, face);
        faceLinkExclusionRepository.deleteByFaceIdAndExcludedPersonId(face.getId(), person.getId());
        personRecognitionService.recalculateCoverFace(person);
        personRecognitionService.recalculatePhotoCount(person);
    }

    @Override
    public PersonDTO mergePersons(List<UUID> personIds) {
        if (personIds == null || personIds.size() != 2 || personIds.get(0).equals(personIds.get(1))) {
            throw new IllegalArgumentException("mergePersons requires exactly 2 distinct person ids");
        }
        return mapToPerson(personRecognitionService.mergePersons(personIds.get(0), personIds.get(1)));
    }

    @Override
    public Page<PersonMergeSuggestionDTO> getMergeSuggestions(UserEntity user, Pageable pageable) {
        SpaceEntity space = spaceService.getDefaultSpaceForUser(user);
        // Fixed sort (most-similar-first) is baked into the repository's derived query - strip
        // any client-requested sort so there's nothing left for Spring Data to layer on top of
        // it, same convention as getPersons above.
        Pageable pageSizeOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return personMergeSuggestionService.getSuggestionsForSpace(space.getId(), pageSizeOnly)
                .map(suggestion -> new PersonMergeSuggestionDTO(
                        suggestion.getId(),
                        mapToPerson(suggestion.getPersonA()),
                        mapToPerson(suggestion.getPersonB()),
                        suggestion.getSimilarity()));
    }

    @Override
    public long countMergeSuggestions(UserEntity user) {
        SpaceEntity space = spaceService.getDefaultSpaceForUser(user);
        return personMergeSuggestionService.countSuggestionsForSpace(space.getId());
    }
}
