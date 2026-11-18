# Rules for changes to this app

- Minimal, with a few purposeful colors from `ui/Theme.kt` (user, 2026-11-18): blue accent for selections, classes and "now"; green for free time; orange/blue priority circles; course names in their course color as text. No card backgrounds or colored chip backgrounds.
- No unnecessary text, emoji, icons or images, in the UI or in notifications (no subtitles, hints, legends, footers).
- Red (`pal.danger`) only for deadlines and overdue items.
- English is the default language; every visible string goes through `tr(ko, en)`.
- Personal app, not distributed; keep it simple.
- Every push builds the APK (release `latest`) and runs the emulator smoke test (prerelease `smoke`); check both before handing over a build.
