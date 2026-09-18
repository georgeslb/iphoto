package org.iphoto.mobile.data.repository

import org.iphoto.mobile.model.TaskDTO
import org.iphoto.mobile.network.TaskApi

class TaskRepository(private val taskApi: TaskApi) {
    suspend fun getTask(taskId: String): TaskDTO {
        return taskApi.get(taskId)
    }
}
