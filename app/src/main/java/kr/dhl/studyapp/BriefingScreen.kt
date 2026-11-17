package kr.dhl.studyapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun BriefingScreen(s: Snapshot, prefs: Prefs, syncing: Boolean, onRefresh: () -> Unit) {
    val today = LocalDate.now()
    val overdue = Brief.overdue(s, prefs, today)
    val todayLines = Brief.todayLines(s, prefs, today)
    val week = Brief.week(s, prefs, today)
    val slice = Brief.slice(s, today)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("데일리 브리핑", Brief.dayLabel(today), syncing, onRefresh) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("${todayLines.size}", "오늘 일정", Modifier.weight(1f))
                Stat(Brief.dur(slice.freeHours), "오늘 빈 시간", Modifier.weight(1f))
                Stat("${week.sumOf { it.second.size }}", "7일 내 항목", Modifier.weight(1f))
                Stat("${overdue.size}", "기한 지남", Modifier.weight(1f), hot = overdue.isNotEmpty())
            }
        }
        if (s.errors.isNotEmpty()) item {
            Card(tint = Color(0xFFFDECEA)) {
                s.errors.forEach { Text("• $it", fontSize = 13.sp, color = Danger) }
            }
        }
        if (overdue.isNotEmpty()) item {
            Card("⚠ 기한 지남", titleColor = Danger) { overdue.forEach { LineRow(it) } }
        }
        item {
            Card("🕒 오늘 슬라이스") {
                SliceBar(slice)
                Legend()
                Spacer(Modifier.height(6.dp))
                if (slice.free.isEmpty()) Text("빈 시간 없음", color = Muted, fontSize = 13.sp)
                slice.free.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                        row.forEach { (a, b) ->
                            Text(
                                "${Brief.fmtH(a)}–${Brief.fmtH(b)} · ${Brief.dur(b - a)}",
                                fontSize = 12.sp, color = Color(0xFF17663F),
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFE3F4EA))
                                    .border(1.dp, Color(0xFF7CC79C), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        item {
            Card("📌 오늘") {
                if (todayLines.isEmpty()) Text("일정 없음", color = Muted, fontSize = 13.sp)
                todayLines.forEach { LineRow(it) }
            }
        }
        if (s.markets.isNotEmpty()) item {
            Card("📈 간밤 미국장") {
                s.markets.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        pair.forEach { m -> MarketTile(m, Modifier.weight(1f)) }
                    }
                }
                s.marketSession?.let { Text("$it 마감 기준", fontSize = 12.sp, color = Muted) }
            }
        }
        item {
            Card("🗓 다음 7일") {
                if (week.isEmpty()) Text("항목 없음", color = Muted, fontSize = 13.sp)
                week.forEach { (label, lines) ->
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                    lines.forEach { LineRow(it) }
                }
            }
        }
        item {
            val t = if (s.fetchedAt > 0) SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(s.fetchedAt)) else "아직 없음"
            Text("마지막 동기화 $t · Google 캘린더 + Todoist · 이동 1시간 기준", fontSize = 11.sp, color = Color(0xFF888888))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, hot: Boolean = false) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F2)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (hot) Danger else Color(0xFF1C211D))
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
fun Card(
    title: String? = null,
    titleColor: Color = Color(0xFF1C211D),
    tint: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint)
            .border(1.dp, Color(0xFFE6E8E5), RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = titleColor, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
fun LineRow(l: Line) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(l.time, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF333333), modifier = Modifier.width(76.dp))
        Column(Modifier.weight(1f)) {
            CourseChip(l.course)
            Text(l.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MarketTile(m: MarketItem, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F2)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(m.name, fontSize = 11.sp, color = Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.close?.let { "%,.2f".format(it) } ?: "확인 불가", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            m.pct?.let {
                Text(
                    " %+.2f%%".format(it), fontSize = 12.sp,
                    color = if (it >= 0) Color(0xFFC4372D) else Color(0xFF2D5BD7),
                )
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        listOf(
            Color(0xFFEBE9FD) to "수업", Color(0xFFFBE8E1) to "알바", Color(0xFFE9ECEF) to "기타",
            Color(0xFFC9CFC9) to "이동", Color(0xFFE3F4EA) to "빈 시간",
        ).forEach { (c, t) ->
            Box(Modifier.padding(start = 4.dp, end = 3.dp).size(9.dp).clip(RoundedCornerShape(2.dp)).background(c))
            Text(t, fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
fun SliceBar(r: SliceResult) {
    Canvas(Modifier.fillMaxWidth().height(68.dp)) {
        val lo = 8.0
        val w = size.width
        val top = 4.dp.toPx()
        val h = 44.dp.toPx()
        fun x(t: Double) = ((t.coerceIn(lo, 24.0) - lo) / (24.0 - lo) * w).toFloat()

        drawRoundRect(Color(0xFFF3F4F2), Offset(0f, top), Size(w, h), CornerRadius(6.dp.toPx()))
        for ((a, b) in r.free) {
            val l = x(a) + 1f
            val ww = max(x(b) - x(a) - 2f, 1f)
            drawRoundRect(Color(0xFFE3F4EA), Offset(l, top + 1f), Size(ww, h - 2f), CornerRadius(5.dp.toPx()))
            drawRoundRect(
                Color(0xFF7CC79C), Offset(l, top + 1f), Size(ww, h - 2f), CornerRadius(5.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
            )
        }
        for ((a, b) in r.travel) {
            if (b <= lo) continue
            val l = x(a)
            val rr = x(b)
            drawRect(Color(0xFFECEEEB), Offset(l, top), Size(max(rr - l, 1f), h))
            clipRect(l, top, rr, top + h) {
                var xx = l - h
                while (xx < rr) {
                    drawLine(Color(0xFFC9CFC9), Offset(xx, top + h), Offset(xx + h, top), strokeWidth = 2.dp.toPx())
                    xx += 6.dp.toPx()
                }
            }
        }
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 10.sp.toPx() }
        for (i in r.items) {
            if (i.b <= lo) continue
            val (bg, ink) = when (i.kind) {
                "cls" -> Color(0xFFEBE9FD) to Color(0xFF3A3290)
                "job" -> Color(0xFFFBE8E1) to Color(0xFF7A2C13)
                else -> Color(0xFFE9ECEF) to Color(0xFF38414A)
            }
            val l = x(i.a)
            val ww = x(i.b) - l
            drawRoundRect(bg, Offset(l, top), Size(max(ww, 1f), h), CornerRadius(4.dp.toPx()))
            if (ww > 24.dp.toPx()) {
                paint.color = ink.toArgb()
                val n = paint.breakText(i.title, true, ww - 4.dp.toPx(), null)
                drawContext.canvas.nativeCanvas.drawText(i.title.take(max(n, 1)), l + 3.dp.toPx(), top + 14.dp.toPx(), paint)
            }
        }
        paint.color = Color(0xFF6A736D).toArgb()
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.textSize = 9.sp.toPx()
        for (hr in 8..24 step 2) {
            val xx = x(hr.toDouble())
            drawLine(Color(0xFF9AA49D), Offset(xx, top + h), Offset(xx, top + h + 4.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(
                "%02d".format(hr % 24), xx.coerceIn(8.dp.toPx(), w - 8.dp.toPx()), top + h + 15.dp.toPx(), paint,
            )
        }
    }
}
