package org.iphoto.mobile.ui.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri

data class ExternalEditorApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable?,
    val isEditAction: Boolean
)

object SystemEditorHelper {

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

    fun queryAvailableEditors(context: Context, uri: Uri, mimeType: String): List<ExternalEditorApp> {
        val pm = context.packageManager
        val editIntent = buildEditIntent(uri, mimeType)
        val viewIntent = buildViewIntent(uri, mimeType)

        val editList = pm.queryIntentActivities(editIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .ifEmpty { pm.queryIntentActivities(editIntent, 0) }

        val viewList = pm.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .ifEmpty { pm.queryIntentActivities(viewIntent, 0) }

        val results = mutableMapOf<String, ExternalEditorApp>()

        // 1. Process EDIT activities first (preferred)
        for (info in editList) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            results[pkg] = ExternalEditorApp(
                packageName = pkg,
                activityName = info.activityInfo.name,
                label = info.loadLabel(pm).toString(),
                icon = try { info.loadIcon(pm) } catch (_: Exception) { null },
                isEditAction = true
            )
        }

        // 2. Add VIEW activities if not already present
        for (info in viewList) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || results.containsKey(pkg)) continue
            results[pkg] = ExternalEditorApp(
                packageName = pkg,
                activityName = info.activityInfo.name,
                label = info.loadLabel(pm).toString(),
                icon = try { info.loadIcon(pm) } catch (_: Exception) { null },
                isEditAction = false
            )
        }

        // 3. Fallback for known packages if list is still empty (e.g. package visibility restrictions)
        if (results.isEmpty()) {
            val knownPackages = listOf(
                "com.google.android.apps.photos" to "Google Photos",
                "com.sec.android.gallery3d" to "Samsung Gallery",
                "com.google.android.apps.snapseed" to "Snapseed",
                "com.adobe.lrmobile" to "Lightroom",
                "com.android.gallery3d" to "Gallery"
            )
            for ((pkg, defaultName) in knownPackages) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    results[pkg] = ExternalEditorApp(
                        packageName = pkg,
                        activityName = "",
                        label = pm.getApplicationLabel(appInfo).toString().ifBlank { defaultName },
                        icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null },
                        isEditAction = true
                    )
                } catch (_: Exception) {
                    // Not installed
                }
            }
        }

        return results.values.sortedBy { it.label }
    }

    fun buildTargetIntent(app: ExternalEditorApp, uri: Uri, mimeType: String): Intent {
        val action = if (app.isEditAction) Intent.ACTION_EDIT else Intent.ACTION_VIEW
        return Intent(action).apply {
            setDataAndType(uri, mimeType)
            setPackage(app.packageName)
            if (uri.authority != "media") {
                clipData = android.content.ClipData.newRawUri("photo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }
}
