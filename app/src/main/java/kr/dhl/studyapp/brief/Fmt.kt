package kr.dhl.studyapp.brief

import kr.dhl.studyapp.i18n.L
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.roundToInt

/** Date and time formatting shared by the screens and notifications. */
object Fmt {
    private const val DOW_KO = "월화수목금토일"
    private val DOW_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** "11/17 (화)" or "Tue 11/17". */
    fun day(d: LocalDate): String {
        val i = d.dayOfWeek.value - 1
        return if (L.en) "${DOW_EN[i]} ${md(d)}" else "${md(d)} (${DOW_KO[i]})"
    }

    /** "11/17". */
    fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"

    /** "09:05". */
    fun time(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    /** Hour of day as a fraction (9.5) → "09:30". */
    fun clock(hours: Double): String {
        val m = (hours * 60).roundToInt()
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Countdown: "in 25 min", "in 3 h 10 min", "in 2 d 5 h" (Korean: "25분 후" …). */
    fun until(from: LocalDateTime, to: LocalDateTime): String {
        val m = max(0L, Duration.between(from, to).toMinutes())
        val text = when {
            m < 60 -> if (L.en) "$m min" else "${m}분"
            m < 24 * 60 -> if (L.en) "${m / 60} h" + (if (m % 60 > 0) " ${m % 60} min" else "") else "${m / 60}시간" + (if (m % 60 > 0) " ${m % 60}분" else "")
            else -> if (L.en) "${m / 1440} d" + (if (m % 1440 >= 60) " ${m % 1440 / 60} h" else "") else "${m / 1440}일" + (if (m % 1440 >= 60) " ${m % 1440 / 60}시간" else "")
        }
        return if (L.en) "in $text" else "$text 후"
    }

    /** Length in hours (1.75) → "1:45". */
    fun duration(hours: Double): String {
        val m = (hours * 60).roundToInt()
        return "%d:%02d".format(m / 60, m % 60)
    }
}
