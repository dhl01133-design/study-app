package kr.dhl.studyapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.dhl.studyapp.MainActivity
import kr.dhl.studyapp.data.Repo
import kr.dhl.studyapp.data.Store
import kr.dhl.studyapp.i18n.tr

/** Bottom navigation with the three tabs. */
@Composable
fun AppRoot(repo: Repo, tab: MutableIntState) {
    val p = pal
    val snap by Store.snapshot.collectAsState()
    val syncing by Store.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val refresh: () -> Unit = { scope.launch { runCatching { repo.sync() } } }

    val tabs = listOf(
        Triple(MainActivity.TAB_BRIEFING, Icons.Default.Home, tr("브리핑", "Briefing")),
        Triple(MainActivity.TAB_TODO, Icons.Default.DateRange, tr("할 일", "To-do")),
        Triple(MainActivity.TAB_SETTINGS, Icons.Default.Settings, tr("설정", "Settings")),
    )

    Scaffold(
        containerColor = p.bg,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    shape = RoundedCornerShape(14.dp),
                    containerColor = if (p.dark) p.tile else Color(0xFF333D4B),
                    contentColor = Color.White,
                    actionColor = Color(0xFF90C2FF),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = p.bg, tonalElevation = 0.dp) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = p.text, selectedTextColor = p.text,
                    unselectedIconColor = p.faint, unselectedTextColor = p.faint,
                    indicatorColor = Color.Transparent,
                )
                tabs.forEach { (index, icon, label) -> TabItem(tab, index, icon, label, colors) }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab.intValue) {
                MainActivity.TAB_BRIEFING -> BriefingScreen(snap, repo.prefs, syncing, refresh)
                MainActivity.TAB_TODO -> TodoScreen(snap, repo, snackbar, syncing, refresh)
                else -> SettingsScreen(repo, snackbar)
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(
    tab: MutableIntState,
    index: Int,
    icon: ImageVector,
    label: String,
    colors: NavigationBarItemColors,
) {
    NavigationBarItem(
        selected = tab.intValue == index,
        onClick = { tab.intValue = index },
        icon = { Icon(icon, null) },
        label = { Text(label, fontSize = 12.sp) },
        colors = colors,
    )
}
