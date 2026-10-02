package org.iphoto.mobile.ui.sync

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

enum class EditorLaunchMode {
    EDIT,
    VIEW
}

object SystemEditorHelper {

    fun decideLaunchMode(hasEditActivities: Boolean): EditorLaunchMode {
        return if (hasEditActivities) EditorLaunchMode.EDIT else EditorLaunchMode.VIEW
    }

    fun buildEditIntent(uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun buildViewIntent(uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun resolveTargetIntent(packageManager: PackageManager, editIntent: Intent, viewIntent: Intent): Intent {
        val editActivities = packageManager.queryIntentActivities(editIntent, 0)
        val mode = decideLaunchMode(editActivities.isNotEmpty())
        return if (mode == EditorLaunchMode.EDIT) {
            Intent.createChooser(editIntent, "Edit photo")
        } else {
            Intent.createChooser(viewIntent, "Open with")
        }
    }
}
