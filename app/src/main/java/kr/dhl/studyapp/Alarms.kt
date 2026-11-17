package kr.dhl.studyapp

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Alarms {
    const val ACTION_BRIEF = "kr.dhl.studyapp.BRIEF"
    const val ACTION_DEADLINE = "kr.dhl.studyapp.DEADLINE"
    private const val BRIEF_CODE = 1

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun exact(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Re-creates every deadline alarm (6 h / 1 h before by default) for the next 7 days, plus the next briefing. */
    fun reschedule(ctx: Context, s: Snapshot) {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (c in p.alarmCodes) {
            val code = c.toIntOrNull() ?: continue
            val old = PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DEADLINE),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (old != null) { am.cancel(old); old.cancel() }
        }
        val now = LocalDateTime.now()
        val codes = mutableSetOf<String>()
        for (d in Brief.deadlines(s, now, now.plusDays(7))) {
            for (h in p.alarmOffsets) {
                val trig = d.at.minusHours(h.toLong())
                if (!trig.isAfter(now)) continue
                var code = (d.key + "|" + h).hashCode()
                if (code == BRIEF_CODE) code = 2
                val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DEADLINE)
                    .putExtra("id", code).putExtra("course", d.course).putExtra("title", d.title)
                    .putExtra("at", d.at.toString()).putExtra("hours", h)
                val pi = PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                exact(am, millis(trig), pi)
                codes += code.toString()
            }
        }
        p.alarmCodes = codes
        scheduleBriefing(ctx)
    }

    fun scheduleBriefing(ctx: Context) {
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(Prefs(ctx).briefingTime)
        if (!next.isAfter(now.plusMinutes(2))) next = next.plusDays(1)
        val pi = PendingIntent.getBroadcast(
            ctx, BRIEF_CODE, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_BRIEF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        exact(ctx.getSystemService(AlarmManager::class.java), millis(next), pi)
    }
}

object Notify {
    const val CH_BRIEF = "briefing"
    const val CH_DEADLINE = "deadline"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BRIEF, "아침 브리핑", NotificationManager.IMPORTANCE_DEFAULT))
        val ch = NotificationChannel(CH_DEADLINE, "마감 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "마감 6시간·1시간 전 알람"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
        }
        nm.createNotificationChannel(ch)
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, tab: Int): PendingIntent = PendingIntent.getActivity(
        ctx, 100 + tab,
        Intent(ctx, MainActivity::class.java).putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    fun briefing(ctx: Context, s: Snapshot) {
        if (!allowed(ctx)) return
        val p = Prefs(ctx)
        val today = LocalDate.now()
        val text = Brief.summaryLines(s, p, today).joinToString("\n")
        val n = NotificationCompat.Builder(ctx, CH_BRIEF)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("☀️ ${Brief.dayLabel(today)} 브리핑")
            .setContentText(Brief.headline(s, p, today))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, 0))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }

    @SuppressLint("MissingPermission")
    fun deadline(ctx: Context, id: Int, course: String, title: String, at: LocalDateTime, hours: Int) {
        if (!allowed(ctx)) return
        val head = if (hours == 0) "⏰ 지금 마감" else "⏰ ${hours}시간 후 마감"
        val body = (if (course.isNotEmpty()) "[$course] " else "") + title + " · " +
            Brief.dayLabel(at.toLocalDate()) + " " + Brief.hm(at.toLocalTime())
        val n = NotificationCompat.Builder(ctx, CH_DEADLINE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(head)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFC4372D.toInt())
            .setContentIntent(openApp(ctx, 1))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Alarms.ACTION_DEADLINE -> {
                val at = runCatching { LocalDateTime.parse(intent.getStringExtra("at")) }.getOrElse { LocalDateTime.now() }
                Notify.deadline(
                    ctx, intent.getIntExtra("id", 3), intent.getStringExtra("course") ?: "",
                    intent.getStringExtra("title") ?: "", at, intent.getIntExtra("hours", 1),
                )
            }
            Alarms.ACTION_BRIEF -> {
                val pending = goAsync()
                val scope = CoroutineScope(Dispatchers.IO)
                scope.launch {
                    try {
                        val repo = Repo(ctx)
                        val job = scope.async { repo.sync() }
                        // Wait up to 8 s for fresh data; otherwise brief from the last sync.
                        val s = withTimeoutOrNull(8_000) { runCatching { job.await() }.getOrNull() }
                            ?: repo.load().also { Alarms.reschedule(ctx, it); SyncWorker.now(ctx) }
                        Notify.briefing(ctx, s)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        Alarms.reschedule(ctx, Repo(ctx).load())
        SyncWorker.now(ctx)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        runCatching { Repo(applicationContext).sync() }.fold({ Result.success() }, { Result.retry() })

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every 3 hours, so deadlines the midnight iCampus sweep adds get alarms the same day. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
