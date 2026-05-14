package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.iphoto.model.TaskStatus;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tasks")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
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


    // Logic for Status using the Enum internally but String in DB
    public TaskStatus getStatus() { 
        return status != null ? TaskStatus.valueOf(status) : TaskStatus.PENDING; 
    }
    public void setStatus(TaskStatus status) { 
        this.status = status != null ? status.name() : TaskStatus.PENDING.name(); 
    }
}
