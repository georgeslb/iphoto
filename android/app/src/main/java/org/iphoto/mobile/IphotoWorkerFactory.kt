package org.iphoto.mobile

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.iphoto.mobile.sync.SyncWorker
import org.iphoto.mobile.upload.UploadWorker

/**
 * WorkManager builds Workers via a no-arg reflective constructor by default, but our workers need
 * the app's AppContainer - this factory is how that gets threaded through instead.
 * Wired up in IPhotoApplication.workManagerConfiguration.
 */
class IphotoWorkerFactory(private val container: AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, container)
        UploadWorker::class.java.name -> UploadWorker(appContext, workerParameters, container)
        else -> null
    }
}
