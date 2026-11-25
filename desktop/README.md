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

Ctrl+click (build 7): hold Ctrl with the pointer over the list and it takes clicks (faint panel, row
highlight); click a task to complete it in Todoist, click it again within 8 s to undo. On Windows,
click-through is WS_EX_TRANSPARENT toggled in place (`enforce_input_style`), not Qt's
WindowTransparentForInput, so Qt never drops the clicks. Deadline alarms: Windows notifications 6 h
and 1 h before real deadlines (`deadlines()` / `alarms_due()`, fired state in `alarms.json`). Settings
→ List hides finished events, earlier-planned tasks or Now/Next, or shows only iCampus tasks;
Settings → Behavior sets the sync interval (5/15/30/60 min). `tests/win_check.py` runs on the
Windows build machine before packaging.

Text outline (build 8): every string is a QPainterPath stroked in the opposite of the text color
(black around light text, white around dark), then filled; sub and faint colors become solid blends so
the stroke doesn't show through. Settings → Appearance → Text outline: Soft shadow (the old halo),
Thin, Medium (default), Thick; the stroke scales with font size. The time bar and "now" line get
the same edge.

Build 9 (user, 2026-11-25): default look is a white panel with dark text (Settings → Appearance →
Background: None / White / Dark; picking one sets a matching text color). The overlay no longer hides
in full screen by default, and while "Always on top" is on it re-asserts HWND_TOPMOST every 2 s
(`keep_on_top`), because borderless full-screen apps make themselves topmost and covered it.
Exclusive (D3D) full-screen games still draw over everything. Existing configs are migrated once
(`cfg_version` 2).

Build 10, lightness: `build()` and the day slice are memoized per minute/data version; the window
paints from a cached pixmap and only re-renders when the picture key changes; ticks are once a
minute (aligned to the minute); the Ctrl poll is 60 ms only while the pointer is over the list, else
400 ms; the iCal feed uses ETag/Last-Modified plus a content hash, so it's only parsed when it changed
or the day changed; after each sync `gc.collect()` and `EmptyWorkingSet`; on start BELOW_NORMAL
priority and Windows 11 efficiency mode (EcoQoS). The workflow measures RAM and CPU of the new and
previous builds (demo mode, idle) and publishes `bench.txt` with the release.

Build 11, lighter (methods from `lite/`): `ics.py` replaces icalendar + recurring_ical_events (checked equal
to the library on 4,800 random calendars in four PC time zones: DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL,
COUNT, UNTIL, BYDAY incl. ordinals, BYMONTHDAY, BYMONTH, BYSETPOS, EXDATE, RECURRENCE-ID, DST); `web.py`
(urllib) replaces requests and drops the token on cross-host redirects; `StudyOverlay.spec` leaves out
Qt parts never loaded (software OpenGL, translations, image/icon/network/PDF/QML) and strips docstrings;
`gc.freeze()` after start-up. Requirements are now PySide6-Essentials + tzdata only.
