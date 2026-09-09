package org.iphoto.entities;

/**
 * Whether a face has already gone through one full face-recognition analysis pass
 * (existing-person matching, see FaceRecognitionServiceImpl) or is still awaiting its first one.
 */
public enum FaceStatus {
    /**
     * Not yet matched against existing people. Freshly detected faces start here (see
     * FaceDetectionServiceImpl) and flip to OLD once a groupFaces() run has analysed them,
     * regardless of outcome (matched, promoted, or still an unclustered singleton) — this keeps a
     * large, permanently-unmatched backlog from being re-analysed against every person on every
     * run, while such faces remain eligible (person IS NULL) as clustering candidates for a later
     * NEW face's own analysis.
     */
    NEW,
    OLD
}
