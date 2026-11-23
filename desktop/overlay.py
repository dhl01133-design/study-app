"""Study Overlay: today's to-do list drawn on the main monitor like a watermark.

Windows desktop companion of the Android app's Today widget. Reads the same sources:
Todoist (personal API token) and Google Calendar (secret iCal address).
Click-through, borderless, no taskbar entry; everything is controlled from the tray icon.

  StudyOverlay.exe                  normal run
  StudyOverlay.exe --demo           demo data, nothing is fetched or saved
  StudyOverlay.exe --demo --shot a.png [--lang ko] [--text dark]   render once to a PNG and exit
"""
import json
import os
import re
import sys
import threading
import time
from datetime import date, datetime, timedelta

import requests
from PySide6.QtCore import QLockFile, QObject, QPoint, QRect, Qt, QTimer, Signal
from PySide6.QtGui import (QAction, QActionGroup, QColor, QFont, QFontMetrics, QGuiApplication, QIcon,
                           QLinearGradient, QPainter, QPen, QPixmap)
from PySide6.QtWidgets import (QApplication, QCheckBox, QDialog, QDialogButtonBox, QFormLayout, QHBoxLayout,
                               QLineEdit, QMenu, QPushButton, QSystemTrayIcon, QWidget)

APP = "StudyOverlay"
WIN = sys.platform == "win32"
CFG_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~/.config"), APP)
CFG_FILE = os.path.join(CFG_DIR, "config.json")
CACHE_FILE = os.path.join(CFG_DIR, "cache.json")
DEFAULTS = {"todoist_token": "", "ical_url": "", "lang": "en", "corner": "bottom-right", "opacity": 0.9,
            "on_top": True, "hide_fullscreen": True, "text": "light", "autostart": True, "visible": True}
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
        return datetime(best.year, best.month, best.day, int(h) % 24 if h else 23, int(mi) if h else 59)


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
    def __init__(self, time, title, course="", kind="event", priority=1, past=False):
        self.time, self.title, self.course, self.kind, self.priority, self.past = time, title, course, kind, priority, past


def build(snapshot, now):
    """Header extras and rows for today, same rules as the app's Brief.todayLines (plus overdue and carried-over tasks)."""
    today = now.date()
    tasks = [Task(t) for t in snapshot.get("tasks", [])]
    tasks = [t for t in tasks if not t.bookkeeping]
    events = [Event(e) for e in snapshot.get("events", [])]
    rows = []

    overdue = sorted([(t, t.deadline) for t in tasks if t.deadline and t.deadline.date() < today], key=lambda x: x[1])
    for t, due in overdue:
        rows.append(Row(md(due.date()), t.title, t.course, "overdue", t.priority))

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
        rows.append(Row(label, e.title, e.course, "deadline" if e.is_deadline else "event", past=past))

    overdue_ids = {t.id for t, _ in overdue}
    carried = [t for t in tasks if t.date and t.date < today and t.id not in overdue_ids]
    todays = [t for t in tasks if t.date == today and t.id not in overdue_ids]
    for t in sorted(carried, key=lambda t: t.planned) + sorted(todays, key=lambda t: t.planned):
        label = md(t.date) if t.date < today else ("~" + hm(t.time) if t.time else "")
        title = t.title
        dl = t.deadline
        if dl and dl.date() != t.date:
            title += tr(f" (마감 {md(dl.date())})", f" (due {md(dl.date())})")
        rows.append(Row(label, title, t.course, "task", t.priority))

    # Now / Next line, like the widget
    timed = sorted([e for e in events if not e.all_day and not e.is_deadline and e.start != e.end], key=lambda e: e.start)
    cur = next((e for e in timed if e.start <= now < e.end), None)
    nxt = next((e for e in timed if e.start > now and e.start.date() == today), None)
    upcoming = []
    if cur:
        upcoming.append((tr("지금", "Now"), cur.title, tr(f"~{hm(cur.end)}", f"until {hm(cur.end)}")))
    if nxt:
        upcoming.append((tr("다음", "Next"), nxt.title, until(now, nxt.start)))
    return rows, len(overdue), upcoming

# ---------------------------------------------------------------- data sources


def todoist_pages(token, path):
    out, cursor = [], None
    while True:
        params = {"limit": 200}
        if cursor:
            params["cursor"] = cursor
        r = requests.get("https://api.todoist.com/api/v1" + path, params=params,
                         headers={"Authorization": f"Bearer {token}"}, timeout=30)
        if r.status_code == 401:
            raise RuntimeError(tr("Todoist 토큰이 올바르지 않음", "Todoist token is invalid"))
        r.raise_for_status()
        j = r.json()
        out += j.get("results", [])
        cursor = j.get("next_cursor")
        if not cursor or len(out) >= 5000:
            return out


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


def fetch_events(url, today):
    import icalendar
    import recurring_ical_events
    r = requests.get(url.strip(), timeout=30)
    r.raise_for_status()
    cal = icalendar.Calendar.from_ical(r.content)
    out = []
    for ev in recurring_ical_events.of(cal).between(today - timedelta(days=1), today + timedelta(days=2)):
        s = ev.get("DTSTART").dt
        e = ev.get("DTEND").dt if ev.get("DTEND") else None
        all_day = not isinstance(s, datetime)
        if all_day:
            s = datetime.combine(s, datetime.min.time())
            e = datetime.combine(e, datetime.min.time()) if e else s + timedelta(days=1)
        else:
            s = s.astimezone().replace(tzinfo=None) if s.tzinfo else s
            if e is None:
                e = s
            elif e.tzinfo:
                e = e.astimezone().replace(tzinfo=None)
        out.append({"summary": str(ev.get("SUMMARY", "")), "start": s.isoformat(), "end": e.isoformat(), "all_day": all_day})
    return out


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


def set_autostart(on):
    if not (WIN and getattr(sys, "frozen", False)):
        return
    import winreg
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE)
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


def fullscreen_app_running():
    """True while a full-screen app, game or presentation is in front (Windows' own notification-quiet check)."""
    if not WIN:
        return False
    import ctypes
    state = ctypes.c_int(0)
    if ctypes.windll.shell32.SHQueryUserNotificationState(ctypes.byref(state)) != 0:
        return False
    return state.value in (2, 3, 4)   # BUSY, RUNNING_D3D_FULL_SCREEN, PRESENTATION_MODE

# ---------------------------------------------------------------- overlay window


W = 440          # logical px
PAD = 4
MAX_ROWS = 16


class Overlay(QWidget):
    def __init__(self, app_state):
        super().__init__()
        self.s = app_state
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.apply_flags()

    def apply_flags(self):
        flags = (Qt.FramelessWindowHint | Qt.Tool | Qt.WindowTransparentForInput | Qt.WindowDoesNotAcceptFocus
                 | Qt.NoDropShadowWindowHint)
        flags |= Qt.WindowStaysOnTopHint if self.s.cfg["on_top"] else Qt.WindowStaysOnBottomHint
        visible = self.isVisible()
        self.setWindowFlags(flags)
        self.setWindowOpacity(float(self.s.cfg["opacity"]))
        if visible:
            self.show()

    # -- palette: light text for dark wallpapers (default) or dark text for light ones
    def colors(self):
        if self.s.cfg["text"] == "dark":
            return dict(text=QColor("#111111"), sub=QColor("#4a4a50"), faint=QColor(0, 0, 0, 110), accent=QColor("#3B6EF5"),
                        danger=QColor("#D93036"), p1=QColor("#E8740C"), p2=QColor("#3B6EF5"),
                        halo=QColor(255, 255, 255, 70), light_course=False)
        return dict(text=QColor("#F4F4F5"), sub=QColor("#C2C2C8"), faint=QColor(255, 255, 255, 120), accent=QColor("#7FA6FF"),
                    danger=QColor("#FF6B6E"), p1=QColor("#FF9F43"), p2=QColor("#7FA6FF"),
                    halo=QColor(0, 0, 0, 80), light_course=True)

    def course_color(self, c, light):
        col = QColor(COURSE_COLORS.get(c, "#5A6270" if not light else "#B8BCC4"))
        if light:   # lighten toward white, like the widget's night colors
            col = QColor(int(col.red() * .5 + 127), int(col.green() * .5 + 127), int(col.blue() * .5 + 127))
        return col

    def fonts(self):
        def f(px, bold=False):
            q = QFont()
            q.setFamilies(["Segoe UI", "Malgun Gothic", "Noto Sans CJK KR", "Noto Sans KR"])
            q.setPixelSize(px)
            q.setBold(bold)
            q.setHintingPreference(QFont.PreferNoHinting)
            return q
        return f(26, True), f(15), f(14), f(12)

    def layout_height(self):
        rows, _, up = self.s.view()
        n = min(len(rows), MAX_ROWS) + (1 if len(rows) > MAX_ROWS else 0)
        n = max(n, 1)
        return PAD + 38 + len(up) * 22 + 10 + n * 25 + (22 if self.s.status() else 0) + PAD

    def place(self):
        h = self.layout_height()
        self.resize(W, h)
        scr = QGuiApplication.primaryScreen().availableGeometry()
        m = 28
        corner = self.s.cfg["corner"]
        x = scr.right() - W - m if "right" in corner else scr.left() + m
        y = scr.bottom() - h - m if "bottom" in corner else scr.top() + m
        self.move(QPoint(x, y))

    def paintEvent(self, _):
        p = QPainter(self)
        self.paint(p)
        p.end()

    def paint(self, p):
        p.setRenderHints(QPainter.Antialiasing | QPainter.TextAntialiasing)
        c = self.colors()
        f_head, f_row, f_up, f_small = self.fonts()
        rows, overdue, upcoming = self.s.view()
        right = W - PAD

        def text(x, y, s, font, color, width=None, align=Qt.AlignLeft):
            p.setFont(font)
            fm = QFontMetrics(font)
            if width is not None:
                s = fm.elidedText(s, Qt.ElideRight, int(width))
            w = width if width is not None else fm.horizontalAdvance(s) + 2
            rect = QRect(int(x), int(y), int(w), fm.height())
            p.setPen(c["halo"])   # soft halo keeps text readable on any wallpaper
            for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1), (0, 2), (2, 0)):
                p.drawText(rect.translated(dx, dy), align | Qt.AlignVCenter, s)
            p.setPen(color)
            p.drawText(rect, align | Qt.AlignVCenter, s)
            return fm.horizontalAdvance(s)

        y = PAD
        text(PAD, y, day_label(self.s.now().date()), f_head, c["text"])
        if overdue:
            text(PAD, y + 6, tr(f"기한 지남 {overdue}", f"{overdue} overdue"), f_row, c["danger"], right - PAD, Qt.AlignRight)
        y += 38
        for label, title, note in upcoming:
            x = PAD + text(PAD, y, label + "  ", f_up, c["sub"])
            nw = QFontMetrics(f_up).horizontalAdvance(note) + 4
            text(x, y, title, f_up, c["text"], right - x - nw - 10)
            text(right - nw, y, note, f_up, c["accent"], nw, Qt.AlignRight)
            y += 22
        y += 10

        if not rows:
            text(PAD + 22, y, tr("없음", "Nothing"), f_row, c["sub"])
            y += 25
        for r in rows[:MAX_ROWS]:
            # priority circle for tasks: orange p1, blue p2, plain otherwise
            if r.kind in ("task", "overdue"):
                ring = c["p1"] if r.priority == 4 else c["p2"] if r.priority == 3 else c["sub"]
                p.setPen(QPen(c["halo"], 3.2))
                p.setBrush(Qt.NoBrush)
                p.drawEllipse(PAD + 2, y + 6, 12, 12)
                p.setPen(QPen(ring, 1.6))
                p.drawEllipse(PAD + 2, y + 6, 12, 12)
            faint = r.past
            tcol = c["danger"] if r.kind in ("overdue", "deadline") else c["faint"] if faint else c["sub"]
            text(PAD + 24, y, r.time, f_row, tcol, 92)
            x = PAD + 24 + 100
            cw = 0
            if r.course:
                cw = min(QFontMetrics(f_small).horizontalAdvance(r.course) + 4, 130)
            tw = text(x, y, r.title, f_row, c["faint"] if faint else c["text"], right - x - (cw + 10 if cw else 0))
            if r.course:
                col = c["faint"] if faint else self.course_color(r.course, c["light_course"])
                text(x + tw + 10, y + 1, r.course, f_small, col, cw)
            y += 25
        if len(rows) > MAX_ROWS:
            text(PAD + 124, y, tr(f"외 {len(rows) - MAX_ROWS}개", f"+{len(rows) - MAX_ROWS} more"), f_row, c["sub"])
            y += 25
        st = self.s.status()
        if st:
            text(PAD + 124, y, st, f_small, c["sub"], right - PAD - 124)

# ---------------------------------------------------------------- settings dialog


class Settings(QDialog):
    def __init__(self, cfg):
        super().__init__()
        self.setWindowTitle(tr("설정", "Settings"))
        self.setMinimumWidth(520)
        form = QFormLayout(self)
        self.token = self.secret(cfg["todoist_token"])
        self.ical = self.secret(cfg["ical_url"])
        form.addRow(tr("Todoist 토큰", "Todoist token"), self.token[0])
        form.addRow(tr("캘린더 iCal 주소", "Calendar iCal address"), self.ical[0])
        self.auto = QCheckBox(tr("Windows 시작 시 실행", "Start with Windows"))
        self.auto.setChecked(cfg["autostart"])
        form.addRow("", self.auto)
        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        form.addRow(bb)

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

    def values(self):
        clean = lambda s: s.strip().strip("'\"").replace(" ", "")
        return {"todoist_token": clean(self.token[1].text()), "ical_url": clean(self.ical[1].text()),
                "autostart": self.auto.isChecked()}

# ---------------------------------------------------------------- app


class Bus(QObject):
    fetched = Signal(dict)


class State:
    def __init__(self, demo=False, fixed_now=None):
        self.demo = demo
        self.fixed_now = fixed_now
        self.cfg = dict(DEFAULTS) if demo else load_json(CFG_FILE, DEFAULTS)
        LANG["en"] = self.cfg["lang"] != "ko"
        self.snap = demo_snapshot() if demo else load_json(CACHE_FILE, {"fetched": 0, "tasks": [], "events": [], "errors": []})
        self.busy = False

    def now(self):
        return self.fixed_now or datetime.now()

    def view(self):
        return build(self.snap, self.now())

    def status(self):
        if self.demo:
            return ""
        if not self.cfg["todoist_token"] and not self.cfg["ical_url"]:
            return tr("트레이 아이콘 → 설정", "Tray icon → Settings")
        errs = self.snap.get("errors") or []
        if errs:
            return "; ".join(errs)[:120]
        return ""


def fetch_all(cfg, today):
    snap, errors = {"fetched": time.time(), "tasks": [], "events": []}, []
    if cfg["todoist_token"]:
        try:
            snap["tasks"] = fetch_tasks(cfg["todoist_token"])
        except Exception as e:
            errors.append(f"Todoist: {e}" if "token" not in str(e) else str(e))
            snap["tasks"] = None
    if cfg["ical_url"]:
        try:
            snap["events"] = fetch_events(cfg["ical_url"], today)
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
    scale = 2
    pm = QPixmap(W * scale + 80, h * scale + 80)
    g = QLinearGradient(0, 0, pm.width(), pm.height())
    if state.cfg["text"] == "dark":
        g.setColorAt(0, QColor("#dfe6ee")); g.setColorAt(1, QColor("#f4efe6"))
    else:
        g.setColorAt(0, QColor("#1e2a3a")); g.setColorAt(1, QColor("#4b5d4a"))
    p = QPainter(pm)
    p.fillRect(pm.rect(), g)
    p.translate(40, 40)
    p.scale(scale, scale)
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

    if "--shot" in args:
        st = State(demo=True, fixed_now=datetime.combine(date.today(), datetime.min.time()).replace(hour=11, minute=20))
        if "--lang" in args:
            st.cfg["lang"] = args[args.index("--lang") + 1]
            LANG["en"] = st.cfg["lang"] != "ko"
        if "--text" in args:
            st.cfg["text"] = args[args.index("--text") + 1]
        render_shot(st, args[args.index("--shot") + 1])
        return 0

    if not demo:
        os.makedirs(CFG_DIR, exist_ok=True)
        lock = QLockFile(os.path.join(CFG_DIR, "running.lock"))
        if not lock.tryLock(100):
            return 0
        app._lock = lock

    st = State(demo=demo)
    ov = Overlay(st)
    bus = Bus()
    last_day = [st.now().date()]

    def refresh():
        ov.place()
        ov.update()

    def on_fetched(snap):
        st.busy = False
        old = st.snap
        for k in ("tasks", "events"):   # keep the last good data when one source fails
            if snap[k] is None:
                snap[k] = old.get(k, [])
        st.snap = snap
        if not st.demo:
            save_json(CACHE_FILE, snap)
        refresh()
    bus.fetched.connect(on_fetched)

    def sync():
        if st.demo or st.busy:
            return
        st.busy = True
        cfg, today = dict(st.cfg), st.now().date()
        threading.Thread(target=lambda: bus.fetched.emit(fetch_all(cfg, today)), daemon=True).start()

    def save_cfg():
        if not st.demo:
            save_json(CFG_FILE, st.cfg)

    def open_settings():
        dlg = Settings(st.cfg)
        if dlg.exec():
            st.cfg.update(dlg.values())
            save_cfg()
            try:
                set_autostart(st.cfg["autostart"])
            except Exception:
                pass
            sync()

    # tray menu
    tray = QSystemTrayIcon(tray_icon())
    tray.setToolTip("Study Overlay")
    menu = QMenu()

    def build_menu():
        menu.clear()
        menu.addAction(tr("동기화", "Sync"), sync)
        shown = QAction(tr("표시", "Show"), menu, checkable=True, checked=st.cfg.get("visible", True))
        shown.toggled.connect(lambda v: (st.cfg.__setitem__("visible", v), save_cfg(), visibility()))
        menu.addAction(shown)
        menu.addSeparator()

        def radio(title, key, options):
            sub = menu.addMenu(title)
            grp = QActionGroup(sub)
            for val, name in options:
                a = QAction(name, sub, checkable=True, checked=st.cfg[key] == val)
                a.triggered.connect(lambda _=False, v=val: choose(key, v))
                grp.addAction(a)
                sub.addAction(a)

        def choose(key, v):
            st.cfg[key] = v
            save_cfg()
            if key == "lang":
                LANG["en"] = v != "ko"
                build_menu()
            ov.apply_flags()
            refresh()

        radio(tr("위치", "Position"), "corner", [("top-left", tr("왼쪽 위", "Top left")), ("top-right", tr("오른쪽 위", "Top right")),
                                                 ("bottom-left", tr("왼쪽 아래", "Bottom left")), ("bottom-right", tr("오른쪽 아래", "Bottom right"))])
        radio(tr("투명도", "Opacity"), "opacity", [(1.0, "100%"), (0.9, "90%"), (0.75, "75%"), (0.6, "60%"), (0.45, "45%")])
        radio(tr("글자색", "Text"), "text", [("light", tr("밝게 (어두운 배경화면)", "Light (dark wallpaper)")),
                                            ("dark", tr("어둡게 (밝은 배경화면)", "Dark (light wallpaper)"))])
        radio(tr("언어", "Language"), "lang", [("en", "English"), ("ko", "한국어")])

        def check(title, key, after=None):
            a = QAction(title, menu, checkable=True, checked=st.cfg[key])
            a.toggled.connect(lambda v: (st.cfg.__setitem__(key, v), save_cfg(), after and after()))
            menu.addAction(a)
        check(tr("항상 위에", "Always on top"), "on_top", lambda: (ov.apply_flags(), refresh()))
        check(tr("전체 화면일 때 숨기기", "Hide in full screen"), "hide_fullscreen", lambda: visibility())
        menu.addSeparator()
        menu.addAction(tr("설정", "Settings"), open_settings)
        menu.addAction(tr("종료", "Quit"), app.quit)
    build_menu()
    tray.setContextMenu(menu)
    tray.activated.connect(lambda r: r == QSystemTrayIcon.Trigger and sync())
    tray.show()

    def visibility():
        want = st.cfg.get("visible", True) and not (st.cfg["hide_fullscreen"] and fullscreen_app_running())
        if want and not ov.isVisible():
            refresh()
            ov.show()
        elif not want and ov.isVisible():
            ov.hide()

    def tick():   # every 30 s: redraw (Now/Next, past events), day rollover
        d = st.now().date()
        if d != last_day[0]:
            last_day[0] = d
            sync()
        refresh()

    t1 = QTimer(); t1.timeout.connect(tick); t1.start(30_000)
    t2 = QTimer(); t2.timeout.connect(sync); t2.start(REFRESH_MIN * 60_000)
    t3 = QTimer(); t3.timeout.connect(visibility); t3.start(2_000)
    app.primaryScreenChanged.connect(lambda _: refresh())
    for s in app.screens():
        s.availableGeometryChanged.connect(lambda _: refresh())

    refresh()
    visibility()
    if not demo and not st.cfg["todoist_token"] and not st.cfg["ical_url"]:
        QTimer.singleShot(300, open_settings)
    else:
        sync()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
