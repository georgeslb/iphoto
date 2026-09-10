package org.iphoto.mobile

import android.app.Application
import androidx.work.Configuration
import org.iphoto.mobile.sync.SyncWorkerFactory

class IPhotoApplication : Application(), Configuration.Provider {

    // Held here (rather than per-Activity) so SyncWorker - which WorkManager can recreate outside
    // any Activity - shares the same container, prefs and hash cache as the UI.
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SyncWorkerFactory(container))
            .build()
}
