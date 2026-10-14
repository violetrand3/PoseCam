package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TakeResultDialogTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    private fun shown(body: String = "20 s, 600 frames, 100% tracked"): AlertDialog {
        TakeResultDialog.show(activity, "Take looks good", body)
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    @Test fun theDialogShowsTheVerdictAndTheBodyAndOnlyOffersClose() {
        val dialog = shown("20 s, 600 frames\n\nUploading to Kitchen tidy (Wi-Fi only).")

        assertEquals("Take looks good", shadowOf(dialog).title.toString())
        assertTrue(shadowOf(dialog).message.toString().contains("Uploading to Kitchen tidy"))
        assertEquals("Close", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        // Nothing is asked: no pipe or campaign buttons, no second choice, and it can be dismissed like any message.
        assertNull(dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.takeIf { it.visibility == android.view.View.VISIBLE }?.text)
        assertNull(dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.takeIf { it.visibility == android.view.View.VISIBLE }?.text)
        assertTrue(shadowOf(dialog).isCancelable)
    }

    @Test fun closingTheDialogDismissesIt() {
        val dialog = shown()

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(!dialog.isShowing)
    }
}
