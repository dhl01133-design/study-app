package kr.dhl.studyapp.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.dhl.studyapp.brief.BlockKind
import kr.dhl.studyapp.brief.DaySlice
import java.time.LocalTime
import kotlin.math.max

private const val FIRST_HOUR = 8.0
private const val LAST_HOUR = 24.0

/** 08–24: blue = class, dark = other event, hatched = travel, green = free, blue line = now. */
@Composable
fun SliceBar(slice: DaySlice) {
    val p = pal
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width
        val h = 32.dp.toPx()
        val r = CornerRadius(4.dp.toPx())
        fun x(hour: Double) = ((hour.coerceIn(FIRST_HOUR, LAST_HOUR) - FIRST_HOUR) / (LAST_HOUR - FIRST_HOUR) * w).toFloat()

        drawRoundRect(p.track, Offset.Zero, Size(w, h), r)

        for ((a, b) in slice.travel) {
            if (b <= FIRST_HOUR) continue
            clipRect(x(a), 0f, x(b), h) {
                var lx = x(a) - h
                while (lx < x(b)) {
                    drawLine(p.hatch, Offset(lx, h), Offset(lx + h, 0f), strokeWidth = 1.dp.toPx())
                    lx += 5.dp.toPx()
                }
            }
        }

        for ((a, b) in slice.free) {
            drawRoundRect(p.goodSoft, Offset(x(a) + 1f, 1f), Size(max(x(b) - x(a) - 2f, 1f), h - 2f), r)
            drawRoundRect(p.good, Offset(x(a) + 1f, 1f), Size(max(x(b) - x(a) - 2f, 1f), h - 2f), r, style = Stroke(1.dp.toPx()))
        }

        val paint = Paint().apply { isAntiAlias = true; textSize = 10.sp.toPx(); color = p.onBusy.toArgb() }
        for (b in slice.blocks) {
            if (b.end <= FIRST_HOUR) continue
            val left = x(b.start)
            val width = x(b.end) - left
            drawRoundRect(if (b.kind == BlockKind.CLASS) p.accent else p.busy, Offset(left, 0f), Size(max(width, 1f), h), r)
            if (width > 24.dp.toPx()) {
                val n = paint.breakText(b.title, true, width - 8.dp.toPx(), null)
                drawContext.canvas.nativeCanvas.drawText(b.title.take(max(n, 1)), left + 4.dp.toPx(), h / 2 + 4.dp.toPx(), paint)
            }
        }

        val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
        if (now in FIRST_HOUR..LAST_HOUR) {
            drawLine(p.accent, Offset(x(now), -4f), Offset(x(now), h + 4f), strokeWidth = 2.dp.toPx())
        }

        paint.apply { color = p.faint.toArgb(); textAlign = Paint.Align.CENTER }
        for (hour in 8..24 step 4) {
            val hx = x(hour.toDouble()).coerceIn(8.dp.toPx(), w - 8.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("%02d".format(hour % 24), hx, h + 16.dp.toPx(), paint)
        }
    }
}
