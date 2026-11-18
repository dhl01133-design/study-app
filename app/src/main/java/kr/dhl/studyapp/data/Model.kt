package kr.dhl.studyapp.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val END_OF_DAY: LocalTime = LocalTime.of(23, 59)

/** An open Todoist task. Its Todoist date is the day you plan to work on it. */
@Serializable
data class TaskItem(
    val id: String,
    val content: String,
    val description: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val labels: List<String> = emptyList(),
    val priority: Int = 1,          // Todoist API: 4 = p1 (urgent) … 1 = p4
    val dueDate: String? = null,    // yyyy-MM-dd
    val dueTime: String? = null,    // HH:mm, null = all day
    val recurring: Boolean = false,
    val order: Int = 0,
) {
    val date: LocalDate? get() = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val time: LocalTime? get() = dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val course: String get() = Courses.prefix(content) ?: Courses.fromProject(projectName)
    val title: String get() = Courses.strip(content)
    val isIcampus: Boolean get() = "icampus" in labels
    val isBookkeeping: Boolean get() = content.startsWith("🤖")

    /** Planned date/time, 23:59 when there's no time. */
    val planned: LocalDateTime? get() = date?.atTime(time ?: END_OF_DAY)

    /**
     * Real deadline. Some tasks carry a planned work day as their Todoist date and the real deadline in the
     * description ("Due 11/22 23:59" / "마감 11/22"; 23:59 when no time). Falls back to [planned].
     */
    val deadline: LocalDateTime?
        get() {
            val m = DUE_RE.find(description) ?: return planned
            val (month, day, hour, minute) = m.destructured
            val t = if (hour.isNotEmpty()) LocalTime.of(hour.toInt() % 24, minute.toInt()) else END_OF_DAY
            val base = date ?: LocalDate.now()
            val closest = (base.year - 1..base.year + 1)
                .mapNotNull { y -> runCatching { LocalDate.of(y, month.toInt(), day.toInt()) }.getOrNull() }
                .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(base, it)) }
            return closest?.atTime(t) ?: planned
        }

    /** True when the planned day differs from the real deadline. */
    val isPlanned: Boolean get() = deadline?.toLocalDate()?.let { it != date } ?: false

    companion object {
        private val DUE_RE = Regex("(?:Due|due|마감)\\s*:?\\s*(\\d{1,2})/(\\d{1,2})(?:\\s*(?:at\\s*)?(\\d{1,2}):(\\d{2}))?")
    }
}

/** A Google Calendar event (or one occurrence of a recurring one), in local time. */
@Serializable
data class CalEvent(
    val uid: String,
    val summary: String,
    val start: String,   // ISO local date-time
    val end: String,
    val allDay: Boolean = false,
) {
    val startAt: LocalDateTime get() = LocalDateTime.parse(start)
    val endAt: LocalDateTime get() = LocalDateTime.parse(end)
    val course: String get() = Courses.prefix(summary) ?: ""
    val title: String get() = Courses.strip(summary)
    val isClass: Boolean get() = CLASS_RE.containsMatchIn(summary)
    val isDeadline: Boolean get() = !isClass && DEADLINE_RE.containsMatchIn(summary)

    /** When a deadline event is due: its start, or 23:59 for an all-day event. */
    val dueAt: LocalDateTime get() = if (allDay) startAt.toLocalDate().atTime(END_OF_DAY) else startAt

    fun occursOn(d: LocalDate): Boolean {
        val s = startAt
        val e = endAt
        if (allDay) {
            val last = if (e.toLocalDate().isAfter(s.toLocalDate())) e.toLocalDate().minusDays(1) else s.toLocalDate()
            return !d.isBefore(s.toLocalDate()) && !d.isAfter(last)
        }
        if (s == e) return s.toLocalDate() == d
        return s.isBefore(d.plusDays(1).atStartOfDay()) && e.isAfter(d.atStartOfDay())
    }

    companion object {
        private val CLASS_RE = Regex("수업\\s*$|출석")
        private val DEADLINE_RE = Regex("마감|제출|\\bdue\\b|\\bdeadline\\b", RegexOption.IGNORE_CASE)
    }
}

@Serializable
data class MarketItem(val name: String, val close: Double? = null, val pct: Double? = null)

/** Everything the app shows, cached to disk between syncs. */
@Serializable
data class Snapshot(
    val fetchedAt: Long = 0,
    val tasks: List<TaskItem> = emptyList(),
    val events: List<CalEvent> = emptyList(),
    val markets: List<MarketItem> = emptyList(),
    val marketSession: String? = null,
    val errors: List<String> = emptyList(),
) {
    fun updateTask(id: String, f: (TaskItem) -> TaskItem) = copy(tasks = tasks.map { if (it.id == id) f(it) else it })
    fun removeTask(id: String) = copy(tasks = tasks.filter { it.id != id })
    fun addTask(t: TaskItem) = copy(tasks = tasks + t)
}
