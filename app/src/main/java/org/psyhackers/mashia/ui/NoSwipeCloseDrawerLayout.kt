package org.psyhackers.mashia.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.psyhackers.mashia.R

class NoSwipeCloseDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DrawerLayout(context, attrs, defStyleAttr) {
    private val rect = Rect()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isDrawerOpen(GravityCompat.START)) {
            val folderRow = findViewById<View?>(R.id.folder_row)
            val tagRow = findViewById<View?>(R.id.tag_row)
            if (isInside(ev, folderRow) || isInside(ev, tagRow)) {
                return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun isInside(ev: MotionEvent, v: View?): Boolean {
        if (v == null || !v.isShown) return false
        if (!v.getGlobalVisibleRect(rect)) return false
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        return rect.contains(x, y)
    }
}
