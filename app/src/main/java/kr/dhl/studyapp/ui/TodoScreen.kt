package kr.dhl.studyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.dhl.studyapp.brief.Brief
import kr.dhl.studyapp.brief.Fmt
import kr.dhl.studyapp.brief.PlanItem
import kr.dhl.studyapp.brief.Planner
import kr.dhl.studyapp.data.Edit
import kr.dhl.studyapp.data.Repo
import kr.dhl.studyapp.data.Snapshot
import kr.dhl.studyapp.data.Store
import kr.dhl.studyapp.data.TaskItem
import kr.dhl.studyapp.i18n.tr
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

private const val DAYS_SHOWN = 14

/** Drop targets: past dates (source only), each day, and "no date". */
private object Drop {
    const val PAST = "past"
    const val NO_DATE = "none"
    fun day(d: LocalDate) = "d:$d"

    fun of(t: TaskItem): String = t.date?.let { if (it.isBefore(LocalDate.now())) PAST else day(it) } ?: NO_DATE

    /** The date a key stands for; null for "no date". */
    fun date(key: String): LocalDate? = if (key == NO_DATE) null else LocalDate.parse(key.removePrefix("d:"))
}

private val byTime = compareBy<TaskItem>({ it.time == null }, { it.time }, { -it.priority }, { it.order })
private val byPriority = compareBy<TaskItem>({ -it.priority }, { it.order })

private typealias Move = Triple<TaskItem, LocalDate?, LocalTime?>

/**
 * Two weeks of tasks.
 * - Long-press a card and drag it onto another day (or "no date"); tap it for quick moves.
 * - Swipe right to complete, left to move to tomorrow; tap the circle to complete.
 * - Quick add at the top, Plan fills free time, "Move to today" clears past dates,
 *   long-press a day's title to complete its lecture videos. Every change can be undone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(s: Snapshot, repo: Repo, snackbar: SnackbarHostState, syncing: Boolean, onRefresh: () -> Unit) {
    val p = pal
    val today = LocalDate.now()
    val tasks = Brief.tasks(s, repo.prefs)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()

    // Drag state; positions are in root (window) coordinates.
    val targets = remember { mutableStateMapOf<String, Rect>() }
    val cardPos = remember { mutableStateMapOf<String, Offset>() }
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    var rootHeight by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf<TaskItem?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var dragWidth by remember { mutableIntStateOf(0) }

    // Dialogs and the quick-add field.
    var detail by remember { mutableStateOf<TaskItem?>(null) }
    var picking by remember { mutableStateOf<TaskItem?>(null) }
    var plan by remember { mutableStateOf<List<PlanItem>?>(null) }
    var videosOf by remember { mutableStateOf<LocalDate?>(null) }
    var newTask by remember { mutableStateOf("") }

    fun hoverKey(): String? = targets.entries.firstOrNull { it.value.contains(pointer) }?.key

    /** Snackbar for a finished change: the error, or the message with Undo ("offline" when queued). */
    fun report(e: Edit, message: String, undo: (suspend () -> Unit)?) = scope.launch {
        when (e) {
            is Edit.Failed -> if (e.message.isNotEmpty()) snackbar.showSnackbar(e.message)
            else -> {
                val text = if (e == Edit.Queued) message + tr(" · 오프라인", " · offline") else message
                if (undo == null) {
                    snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
                } else {
                    val res = snackbar.showSnackbar(text, actionLabel = tr("되돌리기", "Undo"), duration = SnackbarDuration.Short)
                    if (res == SnackbarResult.ActionPerformed) undo()
                }
            }
        }
    }

    fun move(t: TaskItem, date: LocalDate?) = scope.launch {
        val old = t.date
        val where = date?.let { Fmt.day(it) } ?: tr("날짜 없음", "No date")
        report(repo.moveTask(t, date), "‘${t.title}’ → $where") { Store.task(t.id)?.let { repo.moveTask(it, old) } }
    }

    fun complete(t: TaskItem) = scope.launch {
        report(repo.complete(t), tr("‘${t.title}’ 완료", "‘${t.title}’ done")) { repo.reopen(t) }
    }

    /** Several reschedules with one Undo that puts every task back where it was. */
    fun moveAll(moves: List<Move>, message: String) {
        val before = moves.map { (t, _, _) -> Move(t, t.date, t.time) }
        scope.launch {
            report(repo.rescheduleAll(moves), message) {
                repo.rescheduleAll(before.mapNotNull { (t, d, time) -> Store.task(t.id)?.let { Move(it, d, time) } })
            }
        }
    }

    fun completeAll(list: List<TaskItem>) = scope.launch {
        var result: Edit = Edit.Sent
        for (t in list) {
            val r = repo.complete(t)
            if (r is Edit.Failed) { result = r; break }
            if (r == Edit.Queued) result = Edit.Queued
        }
        report(result, tr("${list.size}개 완료", "${list.size} done")) { list.forEach { repo.reopen(it) } }
    }

    fun addTask() {
        val text = newTask.trim()
        if (text.isEmpty()) return
        newTask = ""
        scope.launch { report(repo.add(text), tr("추가됨", "Added"), undo = null) }
    }

    fun startPlan() {
        val items = Planner.plan(Store.snapshot.value, repo.prefs, LocalDateTime.now())
        if (items.isEmpty()) scope.launch { snackbar.showSnackbar(tr("넣을 할 일이 없어요", "Nothing to plan")) } else plan = items
    }

    fun drop() {
        val t = dragging ?: return
        val target = hoverKey()
        dragging = null
        if (target == null || target == Drop.PAST || target == Drop.of(t)) return
        move(t, Drop.date(target))
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

    // Open on today even when past-dated tasks are listed above it.
    LaunchedEffect(Unit) {
        val todayBounds = snapshotFlow { targets[Drop.day(today)] }.filterNotNull().first()
        if (targets.containsKey(Drop.PAST)) {
            val y = todayBounds.top - rootPos.y + scroll.value
            scroll.scrollTo(y.roundToInt().coerceAtLeast(0))
        }
    }

    @Composable
    fun DraggableTask(t: TaskItem) {
        var dx by remember(t.id) { mutableFloatStateOf(0f) }
        val threshold = with(density) { 96.dp.toPx() }
        Box(Modifier.fillMaxWidth()) {
            // Revealed behind the card while swiping.
            if (dx != 0f) {
                Row(Modifier.matchParentSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (dx > 0) Text(tr("완료", "Done"), color = p.good, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    if (dx < 0) Text(tr("내일", "Tomorrow"), color = p.accent, fontSize = 14.sp)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(dx.roundToInt(), 0) }
                    .background(p.bg)
                    .onGloballyPositioned { cardPos[t.id] = it.positionInRoot() }
                    .pointerInput(t.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val cur = Store.task(t.id) ?: t
                                when {
                                    dx > threshold -> complete(cur)
                                    dx < -threshold && cur.recurring -> scope.launch {
                                        snackbar.showSnackbar(tr("반복 작업은 Todoist 앱에서 옮겨 주세요", "Move recurring tasks in the Todoist app"))
                                    }
                                    dx < -threshold -> move(cur, LocalDate.now().plusDays(1))
                                }
                                dx = 0f
                            },
                            onDragCancel = { dx = 0f },
                        ) { change, amount ->
                            if (dragging == null) {
                                change.consume()
                                dx += amount
                            }
                        }
                    }
                    .pointerInput(t.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val cur = Store.task(t.id) ?: t
                                if (cur.recurring) {
                                    scope.launch { snackbar.showSnackbar(tr("반복 작업은 Todoist 앱에서 옮겨 주세요", "Move recurring tasks in the Todoist app")) }
                                } else {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    grab = offset
                                    dragWidth = size.width
                                    pointer = (cardPos[t.id] ?: Offset.Zero) + offset
                                    dragging = cur
                                }
                            },
                            onDrag = { change, _ ->
                                if (dragging != null) {
                                    change.consume()
                                    pointer = (cardPos[t.id] ?: Offset.Zero) + change.position
                                }
                            },
                            onDragEnd = {
                                if (dragging != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                drop()
                            },
                            onDragCancel = { dragging = null },
                        )
                    }
                    .alpha(if (dragging?.id == t.id) 0.3f else 1f)
                    .clickable { detail = Store.task(t.id) ?: t },
            ) {
                TaskCard(t, onCheck = { complete(t) })
            }
        }
    }

    val hovered = if (dragging != null) hoverKey() else null
    val onBounds: (String, Rect) -> Unit = { k, r -> targets[k] = r }
    val past = tasks.filter { Drop.of(it) == Drop.PAST }.sortedBy { it.planned }

    // Forget drop targets that are no longer on screen (e.g. yesterday, after midnight).
    val shownKeys = buildSet {
        if (past.isNotEmpty()) add(Drop.PAST)
        for (n in 0 until DAYS_SHOWN) add(Drop.day(today.plusDays(n.toLong())))
        add(Drop.NO_DATE)
    }
    SideEffect { targets.keys.retainAll(shownKeys) }

    PullToRefreshBox(isRefreshing = syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Title, Plan and quick add stay put; the days scroll below them.
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header(tr("할 일", "To-do"), listOf(Pair(tr("계획", "Plan")) { startPlan() }))
            OutlinedTextField(
                    newTask, { newTask = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text(tr("할 일 추가", "Add task"), color = p.faint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTask() }),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = p.text, unfocusedBorderColor = p.line,
                        focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent,
                    ),
                )
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).onGloballyPositioned {
                rootPos = it.positionInRoot()
                rootHeight = it.size.height.toFloat()
            },
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (past.isNotEmpty()) {
                    DaySection(
                        Drop.PAST, tr("지난 날짜", "Past dates"), null, false, p.danger, onBounds,
                        action = Pair(tr("오늘로", "Move to today")) {
                            moveAll(past.map { Move(it, today, it.time) }, tr("${past.size}개 → 오늘", "${past.size} → today"))
                        },
                    ) {
                        past.forEach { key(it.id) { DraggableTask(it) } }
                    }
                }

                for (n in 0 until DAYS_SHOWN) {
                    val d = today.plusDays(n.toLong())
                    val k = Drop.day(d)
                    val prefix = when (n) {
                        0 -> tr("오늘 · ", "Today · ")
                        1 -> tr("내일 · ", "Tomorrow · ")
                        else -> ""
                    }
                    val dayTasks = tasks.filter { it.date == d }.sortedWith(byTime)
                    DaySection(
                        k, prefix + Fmt.day(d), s.events.filter { it.occursOn(d) }, hovered == k, p.text, onBounds,
                        onTitleLongPress = if (dayTasks.any { it.isVideo }) ({ videosOf = d }) else null,
                    ) {
                        dayTasks.forEach { key(it.id) { DraggableTask(it) } }
                    }
                }

                DaySection(Drop.NO_DATE, tr("날짜 없음", "No date"), null, hovered == Drop.NO_DATE, p.sub, onBounds) {
                    tasks.filter { it.date == null }.sortedWith(byPriority).forEach { key(it.id) { DraggableTask(it) } }
                }
                Spacer(Modifier.height(80.dp))
            }

            // The card that follows the finger.
            dragging?.let { t ->
                Box(
                    Modifier
                        .offset { IntOffset((pointer.x - rootPos.x - grab.x).roundToInt(), (pointer.y - rootPos.y - grab.y).roundToInt()) }
                        .width(with(density) { dragWidth.toDp() })
                        .shadow(8.dp, RoundedCornerShape(10.dp))
                        .background(p.surface),
                ) { TaskCard(t, onCheck = null) }
            }
        }
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
        DatePickerFor(t.date, onPick = { d -> move(t, d) }, onDismiss = { picking = null })
    }
    plan?.let { items ->
        PlanDialog(
            items,
            onApply = {
                plan = null
                moveAll(items.map { Move(it.task, it.date, it.time) }, tr("${items.size}개 계획됨", "${items.size} planned"))
            },
            onDismiss = { plan = null },
        )
    }
    videosOf?.let { d ->
        val videos = tasks.filter { it.date == d && it.isVideo }
        ConfirmDialog(
            tr("동영상 ${videos.size}개를 완료할까요?", "Mark ${videos.size} videos done?"),
            onConfirm = { videosOf = null; completeAll(videos) },
            onDismiss = { videosOf = null },
        )
    }
}

/** Preview of the plan: task → day and time. */
@Composable
private fun PlanDialog(items: List<PlanItem>, onApply: () -> Unit, onDismiss: () -> Unit) {
    val p = pal
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(16.dp),
        title = { Text(tr("계획", "Plan"), color = p.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            Fmt.day(item.date) + " " + Fmt.time(item.time),
                            fontSize = 13.sp, color = p.accent, modifier = Modifier.width(104.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.task.title, fontSize = 14.sp, color = p.text, maxLines = 2)
                            CourseLabel(item.task.course)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text(tr("적용", "Apply"), color = p.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("취소", "Cancel"), color = p.sub) } },
    )
}

@Composable
private fun ConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = pal
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        shape = RoundedCornerShape(16.dp),
        text = { Text(message, color = p.text, fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("완료", "Done"), color = p.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("취소", "Cancel"), color = p.sub) } },
    )
}
