package org.iphoto.mobile

import android.app.Application
import android.net.Uri
import androidx.work.Configuration
import org.iphoto.mobile.sync.SyncWorker
import org.iphoto.mobile.sync.SyncWorkerFactory

class IPhotoApplication : Application(), Configuration.Provider {

    // Held here (rather than per-Activity) so SyncWorker - which WorkManager can recreate outside
    // any Activity - shares the same container, prefs and hash cache as the UI.
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SyncWorkerFactory(container))
            .build()

    override fun onCreate() {
        super.onCreate()
        // Re-asserts the sync schedule on every process start, not just when the user flips the
        // "Sync automatically" switch. enqueueUniquePeriodicWork/addContentUriTrigger only ever
        // apply new parameters (interval, constraints, the content-uri trigger existing at all)
        // when actually called again - not automatically just because the app was updated with
        // new code. Confirmed via a real device: a periodic job scheduled back when the interval
        // was still 6 hours (before this session's later changes to 15 minutes + a content-uri
        // trigger) was found still running under those stale parameters hours later, since the
        // user had no reason to re-toggle the switch after the code changed. Without this, every
        // future schedule-affecting code change would silently strand already-opted-in users on
        // whatever was in effect when they last flipped the switch.
        if (container.syncPrefs.autoSyncEnabled) {
            SyncWorker.schedulePeriodic(applicationContext)
            container.syncPrefs.folderUri?.let {
                SyncWorker.scheduleContentTrigger(applicationContext, Uri.parse(it))
            }
        }
    }
}
