package kr.dhl.studyapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.dhl.studyapp.brief.Brief
import kr.dhl.studyapp.brief.DaySlice
import kr.dhl.studyapp.brief.Fmt
import kr.dhl.studyapp.data.MarketItem
import kr.dhl.studyapp.data.Prefs
import kr.dhl.studyapp.data.Snapshot
import kr.dhl.studyapp.i18n.marketName
import kr.dhl.studyapp.i18n.tr
import java.time.LocalDate

@Composable
fun BriefingScreen(s: Snapshot, prefs: Prefs, syncing: Boolean, onRefresh: () -> Unit) {
    val p = pal
    val today = LocalDate.now()
    val overdue = Brief.overdue(s, prefs, today)
    val todayLines = Brief.todayLines(s, prefs, today)
    val week = Brief.week(s, prefs, today)
    val slice = DaySlice.of(s, today)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item { Header(Fmt.day(today), syncing, onRefresh) }
        item { UpdateBanner(prefs) }
        item {
            Row {
                Stat("${todayLines.size}", tr("오늘", "Today"), Modifier.weight(1f))
                Stat(Fmt.duration(slice.freeHours), tr("빈 시간", "Free"), Modifier.weight(1f), p.good)
                Stat("${week.sumOf { it.second.size }}", tr("7일", "7 days"), Modifier.weight(1f))
                Stat("${overdue.size}", tr("기한 지남", "Overdue"), Modifier.weight(1f), if (overdue.isNotEmpty()) p.danger else p.text)
            }
        }
        if (s.errors.isNotEmpty()) item {
            Column { s.errors.forEach { Text(it, fontSize = 13.sp, color = p.danger, modifier = Modifier.padding(vertical = 2.dp)) } }
        }
        if (overdue.isNotEmpty()) item {
            Section(tr("기한 지남", "Overdue"), titleColor = p.danger) { overdue.forEach { LineRow(it) } }
        }
        item {
            Section(tr("시간", "Time")) {
                SliceBar(slice)
                if (slice.free.isEmpty()) Text(tr("빈 시간 없음", "No free time"), color = p.sub, fontSize = 14.sp)
                slice.free.forEach { (start, end) -> FreeRow(start, end) }
            }
        }
        item {
            Section(tr("오늘", "Today")) {
                if (todayLines.isEmpty()) Text(tr("없음", "Nothing"), color = p.sub, fontSize = 14.sp)
                todayLines.forEach { LineRow(it) }
            }
        }
        if (s.markets.isNotEmpty()) item {
            Section(tr("미국장", "Markets"), trailing = s.marketSession) {
                s.markets.forEach { MarketRow(it) }
            }
        }
        item {
            Section(tr("다음 7일", "Next 7 days")) {
                if (week.isEmpty()) Text(tr("없음", "Nothing"), color = p.sub, fontSize = 14.sp)
                week.forEachIndexed { i, (label, lines) ->
                    Text(
                        label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.text,
                        modifier = Modifier.padding(top = if (i == 0) 4.dp else 16.dp, bottom = 2.dp),
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
    Column(modifier) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Text(label, fontSize = 12.sp, color = pal.sub, maxLines = 1)
    }
}

@Composable
private fun FreeRow(start: Double, end: Double) {
    val p = pal
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${Fmt.clock(start)} – ${Fmt.clock(end)}", fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
        Text(Fmt.duration(end - start), fontSize = 15.sp, color = p.good)
    }
}

@Composable
private fun MarketRow(m: MarketItem) {
    val p = pal
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(marketName(m.name), fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
        Text(m.close?.let { "%,.2f".format(it) } ?: "–", fontSize = 15.sp, color = p.text)
        Text(
            m.pct?.let { "%+.2f%%".format(it) } ?: "",
            fontSize = 15.sp, color = if ((m.pct ?: 0.0) >= 0) p.good else p.sub,
            textAlign = TextAlign.End, modifier = Modifier.width(76.dp),
        )
    }
}
