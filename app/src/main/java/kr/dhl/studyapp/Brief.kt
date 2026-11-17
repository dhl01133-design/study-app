package kr.dhl.studyapp

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min

data class Deadline(val key: String, val course: String, val title: String, val at: LocalDateTime)

/** One line in a list: time label, course chip, title. */
data class Line(val time: String, val course: String, val title: String, val taskId: String? = null)

data class SItem(val a: Double, val b: Double, val title: String, val kind: String, val cb: Boolean, val ca: Boolean)

data class SliceResult(val items: List<SItem>, val travel: List<Pair<Double, Double>>, val free: List<Pair<Double, Double>>) {
    val freeHours: Double get() = free.sumOf { it.second - it.first }
}

object Brief {
    private const val BUF = 1.0
    private const val DAY_START = 9.0
    private const val DAY_END = 24.0
    private const val MIN_BLOCK = 0.5
    private const val DOW = "월화수목금토일"

    fun dayLabel(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth} (${DOW[d.dayOfWeek.value - 1]})"
    fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
    fun dur(h: Double): String {
        val m = Math.round(h * 60).toInt()
        return "%d:%02d".format(m / 60, m % 60)
    }

    /** Tasks shown in the app (bookkeeping 🤖 tasks hidden). */
    fun tasks(s: Snapshot, p: Prefs) = s.tasks.filter { !it.isBookkeeping && (!p.onlyIcampus || "icampus" in it.labels) }

    /** Course deadlines that get alarms: open `icampus` tasks + calendar deadline events (same rule as the Kakao alerts). */
    fun deadlines(s: Snapshot, from: LocalDateTime, to: LocalDateTime): List<Deadline> {
        val list = mutableListOf<Deadline>()
        s.tasks.filter { !it.isBookkeeping && "icampus" in it.labels }.forEach { t ->
            t.realDeadline?.let { list += Deadline("t:${t.id}", t.course, t.title, it) }
        }
        s.events.filter { it.isDeadline }.forEach { e ->
            val at = if (e.allDay) e.startAt.toLocalDate().atTime(23, 59) else e.startAt
            list += Deadline("e:${e.uid}:${e.start}", e.course, e.title, at)
        }
        val hw = Regex("hw\\s*0*(\\d+)", RegexOption.IGNORE_CASE)
        return list.filter { it.at.isAfter(from) && !it.at.isAfter(to) }
            .distinctBy { d -> "${d.course}|${d.at.toLocalDate()}|${hw.find(d.title)?.groupValues?.get(1) ?: d.title}" }
            .sortedBy { it.at }
    }

    /** Tasks whose real deadline has passed (planned work days don't count). */
    fun overdue(s: Snapshot, p: Prefs, today: LocalDate): List<Line> {
        return tasks(s, p).filter { t -> t.realDeadline?.toLocalDate()?.isBefore(today) == true }
            .sortedBy { it.realDeadline }
            .map { val d = it.realDeadline!!.toLocalDate(); Line("${d.monthValue}/${d.dayOfMonth}", it.course, it.title, it.id) }
    }

    private fun eventLine(e: CalEvent, d: LocalDate): Line {
        val time = when {
            e.allDay -> "종일"
            e.isDeadline -> "~" + hm(e.startAt.toLocalTime())
            e.startAt.toLocalDate() == d -> hm(e.startAt.toLocalTime()) + "–" + hm(e.endAt.toLocalTime())
            else -> "~" + hm(e.endAt.toLocalTime())
        }
        return Line(time, e.course, e.title)
    }

    private fun dueNote(t: TaskItem): String =
        if (t.isPlanned) t.realDeadline?.let { " (마감 ${it.monthValue}/${it.dayOfMonth})" } ?: "" else ""

    private fun taskLine(t: TaskItem) = Line(t.time?.let { "~" + hm(it) } ?: "오늘", t.course, t.title + dueNote(t), t.id)

    fun todayLines(s: Snapshot, p: Prefs, today: LocalDate): List<Line> {
        val ev = s.events.filter { it.occursOn(today) }.sortedBy { it.start }.map { eventLine(it, today) }
        val ts = tasks(s, p).filter { it.date == today }.sortedBy { it.deadline }.map { taskLine(it) }
        return ev + ts
    }

    /** Next 7 days (after today): tasks, deadline events and other non-class events. */
    fun week(s: Snapshot, p: Prefs, today: LocalDate): List<Pair<String, List<Line>>> = (1..7).mapNotNull { i ->
        val d = today.plusDays(i.toLong())
        val ev = s.events.filter { it.occursOn(d) && !it.isClass }.map { eventLine(it, d) }
        val ts = tasks(s, p).filter { it.date == d }.sortedBy { it.deadline }
            .map { Line(it.time?.let { t -> "~" + hm(t) } ?: "", it.course, it.title + dueNote(it), it.id) }
        val lines = ev + ts
        if (lines.isEmpty()) null else dayLabel(d) to lines
    }

    // ---- day slice: same rules as the "Week free time" page / Kakao slice.py ----

    private fun hours(t: LocalTime) = t.hour + t.minute / 60.0

    private fun merge(r: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for ((a, b) in r.filter { it.second > it.first }.sortedWith(compareBy({ it.first }, { it.second }))) {
            if (out.isNotEmpty() && a <= out.last().second + 1e-6) {
                out[out.lastIndex] = out.last().first to max(out.last().second, b)
            } else out.add(a to b)
        }
        return out
    }

    private fun sub(r: List<Pair<Double, Double>>, cuts: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        var cur = r
        for ((x, z) in cuts) {
            cur = cur.flatMap { (a, b) ->
                if (z <= a || x >= b) listOf(a to b) else listOf(a to min(b, x), max(a, z) to b)
            }.filter { it.second - it.first > 1e-6 }
        }
        return cur
    }

    fun slice(s: Snapshot, d: LocalDate): SliceResult {
        val dayStart = d.atStartOfDay()
        val dayEnd = d.plusDays(1).atStartOfDay()
        val items = s.events.filter { !it.allDay && !it.isDeadline && it.occursOn(d) && it.startAt != it.endAt }.map { e ->
            val cb = e.startAt.isBefore(dayStart)
            val ca = e.endAt.isAfter(dayEnd)
            val a = if (cb) 0.0 else hours(e.startAt.toLocalTime())
            val b = if (ca || e.endAt == dayEnd) 24.0 else hours(e.endAt.toLocalTime())
            val kind = if (e.isClass) "cls" else if ("알바" in e.summary) "job" else "ev"
            SItem(a, b, e.title, kind, cb, ca)
        }
        val tr = mutableListOf<Pair<Double, Double>>()
        val cls = items.filter { it.kind == "cls" }.sortedBy { it.a }
        if (cls.isNotEmpty()) {
            val f = cls.first()
            val l = cls.maxBy { it.b }
            if (!f.cb) tr += (f.a - BUF) to f.a
            if (!l.ca) tr += l.b to (l.b + BUF)
        }
        for (i in items) {
            if (i.kind == "cls") continue
            if (!i.cb) tr += (i.a - BUF) to i.a
            if (!i.ca) tr += i.b to (i.b + BUF)
        }
        val busy = merge(items.map { it.a to it.b })
        val travel = merge(sub(merge(tr), busy))
        val free = sub(listOf(DAY_START to DAY_END), merge(busy + travel)).filter { it.second - it.first >= MIN_BLOCK - 1e-6 }
        return SliceResult(items, travel, free)
    }

    // ---- notification text ----

    fun headline(s: Snapshot, p: Prefs, today: LocalDate): String {
        val od = overdue(s, p, today).size
        val n = todayLines(s, p, today).size
        val free = slice(s, today).freeHours
        return "오늘 ${n}개 · 빈 시간 ${dur(free)}" + if (od > 0) " · ⚠ 기한 지남 $od" else ""
    }

    fun summaryLines(s: Snapshot, p: Prefs, today: LocalDate): List<String> {
        val out = mutableListOf<String>()
        val od = overdue(s, p, today)
        if (od.isNotEmpty()) out += "⚠ 기한 지남: " + od.take(4).joinToString(", ") { it.title }
        val td = todayLines(s, p, today)
        if (td.isEmpty()) out += "📌 오늘 일정 없음"
        td.take(6).forEach { out += "📌 ${it.time} ${if (it.course.isNotEmpty()) "[${it.course}] " else ""}${it.title}" }
        val sl = slice(s, today)
        out += if (sl.free.isEmpty()) "🟢 빈 시간 없음"
        else "🟢 빈 시간 ${dur(sl.freeHours)}: " + sl.free.joinToString(", ") { "${fmtH(it.first)}–${fmtH(it.second)}" }
        val mk = s.markets.filter { it.pct != null }
        if (mk.isNotEmpty()) out += "📈 " + mk.joinToString(" · ") { "${it.name} %+.2f%%".format(it.pct) }
        val now = LocalDateTime.now()
        deadlines(s, now, now.plusDays(7)).take(4).forEach {
            out += "🗓 ${dayLabel(it.at.toLocalDate())} ${hm(it.at.toLocalTime())} ${if (it.course.isNotEmpty()) "[${it.course}] " else ""}${it.title}"
        }
        return out
    }

    fun fmtH(x: Double): String {
        val m = Math.round(x * 60).toInt()
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
