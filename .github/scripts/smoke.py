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
    dismiss_system_dialogs(nodes())
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


def dismiss_system_dialogs(ns):
    """The emulator's launcher sometimes hangs and shows "isn't responding"; tap Wait so it doesn't block us."""
    if any(t.endswith("isn't responding") for t, _ in ns):
        for t, b in ns:
            if t == "Wait":
                adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
                time.sleep(1)
                return True
    return False


def find(pred):
    ns = nodes()
    if dismiss_system_dialogs(ns):
        ns = nodes()
    for text, b in ns:
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
adb("shell", "settings", "put", "global", "hide_error_dialogs", "1", check=False)
log("install: " + adb("install", "-r", "-g", apk).strip())
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
adb("logcat", "-c")

# 1. Briefing
start("--ez", "demo", "true", "--es", "lang", "en", "--ei", "tab", "0")
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
dst = find(lambda t: t.startswith("Tomorrow · ") or t.startswith("내일 · "))
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

# 3b. Swipe right to complete
start("--ei", "tab", "1")
time.sleep(1.5)
sw = find(lambda t: t.startswith("WeBWorK hw11"))
if sw:
    (x, y) = sw[1]
    adb("shell", "input", "swipe", "200", str(y), "900", str(y), "250")
    time.sleep(1.2)
    shot("9_swipe_done")
    snack = find(lambda t: t.endswith("done") or t.endswith("완료"))
    log("swipe result: " + (snack[0] if snack else "(none)"))
else:
    log("swipe: task not found")
time.sleep(4)

# 3c. Quick add
start("--ei", "tab", "1")
time.sleep(1)
field = find(lambda t: t == "Add task" or t == "할 일 추가")
if field:
    adb("shell", "input", "tap", str(field[1][0]), str(field[1][1]))
    time.sleep(0.8)
    adb("shell", "input", "text", "Quick%sadd%stest")
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(1.2)
    added = find(lambda t: "Quick add test" in t)
    shot("9_quick_add")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    log("quick add: " + (added[0] if added else "not shown"))
    time.sleep(4)
else:
    log("quick add: field not found")

# 3d. Plan
plan = find(lambda t: t == "Plan" or t == "계획")
if plan:
    adb("shell", "input", "tap", str(plan[1][0]), str(plan[1][1]))
    time.sleep(1.5)
    shot("9_plan_dialog")
    apply_btn = find(lambda t: t == "Apply" or t == "적용")
    if apply_btn:
        adb("shell", "input", "tap", str(apply_btn[1][0]), str(apply_btn[1][1]))
        time.sleep(1.5)
        snack = find(lambda t: "planned" in t or "계획됨" in t)
        log("plan result: " + (snack[0] if snack else "(none)"))
        shot("9_plan_applied")
    else:
        log("plan: " + ("no dialog" if not find(lambda t: "Nothing to plan" in t) else "nothing to plan"))
else:
    log("plan: button not found")
time.sleep(4)

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

# 4c. Korean
start("--es", "lang", "ko", "--ei", "tab", "0")
time.sleep(1.5)
shot("7_ko_briefing")
start("--ei", "tab", "1")
time.sleep(1.5)
shot("7_ko_week")
start("--ei", "tab", "2")
time.sleep(1)
shot("7_ko_settings")
start("--ez", "demoNotify", "true")
time.sleep(2)
adb("shell", "cmd", "statusbar", "expand-notifications", check=False)
time.sleep(2)
shot("7_ko_notifications")
adb("shell", "cmd", "statusbar", "collapse", check=False)
adb("shell", "cmd", "notification", "cancel_all", check=False)
start("--es", "lang", "en", "--ei", "tab", "0")

# 5. Notifications
start("--ez", "demoNotify", "true")
time.sleep(2)
adb("shell", "cmd", "statusbar", "expand-notifications", check=False)
time.sleep(2)
shot("5_notifications")
log("alarm buttons: " + ", ".join(t for t in ("Done", "Snooze 1 h", "Tomorrow") if find(lambda x, t=t: x.upper() == t.upper())))
adb("shell", "cmd", "statusbar", "collapse", check=False)

# 5b. Home-screen widget: pin it, accept the launcher dialog, look at the home screen
start("--ez", "pinWidget", "true")
time.sleep(3)
shot("8_widget_dialog")
add = find(lambda t: t.lower().startswith("add"))
if add:
    adb("shell", "input", "tap", str(add[1][0]), str(add[1][1]))
    log("widget: tapped '%s'" % add[0])
else:
    log("widget: no Add button found")
time.sleep(3)
adb("shell", "input", "keyevent", "KEYCODE_HOME")
time.sleep(4)
shot("8_widget_home")

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
