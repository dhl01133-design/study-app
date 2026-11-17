package kr.dhl.studyapp

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Minimal iCalendar reader for Google Calendar's secret iCal address, with DAILY/WEEKLY/MONTHLY/YEARLY expansion. */
object Ics {
    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private class VEvent(val props: List<Prop>) {
        fun first(n: String): Prop? = props.firstOrNull { it.name == n }
        fun all(n: String): List<Prop> = props.filter { it.name == n }
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    fun parse(text: String, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
        val lines = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")
        val events = mutableListOf<VEvent>()
        var cur: MutableList<Prop>? = null
        var depth = 0
        for (line in lines) {
            if (line == "BEGIN:VEVENT") {
                cur = mutableListOf(); depth = 0; continue
            }
            if (line == "END:VEVENT") {
                val c = cur
                if (c != null) events.add(VEvent(c))
                cur = null; continue
            }
            val c = cur ?: continue
            when {
                line.startsWith("BEGIN:") -> depth++
                line.startsWith("END:") -> depth--
                depth == 0 -> {
                    val p = parseProp(line)
                    if (p != null) c.add(p)
                }
            }
        }

        val overridden = mutableSetOf<String>()
        for (e in events) {
            val rid = e.first("RECURRENCE-ID") ?: continue
            val uid = e.first("UID")?.value ?: continue
            runCatching { overridden.add(uid + "|" + dt(rid, zone).first) }
        }

        val out = mutableListOf<CalEvent>()
        for (e in events) {
            runCatching {
                val s = e.first("DTSTART") ?: return@runCatching
                if (e.first("STATUS")?.value?.trim() == "CANCELLED") return@runCatching
                val (start, allDay) = dt(s, zone)
                val end = e.first("DTEND")?.let { dt(it, zone).first }
                    ?: e.first("DURATION")?.let { d -> runCatching { start.plus(Duration.parse(d.value.trim())) }.getOrNull() }
                    ?: if (allDay) start.plusDays(1) else start
                val dur = Duration.between(start, end)
                val summary = unescape(e.first("SUMMARY")?.value ?: "")
                val uid = e.first("UID")?.value ?: summary
                val isOverride = e.first("RECURRENCE-ID") != null
                val rrule = if (isOverride) null else e.first("RRULE")?.value
                val exdates = e.all("EXDATE").flatMap { p ->
                    p.value.split(',').mapNotNull { v -> runCatching { dt(Prop("EXDATE", p.params, v), zone).first }.getOrNull() }
                }.toSet()
                val starts = if (rrule == null) listOf(start) else expand(start, rrule, to, exdates, zone)
                for (st in starts) {
                    if (rrule != null && overridden.contains("$uid|$st")) continue
                    val en = st.plus(dur)
                    if (st.toLocalDate().isAfter(to)) continue
                    if (en.isBefore(from.atStartOfDay())) continue
                    out.add(CalEvent(uid, summary, st.toString(), en.toString(), allDay))
                }
            }
        }
        return out.sortedBy { it.start }
    }

    private fun parseProp(line: String): Prop? {
        var inQuote = false
        var idx = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQuote = !inQuote
            else if (ch == ':' && !inQuote) { idx = i; break }
        }
        if (idx < 0) return null
        val parts = line.substring(0, idx).split(';')
        val params = parts.drop(1).mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(parts[0].uppercase(), params, line.substring(idx + 1))
    }

    /** Returns local date-time in [zone] and whether it was an all-day DATE value. */
    private fun dt(p: Prop, zone: ZoneId): Pair<LocalDateTime, Boolean> {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || v.length == 8) {
            return LocalDate.parse(v.take(8), DATE).atStartOfDay() to true
        }
        val local = LocalDateTime.parse(v.take(15), DATETIME)
        val tzid = p.params["TZID"]
        val res = when {
            v.endsWith("Z") -> local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()
            tzid != null -> {
                val tz = runCatching { ZoneId.of(tzid) }.getOrDefault(zone)
                local.atZone(tz).withZoneSameInstant(zone).toLocalDateTime()
            }
            else -> local
        }
        return res to false
    }

    private fun expand(
        start: LocalDateTime, rule: String, to: LocalDate, ex: Set<LocalDateTime>, zone: ZoneId,
    ): List<LocalDateTime> {
        val r = rule.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i).uppercase() to it.substring(i + 1)
        }.toMap()
        val freq = r["FREQ"] ?: return listOf(start)
        val interval = (r["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1).toLong()
        val count = r["COUNT"]?.toIntOrNull()
        val until: LocalDateTime? = r["UNTIL"]?.let { u ->
            runCatching {
                val (t, isDate) = dt(Prop("UNTIL", emptyMap(), u), zone)
                if (isDate) t.plusDays(1).minusSeconds(1) else t
            }.getOrNull()
        }
        val limit = to.plusDays(1).atStartOfDay()
        val out = mutableListOf<LocalDateTime>()
        var n = 0

        fun accept(t: LocalDateTime): Boolean {
            if (until != null && t.isAfter(until)) return false
            if (count != null && n >= count) return false
            if (!t.isBefore(limit)) return false
            n++
            if (t !in ex) out.add(t)
            return true
        }

        if (freq == "WEEKLY") {
            val days = r["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.trim().takeLast(2)] }?.sorted()
                ?.ifEmpty { null } ?: listOf(start.dayOfWeek)
            var weekStart = start.toLocalDate().with(DayOfWeek.MONDAY)
            var guard = 0
            outer@ while (guard++ < 2000) {
                for (d in days) {
                    val t = weekStart.with(d).atTime(start.toLocalTime())
                    if (t.isBefore(start)) continue
                    if (!accept(t)) break@outer
                }
                weekStart = weekStart.plusWeeks(interval)
            }
        } else {
            var i = 0L
            while (i < 3000) {
                val t = when (freq) {
                    "DAILY" -> start.plusDays(i * interval)
                    "MONTHLY" -> start.plusMonths(i * interval)
                    "YEARLY" -> start.plusYears(i * interval)
                    else -> if (i == 0L) start else null
                } ?: break
                if (!accept(t)) break
                i++
            }
        }
        return out
    }

    private fun unescape(s: String): String =
        s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()
}
