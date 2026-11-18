package kr.dhl.studyapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kr.dhl.studyapp.i18n.tr
import kr.dhl.studyapp.notify.Alarms
import kr.dhl.studyapp.notify.SyncWorker
import kr.dhl.studyapp.widget.TodayWidget
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime

/** What happened to a change: sent to Todoist, kept for later (offline), or rejected. */
sealed interface Edit {
    data object Sent : Edit
    data object Queued : Edit
    data class Failed(val message: String) : Edit
}

/** Loads, syncs and caches the [Snapshot]; changes tasks in Todoist (queued while offline). */
class Repo(ctx: Context) {
    private val app = ctx.applicationContext
    val prefs = Prefs(app)
    private val file = File(app.filesDir, "snapshot.json")
    private val pendingFile = File(app.filesDir, "pending.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val opsSerializer = ListSerializer(PendingOp.serializer())

    /** Current snapshot: in memory if present, otherwise from disk. */
    fun load(): Snapshot {
        val cur = Store.snapshot.value
        if (cur.fetchedAt > 0 || Store.demo) return cur
        val s = runCatching { json.decodeFromString(Snapshot.serializer(), file.readText()) }.getOrNull() ?: Snapshot()
        Store.snapshot.value = s
        return s
    }

    private fun save(s: Snapshot) {
        Store.snapshot.value = s
        if (Store.demo) return
        runCatching { file.writeText(json.encodeToString(Snapshot.serializer(), s)) }
    }

    // ---- offline queue ----

    private fun pending(): List<PendingOp> =
        runCatching { json.decodeFromString(opsSerializer, pendingFile.readText()) }.getOrDefault(emptyList())

    private fun savePending(ops: List<PendingOp>) {
        runCatching { if (ops.isEmpty()) pendingFile.delete() else pendingFile.writeText(json.encodeToString(opsSerializer, ops)) }
    }

    private fun enqueue(op: PendingOp) {
        synchronized(this) { savePending(pending() + op) }
        SyncWorker.now(app)  // runs as soon as there is a network
    }

    private fun Todoist.send(op: PendingOp) {
        when (op.type) {
            "due" -> setDue(op.taskId, op.date?.let { LocalDate.parse(it) }, op.time?.let { LocalTime.parse(it) })
            "close" -> close(op.taskId)
            "reopen" -> reopen(op.taskId)
            "add" -> quickAdd(op.text)
        }
    }

    /** Sends queued changes in order. Rejected ones are dropped; it stops at the first network failure. */
    private fun flush(errors: MutableList<String>): List<PendingOp> {
        val ops = pending()
        if (ops.isEmpty() || prefs.todoistToken.isBlank()) return ops
        val api = Todoist(prefs.todoistToken)
        var i = 0
        while (i < ops.size) {
            try {
                api.send(ops[i])
            } catch (e: HttpException) {
                errors += "Todoist: ${e.message}"
            } catch (e: IOException) {
                break
            }
            i++
        }
        val left = ops.drop(i)
        savePending(left)
        return left
    }

    // ---- sync ----

    /** Sends queued changes, then fetches Todoist, the calendars and markets. A source that fails keeps its last data. */
    suspend fun sync(): Snapshot {
        if (Store.demo) return Store.snapshot.value
        return Store.syncLock.withLock {
            withContext(Dispatchers.IO) {
                Store.syncing.value = true
                try {
                    val old = load()
                    val errors = mutableListOf<String>()
                    val left = flush(errors)
                    val tasks = fetchTasks(old, errors)
                    val events = fetchEvents(old, errors)
                    val (markets, session) = fetchMarkets(old, errors)
                    // Changes still waiting to be sent stay visible on top of the fetched tasks.
                    val s = left.fold(Snapshot(System.currentTimeMillis(), tasks, events, markets, session, errors)) { acc, op -> acc.withOp(op) }
                    if (Store.demo) return@withContext Store.snapshot.value  // demo switched on mid-sync
                    save(s)
                    Alarms.reschedule(app, s)
                    TodayWidget.refresh(app)
                    s
                } finally {
                    Store.syncing.value = false
                }
            }
        }
    }

    private fun fetchTasks(old: Snapshot, errors: MutableList<String>): List<TaskItem> {
        if (prefs.todoistToken.isBlank()) {
            errors += tr("Todoist 토큰 없음", "No Todoist token")
            return old.tasks
        }
        return runCatching { Todoist(prefs.todoistToken).tasks() }.getOrElse {
            errors += when {
                it is HttpException && (it.code == 401 || it.code == 403) -> tr("Todoist 토큰이 올바르지 않아요", "Todoist token is invalid")
                it is HttpException -> "Todoist: ${it.message}"
                else -> tr("오프라인", "Offline")
            }
            old.tasks
        }
    }

    private fun fetchEvents(old: Snapshot, errors: MutableList<String>): List<CalEvent> {
        val urls = prefs.icsUrls
        if (urls.isEmpty()) {
            errors += tr("iCal 주소 없음", "No iCal URL")
            return old.events
        }
        val today = LocalDate.now()
        var failed = false
        val all = urls.flatMap { url ->
            runCatching { Ics.parse(Http.request(url), today.minusDays(7), today.plusDays(28)) }.getOrElse {
                failed = true
                if (it is HttpException) errors += tr("캘린더: ", "Calendar: ") + it.message
                emptyList()
            }
        }
        return if (failed && all.isEmpty()) old.events else all.distinctBy { it.uid + "|" + it.start }
    }

    private fun fetchMarkets(old: Snapshot, errors: MutableList<String>): Pair<List<MarketItem>, String?> {
        if (prefs.marketUrl.isBlank()) return old.markets to old.marketSession
        return runCatching { Markets.fetch(prefs.marketUrl) }.getOrElse {
            if (it is HttpException) errors += tr("시장 데이터: ", "Markets: ") + it.message
            old.markets to old.marketSession
        }
    }

    // ---- task changes ----

    /** Moves a task to another day (null = no date), keeping its time. */
    suspend fun moveTask(t: TaskItem, date: LocalDate?): Edit =
        reschedule(t, date, if (date == null) null else t.time)

    /** Sets a task's day and time (null time = all day). */
    suspend fun reschedule(t: TaskItem, date: LocalDate?, time: LocalTime?): Edit = change(
        PendingOp("due", t.id, date?.toString(), if (date == null) null else time?.toString()),
        revert = { it.updateTask(t.id) { t } },
    )

    suspend fun complete(t: TaskItem): Edit = change(PendingOp("close", t.id), revert = { it.addTask(t) })

    suspend fun reopen(t: TaskItem): Edit = change(PendingOp("reopen", t.id, task = t), revert = { it.removeTask(t.id) })

    /** Quick add: Todoist reads the date, #project, @label and priority from the text. */
    suspend fun add(text: String): Edit {
        val op = PendingOp("add", text = text.trim())
        if (op.text.isEmpty()) return Edit.Failed("")
        val result = change(op, revert = { it.removeTask("local:" + op.text.hashCode()) })
        if (result == Edit.Sent && !Store.demo) sync()  // fetch the parsed task
        return result
    }

    /** Several changes at once (planning, moving past tasks); one result for all. */
    suspend fun rescheduleAll(moves: List<Triple<TaskItem, LocalDate?, LocalTime?>>): Edit {
        var queued = false
        for ((t, d, time) in moves) {
            when (val r = reschedule(t, d, time)) {
                is Edit.Failed -> return r
                Edit.Queued -> queued = true
                Edit.Sent -> Unit
            }
        }
        return if (queued) Edit.Queued else Edit.Sent
    }

    /**
     * Shows the change at once, then sends it. Rejected by Todoist: only this task is put back.
     * No network: the change stays and is sent on the next sync.
     */
    private suspend fun change(op: PendingOp, revert: (Snapshot) -> Snapshot): Edit = withContext(Dispatchers.IO) {
        save(Store.snapshot.value.withOp(op))
        val result = when {
            Store.demo -> Edit.Sent
            pending().isNotEmpty() -> Edit.Queued.also { enqueue(op) }  // keep the order of earlier offline changes
            else -> try {
                Todoist(prefs.todoistToken).send(op)
                Edit.Sent
            } catch (e: HttpException) {
                save(revert(Store.snapshot.value))
                Edit.Failed(if (e.code == 401 || e.code == 403) tr("Todoist 토큰이 올바르지 않아요", "Todoist token is invalid") else e.message.orEmpty())
            } catch (e: IOException) {
                enqueue(op)
                Edit.Queued
            }
        }
        Alarms.reschedule(app, Store.snapshot.value)
        TodayWidget.refresh(app)
        result
    }
}
