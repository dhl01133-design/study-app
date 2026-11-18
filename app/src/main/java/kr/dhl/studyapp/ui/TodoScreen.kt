package kr.dhl.studyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.dhl.studyapp.brief.Brief
import kr.dhl.studyapp.brief.Fmt
import kr.dhl.studyapp.data.Repo
import kr.dhl.studyapp.data.Snapshot
import kr.dhl.studyapp.data.Store
import kr.dhl.studyapp.data.TaskItem
import kr.dhl.studyapp.i18n.tr
import java.time.LocalDate
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

/**
 * Two weeks of tasks. Long-press a card and drag it onto another day (or "no date") to change its
 * Todoist date; tap it for quick moves; tap the circle to complete. Every change can be undone.
 */
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
    var detail by remember { mutableStateOf<TaskItem?>(null) }
    var picking by remember { mutableStateOf<TaskItem?>(null) }

    fun hoverKey(): String? = targets.entries.firstOrNull { it.value.contains(pointer) }?.key

    fun undoable(message: String, undo: suspend () -> Unit) = scope.launch {
        val res = snackbar.showSnackbar(message, actionLabel = tr("되돌리기", "Undo"), duration = SnackbarDuration.Short)
        if (res == SnackbarResult.ActionPerformed) undo()
    }

    fun move(t: TaskItem, date: LocalDate?) = scope.launch {
        val old = t.date
        repo.moveTask(t, date)
            .onFailure { snackbar.showSnackbar(tr("옮기지 못했어요: ", "Couldn't move: ") + it.message) }
            .onSuccess {
                val where = date?.let(Fmt::day) ?: tr("날짜 없음", "No date")
                undoable("‘${t.title}’ → $where") { Store.task(t.id)?.let { repo.moveTask(it, old) } }
            }
    }

    fun complete(t: TaskItem) = scope.launch {
        repo.complete(t)
            .onFailure { snackbar.showSnackbar(tr("완료하지 못했어요: ", "Couldn't complete: ") + it.message) }
            .onSuccess { undoable(tr("‘${t.title}’ 완료", "‘${t.title}’ done")) { repo.reopen(t) } }
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

    @Composable
    fun DraggableTask(t: TaskItem) {
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { cardPos[t.id] = it.positionInRoot() }
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
                        onDragEnd = { drop() },
                        onDragCancel = { dragging = null },
                    )
                }
                .alpha(if (dragging?.id == t.id) 0.3f else 1f)
                .clickable { detail = Store.task(t.id) ?: t },
        ) {
            TaskCard(t, onCheck = { complete(t) })
        }
    }

    val hovered = if (dragging != null) hoverKey() else null
    val onBounds: (String, Rect) -> Unit = { k, r -> targets[k] = r }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            rootPos = it.positionInRoot()
            rootHeight = it.size.height.toFloat()
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(tr("할 일", "To-do"), syncing, onRefresh)

            val past = tasks.filter { Drop.of(it) == Drop.PAST }.sortedBy { it.planned }
            if (past.isNotEmpty()) {
                DaySection(Drop.PAST, tr("지난 날짜", "Past dates"), null, false, p.danger, onBounds) {
                    past.forEach { key(it.id) { DraggableTask(it) } }
                }
            } else targets.remove(Drop.PAST)

            for (n in 0 until DAYS_SHOWN) {
                val d = today.plusDays(n.toLong())
                val k = Drop.day(d)
                val prefix = when (n) {
                    0 -> tr("오늘 · ", "Today · ")
                    1 -> tr("내일 · ", "Tomorrow · ")
                    else -> ""
                }
                DaySection(k, prefix + Fmt.day(d), s.events.filter { it.occursOn(d) }, hovered == k, p.text, onBounds) {
                    tasks.filter { it.date == d }.sortedWith(byTime).forEach { key(it.id) { DraggableTask(it) } }
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
}
