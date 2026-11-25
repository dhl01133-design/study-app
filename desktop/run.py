"""Entry point of StudyOverlay.exe: runs overlay.main() and, if anything fails (even an import), writes the
traceback to %APPDATA%\\StudyOverlay\\crash.log, since the windowed exe has no console to show it."""
import os
import sys
import traceback

try:
    import overlay
    code = overlay.main()
except SystemExit as e:
    code = e.code
except BaseException:
    d = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "StudyOverlay")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "crash.log"), "a", encoding="utf-8") as f:
        f.write(traceback.format_exc() + "\n")
    code = 1
sys.exit(code)
