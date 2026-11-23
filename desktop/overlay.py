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

import requests

import updater
from PySide6.QtCore import QLockFile, QObject, QPoint, QPointF, QRect, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import (QAction, QColor, QFont, QFontMetrics, QGuiApplication, QIcon,
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
            "text_color": "#F4F4F5", "scale": 1.0, "opacity": 0.9, "show_bar": True,
            "corner": "bottom-right", "pos_x": 0, "pos_y": 0, "pos_anchor": "bottom",   # corner or "custom"
            "on_top": True, "hide_fullscreen": True, "start_with_windows": False, "visible": True}
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


def fullscreen_app_running():
    """True while a full-screen app, game or presentation is in front (Windows' own notification-quiet check)."""
    if not WIN:
        return False
    import ctypes
    state = ctypes.c_int(0)
    if ctypes.windll.shell32.SHQueryUserNotificationState(ctypes.byref(state)) != 0:
        return False
    return state.value in (2, 3, 4)   # BUSY, RUNNING_D3D_FULL_SCREEN, PRESENTATION_MODE


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


W = 440          # layout width in logical px, before the size setting
PAD = 4
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
    return dict(
        text=t, sub=with_alpha(t, .74), faint=with_alpha(t, .45), light=light,
        halo=QColor(0, 0, 0, 85) if light else QColor(255, 255, 255, 90),
        accent=accent, on_accent=pick("#0E0E10", "#FFFFFF"), danger=pick("#FF6B6E", "#D93036"),
        p1=pick("#FF9F43", "#E8740C"), p2=accent, good=good, good_soft=with_alpha(good, .28),
        track=with_alpha(t, .14), busy=with_alpha(t, .80), on_busy=pick("#0E0E10", "#FFFFFF"), hatch=with_alpha(t, .38),
    )


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
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.apply_flags()

    def apply_flags(self):
        flags = Qt.FramelessWindowHint | Qt.Tool | Qt.NoDropShadowWindowHint
        if not self.moving:   # click-through except while being moved
            flags |= Qt.WindowTransparentForInput | Qt.WindowDoesNotAcceptFocus
        flags |= Qt.WindowStaysOnTopHint if (self.s.cfg["on_top"] or self.moving) else Qt.WindowStaysOnBottomHint
        visible = self.isVisible()
        self.setWindowFlags(flags)
        self.setWindowOpacity(float(self.s.cfg["opacity"]))
        if visible:
            self.show()

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

    def mouseMoveEvent(self, e):
        if self.moving and self._drag is not None:
            self.move(self.clamp(e.globalPosition().toPoint() - self._drag))

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

    def layout_height(self):
        rows, _, up = self.s.view()
        n = max(min(len(rows), MAX_ROWS) + (1 if len(rows) > MAX_ROWS else 0), 1)
        bar = 48 if self.s.cfg.get("show_bar", True) else 0
        return PAD + 38 + len(up) * 22 + bar + 10 + n * 25 + (22 if self.s.status() else 0) + PAD

    def place(self):
        k = self.scale()
        w, h = int(W * k + 1), int(self.layout_height() * k + 1)
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
        self.move(self.clamp(QPoint(x, y)))

    # ---- drawing
    def paintEvent(self, _):
        p = QPainter(self)
        p.scale(self.scale(), self.scale())
        self.paint(p)
        p.end()

    def paint(self, p):
        p.setRenderHints(QPainter.Antialiasing | QPainter.TextAntialiasing)
        c = palette(self.s.cfg)
        f_head, f_row, f_up, f_small, f_bar = font(26, True), font(15), font(14), font(12), font(10)
        rows, overdue, upcoming = self.s.view()
        right = W - PAD
        now = self.s.now()

        if self.moving:   # visible frame so there's something to grab
            p.setPen(QPen(c["accent"], 1.5, Qt.DashLine))
            p.setBrush(with_alpha(c["accent"], .12))
            p.drawRoundedRect(QRectF(1, 1, W - 2, self.layout_height() - 2), 10, 10)

        def text(x, y, s, fnt, color, width=None, align=Qt.AlignLeft):
            p.setFont(fnt)
            fm = QFontMetrics(fnt)
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
        for r in rows[:MAX_ROWS]:
            if r.kind in ("task", "overdue"):   # priority circle: orange p1, blue p2
                ring = c["p1"] if r.priority == 4 else c["p2"] if r.priority == 3 else c["sub"]
                p.setBrush(Qt.NoBrush)
                p.setPen(QPen(c["halo"], 3.2))
                p.drawEllipse(PAD + 2, y + 6, 12, 12)
                p.setPen(QPen(ring, 1.6))
                p.drawEllipse(PAD + 2, y + 6, 12, 12)
            tcol = c["danger"] if r.kind in ("overdue", "deadline") else c["faint"] if r.past else c["sub"]
            text(PAD + 24, y, r.time, f_row, tcol, 92)
            x = PAD + 24 + 100
            cw = min(QFontMetrics(f_small).horizontalAdvance(r.course) + 4, 130) if r.course else 0
            tw = text(x, y, r.title, f_row, c["faint"] if r.past else c["text"], right - x - (cw + 10 if cw else 0))
            if r.course:
                text(x + tw + 10, y + 1, r.course, f_small, c["faint"] if r.past else course_color(r.course, c["light"]), cw)
            y += 25
        if len(rows) > MAX_ROWS:
            text(PAD + 124, y, tr(f"외 {len(rows) - MAX_ROWS}개", f"+{len(rows) - MAX_ROWS} more"), f_row, c["sub"])
            y += 25
        st = self.s.status()
        if st:
            text(PAD + 124, y, st, f_small, c["sub"], right - PAD - 124)

    def paint_bar(self, p, c, x0, y0, w, now, f_bar, text):
        """Today's time bar, 08–24, like the widget: classes blue, other events solid, travel hatched, free green."""
        blocks, travel, free = day_slice(self.s.snap, now.date())
        bh, r = 22, 4
        x = lambda h: x0 + (min(max(h, BAR_FIRST), BAR_LAST) - BAR_FIRST) / (BAR_LAST - BAR_FIRST) * w
        p.setPen(Qt.NoPen)
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

    def behavior(self):
        w = QWidget()
        form = QFormLayout(w)
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
    update = Signal(object, bool)     # (build, asset_id, size) or error text, manual?
    installed = Signal(object)        # path to start, or error text


class State:
    def __init__(self, demo=False, fixed_now=None):
        self.demo = demo
        self.fixed_now = fixed_now
        self.cfg = dict(DEFAULTS) if demo else load_json(CFG_FILE, DEFAULTS)
        if self.cfg.get("text") == "dark" and self.cfg["text_color"] == DEFAULTS["text_color"]:
            self.cfg["text_color"] = "#111111"   # build 1–3 setting
        LANG["en"] = self.cfg["lang"] != "ko"
        self.snap = demo_snapshot() if demo else load_json(CACHE_FILE, {"fetched": 0, "tasks": [], "events": [], "errors": []})
        self.busy = False
        self.start_move = lambda: None

    def now(self):
        return self.fixed_now or datetime.now()

    def view(self):
        return build(self.snap, self.now())

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

    def apply_cfg():
        LANG["en"] = st.cfg["lang"] != "ko"
        build_menu()
        ov.apply_flags()
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

    def tick():   # every 30 s: redraw (Now/Next, time bar, past events), day rollover
        d = st.now().date()
        if d != last_day[0]:
            last_day[0] = d
            sync()
        refresh()

    t1 = QTimer(); t1.timeout.connect(tick); t1.start(30_000)
    t2 = QTimer(); t2.timeout.connect(sync); t2.start(REFRESH_MIN * 60_000)
    t3 = QTimer(); t3.timeout.connect(visibility); t3.start(2_000)
    t4 = QTimer(); t4.timeout.connect(check_updates); t4.start(6 * 3600_000)
    QTimer.singleShot(20_000, check_updates)
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
