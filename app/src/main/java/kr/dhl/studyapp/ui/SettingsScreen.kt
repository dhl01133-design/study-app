package kr.dhl.studyapp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.dhl.studyapp.data.Repo
import kr.dhl.studyapp.data.Store
import kr.dhl.studyapp.i18n.L
import kr.dhl.studyapp.i18n.tr
import kr.dhl.studyapp.notify.Notify
import java.time.LocalDateTime

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(repo: Repo, snackbar: SnackbarHostState) {
    val p = pal
    val prefs = repo.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by Store.theme.collectAsState()
    val lang by Store.lang.collectAsState()
    var token by remember { mutableStateOf(prefs.todoistToken) }
    var showToken by remember { mutableStateOf(false) }
    var ics by remember { mutableStateOf(prefs.icsText) }
    var market by remember { mutableStateOf(prefs.marketUrl) }
    var brief by remember { mutableStateOf(prefs.briefingText) }
    var offsets by remember { mutableStateOf(prefs.offsetsText) }
    var onlyIcampus by remember { mutableStateOf(prefs.onlyIcampus) }

    fun saveAndSync() {
        prefs.todoistToken = token
        prefs.icsText = ics
        prefs.marketUrl = market
        prefs.briefingText = brief
        prefs.offsetsText = offsets
        scope.launch {
            val s = runCatching { repo.sync() }.getOrNull()
            snackbar.showSnackbar(
                when {
                    s == null -> tr("동기화 실패", "Sync failed")
                    s.errors.isEmpty() -> tr("저장됨", "Saved")
                    else -> s.errors.first()
                },
            )
        }
    }

    fun setLanguage(mode: String) {
        prefs.langMode = mode
        L.apply(mode)
        Notify.channels(ctx)  // rename the channels in Android settings
        Store.lang.value = mode
    }

    fun setTheme(mode: String) {
        prefs.themeMode = mode
        Store.theme.value = mode
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = p.tile, unfocusedContainerColor = p.tile,
        focusedBorderColor = p.accent, unfocusedBorderColor = Color.Transparent,
        focusedLabelColor = p.accent, unfocusedLabelColor = p.sub,
        focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent,
    )
    val fieldShape = RoundedCornerShape(14.dp)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(tr("설정", "Settings"), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = p.text, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

        Group(tr("화면", "Display")) {
            Text(tr("언어", "Language"), fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to tr("시스템", "System"), "ko" to "한국어", "en" to "English").forEach { (k, label) ->
                    Pill(label, Modifier.weight(1f), selected = lang == k) { setLanguage(k) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(tr("테마", "Theme"), fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to tr("시스템", "System"), "light" to tr("라이트", "Light"), "dark" to tr("다크", "Dark")).forEach { (k, label) ->
                    Pill(label, Modifier.weight(1f), selected = theme == k) { setTheme(k) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(tr("icampus만", "icampus only"), fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
                Switch(
                    onlyIcampus, { onlyIcampus = it; prefs.onlyIcampus = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = p.accent, uncheckedTrackColor = p.tile,
                        uncheckedBorderColor = p.faint, uncheckedThumbColor = p.faint,
                    ),
                )
            }
        }

        Group(tr("연결", "Connections")) {
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("Todoist API 토큰", "Todoist API token")) }, singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Text(
                        if (showToken) "🙈" else "👁", fontSize = 18.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showToken = !showToken }.padding(8.dp),
                    )
                },
                supportingText = tokenLength(token)?.let { n ->
                    { Text(tr("40자여야 해요 (지금 ${n}자)", "Should be 40 characters (now $n)"), color = p.danger) }
                },
                colors = fieldColors, shape = fieldShape,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                ics, { ics = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("캘린더 iCal 주소", "Calendar iCal URL")) }, minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors, shape = fieldShape,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                market, { market = it }, Modifier.fillMaxWidth(),
                label = { Text("markets.json") }, singleLine = true, colors = fieldColors, shape = fieldShape,
            )
        }

        Group(tr("알림", "Notifications")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(brief, { brief = it }, Modifier.weight(1f), label = { Text(tr("브리핑", "Briefing")) }, singleLine = true, colors = fieldColors, shape = fieldShape)
                OutlinedTextField(offsets, { offsets = it }, Modifier.weight(1f), label = { Text(tr("알람 (시간 전)", "Alarms (h before)")) }, singleLine = true, colors = fieldColors, shape = fieldShape)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(tr("브리핑 테스트", "Test briefing"), Modifier.weight(1f)) { Notify.briefing(ctx, Store.snapshot.value) }
                Pill(tr("알람 테스트", "Test alarm"), Modifier.weight(1f)) {
                    Notify.deadline(ctx, 999, tr("테스트", "Test"), tr("마감 알람 소리 확인", "Checking the alarm sound"), LocalDateTime.now().plusHours(1), 1)
                }
            }
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                Spacer(Modifier.height(6.dp))
                Pill(tr("배터리 최적화 제외", "Ignore battery optimization"), Modifier.fillMaxWidth()) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                    }
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.accent)
                .clickable(onClick = ::saveAndSync)
                .padding(vertical = 17.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tr("저장", "Save"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = pal
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.card).padding(18.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

/** Length of the token as it will be saved, or null when it looks right (empty or 40 characters). */
private fun tokenLength(raw: String): Int? {
    val n = raw.count { !it.isWhitespace() && it != '"' && it != '\'' }
    return if (n == 0 || n == 40) null else n
}
