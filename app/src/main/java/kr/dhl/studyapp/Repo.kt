package kr.dhl.studyapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** In-process state shared by the UI, receivers and workers. */
object Store {
    val snapshot = MutableStateFlow(Snapshot())
    val syncing = MutableStateFlow(false)
    val theme = MutableStateFlow("system")
    val lock = Mutex()

    /** Emulator smoke test: show [Demo] data, never sync, save or call Todoist. */
    @Volatile var demo = false
}

class Repo(ctx: Context) {
    private val app = ctx.applicationContext
    val prefs = Prefs(app)
    private val file = File(app.filesDir, "snapshot.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    suspend fun sync(): Snapshot = if (Store.demo) Store.snapshot.value else Store.lock.withLock {
        withContext(Dispatchers.IO) {
            Store.syncing.value = true
            try {
                val old = load()
                val errors = mutableListOf<String>()
                val today = LocalDate.now()

                val tasks = if (prefs.todoistToken.isBlank()) {
                    errors += "설정에서 Todoist 토큰을 입력하세요"
                    old.tasks
                } else runCatching { Todoist(prefs.todoistToken).tasks() }.getOrElse {
                    errors += "Todoist: ${it.message}"
                    old.tasks
                }

                val urls = prefs.icsUrls
                val events = if (urls.isEmpty()) {
                    errors += "설정에서 Google 캘린더 iCal 주소를 입력하세요"
                    old.events
                } else {
                    var failed = false
                    val all = urls.flatMap { u ->
                        runCatching { Ics.parse(Http.request(u), today.minusDays(7), today.plusDays(28)) }.getOrElse {
                            failed = true
                            errors += "캘린더: ${it.message}"
                            emptyList()
                        }
                    }
                    if (failed && all.isEmpty()) old.events else all.distinctBy { it.uid + "|" + it.start }
                }

                val (markets, session) = if (prefs.marketUrl.isBlank()) old.markets to old.marketSession
                else runCatching { Markets.fetch(prefs.marketUrl) }.getOrElse {
                    errors += "시장 데이터: ${it.message}"
                    old.markets to old.marketSession
                }

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

    private fun replaceTask(t: TaskItem?, id: String) {
        val s = Store.snapshot.value
        val list = if (t == null) s.tasks.filter { it.id != id } else s.tasks.map { if (it.id == id) t else it }
        save(s.copy(tasks = list))
    }

    /** Optimistic move: the UI updates at once, and reverts if Todoist rejects it. */
    suspend fun moveTask(t: TaskItem, date: LocalDate?): Result<Unit> = withContext(Dispatchers.IO) {
        val before = Store.snapshot.value
        replaceTask(t.copy(dueDate = date?.toString(), dueTime = if (date == null) null else t.dueTime), t.id)
        runCatching { if (!Store.demo) Todoist(prefs.todoistToken).setDue(t.id, date, if (date == null) null else t.time) }
            .onSuccess { Alarms.reschedule(app, Store.snapshot.value) }
            .onFailure { save(before) }
    }

    suspend fun complete(t: TaskItem): Result<Unit> = withContext(Dispatchers.IO) {
        val before = Store.snapshot.value
        replaceTask(null, t.id)
        runCatching { if (!Store.demo) Todoist(prefs.todoistToken).close(t.id) }
            .onSuccess { Alarms.reschedule(app, Store.snapshot.value) }
            .onFailure { save(before) }
    }

    suspend fun reopen(t: TaskItem): Result<Unit> = withContext(Dispatchers.IO) {
        val s = Store.snapshot.value
        save(s.copy(tasks = s.tasks + t))
        runCatching { if (!Store.demo) Todoist(prefs.todoistToken).reopen(t.id) }
            .onSuccess { Alarms.reschedule(app, Store.snapshot.value) }
            .onFailure { save(s) }
    }
}
