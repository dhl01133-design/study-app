package kr.dhl.studyapp.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.dhl.studyapp.MainActivity
import kr.dhl.studyapp.R
import kr.dhl.studyapp.brief.Brief
import kr.dhl.studyapp.brief.Fmt
import kr.dhl.studyapp.data.Prefs
import kr.dhl.studyapp.data.Snapshot
import kr.dhl.studyapp.i18n.tr
import java.time.LocalDate
import java.time.LocalDateTime

/** Posts the briefing and deadline notifications. */
object Notify {
    private const val CH_BRIEF = "briefing"
    private const val CH_DEADLINE = "deadline"
    private const val BRIEF_ID = 1
    private const val ALARM_COLOR = 0xFFC4372D.toInt()

    /** Creates (or renames, after a language change) the notification channels. */
    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_BRIEF, tr("아침 브리핑", "Morning briefing"), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DEADLINE, tr("마감 알람", "Deadline alarms"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            },
        )
    }

    fun briefing(ctx: Context, s: Snapshot) {
        val prefs = Prefs(ctx)
        val today = LocalDate.now()
        post(
            ctx, BRIEF_ID,
            NotificationCompat.Builder(ctx, CH_BRIEF)
                .setContentTitle(tr("☀️ ${Fmt.day(today)} 브리핑", "☀️ Briefing · ${Fmt.day(today)}"))
                .setContentText(Brief.headline(s, prefs, today))
                .setStyle(NotificationCompat.BigTextStyle().bigText(Brief.summary(s, prefs, today).joinToString("\n")))
                .setContentIntent(openApp(ctx, MainActivity.TAB_BRIEFING)),
        )
    }

    fun deadline(ctx: Context, id: Int, course: String, title: String, at: LocalDateTime, hours: Int) {
        val head = if (hours == 0) tr("⏰ 지금 마감", "⏰ Due now") else tr("⏰ ${hours}시간 후 마감", "⏰ Due in $hours h")
        val body = Brief.withCourse(course, title) + " · " + Fmt.day(at.toLocalDate()) + " " + Fmt.time(at.toLocalTime())
        post(
            ctx, id,
            NotificationCompat.Builder(ctx, CH_DEADLINE)
                .setContentTitle(head)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setColor(ALARM_COLOR)
                .setContentIntent(openApp(ctx, MainActivity.TAB_TODO)),
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, builder: NotificationCompat.Builder) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val n = builder.setSmallIcon(R.drawable.ic_notif).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    private fun openApp(ctx: Context, tab: Int): PendingIntent = PendingIntent.getActivity(
        ctx, 100 + tab,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
