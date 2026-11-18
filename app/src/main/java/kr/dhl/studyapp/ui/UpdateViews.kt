package kr.dhl.studyapp.ui

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.dhl.studyapp.data.Prefs
import kr.dhl.studyapp.i18n.tr
import kr.dhl.studyapp.update.UpdateState
import kr.dhl.studyapp.update.Updater

/** Card at the top of the briefing while a new build is available or installing. */
@Composable
fun UpdateBanner(prefs: Prefs) {
    val state by Updater.state.collectAsState()
    val s = state
    if (s is UpdateState.Available || s == UpdateState.Downloading || s == UpdateState.Installing) {
        UpdateStatus(s, prefs)
    }
}

/** One line for the current update state, with an install button when there's a new build. */
@Composable
fun UpdateStatus(s: UpdateState, prefs: Prefs) {
    val p = pal
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    when (s) {
        is UpdateState.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("새 버전 build ${s.build}", "New version: build ${s.build}"),
                fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f),
            )
            Pill(tr("설치", "Install"), Modifier.width(96.dp), selected = true) { startInstall(ctx, scope, prefs.githubToken) }
        }
        UpdateState.Downloading -> Text(tr("다운로드 중…", "Downloading…"), fontSize = 15.sp, color = p.sub)
        UpdateState.Installing -> Text(tr("설치 중…", "Installing…"), fontSize = 15.sp, color = p.sub)
        UpdateState.UpToDate -> Text(tr("최신 버전", "Up to date"), fontSize = 15.sp, color = p.sub)
        is UpdateState.Failed -> Text(s.message, fontSize = 14.sp, color = p.danger)
        UpdateState.Idle -> Unit
    }
}

private fun startInstall(ctx: Context, scope: CoroutineScope, token: String) {
    if (Updater.needsInstallPermission(ctx)) {
        ctx.startActivity(Updater.installPermissionIntent(ctx))  // allow once, then tap 설치 again
        return
    }
    scope.launch { Updater.install(ctx.applicationContext, token) }
}
