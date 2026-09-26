package com.aiwatch.probe.product

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*

/** Small native-view palette; no extra UI runtime or animation loop. */
internal class ProductUi(val activity: Activity) {
    val ink = Color.rgb(240, 234, 222)
    val muted = Color.rgb(177, 191, 182)
    val accent = Color.rgb(208, 226, 176)
    val background = Color.rgb(18, 32, 29)
    val surface = Color.rgb(29, 47, 42)
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    fun text(value: String, size: Float = 16f, color: Int = ink) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
    }
    fun title(value: String) = text(value, 28f).apply { setTypeface(typeface, Typeface.BOLD) }
    fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = label; isAllCaps = false; textSize = 15f; minHeight = dp(52)
        background = shape(if (primary) accent else surface)
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(surface, if (primary) accent else surface))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primary) backgroundColor() else ink)))
        setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { action() }
    }
    private fun backgroundColor() = background
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun add(column: LinearLayout, view: View, top: Int = 8) {
        column.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    fun page(maxWidthDp: Int = 560): LinearLayout {
        val column = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val frame = FrameLayout(activity).apply {
            addView(column, FrameLayout.LayoutParams(minOf(dp(maxWidthDp), activity.resources.displayMetrics.widthPixels), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(this@ProductUi.background); isFillViewport = true; fitsSystemWindows = true; addView(frame)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets.consumeSystemWindowInsets()
            }
        })
        return column
    }
}
