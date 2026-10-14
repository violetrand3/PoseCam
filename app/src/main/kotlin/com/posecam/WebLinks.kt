package com.posecam

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Opens one of the website's pages (the links come from `GET /v1/meta`, cached, or the build constants). Only http(s)
 * links are opened, whatever the server sent. Returns whether a browser was started.
 */
fun Activity.openWebLink(url: String?, whenMissing: String = "That page is not available yet."): Boolean {
    val uri = url?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(Uri::parse)
    if (uri == null) {
        Toast.makeText(this, whenMissing, Toast.LENGTH_SHORT).show()
        return false
    }
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(this, "No browser found to open the page.", Toast.LENGTH_SHORT).show()
        false
    }
}
