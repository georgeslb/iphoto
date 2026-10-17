package org.iphoto.mobile

import android.app.Application
import androidx.work.WorkManager

class IPhotoApplication : Application() {

    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // Cancel any legacy background work if previously scheduled on device
        try {
            WorkManager.getInstance(this).cancelAllWork()
        } catch (_: Exception) {
        }
    }
}
