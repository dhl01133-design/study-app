"""Self-install and self-update for StudyOverlay.exe.

Install: the first run of a downloaded exe copies itself to %LOCALAPPDATA%\\Programs\\StudyOverlay\\
and restarts from there, so autostart and updates always use one fixed path.

Update: the private repo's release "desktop" is named "Study Overlay (Windows) build N". When N is
higher than this build, the new exe is downloaded next to the running one, the running exe is renamed
to *.old.exe (Windows allows renaming a running exe), the new one takes its name and is started.
The .old.exe is deleted on the next start. Needs a read-only GitHub token (the same fine-grained
token as the phone app: study-app, Contents: read).
"""
import glob
import os
import re
import shutil
import subprocess
import sys

import requests

try:
    from build_info import BUILD          # written by the GitHub workflow
except ImportError:
    BUILD = 0

REPO = "dhl01133-design/study-app"
TAG = "desktop"
ASSET = "StudyOverlay.exe"
FROZEN = getattr(sys, "frozen", False)
INSTALL_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "Programs", "StudyOverlay")
INSTALL_EXE = os.path.join(INSTALL_DIR, ASSET)


def _same(a, b):
    return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


def ensure_installed():
    """Returns True when this process should exit because the installed copy was started instead."""
    if not (FROZEN and sys.platform == "win32") or _same(sys.executable, INSTALL_EXE):
        cleanup()
        return False
    os.makedirs(INSTALL_DIR, exist_ok=True)
    tmp = INSTALL_EXE + ".new"
    shutil.copy2(sys.executable, tmp)
    if os.path.exists(INSTALL_EXE):
        try:
            os.replace(INSTALL_EXE, INSTALL_EXE[:-4] + ".old.exe")
        except OSError:
            pass
    os.replace(tmp, INSTALL_EXE)
    launch(INSTALL_EXE)
    return True


def cleanup():
    for f in glob.glob(os.path.join(INSTALL_DIR, "*.old.exe")) + glob.glob(os.path.join(INSTALL_DIR, "*.new")):
        try:
            os.remove(f)
        except OSError:
            pass


def launch(path):
    flags = 0x00000008 | 0x00000200 if sys.platform == "win32" else 0   # DETACHED_PROCESS | NEW_PROCESS_GROUP
    subprocess.Popen([path], creationflags=flags, close_fds=True, cwd=os.path.dirname(path))


def _headers(token, raw=False):
    h = {"Authorization": f"Bearer {token}", "X-GitHub-Api-Version": "2022-11-28",
         "Accept": "application/octet-stream" if raw else "application/vnd.github+json"}
    return h


def check(token):
    """(build, asset_id, size) of the release, or raises with a short reason."""
    r = requests.get(f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}", headers=_headers(token), timeout=30)
    if r.status_code in (401, 403, 404):
        raise RuntimeError("GitHub token can't read the release")
    r.raise_for_status()
    j = r.json()
    m = re.search(r"build\s+(\d+)", j.get("name") or "")
    asset = next((a for a in j.get("assets", []) if a.get("name") == ASSET), None)
    if not m or not asset:
        raise RuntimeError("release has no build")
    return int(m.group(1)), asset["id"], asset["size"]


def install(token, asset_id, size):
    """Downloads the new exe and swaps it in. Returns the path to start."""
    target = sys.executable if FROZEN else INSTALL_EXE
    new = target + ".new"
    with requests.get(f"https://api.github.com/repos/{REPO}/releases/assets/{asset_id}",
                      headers=_headers(token, raw=True), stream=True, timeout=120) as r:
        r.raise_for_status()
        with open(new, "wb") as f:
            for chunk in r.iter_content(1 << 16):
                f.write(chunk)
    if os.path.getsize(new) != size:
        os.remove(new)
        raise RuntimeError("download incomplete")
    old = target[:-4] + ".old.exe"
    if os.path.exists(old):
        os.remove(old)
    os.replace(target, old)      # the running exe can be renamed, not overwritten
    os.replace(new, target)
    return target
