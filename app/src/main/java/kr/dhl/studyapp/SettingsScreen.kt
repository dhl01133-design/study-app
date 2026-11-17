package kr.dhl.studyapp

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
    var ics by remember { mutableStateOf(prefs.icsText) }
    var market by remember { mutableStateOf(prefs.marketUrl) }
    var brief by remember { mutableStateOf(prefs.briefingText) }
    var offsets by remember { mutableStateOf(prefs.offsetsText) }
    var onlyIcampus by remember { mutableStateOf(prefs.onlyIcampus) }

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
            Text("언어 · Language", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to tr("시스템", "System"), "ko" to "한국어", "en" to "English").forEach { (k, label) ->
                    Pill(label, Modifier.weight(1f), selected = lang == k) {
                        prefs.langMode = k
                        L.apply(k)
                        Notify.channels(ctx)
                        Store.lang.value = k
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(tr("테마", "Theme"), fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to tr("시스템", "System"), "light" to tr("라이트", "Light"), "dark" to tr("다크", "Dark")).forEach { (k, label) ->
                    Pill(label, Modifier.weight(1f), selected = theme == k) {
                        prefs.themeMode = k
                        Store.theme.value = k
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(tr("icampus 라벨 할 일만 보기", "Only show icampus tasks"), fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f))
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
                visualTransformation = PasswordVisualTransformation(), colors = fieldColors, shape = fieldShape,
            )
            Hint(tr("Todoist 웹 → 설정 → 연동 → 개발자 → API 토큰 복사", "Todoist web → Settings → Integrations → Developer → copy the API token"))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                ics, { ics = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("Google 캘린더 iCal 비공개 주소", "Google Calendar secret iCal address")) }, minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors, shape = fieldShape,
            )
            Hint(tr("PC에서 calendar.google.com → 설정 → dhl01133@gmail.com → 캘린더 통합 → \"iCal 형식의 비공개 주소\". 한 줄에 하나씩.", "On a PC: calendar.google.com → Settings → dhl01133@gmail.com → Integrate calendar → \"Secret address in iCal format\". One per line."))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                market, { market = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("미국장 markets.json 주소", "US markets markets.json URL")) }, singleLine = true, colors = fieldColors, shape = fieldShape,
            )
        }

        Group(tr("알림", "Notifications")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(brief, { brief = it }, Modifier.weight(1f), label = { Text(tr("브리핑 시각", "Briefing time")) }, singleLine = true, colors = fieldColors, shape = fieldShape)
                OutlinedTextField(offsets, { offsets = it }, Modifier.weight(1f), label = { Text(tr("알람 (시간 전)", "Alarms (hours before)")) }, singleLine = true, colors = fieldColors, shape = fieldShape)
            }
            Hint(tr("알람 6,1 → 실제 마감 6시간 전과 1시간 전 (icampus 할 일 + 캘린더 마감 일정)", "Alarms 6,1 → 6 h and 1 h before the real deadline (icampus tasks + calendar deadlines)"))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(tr("브리핑 미리보기", "Preview briefing"), Modifier.weight(1f)) { Notify.briefing(ctx, Store.snapshot.value) }
                Pill(tr("알람 미리보기", "Preview alarm"), Modifier.weight(1f)) {
                    Notify.deadline(ctx, 999, tr("테스트", "Test"), tr("마감 알람 소리 확인", "Checking the alarm sound"), LocalDateTime.now().plusHours(1), 1)
                }
            }
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                Spacer(Modifier.height(6.dp))
                Pill(tr("배터리 최적화에서 제외 (알람이 늦지 않게)", "Exclude from battery optimization (on-time alarms)"), Modifier.fillMaxWidth()) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                    }
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.accent)
                .clickable {
                    prefs.todoistToken = token; prefs.icsText = ics; prefs.marketUrl = market
                    prefs.briefingText = brief; prefs.offsetsText = offsets; prefs.onlyIcampus = onlyIcampus
                    scope.launch {
                        val s = runCatching { repo.sync() }.getOrNull()
                        val msg = when {
                            s == null -> tr("저장했어요. 동기화는 실패했어요.", "Saved. Sync failed.")
                            s.errors.isEmpty() -> tr("저장 완료 · 할 일 ${s.tasks.size}개, 일정 ${s.events.size}개", "Saved · ${s.tasks.size} tasks, ${s.events.size} events")
                            else -> tr("저장했어요. 확인 필요: ", "Saved. Check: ") + s.errors.first()
                        }
                        snackbar.showSnackbar(msg)
                    }
                }
                .padding(vertical = 17.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tr("저장하고 동기화", "Save and sync"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

        Text(
            tr(
                "예약된 마감 알람 ${prefs.alarmCodes.size}개 · 매일 ${prefs.briefingText} 브리핑 · 3시간마다 자동 동기화",
                "${prefs.alarmCodes.size} alarms scheduled · briefing daily at ${prefs.briefingText} · syncs every 3 h",
            ),
            fontSize = 12.sp, color = p.faint, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
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

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = pal.sub, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
