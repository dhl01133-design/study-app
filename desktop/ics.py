"""Small iCalendar reader for Google Calendar's secret iCal address, replacing icalendar + recurring_ical_events.

Port of the phone app's data/Ics.kt (and lite/Ics.cs), extended to keep what the big library handled:
FREQ DAILY / WEEKLY / MONTHLY / YEARLY with INTERVAL, COUNT, UNTIL, BYDAY (incl. "2TU", "-1FR"),
BYMONTHDAY, BYMONTH, BYSETPOS; EXDATE, RECURRENCE-ID overrides, STATUS:CANCELLED, DURATION.
Recurrences are expanded in the event's own time zone (correct across DST), then converted to this PC's.
"""
import calendar
import re
from datetime import date, datetime, timedelta, timezone

DAYS = {"MO": 0, "TU": 1, "WE": 2, "TH": 3, "FR": 4, "SA": 5, "SU": 6}
WIN_ZONES = {   # Outlook-style TZIDs
    "Korea Standard Time": "Asia/Seoul", "Tokyo Standard Time": "Asia/Tokyo", "China Standard Time": "Asia/Shanghai",
    "UTC": "UTC", "GMT Standard Time": "Europe/London", "W. Europe Standard Time": "Europe/Berlin",
    "Eastern Standard Time": "America/New_York", "Pacific Standard Time": "America/Los_Angeles",
}
_zones = {}


def _zone(tzid):
    if tzid not in _zones:
        try:
            from zoneinfo import ZoneInfo
            _zones[tzid] = ZoneInfo(WIN_ZONES.get(tzid, tzid))
        except Exception:
            _zones[tzid] = None   # unknown: treat as this PC's time
    return _zones[tzid]


def _unfold(text):
    return text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")


def _prop(line):
    q, idx = False, -1
    for i, ch in enumerate(line):
        if ch == '"':
            q = not q
        elif ch == ":" and not q:
            idx = i
            break
    if idx < 0:
        return None
    parts = line[:idx].split(";")
    params = {}
    for p in parts[1:]:
        if "=" in p:
            k, v = p.split("=", 1)
            params[k.upper()] = v.strip('"')
    return parts[0].upper(), params, line[idx + 1:]


def _value(params, v):
    """('date', date) | ('utc', naive UTC datetime) | ('zoned', naive datetime, tzinfo or None)."""
    v = v.strip()
    if params.get("VALUE") == "DATE" or len(v) == 8:
        return ("date", datetime.strptime(v[:8], "%Y%m%d"), None)
    t = datetime.strptime(v[:15], "%Y%m%dT%H%M%S")
    if v.endswith("Z"):
        return ("utc", t, timezone.utc)
    return ("zoned", t, _zone(params["TZID"]) if "TZID" in params else None)


def _local(t, tz):
    """Naive wall time in zone tz → naive local time on this PC."""
    if tz is None:
        return t
    return t.replace(tzinfo=tz).astimezone().replace(tzinfo=None)


_DUR = re.compile(r"^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$")


def _duration(s):
    m = _DUR.match(s.strip())
    if not m:
        return None
    sign, w, d, h, mi, se = m.groups()
    td = timedelta(weeks=int(w or 0), days=int(d or 0), hours=int(h or 0), minutes=int(mi or 0), seconds=int(se or 0))
    return -td if sign == "-" else td


def _byday(spec):
    out = []
    for part in spec.split(","):
        m = re.match(r"^([+-]?\d+)?(MO|TU|WE|TH|FR|SA|SU)$", part.strip().upper())
        if m:
            out.append((int(m.group(1)) if m.group(1) else None, DAYS[m.group(2)]))
    return out


def _month_days(y, mo, byday, bymonthday):
    """Dates in a month matching BYDAY (with optional ordinals) and/or BYMONTHDAY."""
    n = calendar.monthrange(y, mo)[1]
    sets = []
    if byday:
        s = set()
        for nth, wd in byday:
            days = [d for d in range(1, n + 1) if date(y, mo, d).weekday() == wd]
            if nth is None:
                s.update(days)
            elif 1 <= abs(nth) <= len(days):
                s.add(days[nth - 1] if nth > 0 else days[nth])
        sets.append(s)
    if bymonthday:
        sets.append({(d if d > 0 else n + 1 + d) for d in bymonthday if 1 <= (d if d > 0 else n + 1 + d) <= n})
    days = set.intersection(*sets) if sets else set()
    return [date(y, mo, d) for d in sorted(days)]


def _expand(start, rule, limit, until, guard=4000):
    """Occurrence start times (naive, in the event's own zone) up to `limit` (exclusive)."""
    r = {}
    for part in rule.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            r[k.upper()] = v
    freq = r.get("FREQ")
    if not freq:
        return [start]
    interval = max(1, int(r.get("INTERVAL", "1") or 1))
    count = int(r["COUNT"]) if r.get("COUNT", "").isdigit() else None
    byday = _byday(r["BYDAY"]) if "BYDAY" in r else []
    bymonthday = [int(x) for x in r.get("BYMONTHDAY", "").split(",") if re.match(r"^-?\d+$", x)]
    bymonth = [int(x) for x in r.get("BYMONTH", "").split(",") if x.isdigit()]
    setpos = [int(x) for x in r.get("BYSETPOS", "").split(",") if re.match(r"^-?\d+$", x)]
    tod = start.time()
    out, n = [], 0

    def accept(t):
        nonlocal n
        if until is not None and t > until:
            return False
        if count is not None and n >= count:
            return False
        if t >= limit:
            return False
        n += 1
        out.append(t)
        return True

    def pick(cands):
        cands = sorted(cands)
        if setpos:
            cands = sorted({cands[p - 1] if p > 0 else cands[p] for p in setpos if 1 <= abs(p) <= len(cands)})
        return cands

    if freq == "DAILY":
        for i in range(guard):
            t = start + timedelta(days=i * interval)
            if bymonth and t.month not in bymonth:
                if t >= limit:
                    break
                continue
            if not accept(t):
                break
    elif freq == "WEEKLY":
        days = sorted({wd for _, wd in byday}) or [start.weekday()]
        week = start.date() - timedelta(days=start.weekday())
        for _ in range(guard):
            stop = False
            for t in pick(datetime.combine(week + timedelta(days=wd), tod) for wd in days):
                if t < start:
                    continue
                if not accept(t):
                    stop = True
                    break
            if stop or datetime.combine(week, tod) >= limit:
                break
            week += timedelta(weeks=interval)
    elif freq in ("MONTHLY", "YEARLY"):
        y, mo = start.year, start.month
        step = interval if freq == "MONTHLY" else 12 * interval
        months = bymonth if freq == "YEARLY" and bymonth else None
        for _ in range(guard):
            if freq == "YEARLY" and months:
                month_list = [(y, m) for m in sorted(months)]
            else:
                month_list = [(y, mo)]
            stop = False
            for yy, mm in month_list:
                if byday or bymonthday:
                    ds = _month_days(yy, mm, byday, bymonthday)
                else:   # same day of month as DTSTART; months without that day are skipped (RFC 5545)
                    ds = [date(yy, mm, start.day)] if start.day <= calendar.monthrange(yy, mm)[1] else []
                for t in pick(datetime.combine(d, tod) for d in ds):
                    if t < start:
                        continue
                    if not accept(t):
                        stop = True
                        break
                if stop:
                    break
            if stop or datetime(y, mo, 1) >= limit:
                break
            mo += step
            y, mo = y + (mo - 1) // 12, (mo - 1) % 12 + 1
    else:
        accept(start)
    return out


def parse(text, frm, to):
    """Events overlapping [frm, to] (dates), as dicts with local naive ISO start/end, like the old fetch_events()."""
    events, cur, depth = [], None, 0
    for line in _unfold(text):
        if line == "BEGIN:VEVENT":
            cur, depth = [], 0
            continue
        if line == "END:VEVENT":
            if cur is not None:
                events.append(cur)
            cur = None
            continue
        if cur is None:
            continue
        if line.startswith("BEGIN:"):
            depth += 1
        elif line.startswith("END:"):
            depth -= 1
        elif depth == 0:
            p = _prop(line)
            if p:
                cur.append(p)

    def first(e, name):
        return next((p for p in e if p[0] == name), None)

    def to_local(kind, t, tz):
        return t if kind == "date" else _local(t, tz)

    overridden = set()
    for e in events:
        rid, uid = first(e, "RECURRENCE-ID"), first(e, "UID")
        if rid and uid:
            try:
                k, t, tz = _value(rid[1], rid[2])
                overridden.add((uid[2], to_local(k, t, tz)))
            except ValueError:
                pass

    lo = datetime.combine(frm, datetime.min.time())
    hi = datetime.combine(to, datetime.min.time()) + timedelta(days=1)
    out = []
    for e in events:
        try:
            s = first(e, "DTSTART")
            if not s:
                continue
            st = first(e, "STATUS")
            if st and st[2].strip().upper() == "CANCELLED":
                continue
            kind, start, tz = _value(s[1], s[2])
            all_day = kind == "date"
            de, du = first(e, "DTEND"), first(e, "DURATION")
            if de:
                k2, end, tz2 = _value(de[1], de[2])
                if not all_day and tz2 is not tz and tz is not None:   # DTEND in another zone: express it in DTSTART's
                    end = (end.replace(tzinfo=tz2) if tz2 else end.astimezone()).astimezone(tz).replace(tzinfo=None)
                elif not all_day and tz2 is not tz:
                    end = _local(end, tz2)
                dur = end - start   # wall-clock length in the event's own zone, like RFC 5545
            elif du and _duration(du[2]) is not None:
                dur = _duration(du[2])
            else:
                dur = timedelta(days=1) if all_day else timedelta(0)
            summary = first(e, "SUMMARY")
            summary = summary[2] if summary else ""
            summary = summary.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").strip()
            uid = first(e, "UID")
            uid = uid[2] if uid else summary
            is_override = first(e, "RECURRENCE-ID") is not None
            rrule = None if is_override else (first(e, "RRULE") or (None, None, None))[2]
            ex = set()
            for p in e:
                if p[0] == "EXDATE":
                    for v in p[2].split(","):
                        try:
                            k, t, z = _value(p[1], v)
                            ex.add(to_local(k, t, z))
                        except ValueError:
                            pass
            if rrule:
                until = None
                m = re.search(r"UNTIL=([0-9TZ]+)", rrule, re.I)
                if m:
                    uk, ut, uz = _value({}, m.group(1))
                    # UNTIL in the event's zone, for comparing with zone-local occurrence times
                    if uk == "date":
                        until = ut + timedelta(days=1) - timedelta(seconds=1)
                    elif uk == "utc" and tz is not None and kind != "date":
                        until = ut.replace(tzinfo=timezone.utc).astimezone(tz).replace(tzinfo=None)
                    elif uk == "utc":
                        until = _local(ut, timezone.utc)
                    else:
                        until = ut
                # zone-local limit: one day of slack covers any offset between the zones
                starts = _expand(start, rrule, hi + timedelta(days=1), until)
            else:
                starts = [start]
            for t in starts:
                lt = to_local(kind, t, tz)
                if rrule and (lt in ex or (uid, lt) in overridden):
                    continue
                le = to_local(kind, t + dur, tz)
                if lt >= hi or le < lo or (le == lt and lt < lo):
                    continue
                out.append({"summary": summary, "start": lt.isoformat(), "end": le.isoformat(), "all_day": all_day})
        except (ValueError, KeyError, IndexError):
            continue
    return sorted(out, key=lambda x: x["start"])
