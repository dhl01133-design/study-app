package kr.dhl.studyapp.brief

import kr.dhl.studyapp.data.Prefs
import kr.dhl.studyapp.data.Snapshot
import kr.dhl.studyapp.data.TaskItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A suggested work slot for a task. */
data class PlanItem(val task: TaskItem, val date: LocalDate, val time: LocalTime)

/**
 * Puts tasks without a date, or whose planned day has passed, into free time (the same free time the time bar
 * shows), earliest deadline first, one hour each, never after the task's deadline.
 * Timed tasks already planned in those days keep their hour.
 */
object Planner {
    private const val TASK_HOURS = 1.0
    private const val DAYS = 7
    private const val EPS = 1e-6

    fun plan(s: Snapshot, p: Prefs, now: LocalDateTime): List<PlanItem> {
        val today = now.toLocalDate()
        val tasks = Brief.tasks(s, p)
        val candidates = tasks
            .filter { !it.recurring && !it.id.startsWith("local:") && (it.date == null || it.date!!.isBefore(today)) }
            .sortedWith(compareBy<TaskItem>({ it.deadline ?: LocalDateTime.MAX }, { -it.priority }, { it.order }))
        if (candidates.isEmpty()) return emptyList()

        // Free spans per day, minus the past part of today and hours already taken by planned timed tasks.
        val nowHour = ceil((now.hour + now.minute / 60.0) * 4) / 4  // next quarter hour
        val slots = mutableListOf<Triple<LocalDate, Double, Double>>()
        for (n in 0 until DAYS) {
            val d = today.plusDays(n.toLong())
            val taken = tasks.filter { it.date == d && it.time != null }
                .map { val h = it.time!!.hour + it.time!!.minute / 60.0; h to h + TASK_HOURS }
            var free = DaySlice.of(s, d).free
            if (n == 0) free = free.mapNotNull { (a, b) -> if (b <= nowHour) null else max(a, nowHour) to b }
            for ((x, z) in taken) {
                free = free.flatMap { (a, b) -> if (z <= a || x >= b) listOf(a to b) else listOf(a to min(b, x), max(a, z) to b) }
                    .filter { it.second - it.first > EPS }
            }
            free.forEach { (a, b) -> slots += Triple(d, a, b) }
        }

        val out = mutableListOf<PlanItem>()
        for (t in candidates) {
            val lastDay = t.deadline?.toLocalDate()
            val i = slots.indexOfFirst { (d, a, b) -> b - a >= TASK_HOURS - EPS && (lastDay == null || !d.isAfter(lastDay)) }
            if (i < 0) continue
            val (d, a, b) = slots[i]
            val minutes = (a * 60).roundToInt()
            out += PlanItem(t, d, LocalTime.of(minutes / 60 % 24, minutes % 60))
            slots[i] = Triple(d, a + TASK_HOURS, b)
        }
        return out
    }
}
