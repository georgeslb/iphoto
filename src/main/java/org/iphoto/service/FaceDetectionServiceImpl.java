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
import net.coobird.thumbnailator.Thumbnails;
import org.iphoto.configuration.BlurDetector;
import org.iphoto.configuration.FaceAligner;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.util.EmbeddingCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Profile("!test")
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

    // Variance-of-Laplacian blur-rejection threshold (see BlurDetector) - below this, a face crop
    // is rejected outright at detection time and never becomes a FaceEntity, the same place
    // MIN_FACE_SIZE/SMALL_FACE_MIN_CONFIDENCE above reject too-small/low-confidence detections (see
    // TODO.md's "detect blurry faces and reject them at detection time" entry - user confirmed
    // "we have too many [faces] right now", junk/blurry detections bloating the Persons page).
    // ~100 is the commonly-cited starting point for this metric (Pech-Pacheco et al.); this
    // environment has no real photo library to empirically tune it against actual blurry/sharp
    // face crops from this app's own data, so it's the literature default, NOT independently
    // verified here - worth revisiting against real data before trusting it long-term (see
    // BlurDetectorTest for what IS verified: the metric itself correctly discriminates a sharp
    // synthetic image from a genuinely blurred version of itself, and a flat/solid-color image
    // scores near zero).
    private static final double BLUR_VARIANCE_THRESHOLD = 100.0;

    // Bounding box (px) the saved face thumbnail is scaled to fit within, preserving aspect ratio -
    // see the thumbnail-generation comment below for why this matters.
    private static final int FACE_THUMBNAIL_MAX_SIZE = 300;


    @Override
    public void deleteFaces(PhotoEntity photo) {
        // Every person with at least one face on this photo needs a photoCount recalculation once
        // the faces are actually gone below - collected up front (by id, not entity - PersonEntity's
        // Lombok equals/hashCode touches its lazy `faces` collection, not worth relying on just for
        // a Set membership check) since faceRepository.deleteByPhotoId is a bulk delete, not
        // something to hang a per-face callback off of.
        Map<UUID, PersonEntity> affectedPersons = new HashMap<>();
        photo.getFaces().forEach(faceEntity -> {
            if (faceEntity.getPerson() != null) {
                personRecognitionService.removeFaceFromBucket(faceEntity.getPerson(), faceEntity);
                affectedPersons.put(faceEntity.getPerson().getId(), faceEntity.getPerson());
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
        faceRepository.deleteByPhoto(photo);

        // photoCount must be recalculated AFTER the faces are actually gone (same reasoning as
        // FaceService.deleteFace).
        affectedPersons.values().forEach(personEntity -> {
            personRecognitionService.recalculatePhotoCount(personEntity);
            personRepository.save(personEntity);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void detectAndSaveFaces(TaskEntity task) {
        photoRepository.findById(task.getPhotoId()).ifPresent(photo -> { // Filter by user
            try {
                log.info("Starting face detection for photo: {}", photo.getId());
                if (photo.getContentType() != null && photo.getContentType().startsWith("image")) {
                    // ISOLATE_FACES is replayable (admin "Restart face extraction" button, stuck-task
                    // reclaim, updateImageTaskToDo) - clear any faces from a previous run first so a
                    // re-run REBUILDS this photo's faces instead of appending a second set (detection
                    // only ever inserts below, it never reconciles against what's already there).
                    // No-op on the first run: the photo has no faces yet.
                    deleteFaces(photo);
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

                        // Cheapest point to blur-check: the exact crop pixels are already in
                        // memory here, before FaceAligner.align/resize does any extra work on them.
                        double blurVariance = BlurDetector.laplacianVariance((BufferedImage) subImage.getWrappedImage());
                        if (blurVariance < BLUR_VARIANCE_THRESHOLD) {
                            log.info("Skipping blurry face (variance={}, threshold={}) in photo {}",
                                    blurVariance, BLUR_VARIANCE_THRESHOLD, photo.getId());
                            continue;
                        }

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

                        byte[] thumbnailBytes = generateFaceThumbnail((BufferedImage) subImage.getWrappedImage());

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

    // Small JPEG thumbnail for UI display (Persons.jsx cover photos, Lightbox's "Faces Detected"
    // strip - both only ever render this at ~100px CSS size, see App.css's `.person-item img`).
    // This used to save the crop as-is - at its full original-photo pixel size, since detection
    // runs against the full-resolution photo, not the resized-for-hashing copy DuplicationService
    // uses - as a lossless PNG, mismatched with the "data:image/jpeg;base64," prefix
    // FaceServiceImpl.mapToDTO already labels it with. A single crop could be several hundred KB to
    // 1+MB that way, and the Persons page fetches one of these per person per page
    // (~50/page - FaceController#getPersons), which is what made that endpoint's response balloon
    // to tens of MB and take several seconds to load (reported 2026-09-11). Package-private (not
    // private) so FaceDetectionServiceImplTest can exercise it directly without needing the full
    // ONNX detection/embedding pipeline.
    static byte[] generateFaceThumbnail(BufferedImage faceCrop) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Thumbnails.of(faceCrop)
                .size(FACE_THUMBNAIL_MAX_SIZE, FACE_THUMBNAIL_MAX_SIZE)
                .outputFormat("jpg")
                .toOutputStream(baos);
        return baos.toByteArray();
    }

}
