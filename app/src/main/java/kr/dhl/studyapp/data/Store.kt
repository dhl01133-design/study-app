package kr.dhl.studyapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** In-process state shared by the UI, receivers and workers. */
object Store {
    val snapshot = MutableStateFlow(Snapshot())
    val syncing = MutableStateFlow(false)
    val theme = MutableStateFlow("system")
    val lang = MutableStateFlow("system")
    val syncLock = Mutex()

    /** Emulator smoke test: show [Demo] data, never sync, save or call Todoist. */
    @Volatile var demo = false

    fun task(id: String): TaskItem? = snapshot.value.tasks.firstOrNull { it.id == id }
}
