"""Emulator smoke test: install the APK, walk through every screen with demo data,
try a drag-to-reschedule, show the notifications, and save screenshots + crash logs.

Run inside reactivecircus/android-emulator-runner (adb on PATH).
Usage: python3 .github/scripts/smoke.py study-briefing.apk
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "kr.dhl.studyapp"
ACT = PKG + "/.MainActivity"
OUT = "smoke"
os.makedirs(OUT, exist_ok=True)
report = []


def log(msg):
    print(msg, flush=True)
    report.append(msg)


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        log(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def shot(name):
    data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(data)
    log(f"screenshot {name}.png ({len(data)} bytes)")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    x = adb("shell", "cat", "/sdcard/ui.xml", check=False)
    if "<?xml" not in x:
        return []
    root = ET.fromstring(x[x.index("<?xml"):].encode("utf-8"))
    out = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append((n.get("text", "") or n.get("content-desc", ""), (x1, y1, x2, y2)))
    return out


def find(pred):
    for text, b in nodes():
        if text and pred(text):
            return text, ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2), b
    return None


def start(*extras):
    adb("shell", "am", "start", "-n", ACT, *extras)
    time.sleep(3)


def crashed():
    c = adb("logcat", "-d", "-b", "crash", check=False)
    return PKG in c, c


apk = sys.argv[1]
log("install: " + adb("install", "-r", "-g", apk).strip())
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
adb("logcat", "-c")

# 1. Briefing
start("--ez", "demo", "true", "--ei", "tab", "0")
time.sleep(2)
shot("1_briefing_top")
adb("shell", "input", "swipe", "540", "1800", "540", "700", "400")
time.sleep(1)
shot("1_briefing_bottom")

# 2. Week view + drag
start("--ei", "tab", "1")
time.sleep(2)
shot("2_week_before")
src = find(lambda t: t == "DEMO 끌어서 옮기기")
dst = find(lambda t: t.startswith("내일 · "))
if not src or not dst:
    log(f"drag: could not find nodes (src={src}, dst={dst})")
else:
    (sx, sy), (dx, dy) = src[1], dst[1]
    log(f"drag: {src[0]} at {sx},{sy} -> '{dst[0]}' at {dx},{dy}")
    adb("shell", "input", "motionevent", "DOWN", str(sx), str(sy))
    time.sleep(1.2)  # long press
    steps = 12
    for i in range(1, steps + 1):
        y = sy + (dy - sy) * i // steps
        adb("shell", "input", "motionevent", "MOVE", str(sx), str(y))
        time.sleep(0.05)
    shot("2_week_dragging")
    adb("shell", "input", "motionevent", "UP", str(sx), str(dy))
    time.sleep(1.5)
    shot("2_week_after_drop")
    snack = find(lambda t: "→" in t)
    log("drag result snackbar: " + (snack[0] if snack else "(none found)"))
    moved = find(lambda t: t == "DEMO 끌어서 옮기기")
    if moved:
        log(f"task now at y={moved[1][1]} (tomorrow header y={dy}; was y={sy})")

# 3. Task dialog
t = find(lambda t: t.startswith("12주차"))
if t:
    adb("shell", "input", "tap", str(t[1][0]), str(t[1][1]))
    time.sleep(1.5)
    shot("3_task_dialog")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
else:
    log("dialog: task not found on screen")

# 4. Settings
start("--ei", "tab", "2")
time.sleep(1)
shot("4_settings")

# 4b. Dark mode
start("--es", "theme", "dark", "--ei", "tab", "0")
time.sleep(1.5)
shot("6_dark_briefing")
start("--ei", "tab", "1")
time.sleep(1.5)
shot("6_dark_week")
start("--ei", "tab", "2")
time.sleep(1)
shot("6_dark_settings")
start("--es", "theme", "light", "--ei", "tab", "0")

# 4c. English
start("--es", "lang", "en", "--ei", "tab", "0")
time.sleep(1.5)
shot("7_en_briefing")
start("--ei", "tab", "1")
time.sleep(1.5)
shot("7_en_week")
start("--ei", "tab", "2")
time.sleep(1)
shot("7_en_settings")
start("--ez", "demoNotify", "true")
time.sleep(2)
adb("shell", "cmd", "statusbar", "expand-notifications", check=False)
time.sleep(2)
shot("7_en_notifications")
adb("shell", "cmd", "statusbar", "collapse", check=False)
adb("shell", "cmd", "notification", "cancel_all", check=False)
start("--es", "lang", "ko", "--ei", "tab", "0")

# 5. Notifications
start("--ez", "demoNotify", "true")
time.sleep(2)
adb("shell", "cmd", "statusbar", "expand-notifications", check=False)
time.sleep(2)
shot("5_notifications")
adb("shell", "cmd", "statusbar", "collapse", check=False)

# 6. Alarms scheduled by the app
alarms = adb("shell", "dumpsys", "alarm", check=False)
lines = [l.strip() for l in alarms.splitlines() if PKG in l]
log(f"alarm entries mentioning {PKG}: {len(lines)}")

bad, crash = crashed()
errors = adb("logcat", "-d", "*:E", check=False)
with open(f"{OUT}/logcat_errors.txt", "w") as f:
    f.write("\n".join(l for l in errors.splitlines() if PKG in l or "AndroidRuntime" in l))
with open(f"{OUT}/crash.txt", "w") as f:
    f.write(crash)
log("CRASH: yes — see crash.txt" if bad else "CRASH: none")
with open(f"{OUT}/report.txt", "w") as f:
    f.write("\n".join(report) + "\n")
sys.exit(1 if bad else 0)
