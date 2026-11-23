# Study Overlay (Windows)

Today's list from the Android app's widget, drawn on the main monitor like a watermark:
borderless, click-through, no taskbar entry, with the widget's 08–24 time bar. Tray icon: Sync, Show,
Move (drag it; double-click, Enter or Esc to finish), Check for updates, Settings, Quit. Settings has
three tabs: Appearance (text color, size, opacity, position + Drag to move, time bar), Behavior
(language, always on top, hide in full screen, start with Windows) and Accounts (tokens, auto-update).
Appearance changes preview live; Cancel undoes them.

- Same sources as the phone: Todoist personal token + Google Calendar secret iCal address (Settings).
- Rows: overdue tasks (real deadline passed), today's events, tasks planned for earlier days that are
  still open, today's tasks. Same course colors and "Due" rules as `data/Model.kt`.
- Syncs every 15 min, at midnight and on tray-icon click; draws from `cache.json` when offline.
- Config and cache live in `%APPDATA%\StudyOverlay\`.

Built by `.github/workflows/desktop.yml` on windows-latest (PyInstaller, one file) and published as the
release `desktop` (`StudyOverlay.exe` plus two demo screenshots). Local preview on Linux:
`QT_QPA_PLATFORM=offscreen python3 overlay.py --demo --shot out.png [--lang ko] [--color #1f2a44] [--scale 1.2]`.

Updates (`updater.py`): the exe runs wherever it is kept. It never copies itself, and it only writes
the HKCU Run key when the user ticks "Start with Windows" in Settings. AhnLab flagged the earlier
self-install + automatic Run key as Persistence/MDP.AutoRun (2026-11-24). With a read-only GitHub
token in Settings (same fine-grained token as the phone: study-app, Contents: read) it checks the
`desktop` release 20 s after start and every 6 h. When "build N" there is higher than the running build
(`build_info.py`, written by the workflow), it downloads the exe next to itself, renames the running one
to `.old.exe`, swaps the new one in and restarts. Tray → Check for updates does the same on demand.
