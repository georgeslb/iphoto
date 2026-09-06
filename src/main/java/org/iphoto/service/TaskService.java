package org.iphoto.service;

import org.iphoto.model.TaskDTO;

import java.util.Optional;
import java.util.UUID;

public interface TaskService {

    Optional<TaskDTO> getTask(UUID taskId);

}
