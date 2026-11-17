package kr.dhl.studyapp.brief

import kr.dhl.studyapp.data.CalEvent
import kr.dhl.studyapp.data.Prefs
import kr.dhl.studyapp.data.Snapshot
import kr.dhl.studyapp.data.TaskItem
import kr.dhl.studyapp.i18n.marketName
import kr.dhl.studyapp.i18n.tr
import java.time.LocalDate
import java.time.LocalDateTime

/** A course deadline that gets alarms. */
data class Deadline(val key: String, val course: String, val title: String, val at: LocalDateTime)

/** One row in a list: time label, course and title. */
data class Line(val time: String, val course: String, val title: String)

/** What the briefing screen and notification show. */
object Brief {
    private val HW = Regex("hw\\s*0*(\\d+)", RegexOption.IGNORE_CASE)

    /** Tasks shown in the app (🤖 bookkeeping tasks hidden; optionally only `icampus`). */
    fun tasks(s: Snapshot, p: Prefs) = s.tasks.filter { !it.isBookkeeping && (!p.onlyIcampus || it.isIcampus) }

    /**
     * Deadlines in (from, to]: open `icampus` tasks (real deadline) and calendar deadline events.
     * The same item in both (same course, day and hwNN or title) counts once.
     */
    fun deadlines(s: Snapshot, from: LocalDateTime, to: LocalDateTime): List<Deadline> {
        val fromTasks = s.tasks.filter { !it.isBookkeeping && it.isIcampus }
            .mapNotNull { t -> t.deadline?.let { Deadline("t:${t.id}", t.course, t.title, it) } }
        val fromEvents = s.events.filter { it.isDeadline }
            .map { e -> Deadline("e:${e.uid}:${e.start}", e.course, e.title, e.dueAt) }
        return (fromTasks + fromEvents)
            .filter { it.at.isAfter(from) && !it.at.isAfter(to) }
            .distinctBy { "${it.course}|${it.at.toLocalDate()}|${HW.find(it.title)?.groupValues?.get(1) ?: it.title}" }
            .sortedBy { it.at }
    }

    /** Tasks whose real deadline has passed (a planned day in the past doesn't count). */
    fun overdue(s: Snapshot, p: Prefs, today: LocalDate): List<Line> =
        tasks(s, p).mapNotNull { t -> t.deadline?.let { t to it } }
            .filter { (_, due) -> due.toLocalDate().isBefore(today) }
            .sortedBy { (_, due) -> due }
            .map { (t, due) -> Line(Fmt.md(due.toLocalDate()), t.course, t.title) }

    fun todayLines(s: Snapshot, p: Prefs, today: LocalDate): List<Line> {
        val events = s.events.filter { it.occursOn(today) }.sortedBy { it.start }.map { eventLine(it, today) }
        val tasks = tasks(s, p).filter { it.date == today }.sortedBy { it.planned }
            .map { taskLine(it, tr("오늘", "Today")) }
        return events + tasks
    }

    /** The 7 days after today: tasks, deadline events and other non-class events. */
    fun week(s: Snapshot, p: Prefs, today: LocalDate): List<Pair<String, List<Line>>> = (1L..7L).mapNotNull { n ->
        val d = today.plusDays(n)
        val events = s.events.filter { it.occursOn(d) && !it.isClass }.map { eventLine(it, d) }
        val tasks = tasks(s, p).filter { it.date == d }.sortedBy { it.planned }.map { taskLine(it, "") }
        val lines = events + tasks
        if (lines.isEmpty()) null else Fmt.day(d) to lines
    }

    private fun eventLine(e: CalEvent, d: LocalDate): Line {
        val time = when {
            e.allDay -> tr("종일", "All day")
            e.isDeadline -> "~" + Fmt.time(e.startAt.toLocalTime())
            e.startAt.toLocalDate() == d -> Fmt.time(e.startAt.toLocalTime()) + "–" + Fmt.time(e.endAt.toLocalTime())
            else -> "~" + Fmt.time(e.endAt.toLocalTime())
        }
        return Line(time, e.course, e.title)
    }

    private fun taskLine(t: TaskItem, allDayLabel: String): Line {
        val due = t.deadline?.takeIf { t.isPlanned }?.let { tr(" (마감 ", " (due ") + Fmt.md(it.toLocalDate()) + ")" } ?: ""
        return Line(t.time?.let { "~" + Fmt.time(it) } ?: allDayLabel, t.course, t.title + due)
    }

    // ---- notification text ----

    fun headline(s: Snapshot, p: Prefs, today: LocalDate): String {
        val n = todayLines(s, p, today).size
        val free = Fmt.duration(DaySlice.of(s, today).freeHours)
        val overdue = overdue(s, p, today).size
        return tr("오늘 ${n}개 · 빈 시간 $free", "$n today · $free free") +
            if (overdue > 0) tr(" · ⚠ 기한 지남 $overdue", " · ⚠ $overdue overdue") else ""
    }

    fun summary(s: Snapshot, p: Prefs, today: LocalDate): List<String> {
        val out = mutableListOf<String>()
        val overdue = overdue(s, p, today)
        if (overdue.isNotEmpty()) out += tr("⚠ 기한 지남: ", "⚠ Overdue: ") + overdue.take(4).joinToString(", ") { it.title }

        val lines = todayLines(s, p, today)
        if (lines.isEmpty()) out += tr("📌 오늘 일정 없음", "📌 Nothing today")
        lines.take(6).forEach { out += "📌 ${it.time} ${withCourse(it.course, it.title)}" }

        val slice = DaySlice.of(s, today)
        out += if (slice.free.isEmpty()) tr("🟢 빈 시간 없음", "🟢 No free time")
        else tr("🟢 빈 시간 ${Fmt.duration(slice.freeHours)}: ", "🟢 ${Fmt.duration(slice.freeHours)} free: ") +
            slice.free.joinToString(", ") { "${Fmt.clock(it.first)}–${Fmt.clock(it.second)}" }

        val markets = s.markets.filter { it.pct != null }
        if (markets.isNotEmpty()) out += "📈 " + markets.joinToString(" · ") { "${marketName(it.name)} %+.2f%%".format(it.pct) }

        val now = LocalDateTime.now()
        deadlines(s, now, now.plusDays(7)).take(4).forEach {
            out += "🗓 ${Fmt.day(it.at.toLocalDate())} ${Fmt.time(it.at.toLocalTime())} ${withCourse(it.course, it.title)}"
        }
        return out
    }

    fun withCourse(course: String, title: String) = if (course.isNotEmpty()) "[$course] $title" else title
}
