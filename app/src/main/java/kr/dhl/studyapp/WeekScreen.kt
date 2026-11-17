package kr.dhl.studyapp

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

private const val DAYS_SHOWN = 14

@Composable
fun WeekScreen(s: Snapshot, repo: Repo, snackbar: SnackbarHostState, syncing: Boolean, onRefresh: () -> Unit) {
    val p = pal
    val today = LocalDate.now()
    val tasks = Brief.tasks(s, repo.prefs)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()

    // Drag state. Positions are in root (window) coordinates.
    val targets = remember { mutableStateMapOf<String, Rect>() }
    val cardPos = remember { mutableStateMapOf<String, Offset>() }
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    var rootHeight by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf<TaskItem?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var dragWidth by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<TaskItem?>(null) }
    var picking by remember { mutableStateOf<TaskItem?>(null) }

    fun current(id: String): TaskItem? = Store.snapshot.value.tasks.firstOrNull { it.id == id }
    fun keyOf(t: TaskItem): String = t.date?.let { if (it.isBefore(LocalDate.now())) "overdue" else "d:$it" } ?: "none"
    fun hoverKey(): String? = targets.entries.firstOrNull { it.value.contains(pointer) }?.key

    fun move(t: TaskItem, newDate: LocalDate?) {
        val oldDate = t.date
        scope.launch {
            val r = repo.moveTask(t, newDate)
            if (r.isFailure) {
                snackbar.showSnackbar(tr("옮기지 못했어요: ", "Couldn't move: ") + (r.exceptionOrNull()?.message ?: ""))
                return@launch
            }
            val where = newDate?.let { Brief.dayLabel(it) } ?: tr("날짜 없음", "No date")
            val res = snackbar.showSnackbar("‘${t.title}’ → $where", actionLabel = tr("되돌리기", "Undo"), duration = SnackbarDuration.Short)
            if (res == SnackbarResult.ActionPerformed) {
                val cur = current(t.id) ?: return@launch
                repo.moveTask(cur, oldDate)
            }
        }
    }

    fun complete(t: TaskItem) {
        scope.launch {
            val r = repo.complete(t)
            if (r.isFailure) {
                snackbar.showSnackbar(tr("완료하지 못했어요: ", "Couldn't complete: ") + (r.exceptionOrNull()?.message ?: ""))
                return@launch
            }
            val res = snackbar.showSnackbar(tr("‘${t.title}’ 완료", "‘${t.title}’ done"), actionLabel = tr("되돌리기", "Undo"), duration = SnackbarDuration.Short)
            if (res == SnackbarResult.ActionPerformed) repo.reopen(t)
        }
    }

    fun drop() {
        val t = dragging ?: return
        val target = hoverKey()
        dragging = null
        if (target == null || target == "overdue" || target == keyOf(t)) return
        move(t, if (target == "none") null else LocalDate.parse(target.removePrefix("d:")))
    }

    // Auto-scroll while a card is held near the top or bottom edge.
    LaunchedEffect(dragging != null) {
        val edge = with(density) { 80.dp.toPx() }
        while (dragging != null) {
            val y = pointer.y - rootPos.y
            val delta = when {
                y < edge -> -(edge - y) / edge * 28f
                y > rootHeight - edge -> (y - (rootHeight - edge)) / edge * 28f
                else -> 0f
            }
            if (delta != 0f) scroll.scrollBy(delta)
            delay(16)
        }
    }

    @Composable
    fun DraggableTask(t: TaskItem) {
        val isDragged = dragging?.id == t.id
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { cardPos[t.id] = it.positionInRoot() }
                .pointerInput(t.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            val cur = current(t.id) ?: t
                            if (cur.recurring) {
                                scope.launch { snackbar.showSnackbar(tr("반복 작업은 Todoist 앱에서 옮겨 주세요", "Move recurring tasks in the Todoist app")) }
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                grab = off
                                dragWidth = size.width
                                pointer = (cardPos[t.id] ?: Offset.Zero) + off
                                dragging = cur
                            }
                        },
                        onDrag = { change, _ ->
                            if (dragging != null) {
                                change.consume()
                                pointer = (cardPos[t.id] ?: Offset.Zero) + change.position
                            }
                        },
                        onDragEnd = { drop() },
                        onDragCancel = { dragging = null },
                    )
                }
                .alpha(if (isDragged) 0.3f else 1f)
                .clickable { detail = current(t.id) ?: t },
        ) {
            TaskCard(t, onCheck = { complete(t) })
        }
    }

    val hovered = if (dragging != null) hoverKey() else null

    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            rootPos = it.positionInRoot()
            rootHeight = it.size.height.toFloat()
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(tr("할 일", "To-do"), syncing, onRefresh)

            val overdue = tasks.filter { keyOf(it) == "overdue" }.sortedBy { it.deadline }
            if (overdue.isNotEmpty()) {
                DaySection("overdue", tr("지난 날짜", "Past dates"), null, false, p.danger, { k, r -> targets[k] = r }) {
                    overdue.forEach { t -> key(t.id) { DraggableTask(t) } }
                }
            } else targets.remove("overdue")

            for (i in 0 until DAYS_SHOWN) {
                val d = today.plusDays(i.toLong())
                val k = "d:$d"
                val dayTasks = tasks.filter { it.date == d }.sortedWith(compareBy<TaskItem>({ it.time == null }, { it.time }, { -it.priority }, { it.order }))
                val events = s.events.filter { it.occursOn(d) }
                val label = when (i) { 0 -> tr("오늘 · ", "Today · "); 1 -> tr("내일 · ", "Tomorrow · "); else -> "" } + Brief.dayLabel(d)
                val weekend = d.dayOfWeek.value >= 6
                DaySection(k, label, events, hovered == k, if (weekend) p.danger else p.text, { kk, r -> targets[kk] = r }) {
                    dayTasks.forEach { t -> key(t.id) { DraggableTask(t) } }
                }
            }

            val undated = tasks.filter { it.date == null }.sortedWith(compareBy<TaskItem>({ -it.priority }, { it.order }))
            DaySection("none", tr("날짜 없음", "No date"), null, hovered == "none", p.sub, { k, r -> targets[k] = r }) {
                undated.forEach { t -> key(t.id) { DraggableTask(t) } }
            }
            Spacer(Modifier.height(80.dp))
        }

        // The card that follows the finger.
        dragging?.let { t ->
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (pointer.x - rootPos.x - grab.x).roundToInt(),
                            (pointer.y - rootPos.y - grab.y).roundToInt(),
                        )
                    }
                    .width(with(density) { dragWidth.toDp() })
                    .shadow(16.dp, RoundedCornerShape(16.dp)),
            ) { TaskCard(t, onCheck = null) }
        }
    }

    detail?.let { t ->
        TaskDialog(
            t,
            onDismiss = { detail = null },
            onMove = { d -> detail = null; move(t, d) },
            onPick = { detail = null; picking = t },
            onComplete = { detail = null; complete(t) },
        )
    }
    picking?.let { t ->
        PickDate(t.date, onPick = { d -> move(t, d) }, onDismiss = { picking = null })
    }
}

@Composable
private fun DaySection(
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
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .defaultMinSize(minHeight = 44.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = titleColor, modifier = Modifier.padding(start = 4.dp))
        if (!events.isNullOrEmpty()) {
            val classes = events.filter { it.isClass && !it.allDay }
            if (classes.isNotEmpty()) {
                Text(
                    tr("수업  ", "Class  ") + classes.joinToString("  ·  ") { Brief.hm(it.startAt.toLocalTime()) + " " + it.title.removeSuffix("수업").trim() },
                    fontSize = 12.sp, color = p.sub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            events.filter { !it.isClass }.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        when {
                            e.allDay -> tr("종일", "All day")
                            e.isDeadline -> "~" + Brief.hm(e.startAt.toLocalTime())
                            else -> Brief.hm(e.startAt.toLocalTime())
                        },
                        fontSize = 12.sp, color = p.sub, modifier = Modifier.width(46.dp),
                    )
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (e.isDeadline) p.danger else p.faint))
                    Text("  " + e.title, fontSize = 13.sp, color = p.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
        content()
    }
}

@Composable
private fun priorityColor(pr: Int): Color {
    val p = pal
    return when (pr) {
        4 -> p.danger
        3 -> Color(0xFFFF9F0A)
        2 -> p.accent
        else -> p.faint
    }
}

@Composable
private fun TaskCard(t: TaskItem, onCheck: (() -> Unit)?) {
    val p = pal
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(p.tile).padding(horizontal = 14.dp, vertical = 13.dp),
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
            if (t.isPlanned) t.realDeadline?.let {
                Text(tr("마감 ", "Due ") + "${it.monthValue}/${it.dayOfMonth}", fontSize = 11.sp, color = p.danger, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TaskDialog(
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
                    (t.date?.let { Brief.dayLabel(it) + (t.dueTime?.let { tm -> " $tm" } ?: "") } ?: tr("없음", "none")),
                    fontSize = 14.sp, color = p.text,
                )
                if (t.isPlanned) t.realDeadline?.let {
                    Text(tr("마감  ", "Due  ") + Brief.dayLabel(it.toLocalDate()) + " " + Brief.hm(it.toLocalTime()), fontSize = 14.sp, color = p.danger)
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

@Composable
fun Pill(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val p = pal
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) p.accent else if (p.dark) p.tile else p.card)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else p.text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
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
