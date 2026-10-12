package com.posecam

import android.app.Activity
import android.content.res.Resources
import android.view.MenuItem
import android.widget.Toolbar

/**
 * Shows the action bar's up arrow with the title close beside it (the stock bar leaves a 16dp gap between them);
 * pair it with [handleBackArrow] in onOptionsItemSelected. Call it after setContentView, which creates the bar.
 */
fun Activity.showBackArrow() {
    actionBar?.setDisplayHomeAsUpEnabled(true)
    // The bar puts the title 72dp from the edge; the arrow's own button is 56dp wide, so the title can sit right after it.
    findViewById<Toolbar?>(Resources.getSystem().getIdentifier("action_bar", "id", "android"))
        ?.contentInsetStartWithNavigation = (56 * resources.displayMetrics.density).toInt()
}

/** The up arrow closes the screen, like the system Back; true if [item] was the arrow. */
fun Activity.handleBackArrow(item: MenuItem): Boolean {
    if (item.itemId != android.R.id.home) return false
    finish()
    return true
}
