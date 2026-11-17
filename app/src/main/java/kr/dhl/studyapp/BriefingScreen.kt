package kr.dhl.studyapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.max

@Composable
fun BriefingScreen(s: Snapshot, prefs: Prefs, syncing: Boolean, onRefresh: () -> Unit) {
    val p = pal
    val today = LocalDate.now()
    val overdue = Brief.overdue(s, prefs, today)
    val todayLines = Brief.todayLines(s, prefs, today)
    val week = Brief.week(s, prefs, today)
    val slice = Brief.slice(s, today)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(Brief.dayLabel(today), syncing, onRefresh) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${todayLines.size}", tr("오늘 일정", "Today"), Modifier.weight(1f))
                Stat(Brief.dur(slice.freeHours), tr("빈 시간", "Free"), Modifier.weight(1f), color = p.freeInk)
                Stat("${week.sumOf { it.second.size }}", tr("7일 내", "Next 7d"), Modifier.weight(1f))
                Stat("${overdue.size}", tr("기한 지남", "Overdue"), Modifier.weight(1f), color = if (overdue.isNotEmpty()) p.danger else p.text)
            }
        }
        if (s.errors.isNotEmpty()) item {
            Card(tint = p.dangerSoft) {
                s.errors.forEach { Text(it, fontSize = 13.sp, color = p.danger, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
        if (overdue.isNotEmpty()) item {
            Card(tr("기한 지남", "Overdue"), titleColor = p.danger) { overdue.forEach { LineRow(it) } }
        }
        item {
            Card(tr("오늘 슬라이스", "Today's time")) {
                SliceBar(slice)
                Spacer(Modifier.height(6.dp))
                if (slice.free.isEmpty()) Text(tr("빈 시간 없음", "No free time"), color = p.sub, fontSize = 14.sp)
                slice.free.forEach { (a, b) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(p.freeLine))
                        Text("  ${Brief.fmtH(a)} – ${Brief.fmtH(b)}", fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
                        Text(Brief.dur(b - a), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.freeInk)
                    }
                }
            }
        }
        item {
            Card(tr("오늘", "Today")) {
                if (todayLines.isEmpty()) Text(tr("일정 없음", "Nothing scheduled"), color = p.sub, fontSize = 14.sp)
                todayLines.forEach { LineRow(it) }
            }
        }
        if (s.markets.isNotEmpty()) item {
            Card(tr("간밤 미국장", "US markets overnight"), trailing = s.marketSession?.let { tr("$it 마감", "$it close") }) {
                s.markets.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        pair.forEach { m -> MarketTile(m, Modifier.weight(1f)) }
                    }
                }
            }
        }
        item {
            Card(tr("다음 7일", "Next 7 days")) {
                if (week.isEmpty()) Text(tr("항목 없음", "Nothing coming up"), color = p.sub, fontSize = 14.sp)
                week.forEachIndexed { i, (label, lines) ->
                    Text(
                        label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = p.sub,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 12.dp, bottom = 2.dp),
                    )
                    lines.forEach { LineRow(it) }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, color: Color = pal.text) {
    val p = pal
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(p.card).padding(horizontal = 12.dp, vertical = 14.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = color, letterSpacing = (-0.5).sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 12.sp, color = p.sub, maxLines = 1)
    }
}

/** Borderless soft card. */
@Composable
fun Card(
    title: String? = null,
    titleColor: Color = pal.text,
    tint: Color = pal.card,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = pal
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tint).padding(horizontal = 18.dp, vertical = 18.dp)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = titleColor, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, fontSize = 13.sp, color = p.sub)
            }
        }
        content()
    }
}

@Composable
fun LineRow(l: Line) {
    val p = pal
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(l.time, fontSize = 13.sp, color = p.sub, fontWeight = FontWeight.Medium, modifier = Modifier.width(84.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (l.course.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                CourseChip(l.course)
            }
        }
    }
}

@Composable
private fun MarketTile(m: MarketItem, modifier: Modifier) {
    val p = pal
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.tile).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(marketName(m.name), fontSize = 12.sp, color = p.sub)
        Text(m.close?.let { "%,.2f".format(it) } ?: tr("확인 불가", "n/a"), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.text)
        m.pct?.let {
            Text("%+.2f%%".format(it), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (it >= 0) p.up else p.down)
        }
    }
}

@Composable
fun SliceBar(r: SliceResult) {
    val p = pal
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val lo = 8.0
        val w = size.width
        val top = 2.dp.toPx()
        val h = 46.dp.toPx()
        val rad = CornerRadius(10.dp.toPx())
        fun x(t: Double) = ((t.coerceIn(lo, 24.0) - lo) / (24.0 - lo) * w).toFloat()

        drawRoundRect(p.travel.copy(alpha = if (p.dark) 1f else 0.6f), Offset(0f, top), Size(w, h), rad)
        for ((a, b) in r.free) {
            drawRoundRect(p.free, Offset(x(a) + 1.5f, top), Size(max(x(b) - x(a) - 3f, 1f), h), rad)
        }
        for ((a, b) in r.travel) {
            if (b <= lo) continue
            val l = x(a)
            val rr = x(b)
            clipRect(l, top, rr, top + h) {
                var xx = l - h
                while (xx < rr) {
                    drawLine(p.travelLine, Offset(xx, top + h), Offset(xx + h, top), strokeWidth = 1.5.dp.toPx())
                    xx += 6.dp.toPx()
                }
            }
        }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 10.sp.toPx(); isFakeBoldText = true
        }
        for (i in r.items) {
            if (i.b <= lo) continue
            val (bg, ink) = when (i.kind) {
                "cls" -> p.cls to p.clsInk
                "job" -> p.job to p.jobInk
                else -> p.ev to p.evInk
            }
            val l = x(i.a)
            val ww = x(i.b) - l
            drawRoundRect(bg, Offset(l, top), Size(max(ww, 1f), h), CornerRadius(8.dp.toPx()))
            if (ww > 24.dp.toPx()) {
                paint.color = ink.toArgb()
                val n = paint.breakText(i.title, true, ww - 8.dp.toPx(), null)
                drawContext.canvas.nativeCanvas.drawText(i.title.take(max(n, 1)), l + 5.dp.toPx(), top + 16.dp.toPx(), paint)
            }
        }
        paint.isFakeBoldText = false
        paint.color = p.faint.toArgb()
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.textSize = 10.sp.toPx()
        for (hr in 8..24 step 4) {
            val xx = x(hr.toDouble())
            drawContext.canvas.nativeCanvas.drawText(
                "%02d".format(hr % 24), xx.coerceIn(8.dp.toPx(), w - 8.dp.toPx()), top + h + 16.dp.toPx(), paint,
            )
        }
        // "now" marker
        val now = java.time.LocalTime.now().let { it.hour + it.minute / 60.0 }
        if (now in lo..24.0) {
            val xn = x(now)
            drawLine(p.danger, Offset(xn, top - 2f), Offset(xn, top + h + 2f), strokeWidth = 2.dp.toPx())
            drawCircle(p.danger, 3.5.dp.toPx(), Offset(xn, top - 2f))
        }
    }
}
