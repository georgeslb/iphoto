package org.iphoto.service;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;

public interface FaceDetectionService {

    void deleteFaces(PhotoEntity photo);

    // No longer self-locking - ISOLATE_FACES now runs on its own dedicated pool sized to exactly
    // the concurrency this is safe for (see TaskWorkerManager/
    // IPhotoProperties.HeavyTasks#faceDetectionThreads, default 3), instead of a lock that
    // serialized every call to 1 at a time regardless of how many pool threads were available -
    // each call already opens its own DJL Predictor (detectionModel.newPredictor()/
    // embeddingModel.newPredictor()), which is the supported way to run concurrent DJL inference,
    // so the lock was only ever holding back real parallelism, not protecting anything unsafe.
    void detectAndSaveFaces(TaskEntity task);
}
