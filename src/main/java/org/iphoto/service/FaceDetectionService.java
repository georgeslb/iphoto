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
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.exception.ServiceNotAvailableException;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
@RequiredArgsConstructor
public class FaceDetectionService {

    private final FaceRepository faceRepository;
    private final PhotoRepository photoRepository;
    private final ZooModel<Image, DetectedObjects> detectionModel;
    private final ZooModel<Image, float[]> embeddingModel;
    private final ReentrantLock lock = new ReentrantLock();



    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void detectAndSaveFaces(TaskEntity task) {
        if (!lock.tryLock()) {
            throw new ServiceNotAvailableException("Face detection service is busy with task " + task.getId());
        }
        try {
            // Retrieve the user from the task
            UserEntity user = task.getUser();
            if (user == null) {
                log.error("Task {} does not have an associated user. Skipping face detection.", task.getId());
                task.setStatus(TaskStatus.FAILED);
                return;
            }

            photoRepository.findByIdAndUser(task.getPhotoId(), user).ifPresent(photo -> { // Filter by user
                try {
                    log.info("Starting face detection for photo: {}", photo.getId());
                    if (photo.getContentType() != null && photo.getContentType().startsWith("image")) {
                        this.detectAndSaveFaces(user, photo); // Pass user to helper method
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
        } finally {
            lock.unlock();
        }
    }

    private void detectAndSaveFaces(UserEntity user, PhotoEntity photo) throws IOException { // Added UserEntity parameter
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

                    if (w < 60 || h < 60) {
                        log.warn("Skipping tiny detected face ({}x{}) in photo {}", w, h, photo.getId());
                        continue;
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

                        byte[] embeddingBytes = toByteArray(embedding);

                        // Generate JPEG thumbnail for UI from the original crop
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        subImage.save(baos, "png");
                        byte[] thumbnailBytes = baos.toByteArray();

                        FaceEntity faceEntity = new FaceEntity(photo, x, y, w, h);
                        faceEntity.setUser(user); // Set the user for the face
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

    private byte[] toByteArray(float[] floatArray) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * floatArray.length);
        // Set to BIG_ENDIAN to match the reader service
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (float v : floatArray) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }
}