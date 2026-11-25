"""Overrides, EXDATE, all-day, UTC and cancelled events (same cases as the Lite self-test). Run: python tests/test_ics.py"""
import os
import sys
from datetime import date, datetime, timedelta

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import ics  # noqa: E402

today = date.today()
d0, d1, d2 = (today - timedelta(days=7)).strftime("%Y%m%d"), today.strftime("%Y%m%d"), (today + timedelta(days=1)).strftime("%Y%m%d")
utc = (datetime.combine(today, datetime.min.time()) + timedelta(hours=23, minutes=59)).astimezone().astimezone(
    __import__("datetime").timezone.utc).strftime("%Y%m%dT%H%M%SZ")
text = "\r\n".join([
    "BEGIN:VCALENDAR", "VERSION:2.0",
    "BEGIN:VEVENT", "UID:a", f"DTSTART;TZID=Asia/Seoul:{d0}T090000", f"DTEND;TZID=Asia/Seoul:{d0}T101500",
    "RRULE:FREQ=DAILY;COUNT=30", f"EXDATE;TZID=Asia/Seoul:{d2}T090000", "SUMMARY:[English Writing] 영어쓰기 수업", "END:VEVENT",
    "BEGIN:VEVENT", "UID:a", f"RECURRENCE-ID;TZID=Asia/Seoul:{d1}T090000", f"DTSTART;TZID=Asia/Seoul:{d1}T100000",
    f"DTEND;TZID=Asia/Seoul:{d1}T111500", "SUMMARY:moved", "END:VEVENT",
    "BEGIN:VEVENT", "UID:b", f"DTSTART;VALUE=DATE:{d1}", f"DTEND;VALUE=DATE:{d2}", "SUMMARY:All day", "END:VEVENT",
    "BEGIN:VEVENT", "UID:c", "STATUS:CANCELLED", f"DTSTART;VALUE=DATE:{d1}", "SUMMARY:Cancelled", "END:VEVENT",
    "BEGIN:VEVENT", "UID:d", f"DTSTART:{utc}", "SUMMARY:hw11 마감", "END:VEVENT",
    "BEGIN:VEVENT", "UID:e", f"DTSTART;TZID=Asia/Seoul:{d0}T120000", "DURATION:PT1H15M",
    "RRULE:FREQ=MONTHLY;BYDAY=2TU,-1FR", "SUMMARY:monthly", "END:VEVENT",
    "END:VCALENDAR", ""])
ev = ics.parse(text, today - timedelta(days=1), today + timedelta(days=1))
from zoneinfo import ZoneInfo  # noqa: E402


def seoul(d, h, m=0):   # Seoul wall time → this PC's local time, as the parser reports it
    return datetime.combine(d, datetime.min.time()).replace(hour=h, minute=m, tzinfo=ZoneInfo("Asia/Seoul")).astimezone().replace(tzinfo=None).isoformat()


starts = {(e["summary"], e["start"]) for e in ev}
assert ("moved", seoul(today, 10)) in starts, starts                                     # the override
assert ("[English Writing] 영어쓰기 수업", seoul(today, 9)) not in starts, starts          # replaced by it
assert ("[English Writing] 영어쓰기 수업", seoul(today + timedelta(days=1), 9)) not in starts  # EXDATE
assert any(n.startswith("[English") for n, _ in starts), starts                       # normal ones remain
assert ("All day", today.isoformat() + "T00:00:00") in starts
assert not any(e["summary"] == "Cancelled" for e in ev)
assert any(e["summary"] == "hw11 마감" and e["start"].endswith("23:59:00") for e in ev)
print("test_ics ok", len(ev), "events")
