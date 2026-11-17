package kr.dhl.studyapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kr.dhl.studyapp.i18n.tr
import kr.dhl.studyapp.notify.Alarms
import java.io.File
import java.time.LocalDate

/** Loads, syncs and caches the [Snapshot]; changes tasks in Todoist. */
class Repo(ctx: Context) {
    private val app = ctx.applicationContext
    val prefs = Prefs(app)
    private val file = File(app.filesDir, "snapshot.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    /** Fetches Todoist, the calendars and markets. A source that fails keeps its last data. */
    suspend fun sync(): Snapshot {
        if (Store.demo) return Store.snapshot.value
        return Store.syncLock.withLock {
            withContext(Dispatchers.IO) {
                Store.syncing.value = true
                try {
                    val old = load()
                    val errors = mutableListOf<String>()
                    val tasks = fetchTasks(old, errors)
                    val events = fetchEvents(old, errors)
                    val (markets, session) = fetchMarkets(old, errors)
                    val s = Snapshot(System.currentTimeMillis(), tasks, events, markets, session, errors)
                    if (Store.demo) return@withContext Store.snapshot.value  // demo switched on mid-sync
                    save(s)
                    Alarms.reschedule(app, s)
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
            errors += "Todoist: ${it.message}"
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
                errors += tr("캘린더: ", "Calendar: ") + it.message
                emptyList()
            }
        }
        return if (failed && all.isEmpty()) old.events else all.distinctBy { it.uid + "|" + it.start }
    }

    private fun fetchMarkets(old: Snapshot, errors: MutableList<String>): Pair<List<MarketItem>, String?> {
        if (prefs.marketUrl.isBlank()) return old.markets to old.marketSession
        return runCatching { Markets.fetch(prefs.marketUrl) }.getOrElse {
            errors += tr("시장 데이터: ", "Markets: ") + it.message
            old.markets to old.marketSession
        }
    }

    /** Moves a task to another day (null = no date), keeping its time. */
    suspend fun moveTask(t: TaskItem, date: LocalDate?): Result<Unit> = optimistic(
        { it.updateTask(t.id) { task -> task.copy(dueDate = date?.toString(), dueTime = if (date == null) null else task.dueTime) } },
        { setDue(t.id, date, if (date == null) null else t.time) },
    )

    suspend fun complete(t: TaskItem): Result<Unit> = optimistic({ it.removeTask(t.id) }, { close(t.id) })

    suspend fun reopen(t: TaskItem): Result<Unit> = optimistic({ it.addTask(t) }, { reopen(t.id) })

    /** Applies [local] at once so the UI updates, then sends [remote]; reverts if Todoist rejects it. */
    private suspend fun optimistic(local: (Snapshot) -> Snapshot, remote: Todoist.() -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val before = Store.snapshot.value
            save(local(before))
            runCatching { if (!Store.demo) Todoist(prefs.todoistToken).remote() }
                .onSuccess { Alarms.reschedule(app, Store.snapshot.value) }
                .onFailure { save(before) }
        }
}
