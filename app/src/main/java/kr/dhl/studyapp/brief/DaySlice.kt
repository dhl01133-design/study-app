package kr.dhl.studyapp.brief

import kr.dhl.studyapp.data.Snapshot
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min

/** A span of hours within a day, e.g. 9.0 to 10.25. */
typealias Span = Pair<Double, Double>

enum class BlockKind { CLASS, OTHER }

/** One timed event on the day, in hours since midnight. */
data class Block(
    val start: Double,
    val end: Double,
    val title: String,
    val kind: BlockKind,
    val fromYesterday: Boolean,
    val intoTomorrow: Boolean,
)

/**
 * A day's events, travel time and free time, with the same rules as the "Week free time" page:
 * classes get 1 h of travel only before the first class and after the last; every other timed event gets
 * 1 h on both sides; free time counts 09:00–24:00 in blocks of 30 min or more.
 */
data class DaySlice(val blocks: List<Block>, val travel: List<Span>, val free: List<Span>) {
    val freeHours: Double get() = free.sumOf { it.second - it.first }

    companion object {
        private const val TRAVEL = 1.0
        private const val DAY_START = 9.0
        private const val DAY_END = 24.0
        private const val MIN_FREE = 0.5
        private const val EPS = 1e-6

        fun of(s: Snapshot, d: LocalDate): DaySlice {
            val dayStart = d.atStartOfDay()
            val dayEnd = d.plusDays(1).atStartOfDay()
            val blocks = s.events
                .filter { !it.allDay && !it.isDeadline && it.startAt != it.endAt && it.occursOn(d) }
                .map { e ->
                    val fromYesterday = e.startAt.isBefore(dayStart)
                    val intoTomorrow = e.endAt.isAfter(dayEnd)
                    Block(
                        start = if (fromYesterday) 0.0 else hours(e.startAt.toLocalTime()),
                        end = if (intoTomorrow || e.endAt == dayEnd) 24.0 else hours(e.endAt.toLocalTime()),
                        title = e.title,
                        kind = if (e.isClass) BlockKind.CLASS else BlockKind.OTHER,
                        fromYesterday = fromYesterday,
                        intoTomorrow = intoTomorrow,
                    )
                }

            val travel = mutableListOf<Span>()
            val classes = blocks.filter { it.kind == BlockKind.CLASS }
            if (classes.isNotEmpty()) {
                val first = classes.minBy { it.start }
                val last = classes.maxBy { it.end }
                if (!first.fromYesterday) travel += (first.start - TRAVEL) to first.start
                if (!last.intoTomorrow) travel += last.end to (last.end + TRAVEL)
            }
            for (b in blocks.filter { it.kind != BlockKind.CLASS }) {
                if (!b.fromYesterday) travel += (b.start - TRAVEL) to b.start
                if (!b.intoTomorrow) travel += b.end to (b.end + TRAVEL)
            }

            val busy = merge(blocks.map { it.start to it.end })
            val travelOnly = merge(subtract(merge(travel), busy))
            val free = subtract(listOf(DAY_START to DAY_END), merge(busy + travelOnly))
                .filter { it.second - it.first >= MIN_FREE - EPS }
            return DaySlice(blocks, travelOnly, free)
        }

        private fun hours(t: LocalTime) = t.hour + t.minute / 60.0

        private fun merge(spans: List<Span>): List<Span> {
            val out = mutableListOf<Span>()
            for ((a, b) in spans.filter { it.second > it.first }.sortedWith(compareBy({ it.first }, { it.second }))) {
                val last = out.lastOrNull()
                if (last != null && a <= last.second + EPS) out[out.lastIndex] = last.first to max(last.second, b)
                else out += a to b
            }
            return out
        }

        private fun subtract(spans: List<Span>, cuts: List<Span>): List<Span> = cuts.fold(spans) { cur, (x, z) ->
            cur.flatMap { (a, b) -> if (z <= a || x >= b) listOf(a to b) else listOf(a to min(b, x), max(a, z) to b) }
                .filter { it.second - it.first > EPS }
        }
    }
}
