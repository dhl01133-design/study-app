package kr.dhl.studyapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.dhl.studyapp.data.Demo
import kr.dhl.studyapp.data.Repo
import kr.dhl.studyapp.data.Store
import kr.dhl.studyapp.i18n.L
import kr.dhl.studyapp.notify.Notify
import kr.dhl.studyapp.ui.AppRoot
import kr.dhl.studyapp.ui.AppTheme
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(TAB_BRIEFING)
    private lateinit var repo: Repo
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = Repo(this)
        Store.theme.value = repo.prefs.themeMode
        Store.lang.value = repo.prefs.langMode
        L.apply(repo.prefs.langMode)
        handleIntent(intent)
        repo.load()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            val theme by Store.theme.collectAsState()
            val lang by Store.lang.collectAsState()
            AppTheme(theme) { key(lang) { AppRoot(repo, tab) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - Store.snapshot.value.fetchedAt > RESYNC_AFTER_MS) {
            lifecycleScope.launch { runCatching { repo.sync() } }
        }
    }

    /** Opens a tab (from a notification). The other extras are used only by the emulator smoke test. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        tab.intValue = i.getIntExtra(EXTRA_TAB, tab.intValue)
        if (i.getBooleanExtra("demo", false)) {
            Store.demo = true
            Store.snapshot.value = Demo.snapshot()
        }
        i.getStringExtra("theme")?.let { Store.theme.value = it }
        i.getStringExtra("lang")?.let { L.apply(it); Store.lang.value = it }
        if (i.getBooleanExtra("demoNotify", false)) {
            Notify.briefing(this, Store.snapshot.value)
            Notify.deadline(this, 998, "선형대수학", "WeBWorK hw11 외 2개", LocalDateTime.now().plusHours(6), 6)
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_BRIEFING = 0
        const val TAB_TODO = 1
        const val TAB_SETTINGS = 2
        private const val RESYNC_AFTER_MS = 120_000L
    }
}
