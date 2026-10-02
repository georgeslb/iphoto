package org.iphoto.mobile.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemEditorHelperTest {

    @Test
    fun `decideLaunchMode chooses EDIT when edit activities exist`() {
        val mode = SystemEditorHelper.decideLaunchMode(hasEditActivities = true)
        assertEquals(EditorLaunchMode.EDIT, mode)
    }

    @Test
    fun `decideLaunchMode chooses VIEW when no edit activities exist`() {
        val mode = SystemEditorHelper.decideLaunchMode(hasEditActivities = false)
        assertEquals(EditorLaunchMode.VIEW, mode)
    }
}
