# 스터디 브리핑 (Android)

Replaces the KakaoTalk delivery of the daily briefing and deadline alarms.
The cloud iCampus sweep (00:00 KST) still fills Todoist and Google Calendar; this app reads them.

- **브리핑:** a notification every day at 08:17, plus a screen with overdue items, today's time slice, US market closes and the next 7 days.
- **마감 알람:** ringing notifications 6 h and 1 h before each `icampus` Todoist task and each Calendar deadline event (titles with 마감/due/제출) in the next 7 days.
- **할 일:** a two-week view. Long-press a task and drag it onto another day (or "날짜 없음") to change its Todoist due date; the time of day is kept. Tap a task for 오늘/내일/+1주/날짜 선택/완료. Tap the circle to complete. Every change has an undo.
- Syncs every 3 hours, when the app opens, and before the briefing.

## Build
Every push to `main` builds the APK with GitHub Actions and publishes it as the release **latest** (`study-briefing.apk`).
The signing key `app/release.keystore` is committed on purpose, so new builds install over old ones. Keep this repo private.

## Setup on the phone
1. Install the APK (allow "install unknown apps" for your browser/files app).
2. Allow notifications. On Samsung, also tap "배터리 최적화에서 제외" in 설정.
3. 설정 → Todoist API token (Todoist web → Settings → Integrations → Developer).
4. 설정 → Google Calendar "Secret address in iCal format" for dhl01133@gmail.com (calendar.google.com on a PC → Settings → the calendar → Integrate calendar).
5. 저장하고 동기화.
