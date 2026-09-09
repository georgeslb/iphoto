package org.iphoto.mobile.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.iphoto.mobile.AppContainer

/**
 * WorkManager builds Workers via a no-arg reflective constructor by default, but SyncWorker needs
 * the app's AppContainer (for the API clients and stored sync prefs) - this factory is how that
 * gets threaded through instead. Wired up in IPhotoApplication.workManagerConfiguration.
 */
class SyncWorkerFactory(private val container: AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, container)
        else -> null
    }
}
