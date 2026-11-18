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
import kr.dhl.studyapp.data.TaskItem
import java.time.LocalDate
import java.time.LocalDateTime

/** Fires the briefing (after a quick sync) and the deadline alarms, and handles the alarm buttons. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getIntExtra(Alarms.EXTRA_ID, 3)
        val taskIds = intent.getStringArrayExtra(Alarms.EXTRA_TASKS)?.toList().orEmpty()
        when (intent.action) {
            Alarms.ACTION_DEADLINE -> Notify.deadline(
                ctx, id,
                course = intent.getStringExtra(Alarms.EXTRA_COURSE) ?: "",
                title = intent.getStringExtra(Alarms.EXTRA_TITLE) ?: "",
                at = runCatching { LocalDateTime.parse(intent.getStringExtra(Alarms.EXTRA_AT)) }.getOrElse { LocalDateTime.now() },
                taskIds = taskIds,
            )
            Alarms.ACTION_SNOOZE -> {
                Notify.cancel(ctx, id)
                Alarms.snooze(ctx, intent)
            }
            Alarms.ACTION_DONE -> onTask(ctx, id, taskIds) { repo, t -> repo.complete(t) }
            Alarms.ACTION_TOMORROW -> onTask(ctx, id, taskIds) { repo, t -> repo.moveTask(t, LocalDate.now().plusDays(1)) }
            Alarms.ACTION_BRIEF -> briefAfterSync(ctx)
        }
    }

    /** Runs a change on the alarm's task and clears the notification. */
    private fun onTask(ctx: Context, id: Int, taskIds: List<String>, change: suspend (Repo, TaskItem) -> Unit) {
        Notify.cancel(ctx, id)
        val taskId = taskIds.singleOrNull() ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = Repo(ctx)
                repo.load().tasks.firstOrNull { it.id == taskId }?.let { change(repo, it) }
            } finally {
                pending.finish()
            }
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
