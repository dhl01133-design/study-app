package kr.dhl.studyapp

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.channels(this)
        SyncWorker.schedule(this)
        Alarms.scheduleBriefing(this)
    }
}

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private lateinit var repo: Repo
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tab.intValue = intent?.getIntExtra("tab", 0) ?: 0
        repo = Repo(this)
        handleTestExtras(intent)
        repo.load()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { AppRoot(repo, tab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab.intValue = intent.getIntExtra("tab", tab.intValue)
        handleTestExtras(intent)
    }

    /** Used only by the emulator smoke test in GitHub Actions. */
    private fun handleTestExtras(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("demo", false)) {
            Store.demo = true
            Store.snapshot.value = Demo.snapshot()
        }
        if (i.getBooleanExtra("demoNotify", false)) {
            val s = Store.snapshot.value
            Notify.briefing(this, s)
            Notify.deadline(this, 998, "선형대수학", "WeBWorK hw11 외 2개", java.time.LocalDateTime.now().plusHours(6), 6)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh when the app comes back, unless the last sync was under 2 minutes ago.
        if (System.currentTimeMillis() - Store.snapshot.value.fetchedAt > 120_000) {
            lifecycleScope.launch { runCatching { repo.sync() } }
        }
    }
}

private val Scheme = lightColorScheme(
    primary = Color(0xFF23874D), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F4EA), onPrimaryContainer = Color(0xFF17663F),
    secondary = Color(0xFF2F6FDF), background = Color(0xFFFBFBFA), surface = Color.White,
    surfaceVariant = Color(0xFFF3F4F2), onSurfaceVariant = Color(0xFF5F6862), error = Color(0xFFC4372D),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

@Composable
fun AppRoot(repo: Repo, tab: MutableIntState) {
    val snap by Store.snapshot.collectAsState()
    val syncing by Store.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val refresh: () -> Unit = { scope.launch { runCatching { repo.sync() } } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(tab.intValue == 0, { tab.intValue = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("브리핑") })
                NavigationBarItem(tab.intValue == 1, { tab.intValue = 1 }, { Icon(Icons.Default.DateRange, null) }, label = { Text("할 일") })
                NavigationBarItem(tab.intValue == 2, { tab.intValue = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("설정") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab.intValue) {
                0 -> BriefingScreen(snap, repo.prefs, syncing, refresh)
                1 -> WeekScreen(snap, repo, snackbar, syncing, refresh)
                else -> SettingsScreen(repo, snackbar)
            }
        }
    }
}

// ---- shared bits ----

val Muted = Color(0xFF5F6862)
val Danger = Color(0xFFC4372D)

@Composable
fun Header(kicker: String, title: String, syncing: Boolean, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(kicker, fontSize = 12.sp, color = Muted)
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (syncing) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "동기화") }
        }
    }
}

@Composable
fun CourseChip(course: String) {
    if (course.isEmpty()) return
    Text(
        course, color = Color.White, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(Courses.color(course)))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
