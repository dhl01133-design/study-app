# Rules for changes to this app

- Minimal and monochrome: black/white/grays from `ui/Theme.kt`; no card backgrounds or colored chips.
- No unnecessary text, emoji, icons or images, in the UI or in notifications (no subtitles, hints, legends, footers).
- Red (`pal.danger`) only for deadlines and overdue items.
- English is the default language; every visible string goes through `tr(ko, en)`.
- Personal app, not distributed; keep it simple.
- Every push builds the APK (release `latest`) and runs the emulator smoke test (prerelease `smoke`); check both before handing over a build.
