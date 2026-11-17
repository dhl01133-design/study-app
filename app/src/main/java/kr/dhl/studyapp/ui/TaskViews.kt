package kr.dhl.studyapp.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.dhl.studyapp.brief.Fmt
import kr.dhl.studyapp.data.CalEvent
import kr.dhl.studyapp.data.TaskItem
import kr.dhl.studyapp.i18n.tr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A day's card: title, classes in one line, other events, then the tasks. Reports its bounds as a drop target. */
@Composable
fun DaySection(
    key: String,
    title: String,
    events: List<CalEvent>?,
    highlight: Boolean,
    titleColor: Color,
    onBounds: (String, Rect) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = pal
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onBounds(key, it.boundsInRoot()) }
            .clip(shape)
            .background(if (highlight) p.accentSoft else p.card)
            .then(if (highlight) Modifier.border(2.dp, p.accent, shape) else Modifier)
            .padding(14.dp)
            .defaultMinSize(minHeight = 44.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = titleColor, modifier = Modifier.padding(start = 4.dp))
        events.orEmpty().let { evs ->
            val classes = evs.filter { it.isClass && !it.allDay }
            if (classes.isNotEmpty()) {
                Text(
                    tr("수업  ", "Class  ") + classes.joinToString("  ·  ") {
                        Fmt.time(it.startAt.toLocalTime()) + " " + it.title.removeSuffix("수업").trim()
                    },
                    fontSize = 12.sp, color = p.sub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            evs.filter { !it.isClass }.forEach { EventRow(it) }
        }
        content()
    }
}

@Composable
private fun EventRow(e: CalEvent) {
    val p = pal
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Text(
            when {
                e.allDay -> tr("종일", "All day")
                e.isDeadline -> "~" + Fmt.time(e.startAt.toLocalTime())
                else -> Fmt.time(e.startAt.toLocalTime())
            },
            fontSize = 12.sp, color = p.sub, modifier = Modifier.width(46.dp),
        )
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (e.isDeadline) p.danger else p.faint))
        Text("  " + e.title, fontSize = 13.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun priorityColor(priority: Int): Color {
    val p = pal
    return when (priority) {
        4 -> p.danger
        3 -> Color(0xFFFF9F0A)
        2 -> p.accent
        else -> p.faint
    }
}

/** A task: priority circle (tap to complete when [onCheck] is set), title, course, time and real deadline. */
@Composable
fun TaskCard(t: TaskItem, onCheck: (() -> Unit)?) {
    val p = pal
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.tile).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).border(2.dp, priorityColor(t.priority), CircleShape)
                .then(if (onCheck != null) Modifier.clickable { onCheck() } else Modifier),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.course.isNotEmpty() || t.recurring) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CourseChip(t.course)
                    if (t.recurring) Text(" ↻", fontSize = 12.sp, color = p.sub)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            t.dueTime?.let { Text(it, fontSize = 13.sp, color = p.sub, fontWeight = FontWeight.Medium) }
            if (t.isPlanned) t.deadline?.let {
                Text(tr("마감 ", "Due ") + Fmt.md(it.toLocalDate()), fontSize = 11.sp, color = p.danger, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Tap-a-task popup: dates, quick moves and 완료. */
@Composable
fun TaskDialog(
    t: TaskItem,
    onDismiss: () -> Unit,
    onMove: (LocalDate?) -> Unit,
    onPick: () -> Unit,
    onComplete: () -> Unit,
) {
    val p = pal
    val today = LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (p.dark) p.card else Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text(t.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = p.text) },
        text = {
            Column {
                CourseChip(t.course)
                Spacer(Modifier.height(10.dp))
                Text(
                    t.date?.let { Fmt.day(it) + (t.dueTime?.let { time -> " $time" } ?: "") } ?: tr("없음", "none"),
                    fontSize = 14.sp, color = p.text,
                )
                if (t.isPlanned) t.deadline?.let {
                    Text(tr("마감  ", "Due  ") + Fmt.day(it.toLocalDate()) + " " + Fmt.time(it.toLocalTime()), fontSize = 14.sp, color = p.danger)
                }
                if (!t.recurring) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(tr("오늘", "Today"), Modifier.weight(1f)) { onMove(today) }
                        Pill(tr("내일", "Tomorrow"), Modifier.weight(1f)) { onMove(today.plusDays(1)) }
                        Pill(tr("+1주", "+1 week"), Modifier.weight(1f)) { onMove((t.date ?: today).plusDays(7)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(tr("날짜 선택", "Pick date"), Modifier.weight(1f)) { onPick() }
                        Pill(tr("날짜 없음", "No date"), Modifier.weight(1f)) { onMove(null) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onComplete) { Text(tr("완료", "Done"), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("닫기", "Close"), color = p.sub) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerFor(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // DatePicker works in UTC milliseconds.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(tr("옮기기", "Move")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("취소", "Cancel")) } },
    ) { DatePicker(state = state) }
}
