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

/** Today from 08 to 24: events, hatched travel time, green free time and a red "now" line. */
@Composable
fun SliceBar(slice: DaySlice) {
    val p = pal
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val w = size.width
        val top = 2.dp.toPx()
        val h = 46.dp.toPx()
        fun x(hour: Double) = ((hour.coerceIn(FIRST_HOUR, LAST_HOUR) - FIRST_HOUR) / (LAST_HOUR - FIRST_HOUR) * w).toFloat()

        // track and free time
        drawRoundRect(p.travel.copy(alpha = if (p.dark) 1f else 0.6f), Offset(0f, top), Size(w, h), CornerRadius(10.dp.toPx()))
        for ((a, b) in slice.free) {
            drawRoundRect(p.free, Offset(x(a) + 1.5f, top), Size(max(x(b) - x(a) - 3f, 1f), h), CornerRadius(10.dp.toPx()))
        }

        // travel: diagonal hatching
        for ((a, b) in slice.travel) {
            if (b <= FIRST_HOUR) continue
            val left = x(a)
            val right = x(b)
            clipRect(left, top, right, top + h) {
                var lx = left - h
                while (lx < right) {
                    drawLine(p.travelLine, Offset(lx, top + h), Offset(lx + h, top), strokeWidth = 1.5.dp.toPx())
                    lx += 6.dp.toPx()
                }
            }
        }

        // events with their titles
        val paint = Paint().apply { isAntiAlias = true; textSize = 10.sp.toPx(); isFakeBoldText = true }
        for (b in slice.blocks) {
            if (b.end <= FIRST_HOUR) continue
            val (bg, ink) = when (b.kind) {
                BlockKind.CLASS -> p.cls to p.clsInk
                BlockKind.JOB -> p.job to p.jobInk
                BlockKind.OTHER -> p.ev to p.evInk
            }
            val left = x(b.start)
            val width = x(b.end) - left
            drawRoundRect(bg, Offset(left, top), Size(max(width, 1f), h), CornerRadius(8.dp.toPx()))
            if (width > 24.dp.toPx()) {
                paint.color = ink.toArgb()
                val n = paint.breakText(b.title, true, width - 8.dp.toPx(), null)
                drawContext.canvas.nativeCanvas.drawText(b.title.take(max(n, 1)), left + 5.dp.toPx(), top + 16.dp.toPx(), paint)
            }
        }

        // hour labels
        paint.apply {
            isFakeBoldText = false
            color = p.faint.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 10.sp.toPx()
        }
        for (hour in 8..24 step 4) {
            val hx = x(hour.toDouble()).coerceIn(8.dp.toPx(), w - 8.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("%02d".format(hour % 24), hx, top + h + 16.dp.toPx(), paint)
        }

        // now
        val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
        if (now in FIRST_HOUR..LAST_HOUR) {
            val nx = x(now)
            drawLine(p.danger, Offset(nx, top - 2f), Offset(nx, top + h + 2f), strokeWidth = 2.dp.toPx())
            drawCircle(p.danger, 3.5.dp.toPx(), Offset(nx, top - 2f))
        }
    }
}
