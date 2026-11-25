# Study Overlay Lite (Windows)

A lighter rewrite of `desktop/` (Python + Qt) in C# on .NET Framework 4.8, which ships with Windows 10/11:
one small exe, no runtime to install. Same features: today's list with the time bar, white panel by
default, text outlines, Ctrl+click to complete, drag to move, deadline alarms, list filters, sync
interval, in-place updates (release `lite`).

- Draws with GDI+ into one bitmap per change and shows it with `UpdateLayeredWindow` (per-pixel alpha);
  click-through is `WS_EX_TRANSPARENT`, toggled for Ctrl+click and moving.
- Settings live in `%APPDATA%\StudyOverlayLite\`; the first run copies the Python app's settings
  (tokens, look) from `%APPDATA%\StudyOverlay\config.json`.
- Calendar: `Ics.cs` ports the phone app's parser; IANA zone ids are mapped to Windows ids.
- Never copies itself or registers autostart on its own (AhnLab, 2026-11-24); the Run key is written
  only when "Start with Windows" is changed in Settings.
- `--selftest out.txt` (logic and window checks), `--shot a.png`, `--demo`.
- Built by `.github/workflows/lite.yml` on windows-latest: build, self-test, screenshots, RAM/CPU
  comparison with the Python app (`bench.txt`), release `lite`.
