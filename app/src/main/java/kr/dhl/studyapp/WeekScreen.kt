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
import androidx.compose.material3.MaterialTheme
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
                snackbar.showSnackbar("옮기지 못했어요: ${r.exceptionOrNull()?.message ?: ""}")
                return@launch
            }
            val where = newDate?.let { Brief.dayLabel(it) } ?: "날짜 없음"
            val res = snackbar.showSnackbar("‘${t.title}’ → $where", actionLabel = "되돌리기", duration = SnackbarDuration.Short)
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
                snackbar.showSnackbar("완료하지 못했어요: ${r.exceptionOrNull()?.message ?: ""}")
                return@launch
            }
            val res = snackbar.showSnackbar("‘${t.title}’ 완료", actionLabel = "되돌리기", duration = SnackbarDuration.Short)
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
                                scope.launch { snackbar.showSnackbar("반복 작업은 Todoist 앱에서 옮겨 주세요") }
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
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header("할 일 · 길게 눌러 다른 날로 끌어 놓기", "2주 일정", syncing, onRefresh)

            val overdue = tasks.filter { keyOf(it) == "overdue" }.sortedBy { it.deadline }
            if (overdue.isNotEmpty()) {
                DaySection("overdue", "⚠ 지난 날짜 (다시 배치하세요)", null, false, Danger, { k, r -> targets[k] = r }) {
                    overdue.forEach { t -> key(t.id) { DraggableTask(t) } }
                }
            } else targets.remove("overdue")

            for (i in 0 until DAYS_SHOWN) {
                val d = today.plusDays(i.toLong())
                val k = "d:$d"
                val dayTasks = tasks.filter { it.date == d }.sortedWith(compareBy<TaskItem>({ it.time == null }, { it.time }, { -it.priority }, { it.order }))
                val events = s.events.filter { it.occursOn(d) }
                val label = when (i) { 0 -> "오늘 · "; 1 -> "내일 · "; else -> "" } + Brief.dayLabel(d)
                val weekend = d.dayOfWeek.value >= 6
                DaySection(k, label, events, hovered == k, if (weekend) Color(0xFFB4420F) else Color(0xFF1C211D), { kk, r -> targets[kk] = r }) {
                    dayTasks.forEach { t -> key(t.id) { DraggableTask(t) } }
                }
            }

            val undated = tasks.filter { it.date == null }.sortedWith(compareBy<TaskItem>({ -it.priority }, { it.order }))
            DaySection("none", "날짜 없음", null, hovered == "none", Muted, { k, r -> targets[k] = r }) {
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
                    .shadow(10.dp, RoundedCornerShape(10.dp)),
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
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onBounds(key, it.boundsInRoot()) }
            .clip(shape)
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else Color.White)
            .border(if (highlight) 2.dp else 1.dp, if (highlight) MaterialTheme.colorScheme.primary else Color(0xFFE6E8E5), shape)
            .padding(10.dp)
            .defaultMinSize(minHeight = 40.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = titleColor)
        if (!events.isNullOrEmpty()) {
            val classes = events.filter { it.isClass && !it.allDay }
            if (classes.isNotEmpty()) {
                Text(
                    "수업 " + classes.joinToString(" · ") { Brief.hm(it.startAt.toLocalTime()) + " " + it.title.removeSuffix("수업").trim() },
                    fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            events.filter { !it.isClass }.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            e.allDay -> "종일"
                            e.isDeadline -> "~" + Brief.hm(e.startAt.toLocalTime())
                            else -> Brief.hm(e.startAt.toLocalTime())
                        },
                        fontSize = 12.sp, color = Muted, modifier = Modifier.width(48.dp),
                    )
                    Text("📅 ", fontSize = 12.sp)
                    CourseChip(e.course)
                    Text(" " + e.title, fontSize = 12.sp, color = Color(0xFF38414A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        content()
    }
}

private fun priorityColor(p: Int) = when (p) {
    4 -> Color(0xFFD1453B)
    3 -> Color(0xFFEB8909)
    2 -> Color(0xFF246FE0)
    else -> Color(0xFFA0A6A2)
}

@Composable
private fun TaskCard(t: TaskItem, onCheck: (() -> Unit)?) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFFFAFBFA)).border(1.dp, Color(0xFFE1E4E0), shape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, priorityColor(t.priority), CircleShape)
                .then(if (onCheck != null) Modifier.clickable { onCheck() } else Modifier),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (t.course.isNotEmpty() || t.recurring) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CourseChip(t.course)
                    if (t.recurring) Text(" 🔁", fontSize = 11.sp)
                }
            }
            Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            t.dueTime?.let { Text(it, fontSize = 12.sp, color = Muted) }
            if (t.isPlanned) t.realDeadline?.let { Text("마감 ${it.monthValue}/${it.dayOfMonth}", fontSize = 11.sp, color = Danger) }
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
    val today = LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.title, fontSize = 18.sp) },
        text = {
            Column {
                Row { CourseChip(t.course) }
                Text(
                    "할 날: " + (t.date?.let { Brief.dayLabel(it) + (t.dueTime?.let { tm -> " $tm" } ?: "") } ?: "없음") +
                        (t.realDeadline?.let { "\n마감: " + Brief.dayLabel(it.toLocalDate()) + " " + Brief.hm(it.toLocalTime()) } ?: ""),
                    fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp),
                )
                if (t.description.isNotBlank()) {
                    Text(t.description.take(300), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                if (t.recurring) {
                    Text("반복 작업은 Todoist 앱에서 날짜를 바꿔 주세요.", fontSize = 12.sp, color = Danger, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { onMove(today) }) { Text("오늘") }
                        TextButton(onClick = { onMove(today.plusDays(1)) }) { Text("내일") }
                        TextButton(onClick = { onMove((t.date ?: today).plusDays(7)) }) { Text("+1주") }
                    }
                    Row {
                        TextButton(onClick = onPick) { Text("날짜 선택…") }
                        TextButton(onClick = { onMove(null) }) { Text("날짜 없음") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onComplete) { Text("완료") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
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
            }) { Text("옮기기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) { DatePicker(state = state) }
}
