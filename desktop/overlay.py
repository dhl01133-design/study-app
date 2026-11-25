"""Study Overlay: today's to-do list drawn on the main monitor like a watermark.

Windows desktop companion of the Android app's Today widget. Reads the same sources:
Todoist (personal API token) and Google Calendar (secret iCal address).
Click-through, borderless, no taskbar entry; everything is controlled from the tray icon.

  StudyOverlay.exe                  normal run
  StudyOverlay.exe --demo           demo data, nothing is fetched or saved
  StudyOverlay.exe --demo --shot a.png [--lang ko] [--color #222222] [--scale 1.2]   render a PNG and exit
"""
import json
import os
import re
import sys
import threading
import time
from datetime import date, datetime, timedelta

import ics
import web

import updater
from PySide6.QtCore import QLockFile, QObject, QPoint, QPointF, QRect, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import (QAction, QColor, QCursor, QPainterPath, QFont, QFontMetrics, QGuiApplication, QIcon,
                           QLinearGradient, QPainter, QPen, QPixmap)
from PySide6.QtWidgets import (QApplication, QCheckBox, QColorDialog, QComboBox, QDialog, QDialogButtonBox,
                               QFormLayout, QHBoxLayout, QLabel, QLineEdit, QMenu, QPushButton, QSlider,
                               QSystemTrayIcon, QTabWidget, QVBoxLayout, QWidget)

APP = "StudyOverlay"
WIN = sys.platform == "win32"
CFG_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~/.config"), APP)
CFG_FILE = os.path.join(CFG_DIR, "config.json")
CACHE_FILE = os.path.join(CFG_DIR, "cache.json")
DEFAULTS = {"todoist_token": "", "ical_url": "", "github_token": "", "auto_update": True, "lang": "en",
            "text_color": "#111111", "background": "white", "scale": 1.0, "opacity": 0.95, "show_bar": True,
            "corner": "bottom-right", "pos_x": 0, "pos_y": 0, "pos_anchor": "bottom",   # corner or "custom"
            "on_top": True, "hide_fullscreen": False, "cfg_version": 2, "start_with_windows": False, "visible": True,
            "sync_min": 15, "alarms": True, "outline": "medium",   # shadow | thin | medium | thick
            "show_past": True, "show_carried": True, "show_upcoming": True, "only_icampus": False}
ALARM_FILE = os.path.join(CFG_DIR, "alarms.json")
SECRET_KEYS = ("todoist_token", "ical_url", "github_token")
LIST_KEYS = ("show_past", "show_carried", "show_upcoming", "only_icampus")
ALARM_LEADS = (6, 1)   # hours before a real deadline, like the phone app
REFRESH_MIN = 15

# ---------------------------------------------------------------- language

LANG = {"en": True}


def tr(ko, en):
    return en if LANG["en"] else ko


DOW_KO, DOW_EN = "월화수목금토일", ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def md(d):
    return f"{d.month}/{d.day}"


def day_label(d):
    return f"{DOW_EN[d.weekday()]} {md(d)}" if LANG["en"] else f"{md(d)} ({DOW_KO[d.weekday()]})"


def hm(t):
    return f"{t.hour:02d}:{t.minute:02d}"


def until(now, to):
    m = max(0, int((to - now).total_seconds() // 60))
    if m < 60:
        s = tr(f"{m}분", f"{m} min")
    elif m < 1440:
        s = tr(f"{m // 60}시간", f"{m // 60} h") + (tr(f" {m % 60}분", f" {m % 60} min") if m % 60 else "")
    else:
        s = tr(f"{m // 1440}일", f"{m // 1440} d") + (tr(f" {m % 1440 // 60}시간", f" {m % 1440 // 60} h") if m % 1440 >= 60 else "")
    return tr(f"{s} 후", f"in {s}")

# ---------------------------------------------------------------- courses (same as data/Courses.kt)


COURSE_COLORS = {
    "경제학입문": "#2F6FDF", "창의적글쓰기": "#C96A14", "일반화학2": "#23874D", "General Chemistry 2": "#23874D",
    "창의적융합디자인": "#7A45C4", "선형대수학": "#B4420F", "미적분2": "#0B6C84", "Calculus 2": "#0B6C84",
    "영어쓰기": "#B0175A", "영어작문": "#B0175A", "English Writing": "#B0175A",
}
PREFIX = re.compile(r"^\[([^\]]+)]\s*")
CLASS_RE = re.compile(r"수업\s*$|출석")
DEADLINE_RE = re.compile(r"마감|제출|\bdue\b|\bdeadline\b", re.I)
DUE_RE = re.compile(r"(?:Due|due|마감)\s*:?\s*(\d{1,2})/(\d{1,2})(?:\s*(?:at\s*)?(\d{1,2}):(\d{2}))?")


def course_prefix(s):
    m = PREFIX.match(s)
    return m.group(1) if m and m.group(1) in COURSE_COLORS else None


def strip_course(s):
    return (PREFIX.sub("", s, count=1) or s) if course_prefix(s) else s

# ---------------------------------------------------------------- model


class Task:
    def __init__(self, d):
        self.d = d
        self.id = d["id"]
        self.content = d.get("content", "")
        self.description = d.get("description", "")
        self.project = d.get("project", "")
        self.labels = d.get("labels", [])
        self.priority = d.get("priority", 1)        # Todoist API: 4 = p1 … 1 = p4
        self.date = date.fromisoformat(d["date"]) if d.get("date") else None
        self.time = datetime.strptime(d["time"], "%H:%M").time() if d.get("time") else None

    @property
    def course(self):
        return course_prefix(self.content) or ("" if self.project in ("", "Inbox") else self.project)

    @property
    def title(self):
        return strip_course(self.content)

    @property
    def bookkeeping(self):
        return self.content.startswith("🤖")

    @property
    def planned(self):
        return datetime.combine(self.date, self.time or datetime.strptime("23:59", "%H:%M").time()) if self.date else None

    @property
    def deadline(self):
        """Real deadline: "Due 11/22 [HH:MM]" / "마감 11/22" in the description, else the Todoist date."""
        m = DUE_RE.search(self.description)
        if not m:
            return self.planned
        mo, dy, h, mi = m.groups()
        base = self.date or date.today()
        cands = []
        for y in (base.year - 1, base.year, base.year + 1):
            try:
                cands.append(date(y, int(mo), int(dy)))
            except ValueError:
                pass
        if not cands:
            return self.planned
        best = min(cands, key=lambda c: abs((c - base).days))
        hour, minute = (int(h), int(mi)) if h else (23, 59)
        if hour >= 24:   # "24:00" is the end of that day, not its start
            hour, minute = 23, 59
        return datetime(best.year, best.month, best.day, hour, minute)


class Event:
    def __init__(self, d):
        self.summary = d["summary"]
        self.start = datetime.fromisoformat(d["start"])
        self.end = datetime.fromisoformat(d["end"])
        self.all_day = d.get("all_day", False)

    course = property(lambda s: course_prefix(s.summary) or "")
    title = property(lambda s: strip_course(s.summary))
    is_class = property(lambda s: bool(CLASS_RE.search(s.summary)))
    is_deadline = property(lambda s: not s.is_class and bool(DEADLINE_RE.search(s.summary)))

    def occurs_on(self, d):
        if self.all_day:
            last = self.end.date() - timedelta(days=1) if self.end.date() > self.start.date() else self.start.date()
            return self.start.date() <= d <= last
        if self.start == self.end:
            return self.start.date() == d
        day0 = datetime.combine(d, datetime.min.time())
        return self.start < day0 + timedelta(days=1) and self.end > day0


class Row:
    def __init__(self, time, title, course="", kind="event", priority=1, past=False, task_id=None, done=False):
        self.time, self.title, self.course, self.kind, self.priority, self.past = time, title, course, kind, priority, past
        self.task_id, self.done = task_id, done


def build(snapshot, now, opts=None, done=()):
    """Header extras and rows for today, same rules as the app's Brief.todayLines (plus overdue and carried-over tasks).

    opts: the List settings (show_past, show_carried, show_upcoming, only_icampus). done: ids just completed,
    still shown struck through so a second click can undo.
    """
    o = {**{k: DEFAULTS[k] for k in ("show_past", "show_carried", "show_upcoming", "only_icampus")}, **(opts or {})}
    today = now.date()
    tasks = [Task(t) for t in snapshot.get("tasks", [])]
    tasks = [t for t in tasks if not t.bookkeeping and (not o["only_icampus"] or "icampus" in t.labels)]
    events = [Event(e) for e in snapshot.get("events", [])]
    rows = []

    overdue = sorted([(t, t.deadline) for t in tasks if t.deadline and t.deadline.date() < today], key=lambda x: x[1])
    for t, due in overdue:
        rows.append(Row(md(due.date()), t.title, t.course, "overdue", t.priority, task_id=t.id, done=t.id in done))

    for e in sorted([e for e in events if e.occurs_on(today)], key=lambda e: (not e.all_day, e.start)):
        if e.all_day:
            label = tr("종일", "All day")
        elif e.is_deadline:
            label = "~" + hm(e.start)
        elif e.start.date() == today:
            label = f"{hm(e.start)}–{hm(e.end)}"
        else:
            label = "~" + hm(e.end)
        past = not e.all_day and not e.is_deadline and e.end <= now
        if past and not o["show_past"]:
            continue
        rows.append(Row(label, e.title, e.course, "deadline" if e.is_deadline else "event", past=past))

    overdue_ids = {t.id for t, _ in overdue}
    carried = [t for t in tasks if t.date and t.date < today and t.id not in overdue_ids] if o["show_carried"] else []
    todays = [t for t in tasks if t.date == today and t.id not in overdue_ids]
    for t in sorted(carried, key=lambda t: t.planned) + sorted(todays, key=lambda t: t.planned):
        label = md(t.date) if t.date < today else ("~" + hm(t.time) if t.time else "")
        title = t.title
        dl = t.deadline
        if dl and dl.date() != t.date:
            title += tr(f" (마감 {md(dl.date())})", f" (due {md(dl.date())})")
        rows.append(Row(label, title, t.course, "task", t.priority, task_id=t.id, done=t.id in done))

    # Now / Next line, like the widget
    timed = sorted([e for e in events if not e.all_day and not e.is_deadline and e.start != e.end], key=lambda e: e.start)
    cur = next((e for e in timed if e.start <= now < e.end), None)
    nxt = next((e for e in timed if e.start > now and e.start.date() == today), None)
    upcoming = []
    if not o["show_upcoming"]:
        cur = nxt = None
    if cur:
        upcoming.append((tr("지금", "Now"), cur.title, tr(f"~{hm(cur.end)}", f"until {hm(cur.end)}")))
    if nxt:
        upcoming.append((tr("다음", "Next"), nxt.title, until(now, nxt.start)))
    return rows, len([1 for t, _ in overdue if t.id not in done]), upcoming


HW_RE = re.compile(r"hw\s*0*(\d+)", re.I)


def deadlines(snapshot, opts=None):
    """Real deadlines, like the app's Brief.deadlines: open `icampus` tasks (description "Due" or Todoist date) and
    calendar deadline events (all-day ones at 23:59). The same item from both counts once."""
    out, seen = [], set()
    tasks = [Task(t) for t in snapshot.get("tasks", [])]
    items = [(t.course, t.title, t.deadline) for t in tasks if not t.bookkeeping and "icampus" in t.labels and t.deadline]
    for e in (Event(x) for x in snapshot.get("events", [])):
        if e.is_deadline:
            at = datetime.combine(e.start.date(), datetime.strptime("23:59", "%H:%M").time()) if e.all_day else e.start
            items.append((e.course, e.title, at))
    for course, title, at in sorted(items, key=lambda x: x[2]):
        hw = HW_RE.search(title)
        key = f"{course}|{at.date()}|{hw.group(1) if hw else title}"
        if key not in seen:
            seen.add(key)
            out.append(dict(key=key, course=course, title=title, at=at))
    return out


def alarms_due(dls, fired, now):
    """Deadlines whose 6 h or 1 h alarm is due now and hasn't fired. Marks them in `fired` ({key|lead: deadline iso}).
    After a late start only the nearest alarm fires (one notification, not two)."""
    due = []
    for d in dls:
        if d["at"] <= now:
            continue
        open_leads = [h for h in ALARM_LEADS if now >= d["at"] - timedelta(hours=h)]
        if not open_leads:
            continue
        new = [h for h in open_leads if f"{d['key']}|{h}" not in fired]
        for h in open_leads:
            fired[f"{d['key']}|{h}"] = d["at"].isoformat()
        if new:
            due.append(d)
    for k in [k for k, v in fired.items() if datetime.fromisoformat(v) < now - timedelta(days=2)]:
        del fired[k]   # forget alarms for deadlines long past
    return due

# ---------------------------------------------------------------- data sources


def todoist_pages(token, path):
    out, cursor = [], None
    while True:
        params = {"limit": 200}
        if cursor:
            params["cursor"] = cursor
        r = web.request("https://api.todoist.com/api/v1" + path, params=params,
                        headers={"Authorization": f"Bearer {token}"}, timeout=30)
        if r.status_code == 401:
            raise RuntimeError(tr("Todoist 토큰이 올바르지 않음", "Todoist token is invalid"))
        if r.status_code >= 300:
            raise RuntimeError(f"HTTP {r.status_code}")
        j = r.json()
        out += j.get("results", [])
        cursor = j.get("next_cursor")
        if not cursor or len(out) >= 5000:
            return out


def todoist_post(token, path):
    r = web.request("https://api.todoist.com/api/v1" + path, "POST", headers={"Authorization": f"Bearer {token}"}, timeout=30)
    if r.status_code == 401:
        raise RuntimeError(tr("Todoist 토큰이 올바르지 않음", "Todoist token is invalid"))
    if r.status_code >= 300:
        raise RuntimeError(f"HTTP {r.status_code}")


def parse_due(raw):
    if "T" not in raw:
        return raw[:10], None
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if dt.tzinfo:
            dt = dt.astimezone().replace(tzinfo=None)
    except ValueError:
        dt = datetime.fromisoformat(raw[:19])
    return dt.date().isoformat(), hm(dt)


def fetch_tasks(token):
    projects = {p["id"]: p.get("name", "") for p in todoist_pages(token, "/projects")}
    out = []
    for t in todoist_pages(token, "/tasks"):
        due = t.get("due") or {}
        raw = due.get("datetime") or due.get("date")
        d, tm = parse_due(raw) if raw else (None, None)
        out.append({"id": t["id"], "content": t.get("content", ""), "description": t.get("description", ""),
                    "project": projects.get(t.get("project_id"), ""), "labels": t.get("labels", []),
                    "priority": t.get("priority", 1), "date": d, "time": tm})
    return out


def fetch_events(url, today, cache=None):
    """Google's feed holds the whole calendar history; it's only downloaded and parsed again when it changed
    (ETag / Last-Modified, then a content hash) or the day changed. `cache` is updated in place."""
    import hashlib
    cache = cache if cache is not None else {}
    headers = {}
    if cache.get("events") is not None and cache.get("day") == today.isoformat():
        if cache.get("etag"):
            headers["If-None-Match"] = cache["etag"]
        if cache.get("last_modified"):
            headers["If-Modified-Since"] = cache["last_modified"]
    r = web.request(url.strip(), timeout=30, headers=headers)
    if r.status_code == 304 and cache.get("events") is not None:
        return cache["events"]
    if r.status_code >= 300:
        raise RuntimeError(f"HTTP {r.status_code}")
    digest = hashlib.sha1(r.content).hexdigest()
    cache.update(etag=r.headers.get("ETag"), last_modified=r.headers.get("Last-Modified"))
    if digest == cache.get("hash") and cache.get("day") == today.isoformat() and cache.get("events") is not None:
        return cache["events"]
    events = parse_events(r.content, today)
    cache.update(hash=digest, day=today.isoformat(), events=events)
    return events


def parse_events(content, today):
    """The small built-in parser (ics.py) instead of icalendar + recurring_ical_events: same results on the
    days the overlay shows (checked against the library on thousands of random calendars), far less memory."""
    return ics.parse(content.decode("utf-8", "replace"), today - timedelta(days=1), today + timedelta(days=1))


def demo_snapshot():
    t = date.today()
    at = lambda h, m: datetime.combine(t, datetime.min.time()).replace(hour=h, minute=m).isoformat()
    return {"fetched": time.time(), "errors": [], "events": [
        {"summary": "[English Writing] 영어쓰기 수업", "start": at(9, 0), "end": at(10, 15)},
        {"summary": "[창의적글쓰기] 창의적글쓰기 수업", "start": at(12, 0), "end": at(13, 15)},
        {"summary": "[선형대수학] WeBWorK hw11 마감", "start": at(23, 59), "end": at(23, 59)},
        {"summary": "Dentist", "start": at(17, 0), "end": at(17, 30)},
    ], "tasks": [
        {"id": "1", "content": "Report 4", "project": "General Chemistry 2", "priority": 4,
         "date": (t - timedelta(days=2)).isoformat(), "labels": ["icampus"]},
        {"id": "2", "content": "[Calculus 2] 13주차 강의 영상", "project": "Inbox", "priority": 3,
         "date": t.isoformat(), "description": f"Due {md(t + timedelta(days=3))}", "labels": ["icampus"]},
        {"id": "3", "content": "연습문제 12주차", "project": "경제학입문", "priority": 1,
         "date": t.isoformat(), "time": "18:00", "labels": ["icampus"]},
        {"id": "4", "content": "Draft team slides", "project": "창의적융합디자인", "priority": 1,
         "date": (t - timedelta(days=1)).isoformat(), "description": f"Due {md(t + timedelta(days=2))}"},
    ]}

# ---------------------------------------------------------------- config


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return {**default, **json.load(f)}
    except Exception:
        return dict(default)


def save_json(path, data):
    os.makedirs(CFG_DIR, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_registered():
    if not WIN:
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP)
            return True
    except OSError:
        return False


def set_autostart(on):
    """Only ever called when the user ticks or unticks "Start with Windows" in Settings."""
    if not (WIN and getattr(sys, "frozen", False)):
        return
    import winreg
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE)
    try:
        if on:
            winreg.SetValueEx(key, APP, 0, winreg.REG_SZ, f'"{sys.executable}"')
        else:
            try:
                winreg.DeleteValue(key, APP)
            except FileNotFoundError:
                pass
    finally:
        winreg.CloseKey(key)


_WIN_API = {}


def _user32():
    """user32 with argument types set, so 64-bit window and monitor handles aren't truncated."""
    if "u" not in _WIN_API:
        import ctypes
        from ctypes import wintypes
        u = ctypes.WinDLL("user32", use_last_error=True)
        h = ctypes.c_void_p
        u.GetForegroundWindow.restype = h
        u.GetClassNameW.argtypes = [h, ctypes.c_wchar_p, ctypes.c_int]
        u.GetWindowThreadProcessId.argtypes = [h, ctypes.POINTER(wintypes.DWORD)]
        u.GetWindowLongW.argtypes = [h, ctypes.c_int]
        u.GetWindowLongW.restype = ctypes.c_long
        u.GetWindowRect.argtypes = [h, ctypes.POINTER(wintypes.RECT)]
        u.MonitorFromWindow.argtypes = [h, wintypes.DWORD]
        u.MonitorFromWindow.restype = h
        u.GetMonitorInfoW.argtypes = [h, ctypes.c_void_p]
        u.GetWindowLongPtrW.argtypes = [h, ctypes.c_int]
        u.GetWindowLongPtrW.restype = ctypes.c_ssize_t
        u.SetWindowLongPtrW.argtypes = [h, ctypes.c_int, ctypes.c_ssize_t]
        u.SetWindowLongPtrW.restype = ctypes.c_ssize_t
        u.GetAsyncKeyState.argtypes = [ctypes.c_int]
        u.GetAsyncKeyState.restype = ctypes.c_short
        u.SetWindowPos.argtypes = [h, h, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
        u.SetWindowPos.restype = ctypes.c_int

        class MonitorInfo(ctypes.Structure):
            _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                        ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]
        _WIN_API.update(u=u, MonitorInfo=MonitorInfo, ctypes=ctypes, wintypes=wintypes)
    return _WIN_API


def go_light():
    """Below-normal priority and Windows 11 efficiency mode (EcoQoS): the overlay never competes with
    what you're doing."""
    if not WIN:
        return
    try:
        import ctypes
        k = ctypes.windll.kernel32
        proc = k.GetCurrentProcess()
        k.SetPriorityClass(proc, 0x00004000)   # BELOW_NORMAL_PRIORITY_CLASS

        class PowerThrottling(ctypes.Structure):
            _fields_ = [("Version", ctypes.c_ulong), ("ControlMask", ctypes.c_ulong), ("StateMask", ctypes.c_ulong)]
        pt = PowerThrottling(1, 0x1, 0x1)       # PROCESS_POWER_THROTTLING_EXECUTION_SPEED on
        k.SetProcessInformation(proc, 4, ctypes.byref(pt), ctypes.sizeof(pt))   # ProcessPowerThrottling
    except Exception:
        pass


def trim_memory():
    """Frees Python garbage and hands unused pages back to Windows after a sync."""
    import gc
    gc.collect()
    if WIN:
        try:
            import ctypes
            ctypes.windll.psapi.EmptyWorkingSet(ctypes.windll.kernel32.GetCurrentProcess())
        except Exception:
            pass


def ctrl_down():
    if not WIN:
        return False
    try:
        return bool(_user32()["u"].GetAsyncKeyState(0x11) & 0x8000)   # VK_CONTROL
    except Exception:
        return False


DESKTOP_CLASSES = {"Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd"}


def fullscreen_app_running():
    """True while a full-screen app, game, video or presentation covers the main monitor.

    Windows' SHQueryUserNotificationState alone also reports "busy" when the desktop itself has focus
    (clicking through the overlay focuses the desktop), which made the overlay vanish on click (build 4).
    So: D3D full screen / presentation mode count; otherwise the foreground window must be a real app
    window without a title bar that covers the whole primary monitor.
    """
    if not WIN:
        return False
    try:
        return _fullscreen_check()
    except Exception:
        return False


def _fullscreen_check():
    if True:
        api = _user32()
        ctypes, wintypes, u = api["ctypes"], api["wintypes"], api["u"]
        state = ctypes.c_int(0)
        if ctypes.windll.shell32.SHQueryUserNotificationState(ctypes.byref(state)) == 0 and state.value in (3, 4):
            return True   # RUNNING_D3D_FULL_SCREEN, PRESENTATION_MODE
        hwnd = u.GetForegroundWindow()
        if not hwnd:
            return False
        cls = ctypes.create_unicode_buffer(64)
        u.GetClassNameW(hwnd, cls, 64)
        if cls.value in DESKTOP_CLASSES:
            return False
        pid = wintypes.DWORD(0)
        u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value == os.getpid():
            return False
        if u.GetWindowLongW(hwnd, -16) & 0x00C00000 == 0x00C00000:   # GWL_STYLE has WS_CAPTION: a normal window
            return False
        mon = u.MonitorFromWindow(hwnd, 2)   # MONITOR_DEFAULTTONEAREST
        mi = api["MonitorInfo"]()
        mi.cbSize = ctypes.sizeof(mi)
        if not mon or not u.GetMonitorInfoW(mon, ctypes.byref(mi)) or not (mi.dwFlags & 1):
            return False   # only the primary monitor, where the overlay lives
        r, m = wintypes.RECT(), mi.rcMonitor
        u.GetWindowRect(hwnd, ctypes.byref(r))
        return r.left <= m.left and r.top <= m.top and r.right >= m.right and r.bottom >= m.bottom

# ---------------------------------------------------------------- day slice (same rules as brief/DaySlice.kt)

TRAVEL, DAY_START, DAY_END, MIN_FREE, EPS = 1.0, 9.0, 24.0, 0.5, 1e-6
BAR_FIRST, BAR_LAST = 8.0, 24.0


def _merge(spans):
    out = []
    for a, b in sorted(s for s in spans if s[1] > s[0]):
        if out and a <= out[-1][1] + EPS:
            out[-1] = (out[-1][0], max(out[-1][1], b))
        else:
            out.append((a, b))
    return out


def _subtract(spans, cuts):
    for x, z in cuts:
        spans = [p for a, b in spans for p in ([(a, b)] if z <= a or x >= b else [(a, min(b, x)), (max(a, z), b)])
                 if p[1] - p[0] > EPS]
    return spans


def day_slice(snapshot, d):
    """Blocks (start, end, title, is_class), travel spans and free spans for day d, in hours."""
    day0 = datetime.combine(d, datetime.min.time())
    day1 = day0 + timedelta(days=1)
    hours = lambda t: t.hour + t.minute / 60
    blocks = []
    for e in (Event(x) for x in snapshot.get("events", [])):
        if e.all_day or e.is_deadline or e.start == e.end or not e.occurs_on(d):
            continue
        fy, it = e.start < day0, e.end > day1
        blocks.append(dict(a=0.0 if fy else hours(e.start), b=24.0 if it or e.end == day1 else hours(e.end),
                           title=e.title, cls=e.is_class, fy=fy, it=it))
    travel = []
    classes = [b for b in blocks if b["cls"]]
    if classes:
        first, last = min(classes, key=lambda b: b["a"]), max(classes, key=lambda b: b["b"])
        if not first["fy"]:
            travel.append((first["a"] - TRAVEL, first["a"]))
        if not last["it"]:
            travel.append((last["b"], last["b"] + TRAVEL))
    for b in blocks:
        if not b["cls"]:
            if not b["fy"]:
                travel.append((b["a"] - TRAVEL, b["a"]))
            if not b["it"]:
                travel.append((b["b"], b["b"] + TRAVEL))
    busy = _merge([(b["a"], b["b"]) for b in blocks])
    travel_only = _merge(_subtract(_merge(travel), busy))
    free = [s for s in _subtract([(DAY_START, DAY_END)], _merge(busy + travel_only)) if s[1] - s[0] >= MIN_FREE - EPS]
    return blocks, travel_only, free

# ---------------------------------------------------------------- overlay window


W = 462          # layout width in logical px, before the size setting
PAD = 14         # inner margin (the default white panel needs room around the text)
MAX_ROWS = 16
MARGIN = 28


def with_alpha(c, a):
    c = QColor(c)
    c.setAlphaF(a)
    return c


def palette(cfg):
    """Everything derives from the chosen text color; light text means a dark wallpaper."""
    t = QColor(cfg.get("text_color") or DEFAULTS["text_color"])
    if not t.isValid():
        t = QColor(DEFAULTS["text_color"])
    light = t.lightnessF() >= 0.5
    pick = lambda on_dark, on_light: QColor(on_dark if light else on_light)
    good = pick("#4CC98A", "#1F9D63")
    accent = pick("#7FA6FF", "#3B6EF5")
    outline = cfg.get("outline", "medium")
    stroke = OUTLINE_W.get(outline, 0.0)
    edge = QColor("#000000") if light else QColor("#FFFFFF")   # always the opposite of the text
    if stroke:
        sub, faint = mix(t, edge, .25), mix(t, edge, .48)
    else:
        sub, faint = with_alpha(t, .74), with_alpha(t, .45)
    bg = cfg.get("background", "white")
    panel = {"white": QColor(255, 255, 255), "dark": QColor(22, 22, 26)}.get(bg)
    return dict(
        panel=panel, panel_line=QColor(0, 0, 0, 30) if bg == "white" else QColor(255, 255, 255, 30),
        text=t, sub=sub, faint=faint, light=light, stroke=stroke, edge=edge,
        halo=(with_alpha(edge, .9) if stroke else QColor(0, 0, 0, 85) if light else QColor(255, 255, 255, 90)),
        accent=accent, on_accent=pick("#0E0E10", "#FFFFFF"), danger=pick("#FF6B6E", "#D93036"),
        p1=pick("#FF9F43", "#E8740C"), p2=accent, good=good, good_soft=with_alpha(good, .28),
        track=with_alpha(t, .14), busy=with_alpha(t, .80), on_busy=pick("#0E0E10", "#FFFFFF"), hatch=with_alpha(t, .38),
    )


OUTLINE_W = {"thin": 2.4, "medium": 3.6, "thick": 5.2}   # stroke width in layout px; half of it shows outside the glyph


def mix(a, b, f):
    """a blended toward b by f, opaque (outlined text needs solid fills, or the stroke shows through)."""
    return QColor(int(a.red() + (b.red() - a.red()) * f), int(a.green() + (b.green() - a.green()) * f),
                  int(a.blue() + (b.blue() - a.blue()) * f))


def course_color(c, light):
    col = QColor(COURSE_COLORS.get(c, "#5A6270" if not light else "#B8BCC4"))
    if light:   # lighten toward white, like the widget's night colors
        col = QColor(int(col.red() * .5 + 127), int(col.green() * .5 + 127), int(col.blue() * .5 + 127))
    return col


def font(px, bold=False):
    q = QFont()
    q.setFamilies(["Segoe UI", "Malgun Gothic", "Noto Sans CJK KR", "Noto Sans KR"])
    q.setPixelSize(px)
    q.setBold(bold)
    q.setHintingPreference(QFont.PreferNoHinting)
    return q


class Overlay(QWidget):
    def __init__(self, app_state):
        super().__init__()
        self.s = app_state
        self.moving = False
        self._drag = None
        self.on_move_done = None
        self.interactive = False    # Ctrl held over the overlay: rows can be clicked
        self.hits = []              # (QRectF in layout px, task_id) from the last paint
        self._pic = (None, None)    # (key, QPixmap) of the last drawn picture
        self.hover = None
        self.on_task_click = None
        self.setMouseTracking(True)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.apply_flags()

    def apply_flags(self):
        flags = Qt.FramelessWindowHint | Qt.Tool | Qt.NoDropShadowWindowHint
        if not self.moving:
            flags |= Qt.WindowDoesNotAcceptFocus
            if not WIN:   # on Windows click-through is WS_EX_TRANSPARENT, set directly (see enforce_input_style)
                flags |= Qt.WindowTransparentForInput
        flags |= Qt.WindowStaysOnTopHint if (self.s.cfg["on_top"] or self.moving) else Qt.WindowStaysOnBottomHint
        visible = self.isVisible()
        self.interactive = False
        self.setWindowFlags(flags)
        self.setWindowOpacity(float(self.s.cfg["opacity"]))
        if visible:
            self.show()
        self.enforce_input_style()

    def showEvent(self, e):
        super().showEvent(e)
        self.enforce_input_style()

    def enforce_input_style(self):
        """Click-through unless Ctrl-clicking or moving. Qt's own WindowTransparentForInput isn't used on Windows,
        so Qt never drops the clicks; the extended style is toggled in place instead (no flicker)."""
        if not WIN or not self.isVisible():
            return
        try:
            u = _user32()["u"]
            hwnd = int(self.winId())
            style = u.GetWindowLongPtrW(hwnd, -20)   # GWL_EXSTYLE
            want = (style | 0x20 | 0x80000) if not (self.interactive or self.moving) else (style & ~0x20)
            if want != style:   # WS_EX_TRANSPARENT (0x20), WS_EX_LAYERED (0x80000)
                u.SetWindowLongPtrW(hwnd, -20, want)
        except Exception:
            pass

    def keep_on_top(self):
        """Re-asserts topmost. Borderless full-screen apps (games, F11 browsers, video players) often make
        themselves topmost too and end up above the overlay; this puts it back in front (without focus)."""
        if not WIN or not self.isVisible() or not self.s.cfg["on_top"]:
            return
        try:
            u = _user32()["u"]
            # HWND_TOPMOST; SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOOWNERZORDER
            u.SetWindowPos(int(self.winId()), -1, 0, 0, 0, 0, 0x1 | 0x2 | 0x10 | 0x200)
        except Exception:
            pass

    # ---- Ctrl+click
    def set_interactive(self, on):
        self.enforce_input_style()   # also repairs the style if Qt reset it
        if on == self.interactive or self.moving or not WIN:
            return
        self.interactive = on
        self.enforce_input_style()
        self.hover = None
        self.setCursor(Qt.PointingHandCursor) if on else self.unsetCursor()
        self.update()

    def hit(self, pos):
        k = self.scale()
        pt = QPointF(pos.x() / k, pos.y() / k)
        return next((tid for rect, tid in self.hits if rect.contains(pt)), None)

    # ---- moving
    def set_move(self, on):
        if on == self.moving:
            return
        self.moving = on
        self.apply_flags()
        self.show()
        if on:
            self.raise_()
            self.activateWindow()
            self.setFocus()
            self.setCursor(Qt.SizeAllCursor)
        else:
            self.unsetCursor()
            if self.on_move_done:
                self.on_move_done()
        self.update()

    def mousePressEvent(self, e):
        if self.moving and e.button() == Qt.LeftButton:
            self._drag = e.globalPosition().toPoint() - self.frameGeometry().topLeft()
        elif self.interactive and e.button() == Qt.LeftButton:
            tid = self.hit(e.position())
            if tid and self.on_task_click:
                self.on_task_click(tid)

    def mouseMoveEvent(self, e):
        if self.moving and self._drag is not None:
            self.move(self.clamp(e.globalPosition().toPoint() - self._drag))
        elif self.interactive:
            h = self.hit(e.position())
            if h != self.hover:
                self.hover = h
                self.update()

    def mouseReleaseEvent(self, e):
        if self.moving and self._drag is not None:
            self._drag = None
            self.remember_position()

    def mouseDoubleClickEvent(self, e):
        if self.moving:
            self.set_move(False)

    def keyPressEvent(self, e):
        if self.moving and e.key() in (Qt.Key_Escape, Qt.Key_Return, Qt.Key_Enter):
            self.set_move(False)

    def screen_rect(self):
        return QGuiApplication.primaryScreen().availableGeometry()

    def clamp(self, pt):
        r = self.screen_rect()
        return QPoint(min(max(pt.x(), r.left()), r.right() - self.width() + 1),
                      min(max(pt.y(), r.top()), r.bottom() - self.height() + 1))

    def remember_position(self):
        """Saves the dragged position relative to the nearest top or bottom edge, so a growing list stays on screen."""
        r = self.screen_rect()
        g = self.frameGeometry()
        bottom_half = g.center().y() > r.center().y()
        self.s.cfg.update(corner="custom", pos_x=g.left() - r.left(),
                          pos_anchor="bottom" if bottom_half else "top",
                          pos_y=(r.bottom() - g.bottom()) if bottom_half else (g.top() - r.top()))
        self.s.save_cfg()

    # ---- layout
    def scale(self):
        return max(0.6, min(2.0, float(self.s.cfg.get("scale", 1.0))))

    def fixed_height(self, up):
        bar = 48 if self.s.cfg.get("show_bar", True) else 0
        return PAD + 38 + len(up) * 22 + bar + 10 + (22 if self.s.status() else 0) + PAD

    def max_rows(self, n_rows, up):
        """Row cap: MAX_ROWS, fewer when a big size setting would push the list off the screen."""
        avail = self.screen_rect().height() / self.scale() - self.fixed_height(up)
        fit = max(int(avail // 25), 2)
        if n_rows <= min(MAX_ROWS, fit):
            return n_rows
        return max(min(MAX_ROWS, fit) - 1, 1)   # one line goes to "+N more"

    def layout_height(self):
        rows, _, up = self.s.view()
        shown = self.max_rows(len(rows), up)
        n = max(shown + (1 if len(rows) > shown else 0), 1)
        return self.fixed_height(up) + n * 25

    def place(self):
        k = self.scale()
        w, h = int(W * k + 1), int(self.layout_height() * k + 1)
        if (self.width(), self.height()) != (w, h):
            self.resize(w, h)
        if self._drag is not None:
            return
        r = self.screen_rect()
        c = self.s.cfg
        if c["corner"] == "custom":
            x = r.left() + int(c["pos_x"])
            y = r.bottom() - h + 1 - int(c["pos_y"]) if c["pos_anchor"] == "bottom" else r.top() + int(c["pos_y"])
        else:
            x = r.right() - w + 1 - MARGIN if "right" in c["corner"] else r.left() + MARGIN
            y = r.bottom() - h + 1 - MARGIN if "bottom" in c["corner"] else r.top() + MARGIN
        pt = self.clamp(QPoint(x, y))
        if pt != self.pos():
            self.move(pt)

    # ---- drawing
    def picture_key(self):
        return (self.s.view_key(), tuple(sorted((k, str(v)) for k, v in self.s.cfg.items() if k not in SECRET_KEYS)),
                self.interactive, self.hover, self.moving, self.width(), self.height(), self.devicePixelRatioF(),
                self.s.status())

    def paintEvent(self, _):
        """Draws from a cached picture; the outlined text is only re-rendered when something visible changed."""
        key = self.picture_key()
        if self._pic[0] != key:
            dpr = self.devicePixelRatioF()
            pm = QPixmap(int(self.width() * dpr), int(self.height() * dpr))
            pm.setDevicePixelRatio(dpr)
            pm.fill(Qt.transparent)
            q = QPainter(pm)
            q.scale(self.scale(), self.scale())
            self.paint(q)
            q.end()
            self._pic = (key, pm)
        p = QPainter(self)
        p.drawPixmap(0, 0, self._pic[1])
        p.end()

    def refresh_if_changed(self):
        """Repaints only when the picture would differ (the minute ticked, data or settings changed)."""
        self.place()
        if self._pic[0] != self.picture_key():
            self.update()

    def paint(self, p):
        p.setRenderHints(QPainter.Antialiasing | QPainter.TextAntialiasing)
        c = palette(self.s.cfg)
        f_head, f_row, f_up, f_small, f_bar = font(26, True), font(15), font(14), font(12), font(10)
        rows, overdue, upcoming = self.s.view()
        right = W - PAD
        now = self.s.now()
        self.hits = []

        if c["panel"] is not None:   # background panel (default: white)
            p.setPen(QPen(c["panel_line"], 1))
            p.setBrush(c["panel"])
            p.drawRoundedRect(QRectF(0.5, 0.5, W - 1, self.layout_height() - 1), 12, 12)
        if self.moving:   # visible frame so there's something to grab
            p.setPen(QPen(c["accent"], 1.5, Qt.DashLine))
            p.setBrush(with_alpha(c["accent"], .12))
            p.drawRoundedRect(QRectF(1, 1, W - 2, self.layout_height() - 2), 10, 10)
        elif self.interactive and c["panel"] is None:   # faint panel: the whole overlay takes clicks while Ctrl is held
            p.setPen(Qt.NoPen)
            p.setBrush(with_alpha(c["halo"], .35))
            p.drawRoundedRect(QRectF(0, 0, W, self.layout_height()), 10, 10)

        def text(x, y, s, fnt, color, width=None, align=Qt.AlignLeft, strike=False):
            fm = QFontMetrics(fnt)
            if width is not None:
                s = fm.elidedText(s, Qt.ElideRight, int(width))
            adv = fm.horizontalAdvance(s)
            w = width if width is not None else adv + 2
            if c["stroke"]:
                # solid outline in the opposite color: stroke the glyph outlines, then fill on top
                x0 = x + (w - adv if align & Qt.AlignRight else (w - adv) / 2 if align & Qt.AlignHCenter else 0)
                base = y + (fm.height() - fm.ascent() - fm.descent()) / 2 + fm.ascent()
                path = QPainterPath()
                path.addText(QPointF(x0, base), fnt, s)
                if strike:
                    sy = base - fm.strikeOutPos()
                    path.addRect(QRectF(x0, sy - fm.lineWidth() / 2, adv, max(fm.lineWidth(), 1.2)))
                sw = c["stroke"] * min(max(fnt.pixelSize() / 15, 0.7), 1.4)   # thinner on small text, bolder on the date
                p.strokePath(path, QPen(c["edge"], sw, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin))
                p.fillPath(path, color)
                return adv
            if strike:
                fnt = QFont(fnt)
                fnt.setStrikeOut(True)
            p.setFont(fnt)
            rect = QRect(int(x), int(y), int(w), fm.height())
            p.setPen(c["halo"])   # soft shadow
            for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1), (0, 2), (2, 0)):
                p.drawText(rect.translated(dx, dy), align | Qt.AlignVCenter, s)
            p.setPen(color)
            p.drawText(rect, align | Qt.AlignVCenter, s)
            return adv

        y = PAD
        text(PAD, y, day_label(now.date()), f_head, c["text"])
        if overdue:
            text(PAD, y + 6, tr(f"기한 지남 {overdue}", f"{overdue} overdue"), f_row, c["danger"], right - PAD, Qt.AlignRight)
        y += 38
        for label, title, note in upcoming:
            x = PAD + text(PAD, y, label + "  ", f_up, c["sub"])
            nw = QFontMetrics(f_up).horizontalAdvance(note) + 4
            text(x, y, title, f_up, c["text"], right - x - nw - 10)
            text(right - nw, y, note, f_up, c["accent"], nw, Qt.AlignRight)
            y += 22

        if self.s.cfg.get("show_bar", True):
            self.paint_bar(p, c, PAD, y + 8, right - PAD, now, f_bar, text)
            y += 48
        y += 10

        if not rows:
            text(PAD + 22, y, tr("없음", "Nothing"), f_row, c["sub"])
            y += 25
        shown = self.max_rows(len(rows), upcoming)
        for r in rows[:shown]:
            if r.task_id:
                self.hits.append((QRectF(0, y, W, 25), r.task_id))
                if self.interactive and self.hover == r.task_id:
                    p.setPen(Qt.NoPen)
                    p.setBrush(with_alpha(c["text"], .10))
                    p.drawRoundedRect(QRectF(PAD - 2, y, W - 2 * PAD + 4, 25), 5, 5)
            if r.kind in ("task", "overdue"):   # priority circle: orange p1, blue p2; filled once completed
                ring = c["p1"] if r.priority == 4 else c["p2"] if r.priority == 3 else c["sub"]
                p.setBrush(Qt.NoBrush)
                p.setPen(QPen(c["edge"] if c["stroke"] else c["halo"], 1.6 + (c["stroke"] or 1.6)))
                p.drawEllipse(PAD + 2, y + 6, 12, 12)
                p.setPen(QPen(ring, 1.6))
                p.setBrush(ring if r.done else Qt.NoBrush)
                p.drawEllipse(PAD + 2, y + 6, 12, 12)
            dim = r.past or r.done
            tcol = c["faint"] if dim else c["danger"] if r.kind in ("overdue", "deadline") else c["sub"]
            text(PAD + 24, y, r.time, f_row, tcol, 92)
            x = PAD + 24 + 100
            cw = min(QFontMetrics(f_small).horizontalAdvance(r.course) + 4, 130) if r.course else 0
            tw = text(x, y, r.title, f_row, c["faint"] if dim else c["text"], right - x - (cw + 10 if cw else 0), strike=r.done)
            if r.course:
                text(x + tw + 10, y + 1, r.course, f_small, c["faint"] if dim else course_color(r.course, c["light"]), cw)
            y += 25
        if len(rows) > shown:
            text(PAD + 124, y, tr(f"외 {len(rows) - shown}개", f"+{len(rows) - shown} more"), f_row, c["sub"])
            y += 25
        st = self.s.status()
        if st:
            text(PAD + 124, y, st, f_small, c["sub"], right - PAD - 124)

    def paint_bar(self, p, c, x0, y0, w, now, f_bar, text):
        """Today's time bar, 08–24, like the widget: classes blue, other events solid, travel hatched, free green."""
        blocks, travel, free = self.s.slice()
        bh, r = 22, 4
        x = lambda h: x0 + (min(max(h, BAR_FIRST), BAR_LAST) - BAR_FIRST) / (BAR_LAST - BAR_FIRST) * w
        p.setPen(QPen(c["edge"], 1.6) if c["stroke"] else Qt.NoPen)   # edge keeps the bar visible on any wallpaper
        p.setBrush(c["track"])
        p.drawRoundedRect(QRectF(x0, y0, w, bh), r, r)

        p.setPen(QPen(c["hatch"], 1))
        for a, b in travel:
            if b <= BAR_FIRST:
                continue
            p.save()
            p.setClipRect(QRectF(x(a), y0, x(b) - x(a), bh))
            lx = x(a) - bh
            while lx < x(b):
                p.drawLine(QPointF(lx, y0 + bh), QPointF(lx + bh, y0))
                lx += 5
            p.restore()

        for a, b in free:
            p.setPen(QPen(c["good"], 1))
            p.setBrush(c["good_soft"])
            p.drawRoundedRect(QRectF(x(a) + 1, y0 + 1, max(x(b) - x(a) - 2, 1), bh - 2), r, r)

        p.setFont(f_bar)
        fm = QFontMetrics(f_bar)
        for b in blocks:
            if b["b"] <= BAR_FIRST:
                continue
            left, rt = x(b["a"]), max(x(b["b"]), x(b["a"]) + 1)
            p.setPen(Qt.NoPen)
            p.setBrush(c["accent"] if b["cls"] else c["busy"])
            p.drawRoundedRect(QRectF(left, y0, rt - left, bh), r, r)
            if rt - left > 22:
                p.setPen(c["on_accent"] if b["cls"] else c["on_busy"])
                p.drawText(QRectF(left + 3, y0, rt - left - 5, bh), Qt.AlignVCenter | Qt.AlignLeft,
                           fm.elidedText(b["title"], Qt.ElideRight, int(rt - left - 5)))

        hnow = now.hour + now.minute / 60
        if BAR_FIRST <= hnow <= BAR_LAST:
            if c["stroke"]:
                p.setPen(QPen(c["edge"], 4))
                p.drawLine(QPointF(x(hnow), y0 - 3), QPointF(x(hnow), y0 + bh + 3))
            p.setPen(QPen(c["accent"], 2))
            p.drawLine(QPointF(x(hnow), y0 - 2), QPointF(x(hnow), y0 + bh + 2))

        for hr in range(8, 25, 4):
            hx = min(max(x(hr), x0 + 7), x0 + w - 7)
            text(hx - 12, y0 + bh + 2, f"{hr % 24:02d}", f_bar, c["faint"], 24, Qt.AlignHCenter)

# ---------------------------------------------------------------- settings window


class Settings(QDialog):
    """Non-modal, so the overlay can be dragged while it's open. Appearance changes preview live; Cancel undoes them."""

    def __init__(self, st, apply):
        super().__init__()
        self.st, self.apply = st, apply
        self.original = dict(st.cfg)
        self.setWindowTitle(tr("설정", "Settings"))
        self.setMinimumWidth(540)
        tabs = QTabWidget()
        tabs.addTab(self.appearance(), tr("모양", "Appearance"))
        tabs.addTab(self.list_tab(), tr("목록", "List"))
        tabs.addTab(self.behavior(), tr("동작", "Behavior"))
        tabs.addTab(self.accounts(), tr("계정", "Accounts"))
        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        lay = QVBoxLayout(self)
        lay.addWidget(tabs)
        lay.addWidget(bb)

    def live(self, **kw):
        self.st.cfg.update(kw)
        self.apply()

    # ---- tabs
    def appearance(self):
        w = QWidget()
        form = QFormLayout(w)

        self.color_btn = QPushButton()
        self.color_btn.clicked.connect(self.pick_color)
        reset = QPushButton(tr("기본값", "Default"))
        reset.clicked.connect(lambda: self.set_color(DEFAULTS["text_color"]))
        row = QHBoxLayout()
        row.addWidget(self.color_btn, 1)
        row.addWidget(reset)
        form.addRow(tr("글자색", "Text color"), row)
        self.set_color(self.st.cfg["text_color"], live=False)

        self.bg = QComboBox()
        self.bgs = [("none", tr("없음", "None")), ("white", tr("흰색", "White")), ("dark", tr("어두운색", "Dark"))]
        for _, name in self.bgs:
            self.bg.addItem(name)
        keys = [k for k, _ in self.bgs]
        cur = self.st.cfg.get("background", "white")
        self.bg.setCurrentIndex(keys.index(cur) if cur in keys else 1)
        self.bg.currentIndexChanged.connect(self.pick_bg)
        form.addRow(tr("배경", "Background"), self.bg)

        self.outline = QComboBox()
        self.outlines = [("shadow", tr("그림자", "Soft shadow")), ("thin", tr("얇게", "Thin")),
                         ("medium", tr("보통", "Medium")), ("thick", tr("두껍게", "Thick"))]
        for _, name in self.outlines:
            self.outline.addItem(name)
        keys = [k for k, _ in self.outlines]
        cur = self.st.cfg.get("outline", "medium")
        self.outline.setCurrentIndex(keys.index(cur) if cur in keys else 2)
        self.outline.currentIndexChanged.connect(lambda i: self.live(outline=self.outlines[i][0]))
        form.addRow(tr("글자 테두리", "Text outline"), self.outline)

        self.size = self.slider(60, 200, int(round(self.st.cfg["scale"] * 100)), lambda v: self.live(scale=v / 100), "%")
        form.addRow(tr("크기", "Size"), self.size)
        self.opacity = self.slider(25, 100, int(round(self.st.cfg["opacity"] * 100)), lambda v: self.live(opacity=v / 100), "%")
        form.addRow(tr("불투명도", "Opacity"), self.opacity)

        self.corner = QComboBox()
        self.corners = [("top-left", tr("왼쪽 위", "Top left")), ("top-right", tr("오른쪽 위", "Top right")),
                        ("bottom-left", tr("왼쪽 아래", "Bottom left")), ("bottom-right", tr("오른쪽 아래", "Bottom right")),
                        ("custom", tr("직접 지정", "Custom"))]
        for _, name in self.corners:
            self.corner.addItem(name)
        self.sync_corner()
        self.corner.currentIndexChanged.connect(lambda i: self.live(corner=self.corners[i][0]))
        move = QPushButton(tr("끌어서 옮기기", "Drag to move"))
        move.clicked.connect(self.start_move)
        row = QHBoxLayout()
        row.addWidget(self.corner, 1)
        row.addWidget(move)
        form.addRow(tr("위치", "Position"), row)

        self.bar = QCheckBox(tr("오늘 시간 막대", "Today's time bar"))
        self.bar.setChecked(self.st.cfg.get("show_bar", True))
        self.bar.toggled.connect(lambda v: self.live(show_bar=v))
        form.addRow("", self.bar)
        return w

    def list_tab(self):
        w = QWidget()
        form = QFormLayout(w)
        self.check(tr("끝난 일정", "Finished events"), "show_past", form)
        self.check(tr("지난 날로 계획된 할 일", "Tasks planned for earlier days"), "show_carried", form)
        self.check(tr("지금 / 다음", "Now / Next"), "show_upcoming", form)
        self.check(tr("iCampus 할 일만", "Only iCampus tasks"), "only_icampus", form)
        return w

    def behavior(self):
        w = QWidget()
        form = QFormLayout(w)
        self.sync_box = QComboBox()
        self.sync_opts = [5, 15, 30, 60]
        self.sync_box.addItems([tr(f"{m}분마다", f"Every {m} min") for m in self.sync_opts])
        cur = int(self.st.cfg.get("sync_min", 15))
        self.sync_box.setCurrentIndex(self.sync_opts.index(cur) if cur in self.sync_opts else 1)
        self.sync_box.currentIndexChanged.connect(lambda i: self.live(sync_min=self.sync_opts[i]))
        form.addRow(tr("동기화", "Sync"), self.sync_box)
        self.check(tr("마감 알림 (6시간·1시간 전)", "Deadline alarms (6 h and 1 h before)"), "alarms", form)
        self.lang = QComboBox()
        self.lang.addItems(["English", "한국어"])
        self.lang.setCurrentIndex(1 if self.st.cfg["lang"] == "ko" else 0)
        self.lang.currentIndexChanged.connect(lambda i: self.live(lang="ko" if i else "en"))
        form.addRow(tr("언어", "Language"), self.lang)
        self.on_top = self.check(tr("항상 위에", "Always on top"), "on_top", form)
        self.hide_fs = self.check(tr("전체 화면일 때 숨기기", "Hide in full screen"), "hide_fullscreen", form)
        self.auto = QCheckBox(tr("Windows 시작 시 실행", "Start with Windows"))
        self.auto.setChecked(autostart_registered())
        form.addRow("", self.auto)
        return w

    def accounts(self):
        w = QWidget()
        form = QFormLayout(w)
        self.token = self.secret(self.st.cfg["todoist_token"])
        self.ical = self.secret(self.st.cfg["ical_url"])
        self.gh = self.secret(self.st.cfg.get("github_token", ""))
        form.addRow(tr("Todoist 토큰", "Todoist token"), self.token[0])
        form.addRow(tr("캘린더 iCal 주소", "Calendar iCal address"), self.ical[0])
        form.addRow(tr("GitHub 토큰", "GitHub token"), self.gh[0])
        self.upd = QCheckBox(tr("자동 업데이트", "Update automatically"))
        self.upd.setChecked(self.st.cfg.get("auto_update", True))
        form.addRow("", self.upd)
        return w

    # ---- controls
    def slider(self, lo, hi, val, on_change, unit):
        box = QWidget()
        lay = QHBoxLayout(box)
        lay.setContentsMargins(0, 0, 0, 0)
        s = QSlider(Qt.Horizontal)
        s.setRange(lo, hi)
        s.setValue(val)
        lab = QLabel(f"{val}{unit}")
        lab.setMinimumWidth(44)
        s.valueChanged.connect(lambda v: (lab.setText(f"{v}{unit}"), on_change(v)))
        lay.addWidget(s, 1)
        lay.addWidget(lab)
        return box

    def check(self, title, key, form):
        b = QCheckBox(title)
        b.setChecked(self.st.cfg[key])
        b.toggled.connect(lambda v: self.live(**{key: v}))
        form.addRow("", b)
        return b

    def secret(self, value):
        box = QWidget()
        lay = QHBoxLayout(box)
        lay.setContentsMargins(0, 0, 0, 0)
        e = QLineEdit(value)
        e.setEchoMode(QLineEdit.Password)
        b = QPushButton(tr("보기", "Show"))
        b.setFlat(True)

        def toggle():
            hidden = e.echoMode() == QLineEdit.Password
            e.setEchoMode(QLineEdit.Normal if hidden else QLineEdit.Password)
            b.setText(tr("숨기기", "Hide") if hidden else tr("보기", "Show"))
        b.clicked.connect(toggle)
        lay.addWidget(e)
        lay.addWidget(b)
        return box, e

    def pick_bg(self, i):
        key = self.bgs[i][0]
        match = {"white": "#111111", "dark": "#F4F4F5"}.get(key)   # readable text for that panel
        self.st.cfg["background"] = key
        if match:
            self.set_color(match)
        else:
            self.live()

    def set_color(self, hexstr, live=True):
        col = QColor(hexstr)
        pm = QPixmap(14, 14)
        pm.fill(col)
        self.color_btn.setIcon(QIcon(pm))
        self.color_btn.setText(col.name().upper())
        if live:
            self.live(text_color=col.name())

    def pick_color(self):
        col = QColorDialog.getColor(QColor(self.st.cfg["text_color"]), self, tr("글자색", "Text color"))
        if col.isValid():
            self.set_color(col.name())

    def sync_corner(self):
        keys = [k for k, _ in self.corners]
        self.corner.blockSignals(True)
        self.corner.setCurrentIndex(keys.index(self.st.cfg["corner"]) if self.st.cfg["corner"] in keys else 3)
        self.corner.blockSignals(False)

    def start_move(self):
        self.st.start_move()

    def values(self):
        clean = lambda s: s.strip().strip("'\"").replace(" ", "")
        return {"todoist_token": clean(self.token[1].text()), "ical_url": clean(self.ical[1].text()),
                "github_token": clean(self.gh[1].text()), "auto_update": self.upd.isChecked(),
                "start_with_windows": self.auto.isChecked()}

# ---------------------------------------------------------------- app


class Bus(QObject):
    fetched = Signal(dict)
    task_op = Signal(str, str, str)   # task id, "close"/"reopen", error text ("" = ok)
    update = Signal(object, bool)     # (build, asset_id, size) or error text, manual?
    installed = Signal(object)        # path to start, or error text


class State:
    def __init__(self, demo=False, fixed_now=None):
        self.demo = demo
        self.fixed_now = fixed_now
        self.cfg = dict(DEFAULTS) if demo else load_json(CFG_FILE, DEFAULTS)
        raw = {} if demo else load_json(CFG_FILE, {})
        if raw and raw.get("cfg_version", 1) < 2:
            # build 9 (user, 2026-11-25): white background with dark text by default, and stay visible in full screen
            self.cfg.update(background="white", hide_fullscreen=False, cfg_version=2)
            if raw.get("text_color", "#F4F4F5").upper() == "#F4F4F5":
                self.cfg["text_color"] = "#111111"
            self.save_cfg()
        LANG["en"] = self.cfg["lang"] != "ko"
        self.snap = demo_snapshot() if demo else load_json(CACHE_FILE, {"fetched": 0, "tasks": [], "events": [], "errors": []})
        self.busy = False
        self.start_move = lambda: None
        self.done = {}   # task id -> completion time; shown struck through until purged
        self.version = 0
        self._view = (None, None)
        self._slice = (None, None)
        self.ical = {}   # iCal download cache: etag, last_modified, hash, day, events

    def now(self):
        return self.fixed_now or datetime.now()

    def bump(self):
        """Call after the snapshot changes; cached views and the drawn picture are rebuilt."""
        self.version += 1

    def view_key(self):
        n = self.now()
        return (self.version, n.date(), n.hour, n.minute, tuple(self.cfg.get(k) for k in LIST_KEYS + ("lang",)),
                frozenset(self.done))

    def view(self):
        """build() runs once per minute or data change, not on every layout/paint call."""
        key = self.view_key()
        if self._view[0] != key:
            self._view = (key, build(self.snap, self.now(), self.cfg, self.done))
        return self._view[1]

    def slice(self):
        key = (self.version, self.now().date())
        if self._slice[0] != key:
            self._slice = (key, day_slice(self.snap, self.now().date()))
        return self._slice[1]

    def save_cfg(self):
        if not self.demo:
            save_json(CFG_FILE, self.cfg)

    def status(self):
        if self.demo:
            return ""
        if not self.cfg["todoist_token"] and not self.cfg["ical_url"]:
            return tr("트레이 아이콘 → 설정", "Tray icon → Settings")
        errs = self.snap.get("errors") or []
        return "; ".join(errs)[:120] if errs else ""


def fetch_all(cfg, today, ical_cache=None):
    snap, errors = {"fetched": time.time(), "tasks": [], "events": []}, []
    if cfg["todoist_token"]:
        try:
            snap["tasks"] = fetch_tasks(cfg["todoist_token"])
        except Exception as e:
            errors.append(f"Todoist: {e}" if "token" not in str(e) else str(e))
            snap["tasks"] = None
    if cfg["ical_url"]:
        try:
            snap["events"] = fetch_events(cfg["ical_url"], today, ical_cache)
        except Exception as e:
            errors.append(tr("캘린더: ", "Calendar: ") + type(e).__name__)
            snap["events"] = None
    snap["errors"] = errors
    return snap


def tray_icon():
    pm = QPixmap(64, 64)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setPen(QPen(QColor("#3B6EF5"), 9))
    p.drawEllipse(10, 10, 44, 44)
    p.end()
    return QIcon(pm)


def render_shot(state, path):
    ov = Overlay(state)
    h = ov.layout_height()
    k = 2 * ov.scale()
    pm = QPixmap(int(W * k) + 80, int(h * k) + 80)
    g = QLinearGradient(0, 0, pm.width(), pm.height())
    if palette(state.cfg)["light"]:
        g.setColorAt(0, QColor("#1e2a3a")); g.setColorAt(1, QColor("#4b5d4a"))
    else:
        g.setColorAt(0, QColor("#dfe6ee")); g.setColorAt(1, QColor("#f4efe6"))
    p = QPainter(pm)
    p.fillRect(pm.rect(), g)
    p.translate(40, 40)
    p.scale(k, k)
    p.setOpacity(float(state.cfg["opacity"]))
    ov.paint(p)
    p.end()
    pm.save(path)


def main():
    args = sys.argv[1:]
    demo = "--demo" in args
    QGuiApplication.setHighDpiScaleFactorRoundingPolicy(Qt.HighDpiScaleFactorRoundingPolicy.PassThrough)
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    go_light()

    if "--check-tz" in args:   # build machine: the bundled exe can read calendar time zones (tzdata)
        ok = ics._zone("America/New_York") is not None and ics._zone("Asia/Seoul") is not None
        with open(args[args.index("--check-tz") + 1], "w") as f:
            f.write("tz ok" if ok else "tz MISSING")
        return 0

    if "--shot" in args:
        st = State(demo=True, fixed_now=datetime.combine(date.today(), datetime.min.time()).replace(hour=11, minute=20))
        arg = lambda k: args[args.index(k) + 1] if k in args else None
        if arg("--lang"):
            st.cfg["lang"] = arg("--lang")
            LANG["en"] = st.cfg["lang"] != "ko"
        if arg("--color"):
            st.cfg["text_color"] = arg("--color")
        if arg("--scale"):
            st.cfg["scale"] = float(arg("--scale"))
        render_shot(st, arg("--shot"))
        return 0

    if not demo:
        os.makedirs(CFG_DIR, exist_ok=True)
        lock = QLockFile(os.path.join(CFG_DIR, "running.lock"))
        if not lock.tryLock(100):
            return 0
        app._lock = lock
        updater.cleanup()

    st = State(demo=demo)
    ov = Overlay(st)
    bus = Bus()
    last_day = [st.now().date()]
    dialog = [None]

    def refresh():
        ov.refresh_if_changed()

    def on_fetched(snap):
        st.busy = False
        old = st.snap
        for k in ("tasks", "events"):   # keep the last good data when one source fails
            if snap[k] is None:
                snap[k] = old.get(k, [])
        changed = {k: snap[k] for k in ("tasks", "events")} != {k: old.get(k) for k in ("tasks", "events")}
        st.snap = snap
        if changed:
            st.bump()
            if not st.demo:
                save_json(CACHE_FILE, snap)
        check_alarms()
        trim_memory()
        if snap["errors"]:   # e.g. just after login, before Wi-Fi is up: try again soon, backing off to 10 min
            retry[0] = min(retry[0] * 2, 600) if retry[0] else 60
            retry_timer.start(retry[0] * 1000)
        else:
            retry[0] = 0
            retry_timer.stop()
        refresh()
    bus.fetched.connect(on_fetched)
    retry = [0]
    retry_timer = QTimer()
    retry_timer.setSingleShot(True)
    retry_timer.timeout.connect(lambda: sync())

    def sync():
        if st.demo or st.busy:
            return
        st.busy = True
        cfg, today = dict(st.cfg), st.now().date()
        threading.Thread(target=lambda: bus.fetched.emit(fetch_all(cfg, today, st.ical)), daemon=True).start()

    def apply_cfg():
        LANG["en"] = st.cfg["lang"] != "ko"
        build_menu()
        ov.apply_flags()
        t2.start(max(5, int(st.cfg.get("sync_min", 15))) * 60_000)
        refresh()
        visibility()

    # ---- moving
    def start_move():
        st.cfg["visible"] = True
        visibility()
        ov.set_move(True)
        build_menu()

    def move_done():
        build_menu()
        if dialog[0]:
            dialog[0].sync_corner()
    st.start_move = start_move

    # ---- Ctrl+click to complete; a second click within 8 s undoes it
    UNDO_MS = 8000

    def on_task_click(tid):
        tok = st.cfg["todoist_token"]
        if st.demo:
            op = "reopen" if tid in st.done else "close"
            bus.task_op.emit(tid, op + "-local", "")
            return
        if not tok:
            return
        op = "reopen" if tid in st.done else "close"
        if op == "close":
            st.done[tid] = time.time()
        else:
            st.done.pop(tid, None)
        refresh()

        def work():
            try:
                todoist_post(tok, f"/tasks/{tid}/{op}")
                bus.task_op.emit(tid, op, "")
            except Exception as e:
                bus.task_op.emit(tid, op, str(e) or type(e).__name__)
        threading.Thread(target=work, daemon=True).start()

    def on_task_op(tid, op, err):
        if op.endswith("-local"):   # demo mode: no Todoist
            op = op[:-6]
            if op == "close":
                st.done[tid] = time.time()
            else:
                st.done.pop(tid, None)
        elif err:
            if op == "close":
                st.done.pop(tid, None)
            else:
                st.done[tid] = time.time()
            tray.showMessage("Study Overlay", err)
            refresh()
            return
        if op == "close":
            QTimer.singleShot(UNDO_MS, lambda: purge(tid))
        refresh()
    bus.task_op.connect(on_task_op)

    def purge(tid):
        t0 = st.done.get(tid)
        if t0 is None or time.time() - t0 < UNDO_MS / 1000 - 0.5:
            return   # undone, or completed again later (that click has its own timer)
        st.done.pop(tid, None)
        st.snap["tasks"] = [t for t in st.snap.get("tasks", []) if t["id"] != tid]
        st.bump()
        if not st.demo:
            save_json(CACHE_FILE, st.snap)
        refresh()
    ov.on_task_click = on_task_click

    def poll_ctrl():   # Ctrl held with the pointer over the list makes it clickable
        over = ov.isVisible() and not ov.moving and ov.frameGeometry().contains(QCursor.pos())
        ov.set_interactive(over and ctrl_down())
        t5.setInterval(60 if over else 400)   # quick to react near the list, idle elsewhere

    # ---- deadline alarms
    fired = load_json(ALARM_FILE, {}) if not demo else {}

    def check_alarms():
        if st.demo or not st.cfg.get("alarms", True):
            return
        now = st.now()
        due = alarms_due(deadlines(st.snap), fired, now)
        save_json(ALARM_FILE, fired)
        if not due:
            return
        first = due[0]
        title = tr("마감 ", "Due ") + until(now, first["at"])
        body = "\n".join((f"[{d['course']}] " if d["course"] else "") + d["title"] + ("" if d is first else f"  {hm(d['at'])}")
                         for d in due[:5])
        tray.showMessage(title, body, QSystemTrayIcon.Information, 15000)
    ov.on_move_done = move_done

    # ---- settings
    def open_settings():
        if dialog[0]:
            dialog[0].raise_()
            dialog[0].activateWindow()
            return
        dlg = Settings(st, apply_cfg)
        dialog[0] = dlg
        before_auto = autostart_registered()
        before_accounts = (st.cfg["todoist_token"], st.cfg["ical_url"], st.cfg.get("github_token"))

        def done(ok):
            dialog[0] = None
            ov.set_move(False)
            if not ok:
                st.cfg.clear()
                st.cfg.update(dlg.original)   # Cancel undoes every preview, including a drag
                st.save_cfg()
                apply_cfg()
                return
            st.cfg.update(dlg.values())
            st.save_cfg()
            if st.cfg["start_with_windows"] != before_auto:   # touch the registry only on an explicit change
                try:
                    set_autostart(st.cfg["start_with_windows"])
                except Exception:
                    pass
            apply_cfg()
            if (st.cfg["todoist_token"], st.cfg["ical_url"], st.cfg.get("github_token")) != before_accounts:
                sync()
                check_updates()
        dlg.finished.connect(lambda r: done(r == QDialog.Accepted))
        dlg.show()
        dlg.raise_()
        dlg.activateWindow()

    # ---- updates
    tray = QSystemTrayIcon(tray_icon())
    tray.setToolTip(f"Study Overlay build {updater.BUILD}")

    def check_updates(manual=False):
        tok = st.cfg.get("github_token", "")
        if st.demo or not updater.FROZEN:
            return
        if not tok:
            if manual:
                tray.showMessage("Study Overlay", tr("설정 → 계정에 GitHub 토큰을 입력하세요", "Add a GitHub token in Settings → Accounts"))
            return

        def work():
            try:
                bus.update.emit(updater.check(tok), manual)
            except Exception as e:
                bus.update.emit(str(e), manual)
        threading.Thread(target=work, daemon=True).start()

    def on_update(res, manual):
        if isinstance(res, str):
            if manual:
                tray.showMessage("Study Overlay", res)
            return
        build_no, asset_id, size = res
        if build_no <= updater.BUILD:
            if manual:
                tray.showMessage("Study Overlay", tr(f"최신 버전 (build {updater.BUILD})", f"Up to date (build {updater.BUILD})"))
            return
        if not (manual or st.cfg.get("auto_update", True)):
            return
        tok = st.cfg["github_token"]

        def work():
            try:
                bus.installed.emit(updater.install(tok, asset_id, size))
            except Exception as e:
                bus.installed.emit(tr("업데이트 실패: ", "Update failed: ") + str(e))
        threading.Thread(target=work, daemon=True).start()

    def on_installed(res):
        if not os.path.exists(str(res)):
            tray.showMessage("Study Overlay", str(res))
            return
        tray.hide()
        getattr(app, "_lock", None) and app._lock.unlock()
        updater.launch(res)
        app.quit()
    bus.update.connect(on_update)
    bus.installed.connect(on_installed)

    # ---- tray menu
    menu = QMenu()

    def build_menu():
        menu.clear()
        menu.addAction(tr("동기화", "Sync"), sync)
        shown = QAction(tr("표시", "Show"), menu, checkable=True, checked=st.cfg.get("visible", True))
        shown.toggled.connect(lambda v: (st.cfg.__setitem__("visible", v), st.save_cfg(), visibility()))
        menu.addAction(shown)
        if ov.moving:
            menu.addAction(tr("옮기기 완료", "Done moving"), lambda: ov.set_move(False))
        else:
            menu.addAction(tr("옮기기", "Move"), start_move)
        menu.addSeparator()
        menu.addAction(tr("업데이트 확인", "Check for updates"), lambda: check_updates(True))
        menu.addAction(tr("설정", "Settings"), open_settings)
        menu.addAction(tr("종료", "Quit"), app.quit)
    build_menu()
    tray.setContextMenu(menu)
    tray.activated.connect(lambda r: r == QSystemTrayIcon.Trigger and sync())
    tray.show()

    def visibility():
        want = ov.moving or (st.cfg.get("visible", True) and not (st.cfg["hide_fullscreen"] and fullscreen_app_running()))
        if want and not ov.isVisible():
            refresh()
            ov.show()
        elif not want and ov.isVisible():
            ov.hide()
        if want:
            ov.keep_on_top()

    def tick():   # every 30 s: redraw (Now/Next, time bar, past events), day rollover, alarms
        d = st.now().date()
        if d != last_day[0]:
            last_day[0] = d
            sync()
        refresh()
        check_alarms()

    # once a minute, just after the minute starts, so the clock and Now/Next change on time
    t1 = QTimer(); t1.timeout.connect(tick)

    def start_minutely():
        tick()
        t1.start(60_000)
    QTimer.singleShot(int((60 - datetime.now().second) * 1000 + 300), start_minutely)
    t2 = QTimer(); t2.timeout.connect(sync); t2.start(max(5, int(st.cfg.get("sync_min", REFRESH_MIN))) * 60_000)
    t5 = QTimer(); t5.timeout.connect(poll_ctrl); t5.start(80)
    t3 = QTimer(); t3.timeout.connect(visibility); t3.setTimerType(Qt.CoarseTimer); t3.start(2_000)
    t4 = QTimer(); t4.timeout.connect(check_updates); t4.start(6 * 3600_000)
    QTimer.singleShot(20_000, check_updates)
    app.primaryScreenChanged.connect(lambda _: refresh())
    for s in app.screens():
        s.availableGeometryChanged.connect(lambda _: refresh())

    refresh()
    visibility()
    def settle():   # after start-up allocations settle: free them, and stop the GC from re-scanning what stays
        trim_memory()
        import gc
        gc.freeze()
    QTimer.singleShot(15_000, settle)
    if not demo and not st.cfg["todoist_token"] and not st.cfg["ical_url"]:
        QTimer.singleShot(300, open_settings)
    else:
        sync()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
