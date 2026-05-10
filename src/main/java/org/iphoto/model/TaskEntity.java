package org.iphoto.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String taskType;

    @Column(nullable = false)
    private String status = TaskStatus.PENDING.name();

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    private LocalDateTime executeAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public TaskEntity() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (executeAt == null) {
            executeAt = createdAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    // Logic for Status using the Enum internally but String in DB
    public TaskStatus getStatus() { 
        return status != null ? TaskStatus.valueOf(status) : TaskStatus.PENDING; 
    }
    public void setStatus(TaskStatus status) { 
        this.status = status != null ? status.name() : TaskStatus.PENDING.name(); 
    }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    public LocalDateTime getExecuteAt() { return executeAt; }
    public void setExecuteAt(LocalDateTime executeAt) { this.executeAt = executeAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
