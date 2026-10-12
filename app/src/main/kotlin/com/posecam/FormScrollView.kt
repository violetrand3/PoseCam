package com.posecam

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import kotlin.math.max

/**
 * A ScrollView that stays scrollable while the keyboard is up. The screens pad its bottom by the keyboard's height, and the
 * stock view then compares the content against the whole view instead of the part left visible, decides there is nothing
 * to scroll, and stops taking over drags that start on a text field (the field keeps them, so the page will not move).
 */
class FormScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    override fun canScrollVertically(direction: Int): Boolean {
        val content = getChildAt(0) ?: return false
        val range = max(0, content.height - (height - paddingTop - paddingBottom))
        return if (direction < 0) scrollY > 0 else scrollY < range
    }
}
