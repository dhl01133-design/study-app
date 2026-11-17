package kr.dhl.studyapp

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
    val deadline: LocalDateTime? get() = date?.atTime(time ?: LocalTime.of(23, 59))
    val isBookkeeping: Boolean get() = content.startsWith("🤖")
}

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
        val CLASS_RE = Regex("수업\\s*$|출석")
        val DEADLINE_RE = Regex("마감|due|제출|deadline", RegexOption.IGNORE_CASE)
    }
}

@Serializable
data class MarketItem(val name: String, val close: Double? = null, val pct: Double? = null)

@Serializable
data class Snapshot(
    val fetchedAt: Long = 0,
    val tasks: List<TaskItem> = emptyList(),
    val events: List<CalEvent> = emptyList(),
    val markets: List<MarketItem> = emptyList(),
    val marketSession: String? = null,
    val errors: List<String> = emptyList(),
)

object Courses {
    private val PREFIX = Regex("^\\[([^\\]]+)]\\s*")

    fun prefix(s: String): String? = PREFIX.find(s)?.groupValues?.get(1)
    fun strip(s: String): String = s.replaceFirst(PREFIX, "").ifBlank { s }
    fun fromProject(p: String): String = if (p.isBlank() || p == "Inbox") "" else p

    private val COLORS = mapOf(
        "경제학입문" to 0xFF2F6FDF, "창의적글쓰기" to 0xFFC96A14,
        "일반화학2" to 0xFF23874D, "General Chemistry 2" to 0xFF23874D,
        "창의적융합디자인" to 0xFF7A45C4, "선형대수학" to 0xFFB4420F,
        "미적분2" to 0xFF0B6C84, "Calculus 2" to 0xFF0B6C84,
        "영어쓰기" to 0xFFB0175A, "영어작문" to 0xFFB0175A, "English Writing" to 0xFFB0175A,
    )

    fun color(c: String): Long = COLORS[c] ?: 0xFF5A6270
}
