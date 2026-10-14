package com.posecam

import android.app.Activity
import android.app.AlertDialog

/**
 * The dialog shown when a take ends: the verdict, and (with cloud upload on) what happens to the take. It asks nothing:
 * the campaign was chosen BEFORE recording, a good complete take is queued under it automatically ("Uploading to
 * <campaign>"), and a redo take is never uploaded (it says so). Without cloud upload it is exactly what PoseCam always
 * showed: a verdict and Close.
 */
object TakeResultDialog {
    fun show(activity: Activity, headline: String, body: String) {
        AlertDialog.Builder(activity).setTitle(headline).setMessage(body).setPositiveButton(R.string.close, null).show()
    }
}
