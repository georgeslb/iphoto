package org.iphoto.service;

public interface FaceRecognitionService {

    // No longer self-locking - FACE_RECOGNITION now runs on its own dedicated single-thread pool
    // (see TaskWorkerManager/IPhotoProperties.HeavyTasks#faceRecognitionThreads), which already
    // guarantees only one call runs at a time.
    void groupFaces();
}
