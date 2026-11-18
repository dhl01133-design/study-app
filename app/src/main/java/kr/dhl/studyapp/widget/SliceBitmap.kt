package kr.dhl.studyapp.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import kr.dhl.studyapp.brief.BlockKind
import kr.dhl.studyapp.brief.DaySlice
import kr.dhl.studyapp.ui.DarkPal
import kr.dhl.studyapp.ui.LightPal
import java.time.LocalTime
import kotlin.math.max

/** The app's time bar (08–24) drawn into a bitmap, since widgets can't draw shapes themselves. */
object SliceBitmap {
    private const val FIRST_HOUR = 8.0
    private const val LAST_HOUR = 24.0

    /** A small gray ring for the widget's completion circles. */
    fun ring(density: Float, dark: Boolean): Bitmap {
        val p = if (dark) DarkPal else LightPal
        val size = (18 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = p.faint.toArgb()
        }
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size / 2f - 1.5f * density, paint)
        return bmp
    }

    fun render(slice: DaySlice, widthDp: Float, density: Float, dark: Boolean): Bitmap {
        val p = if (dark) DarkPal else LightPal
        val w = max((widthDp * density).toInt(), 10)
        val barH = 22 * density
        val bmp = Bitmap.createBitmap(w, (barH + 14 * density).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 4 * density
        fun x(hour: Double) = ((hour.coerceIn(FIRST_HOUR, LAST_HOUR) - FIRST_HOUR) / (LAST_HOUR - FIRST_HOUR) * w).toFloat()

        // track (a step lighter than the dark widget background)
        paint.color = if (dark) 0xFF26262A.toInt() else p.track.toArgb()
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), barH), r, r, paint)

        // travel: diagonal hatching
        paint.color = p.hatch.toArgb()
        paint.strokeWidth = density
        for ((a, b) in slice.travel) {
            if (b <= FIRST_HOUR) continue
            c.save()
            c.clipRect(x(a), 0f, x(b), barH)
            var lx = x(a) - barH
            while (lx < x(b)) {
                c.drawLine(lx, barH, lx + barH, 0f, paint)
                lx += 5 * density
            }
            c.restore()
        }

        // free time: green fill and outline
        for ((a, b) in slice.free) {
            val rect = RectF(x(a) + 1, 1f, max(x(b) - 1, x(a) + 2), barH - 1)
            paint.style = Paint.Style.FILL
            paint.color = p.goodSoft.toArgb()
            c.drawRoundRect(rect, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.color = p.good.toArgb()
            c.drawRoundRect(rect, r, r, paint)
        }
        paint.style = Paint.Style.FILL

        // events with their titles
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9 * density; color = p.onBusy.toArgb() }
        for (b in slice.blocks) {
            if (b.end <= FIRST_HOUR) continue
            val left = x(b.start)
            val right = max(x(b.end), left + 1)
            paint.color = (if (b.kind == BlockKind.CLASS) p.accent else p.busy).toArgb()
            c.drawRoundRect(RectF(left, 0f, right, barH), r, r, paint)
            if (right - left > 22 * density) {
                val n = label.breakText(b.title, true, right - left - 6 * density, null)
                c.drawText(b.title.take(max(n, 1)), left + 3 * density, barH / 2 + 3 * density, label)
            }
        }

        // now
        val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
        if (now in FIRST_HOUR..LAST_HOUR) {
            paint.color = p.accent.toArgb()
            paint.strokeWidth = 2 * density
            c.drawLine(x(now), 0f, x(now), barH, paint)
        }

        // hour labels
        val hours = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9 * density; color = p.faint.toArgb(); textAlign = Paint.Align.CENTER
        }
        for (hour in 8..24 step 4) {
            val hx = x(hour.toDouble()).coerceIn(7 * density, w - 7 * density)
            c.drawText("%02d".format(hour % 24), hx, barH + 11 * density, hours)
        }
        return bmp
    }
}
