package org.iphoto.mobile.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEditorHelperTest {

    @Test
    fun `ExternalEditorApp holds properties correctly`() {
        val app = ExternalEditorApp(
            packageName = "com.google.android.apps.photos",
            activityName = "com.google.android.apps.photos.editor.PhotoEditorActivity",
            label = "Google Photos",
            icon = null,
            isEditAction = true
        )

        assertEquals("com.google.android.apps.photos", app.packageName)
        assertEquals("com.google.android.apps.photos.editor.PhotoEditorActivity", app.activityName)
        assertEquals("Google Photos", app.label)
        assertNull(app.icon)
        assertTrue(app.isEditAction)
    }
}
