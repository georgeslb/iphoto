package org.iphoto.service;

import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.DetectedObjects.DetectedObject;
import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.repository.zoo.ZooModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.configuration.FaceAligner;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.util.EmbeddingCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;

@Service
@Slf4j
@RequiredArgsConstructor
public class FaceDetectionServiceImpl implements FaceDetectionService {

    private final FaceRepository faceRepository;
    private final PhotoRepository photoRepository;
    private final ZooModel<Image, DetectedObjects> detectionModel;
    private final ZooModel<Image, float[]> embeddingModel;
    private final PersonRepository personRepository;
    private final PersonRecognitionService personRecognitionService;

    // Hard floor (post-padding, see padX/padY below): below this, there just isn't enough source
    // resolution left for the embedding model to produce anything usable. RetinaFace's smallest
    // anchor tier (scale=16, see FaceDetectionConfiguration) is built to detect faces well below
    // the old 60px floor, so a flat 60px cutoff was discarding faces the detector correctly found.
    private static final int MIN_FACE_SIZE = 32;
    // Faces between MIN_FACE_SIZE and this used to be rejected outright. They're let through now,
    // but only when the detector is unusually confident about them (see SMALL_FACE_MIN_CONFIDENCE)
    // to compensate for the higher false-positive/noisy-embedding risk small crops carry.
    private static final int SMALL_FACE_SIZE_THRESHOLD = 60;
    // Stricter than the model's own confThresh (0.85, FaceDetectionConfiguration) — only applied to
    // the small-face band above, not to every detection.
    private static final double SMALL_FACE_MIN_CONFIDENCE = 0.92;


    @Override
    public void deleteFaces(PhotoEntity photo) {
        photo.getFaces().forEach(faceEntity -> {
            if (faceEntity.getPerson() != null) {
                personRecognitionService.removeFaceFromBucket(faceEntity.getPerson(), faceEntity);
            }
            personRepository.findByCoverFace(faceEntity).forEach(personEntity -> {
                personEntity.setCoverFace(null);
                // Remove it from the in-memory collection too, not just the cover-face
                // pointer — recalculateCoverFace picks the most-recent face from this same
                // collection, and the face row hasn't actually been deleted from the DB yet
                // at this point (that happens below), so without this it would just pick the
                // same about-to-be-deleted face right back.
                personEntity.getFaces().remove(faceEntity);
                personRecognitionService.recalculateCoverFace(personEntity);
                personRepository.save(personEntity);
            });
        });
        faceRepository.deleteByPhotoId(photo);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void detectAndSaveFaces(TaskEntity task) {
        photoRepository.findById(task.getPhotoId()).ifPresent(photo -> { // Filter by user
            try {
                log.info("Starting face detection for photo: {}", photo.getId());
                if (photo.getContentType() != null && photo.getContentType().startsWith("image")) {
                    this.detectAndSaveFaces(photo); // Pass user to helper method
                    task.setStatus(TaskStatus.COMPLETED);
                } else {
                    log.warn("Skipping face detection for non-image file: {}", photo.getId());
                    task.setStatus(TaskStatus.IGNORED);
                }
            } catch (Exception e) {
                log.error("Error during face detection for photo: {}", photo.getId(), e);
                throw new RuntimeException("Failed to process faces for photo " + photo.getId(), e);
            }
        });
    }

    private void detectAndSaveFaces(PhotoEntity photo) throws IOException { // Added UserEntity parameter
        ImageFactory factory = ImageFactory.getInstance();
        Image image = factory.fromFile(Paths.get(photo.getOriginalPath()));

        try (Predictor<Image, DetectedObjects> predictor = detectionModel.newPredictor();
             Predictor<Image, float[]> embedder = embeddingModel.newPredictor()) {

            DetectedObjects faces = predictor.predict(image);
            for (Classifications.Classification classification : faces.items()) {
                if (classification instanceof DetectedObject face) {
                    BoundingBox box = face.getBoundingBox();
                    Rectangle bounds = box.getBounds();

                    int x = (int) (bounds.getX() * image.getWidth());
                    int y = (int) (bounds.getY() * image.getHeight());
                    int w = (int) (bounds.getWidth() * image.getWidth());
                    int h = (int) (bounds.getHeight() * image.getHeight());

                    int padX = w / 5;
                    int padY = h / 5;

                    x = Math.max(0, x - padX);
                    y = Math.max(0, y - padY);

                    w = Math.min(image.getWidth() - x, w + 2 * padX);
                    h = Math.min(image.getHeight() - y, h + 2 * padY);

                    double probability = face.getProbability();

                    if (w < MIN_FACE_SIZE || h < MIN_FACE_SIZE) {
                        log.info("Skipping tiny detected face ({}x{}, confidence={}) in photo {}: below hard floor of {}px",
                                w, h, probability, photo.getId(), MIN_FACE_SIZE);
                        continue;
                    }
                    boolean isSmallFace = w < SMALL_FACE_SIZE_THRESHOLD || h < SMALL_FACE_SIZE_THRESHOLD;
                    if (isSmallFace && probability < SMALL_FACE_MIN_CONFIDENCE) {
                        log.info("Skipping low-confidence small face ({}x{}, confidence={}) in photo {}: below {} required for faces under {}px",
                                w, h, probability, photo.getId(), SMALL_FACE_MIN_CONFIDENCE, SMALL_FACE_SIZE_THRESHOLD);
                        continue;
                    }
                    if (isSmallFace) {
                        log.info("Keeping small face ({}x{}, confidence={}) in photo {}", w, h, probability, photo.getId());
                    }
                    try {
                        Image subImage =
                                image.getSubImage(x, y, w, h);
                        Image embeddingInput;

                        if (box instanceof Landmark landmark) {
                            embeddingInput =
                                    FaceAligner.align(image, landmark);
                        } else {
                            embeddingInput =
                                    subImage.resize(112, 112, false);
                        }

                        float[] embedding =
                                embedder.predict(embeddingInput);

                        byte[] embeddingBytes = EmbeddingCodec.toBytes(embedding);

                        // Generate JPEG thumbnail for UI from the original crop
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        subImage.save(baos, "png");
                        byte[] thumbnailBytes = baos.toByteArray();

                        FaceEntity faceEntity =
                                FaceEntity.builder().width(w).height(h).x(x).y(y).photo(photo).build();
                        faceEntity.setEmbedding(embeddingBytes);
                        faceEntity.setFaceThumbnail(thumbnailBytes);

                        faceRepository.save(faceEntity);
                    } catch (Exception e) {
                        log.info("can't parse a face at position x {}, y {}, w {}, h {}", x, y , w, h);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to detect or embed faces for photo " + photo.getId(), e);
        }
    }

}
