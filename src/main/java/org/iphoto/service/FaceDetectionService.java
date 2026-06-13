package org.iphoto.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_dnn.blobFromImage;
import static org.bytedeco.opencv.global.opencv_dnn.readNetFromTorch;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

@Service
@Slf4j
public class FaceDetectionService {

    private final FaceRepository faceRepository;
    private final CascadeClassifier faceCascade;
    private final PhotoRepository photoRepository;
    private final Net faceNet;
    private static final String MODEL_FILE = "nn4.small2.v1.t7";


    public FaceDetectionService(FaceRepository faceRepository, PhotoRepository photoRepository, TaskManagerService taskManagerService) throws IOException {
        this.faceRepository = faceRepository;
        this.photoRepository = photoRepository;
        faceCascade = new CascadeClassifier(
                ResourceUtils.getFile("classpath:haarcascade_frontalface_alt.xml").getAbsolutePath()
        );
        String modelPath = loadModelFile();
        this.faceNet = readNetFromTorch(modelPath);
        if (this.faceNet.empty()) {
            throw new IllegalStateException("Failed to load Face Recognition model from " + modelPath);
        }
    }

    private void detectAndSaveFaces(PhotoEntity photo) {
        Mat image = imread(photo.getOriginalPath());
        if (image.empty()) {
            log.info("path {} for image {} does not exist", photo.getOriginalPath(), photo.getId());
            return;
        }

        Mat grayImage = new Mat();
        cvtColor(image, grayImage, COLOR_BGR2GRAY);

        // Ensure the image is large enough for the CascadeClassifier to process.
        // The default minimum window size for haarcascade_frontalface_alt.xml is typically 20x20.
        // Passing an image smaller than this will cause an OpenCV assertion failure.
        if (grayImage.rows() < 20 || grayImage.cols() < 20) {
            log.warn("Photo {} ({}x{}) is too small for face detection, skipping", photo.getId(), grayImage.cols(), grayImage.rows());
            return;
        }

        RectVector faces = new RectVector();
        try {
            faceCascade.detectMultiScale(grayImage, faces);
        } catch (RuntimeException e) {
            log.error("OpenCV error during face detection for photo {}: {}", photo.getId(), e.getMessage());
            return;
        }

        for (Rect faceRect : faces.get()) {
            FaceEntity faceEntity = new FaceEntity(photo, faceRect.x(), faceRect.y(), faceRect.width(), faceRect.height());
            faceEntity.setEmbedding(extractEmbedding(cropFace(faceEntity)));
            faceRepository.save(faceEntity);
        }
    }

    /**
     * Crops the face from the original photo based on the coordinates in the FaceEntity.
     *
     * @param face The FaceEntity containing the coordinates and associated PhotoEntity.
     * @return A Mat object containing the cropped face image, or null if the image cannot be loaded or if the crop region is invalid.
     */
    public Mat cropFace(FaceEntity face) {
        if (face == null || face.getPhoto() == null) {
            return null;
        }

        String imagePath = face.getPhoto().getOriginalPath();
        Mat originalImage = imread(imagePath);

        if (originalImage.empty()) {
            return null; // Could not load the image
        }

        // The detected region of the face
        try (Rect detectedRect = new Rect(face.getX(), face.getY(), face.getWidth(), face.getHeight());
                Rect imageBounds = new Rect(0, 0, originalImage.cols(), originalImage.rows())) {

            // --- Corrected Intersection Logic ---
            int x = Math.max(detectedRect.x(), imageBounds.x());
            int y = Math.max(detectedRect.y(), imageBounds.y());
            int width = Math.min(detectedRect.x() + detectedRect.width(), imageBounds.x() + imageBounds.width()) - x;
            int height = Math.min(detectedRect.y() + detectedRect.height(), imageBounds.y() + imageBounds.height()) - y;

            // Check if there is a valid, non-empty intersection
            if (width <= 0 || height <= 0) {
                return null;
            }

            Rect validCropRegion = new Rect(x, y, width, height);
            // --- End of Correction ---

            // Return a new Mat containing only the cropped region
            return new Mat(originalImage, validCropRegion);
        }
    }

    /**
     * Extracts a 128-dimensional embedding from a cropped face image.
     */
    public byte[] extractEmbedding(Mat croppedFace) {
        if (croppedFace == null || croppedFace.empty()) {
            return null;
        }
        Size inputSize = new Size(96, 96);
        Mat blob = blobFromImage(croppedFace, 1.0 / 255.0, inputSize, new Scalar(0, 0, 0, 0), true, false, CV_32F);
        faceNet.setInput(blob);
        Mat embeddingMat = faceNet.forward();
        if (embeddingMat.empty() || embeddingMat.cols() != 128) {
            return null;
        }
        return matToByteArray(embeddingMat);
    }

    /**
     * Converts a Mat containing a single row of floats into a byte array.
     */
    private byte[] matToByteArray(Mat mat) {
        int floatCount = mat.cols();
        int byteCount = floatCount * 4;
        byte[] byteArray = new byte[byteCount];
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        FloatPointer floatData = new FloatPointer(mat.data());
        for (int i = 0; i < floatCount; i++) {
            buffer.putFloat(floatData.get(i));
        }
        return byteArray;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void detectAndSaveFaces(TaskEntity task) {
        photoRepository.findById(task.getPhotoId()).ifPresent(photo -> {
            try {
                log.info("starting isolating faces for photo: {}", photo.getId());
                this.detectAndSaveFaces(photo);
            } catch (Exception e) {
                log.error("error while isolating faces for photo: {}", photo.getId());
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    private String loadModelFile() throws IOException {
        File file = ResourceUtils.getFile("classpath:"+ MODEL_FILE);
        return file.getPath();
    }

}