package org.iphoto.model;

import lombok.Getter;

@Getter
public enum TaskType {
    PERMANENT_DELETE (false),
    PROCESS_UPLOAD (false),
    HASH_IMAGE(true),
    CHECK_DUPLICATION(true),
    ISOLATE_FACES(true),
    FACE_RECOGNITION (false),
    ;

    private final boolean canBeReplayed;

    TaskType(boolean canBeReplayed) {
        this.canBeReplayed = canBeReplayed;
    }

}
