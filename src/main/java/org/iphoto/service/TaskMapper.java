package org.iphoto.service;

import org.iphoto.entities.TaskEntity;

import java.util.Set;
import java.util.UUID;

public interface TaskMapper {

    enum Action {UPLOADED_PHOTO_TO_MAIN, UPLOADED_PHOTO_NOT_TO_MAIN, PHOTO_MOVED_TO_GARBAGE
    , PHOTO_MOVED_TO_MAIN, PHOTO_PERMANENTLY_DELETED, AFTER_PHOTO_HASH, ON_EDIT_PHOTO_ACTION, ON_CREATE_PHOTO_ACTION, PHOTO_REMOVED_FROM_GARBAGE}

    //TODO when photo is moved to MAIN we should run Duplicate job
    Set<TaskEntity> updateTasksToAction(Action action, UUID photoId);
}
