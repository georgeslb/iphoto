package org.iphoto.controller;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskDTO;
import org.iphoto.repository.TaskRepository;
import org.iphoto.service.TaskManagerService;
import org.iphoto.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/{id}")
    public ResponseEntity<TaskDTO> getTaskStatus(@PathVariable UUID id) {
        return taskService.getTask(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
