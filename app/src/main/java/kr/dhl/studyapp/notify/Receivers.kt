package kr.dhl.studyapp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.dhl.studyapp.data.Repo
import java.time.LocalDateTime

/** Fires the briefing (after a quick sync) and the deadline alarms. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            Alarms.ACTION_DEADLINE -> Notify.deadline(
                ctx,
                id = intent.getIntExtra(Alarms.EXTRA_ID, 3),
                course = intent.getStringExtra(Alarms.EXTRA_COURSE) ?: "",
                title = intent.getStringExtra(Alarms.EXTRA_TITLE) ?: "",
                at = runCatching { LocalDateTime.parse(intent.getStringExtra(Alarms.EXTRA_AT)) }.getOrElse { LocalDateTime.now() },
                hours = intent.getIntExtra(Alarms.EXTRA_HOURS, 1),
            )
            Alarms.ACTION_BRIEF -> briefAfterSync(ctx)
        }
    }

    /** Waits up to 8 s for fresh data; otherwise briefs from the last sync and syncs in the background. */
    private fun briefAfterSync(ctx: Context) {
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val repo = Repo(ctx)
                val sync = scope.async { repo.sync() }
                val s = withTimeoutOrNull(8_000) { runCatching { sync.await() }.getOrNull() }
                    ?: repo.load().also { Alarms.reschedule(ctx, it); SyncWorker.now(ctx) }
                Notify.briefing(ctx, s)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Restores alarms after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        Alarms.reschedule(ctx, Repo(ctx).load())
        SyncWorker.now(ctx)
    }
}
