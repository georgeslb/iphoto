package org.iphoto.model;

import lombok.Getter;

@Getter
public enum TaskType {
    PERMANENT_DELETE (false, 0),
    PROCESS_UPLOAD (false, 0),
    HASH_IMAGE(true, 1),
    CHECK_DUPLICATION(true, 2),
    ISOLATE_FACES(true, 1),
    FACE_RECOGNITION (false, 2),
    ;

    private final boolean canBeReplayed;
    private final int order;

    TaskType(boolean canBeReplayed, int order) {
        this.canBeReplayed = canBeReplayed;
        this.order = order;
    }

}
