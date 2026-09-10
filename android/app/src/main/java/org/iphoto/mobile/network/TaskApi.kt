package org.iphoto.mobile.network

import org.iphoto.mobile.model.TaskDTO
import retrofit2.http.GET
import retrofit2.http.Path

interface TaskApi {
    @GET("api/tasks/{id}")
    suspend fun get(@Path("id") id: String): TaskDTO
}
