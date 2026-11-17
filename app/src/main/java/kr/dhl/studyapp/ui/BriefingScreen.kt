package kr.dhl.studyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(Fmt.day(today), syncing, onRefresh) }
        item { UpdateBanner(prefs) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${todayLines.size}", tr("오늘 일정", "Today"), Modifier.weight(1f))
                Stat(Fmt.duration(slice.freeHours), tr("빈 시간", "Free"), Modifier.weight(1f), color = p.freeInk)
                Stat("${week.sumOf { it.second.size }}", tr("7일 내", "Next 7d"), Modifier.weight(1f))
                Stat("${overdue.size}", tr("기한 지남", "Overdue"), Modifier.weight(1f), color = if (overdue.isNotEmpty()) p.danger else p.text)
            }
        }
        if (s.errors.isNotEmpty()) item {
            SoftCard(tint = p.dangerSoft) {
                s.errors.forEach { Text(it, fontSize = 13.sp, color = p.danger, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
        if (overdue.isNotEmpty()) item {
            SoftCard(tr("기한 지남", "Overdue"), titleColor = p.danger) { overdue.forEach { LineRow(it) } }
        }
        item {
            SoftCard(tr("오늘 슬라이스", "Today's time")) {
                SliceBar(slice)
                Spacer(Modifier.height(6.dp))
                if (slice.free.isEmpty()) Text(tr("빈 시간 없음", "No free time"), color = p.sub, fontSize = 14.sp)
                slice.free.forEach { (start, end) -> FreeRow(start, end) }
            }
        }
        item {
            SoftCard(tr("오늘", "Today")) {
                if (todayLines.isEmpty()) Text(tr("일정 없음", "Nothing scheduled"), color = p.sub, fontSize = 14.sp)
                todayLines.forEach { LineRow(it) }
            }
        }
        if (s.markets.isNotEmpty()) item {
            SoftCard(tr("간밤 미국장", "US markets overnight"), trailing = s.marketSession?.let { tr("$it 마감", "$it close") }) {
                s.markets.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        pair.forEach { MarketTile(it, Modifier.weight(1f)) }
                    }
                }
            }
        }
        item {
            SoftCard(tr("다음 7일", "Next 7 days")) {
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

@Composable
private fun FreeRow(start: Double, end: Double) {
    val p = pal
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(p.freeLine))
        Text("  ${Fmt.clock(start)} – ${Fmt.clock(end)}", fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
        Text(Fmt.duration(end - start), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.freeInk)
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
