package kr.dhl.studyapp.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kr.dhl.studyapp.brief.Brief
import kr.dhl.studyapp.brief.Deadline
import kr.dhl.studyapp.data.Prefs
import kr.dhl.studyapp.data.Snapshot
import kr.dhl.studyapp.i18n.tr
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Schedules the daily briefing and the deadline alarms with AlarmManager. */
object Alarms {
    const val ACTION_BRIEF = "kr.dhl.studyapp.BRIEF"
    const val ACTION_DEADLINE = "kr.dhl.studyapp.DEADLINE"
    const val EXTRA_ID = "id"
    const val EXTRA_COURSE = "course"
    const val EXTRA_TITLE = "title"
    const val EXTRA_AT = "at"
    const val EXTRA_HOURS = "hours"

    private const val BRIEF_CODE = 1
    private const val GROUP_LIST_MAX = 6

    /** Replaces every deadline alarm for the next 7 days, then schedules the next briefing. */
    fun reschedule(ctx: Context, s: Snapshot) {
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        cancelDeadlineAlarms(ctx, am, prefs.alarmCodes)

        val now = LocalDateTime.now()
        val codes = mutableSetOf<String>()
        for (d in groupByTime(Brief.deadlines(s, now, now.plusDays(7)))) {
            for (hours in prefs.alarmOffsets) {
                val ringAt = d.at.minusHours(hours.toLong())
                if (!ringAt.isAfter(now)) continue
                val code = (d.key + "|" + hours).hashCode().let { if (it == BRIEF_CODE) BRIEF_CODE + 1 else it }
                val intent = intent(ctx, ACTION_DEADLINE)
                    .putExtra(EXTRA_ID, code).putExtra(EXTRA_COURSE, d.course).putExtra(EXTRA_TITLE, d.title)
                    .putExtra(EXTRA_AT, d.at.toString()).putExtra(EXTRA_HOURS, hours)
                setExact(am, ringAt, PendingIntent.getBroadcast(ctx, code, intent, UPDATE))
                codes += code.toString()
            }
        }
        prefs.alarmCodes = codes
        scheduleBriefing(ctx)
    }

    fun scheduleBriefing(ctx: Context) {
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(Prefs(ctx).briefingTime)
        if (!next.isAfter(now.plusMinutes(2))) next = next.plusDays(1)  // avoid a double briefing if the alarm fires early
        val pi = PendingIntent.getBroadcast(ctx, BRIEF_CODE, intent(ctx, ACTION_BRIEF), UPDATE)
        setExact(ctx.getSystemService(AlarmManager::class.java), next, pi)
    }

    /** Deadlines at the same moment share one alarm: 19 tasks due 11/22 23:59 ring once, listing them. */
    private fun groupByTime(deadlines: List<Deadline>): List<Deadline> = deadlines.groupBy { it.at }.map { (at, ds) ->
        if (ds.size == 1) return@map ds[0]
        val courses = ds.map { it.course }.distinct()
        val listed = ds.take(GROUP_LIST_MAX).joinToString(", ") { if (courses.size > 1) Brief.withCourse(it.course, it.title) else it.title }
        val more = ds.size - GROUP_LIST_MAX
        Deadline(
            key = "at:$at",
            course = courses.singleOrNull() ?: "",
            title = tr("${ds.size}개 — ", "${ds.size} items — ") + listed + if (more > 0) tr(" 외 ${more}개", " +$more more") else "",
            at = at,
        )
    }

    private fun cancelDeadlineAlarms(ctx: Context, am: AlarmManager, codes: Set<String>) {
        for (code in codes.mapNotNull { it.toIntOrNull() }) {
            val pi = PendingIntent.getBroadcast(
                ctx, code, intent(ctx, ACTION_DEADLINE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            am.cancel(pi)
            pi.cancel()
        }
    }

    private const val UPDATE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun intent(ctx: Context, action: String) = Intent(ctx, AlarmReceiver::class.java).setAction(action)

    private fun setExact(am: AlarmManager, at: LocalDateTime, pi: PendingIntent) {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }
}
