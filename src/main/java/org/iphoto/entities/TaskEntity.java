package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tasks", indexes = {@Index(columnList = "status", name = "status_index"), @Index(columnList = "status,task_type", name = "status_task_type_index"), @Index(columnList = "status,photo_id", name = "status_photo_id_index")})
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@Setter(value = AccessLevel.PUBLIC)
@Getter
public class TaskEntity{

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column()
    private UUID photoId;

    @Column()
    private UUID userId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TaskType taskType;

    @Column(nullable = false)
    private String status;

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