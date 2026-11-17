# 스터디 브리핑 (Android)

Personal app that replaces the KakaoTalk briefing and deadline alarms. The cloud iCampus sweep (00:00 KST) keeps filling Todoist and Google Calendar; this app reads them.

## Code layout (`app/src/main/java/kr/dhl/studyapp`)

| Package | Files | What it does |
|---|---|---|
| root | `App`, `MainActivity` | Startup (language, channels, background sync, briefing alarm); the activity and smoke-test hooks |
| `data` | `Model`, `Courses`, `Prefs`, `Store`, `Repo`, `Http`, `Todoist`, `Markets`, `Ics`, `Demo` | Tasks/events/markets, settings, in-memory state, sync and optimistic Todoist edits, iCal parsing, demo data |
| `brief` | `Fmt`, `DaySlice`, `Brief` | Date formatting, the day's free-time slice, briefing contents and deadline list |
| `notify` | `Alarms`, `Notify`, `Receivers`, `SyncWorker` | AlarmManager scheduling, notifications, alarm/boot receivers, 3-hourly sync |
| `ui` | `Theme`, `Components`, `AppRoot`, `BriefingScreen`, `SliceBar`, `TodoScreen`, `TaskViews`, `SettingsScreen` | Compose screens |
| `i18n` | `Lang` | 한국어 / English (`tr(ko, en)`) |

`TaskItem.planned` is the Todoist date (the day you plan to work on it); `TaskItem.deadline` is the real deadline, taken from "Due M/D [HH:MM]" in the description when present.

## Build and test
Every push to `main`:
1. builds the APK and publishes it as the release **latest** (`study-briefing.apk`);
2. runs `.github/scripts/smoke.py` on an Android 14 emulator with demo data (screens, drag, dialog, dark mode, English, notifications, crash check) and publishes screenshots and logs as the prerelease **smoke** (`smoke.zip`).

`app/release.keystore` is committed so new builds install over old ones. Keep this repo private.
