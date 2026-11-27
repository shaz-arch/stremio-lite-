package app.stremiolite

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View

/** Ricing on Android ([Rice]): the rules' line style. */
object RiceLook {
    /** A top or bottom rule in the chosen line style. */
    class RuleDrawable(private val style: Int, color: Int, private val dp: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = Paint.Style.STROKE }
        override fun draw(c: Canvas) {
            val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
            when (style) {
                1 -> { p.strokeWidth = dp; c.drawLine(0f, dp / 2, w, dp / 2, p); c.drawLine(0f, h - dp / 2, w, h - dp / 2, p) }  // double
                2 -> { p.strokeWidth = h; c.drawLine(0f, h / 2, w, h / 2, p) }                                                  // heavy
                3 -> { p.strokeWidth = h; p.pathEffect = DashPathEffect(floatArrayOf(dp * 3, dp * 3), 0f); c.drawLine(0f, h / 2, w, h / 2, p) } // dotted
                4 -> { p.strokeWidth = h; p.pathEffect = DashPathEffect(floatArrayOf(dp * 8, dp * 4), 0f); c.drawLine(0f, h / 2, w, h / 2, p) } // ascii dashes
                else -> { p.strokeWidth = h; c.drawLine(0f, h / 2, w, h / 2, p) }
            }
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Rules and background, for the theme and line style in use. */
    fun applyLook(app: MainActivity, root: View, top: View, bottom: View, t: Look.Theme) {
        val r = Look.rice(app)
        val dp = app.resources.displayMetrics.density
        val style = r.lines.coerceIn(0, Rice.LINES.lastIndex)
        fun height(v: View, plain: Float) {
            val px = (when (style) { 1 -> 5f; 2 -> 5f; else -> plain } * dp).toInt().coerceAtLeast(1)
            if (v.layoutParams.height != px) { v.layoutParams.height = px; v.requestLayout() }
        }
        height(top, 3f); height(bottom, 1f)
        top.background = RuleDrawable(style, t.bar, dp)
        bottom.background = RuleDrawable(style, t.bar, dp)
        root.background = ColorDrawable(t.background)
    }

}
