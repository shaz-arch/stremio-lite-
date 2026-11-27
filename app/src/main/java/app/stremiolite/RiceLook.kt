package app.stremiolite

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/** Ricing on Android ([Rice]): the rules' line style, the frame round the screen, the banner and the bars. */
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

    /** The frame, drawn [inset] px inside the screen's edges. */
    class FrameDrawable(private val frame: Int, private val ascii: Boolean, color: Int, private val dp: Float, private val insetX: Float, private val insetY: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE }
        override fun draw(c: Canvas) {
            val r = RectF(bounds.left + insetX, bounds.top + insetY, bounds.right - insetX, bounds.bottom - insetY)
            if (ascii) { p.strokeWidth = dp * 1.5f; p.pathEffect = DashPathEffect(floatArrayOf(dp * 8, dp * 4), 0f); c.drawRect(r, p); return }
            when (frame) {
                1 -> { p.strokeWidth = dp * 1.5f; c.drawRect(r, p) }
                2 -> { p.strokeWidth = dp * 1.5f; c.drawRoundRect(r, dp * 16, dp * 16, p) }
                3 -> { p.strokeWidth = dp * 1.2f; c.drawRect(r, p); val g = dp * 4; c.drawRect(RectF(r.left + g, r.top + g, r.right - g, r.bottom - g), p) }
                4 -> { p.strokeWidth = dp * 4; c.drawRect(r, p) }
            }
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Rules, frame and background, for the theme and line style in use. */
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
        val bg = ColorDrawable(t.background)
        root.background = if (r.frame <= 0) bg else {
            // The frame sits in the screen's safe margin, just outside everything on it.
            val ix = (root.paddingLeft - 14 * dp).coerceAtLeast(2 * dp)
            val iy = (root.paddingTop - 12 * dp).coerceAtLeast(2 * dp)
            LayerDrawable(arrayOf(bg, FrameDrawable(r.frame, r.line.ascii, t.bar, dp, ix, iy)))
        }
    }

    /** The banner (Home only) and what the bars show, for [stackSize] screens deep. */
    fun applyBars(app: MainActivity, banner: android.widget.TextView, onHome: Boolean, t: Look.Theme, sp: Float) {
        val r = Look.rice(app)
        val lines = if (onHome) Rice.banner(r) else emptyList()
        // A banner needs room: not on a short phone screen held sideways.
        val tall = app.resources.displayMetrics.let { it.heightPixels / it.density } >= 400
        if (lines.isEmpty() || !tall) { banner.visibility = View.GONE; return }
        banner.text = lines.joinToString("\n")
        banner.setTextColor(t.title)
        banner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (lines.size > 1) (sp * 0.9f) else sp + 2)
        banner.visibility = View.VISIBLE
    }
}
