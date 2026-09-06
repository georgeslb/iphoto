package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.model.TaskDTO;
import org.iphoto.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TaskDTO> getTask(UUID taskId) {
        return taskRepository.findById(taskId).map(taskEntity -> new TaskDTO(taskEntity.getId(), taskEntity.getStatus().toString(), taskEntity.getPhotoId()));
    }

}
