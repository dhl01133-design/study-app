package kr.dhl.studyapp

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val p = repo.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(p.todoistToken) }
    var ics by remember { mutableStateOf(p.icsText) }
    var market by remember { mutableStateOf(p.marketUrl) }
    var brief by remember { mutableStateOf(p.briefingText) }
    var offsets by remember { mutableStateOf(p.offsetsText) }
    var onlyIcampus by remember { mutableStateOf(p.onlyIcampus) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("설정", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            token, { token = it }, Modifier.fillMaxWidth(),
            label = { Text("Todoist API 토큰") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Hint("Todoist 웹 → 설정 → 연동 → 개발자 → API 토큰 복사")

        OutlinedTextField(
            ics, { ics = it }, Modifier.fillMaxWidth(),
            label = { Text("Google 캘린더 iCal 비공개 주소 (한 줄에 하나)") }, minLines = 2,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Hint("PC에서 calendar.google.com → 설정 → 내 캘린더의 dhl01133@gmail.com → 캘린더 통합 → \"iCal 형식의 비공개 주소\" 복사. 주소가 알려지면 같은 곳에서 재설정하세요.")

        OutlinedTextField(
            market, { market = it }, Modifier.fillMaxWidth(),
            label = { Text("미국장 markets.json 주소") }, singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(brief, { brief = it }, Modifier.weight(1f), label = { Text("브리핑 시각") }, singleLine = true)
            OutlinedTextField(offsets, { offsets = it }, Modifier.weight(1f), label = { Text("알람 (시간 전)") }, singleLine = true)
        }
        Hint("예: 브리핑 08:17, 알람 6,1 → 마감 6시간 전과 1시간 전에 울림 (icampus 라벨 할 일 + 캘린더 마감 일정)")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(onlyIcampus, { onlyIcampus = it })
            Text("  icampus 라벨이 붙은 할 일만 보기", fontSize = 14.sp)
        }

        Button(
            onClick = {
                p.todoistToken = token; p.icsText = ics; p.marketUrl = market
                p.briefingText = brief; p.offsetsText = offsets; p.onlyIcampus = onlyIcampus
                scope.launch {
                    val s = runCatching { repo.sync() }.getOrNull()
                    val msg = when {
                        s == null -> "저장했어요. 동기화는 실패했어요."
                        s.errors.isEmpty() -> "저장 완료 · 할 일 ${s.tasks.size}개, 일정 ${s.events.size}개"
                        else -> "저장했어요. 확인 필요: " + s.errors.first()
                    }
                    snackbar.showSnackbar(msg)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장하고 동기화") }

        Text("테스트", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton({ Notify.briefing(ctx, Store.snapshot.value) }, Modifier.fillMaxWidth()) { Text("아침 브리핑 알림 미리보기") }
        OutlinedButton(
            { Notify.deadline(ctx, 999, "테스트", "마감 알람 소리 확인", LocalDateTime.now().plusHours(1), 1) },
            Modifier.fillMaxWidth(),
        ) { Text("마감 알람 미리보기") }

        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            OutlinedButton(
                {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)),
                        )
                    }
                },
                Modifier.fillMaxWidth(),
            ) { Text("배터리 최적화에서 제외 (알람이 늦지 않게)") }
        }

        Hint("예약된 마감 알람 ${p.alarmCodes.size}개 · 매일 ${p.briefingText} 브리핑 · 3시간마다 자동 동기화")
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = Muted)
