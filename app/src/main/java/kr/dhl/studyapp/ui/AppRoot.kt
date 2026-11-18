package kr.dhl.studyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.dhl.studyapp.MainActivity
import kr.dhl.studyapp.data.Repo
import kr.dhl.studyapp.data.Store
import kr.dhl.studyapp.i18n.tr

/** Three tabs with a text-only bar at the bottom. */
@Composable
fun AppRoot(repo: Repo, tab: MutableIntState) {
    val p = pal
    val snap by Store.snapshot.collectAsState()
    val syncing by Store.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val refresh: () -> Unit = { scope.launch { runCatching { repo.sync() } } }

    val tabs = listOf(
        MainActivity.TAB_BRIEFING to tr("브리핑", "Briefing"),
        MainActivity.TAB_TODO to tr("할 일", "To-do"),
        MainActivity.TAB_SETTINGS to tr("설정", "Settings"),
    )

    Scaffold(
        containerColor = p.bg,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, shape = RoundedCornerShape(10.dp), containerColor = p.text, contentColor = p.bg, actionColor = p.bg)
            }
        },
        bottomBar = {
            Column(Modifier.background(p.bg).navigationBarsPadding()) {
                Rule()
                Row(Modifier.fillMaxWidth()) {
                    tabs.forEach { (index, label) ->
                        val selected = tab.intValue == index
                        Box(
                            Modifier.weight(1f).clickable { tab.intValue = index }.padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label, fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) p.text else p.faint,
                            )
                        }
                    }
                }
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
