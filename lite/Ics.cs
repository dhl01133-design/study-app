// Minimal iCalendar reader for Google Calendar's secret iCal address: a port of the phone app's data/Ics.kt
// (DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL, BYDAY for weekly, EXDATE, RECURRENCE-ID, STATUS:CANCELLED).
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace StudyOverlayLite
{
    static class Ics
    {
        class Prop { public string Name, Value; public Dictionary<string, string> Params; }

        static readonly Dictionary<string, DayOfWeek> Days = new Dictionary<string, DayOfWeek>
        {
            ["MO"] = DayOfWeek.Monday, ["TU"] = DayOfWeek.Tuesday, ["WE"] = DayOfWeek.Wednesday, ["TH"] = DayOfWeek.Thursday,
            ["FR"] = DayOfWeek.Friday, ["SA"] = DayOfWeek.Saturday, ["SU"] = DayOfWeek.Sunday,
        };

        // .NET Framework only knows Windows zone ids; Google writes IANA ids.
        static readonly Dictionary<string, string> Zones = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["Asia/Seoul"] = "Korea Standard Time", ["Asia/Tokyo"] = "Tokyo Standard Time", ["Asia/Shanghai"] = "China Standard Time",
            ["Asia/Hong_Kong"] = "China Standard Time", ["Asia/Taipei"] = "Taipei Standard Time", ["Asia/Singapore"] = "Singapore Standard Time",
            ["UTC"] = "UTC", ["Etc/UTC"] = "UTC", ["Etc/GMT"] = "UTC", ["GMT"] = "UTC",
            ["Europe/London"] = "GMT Standard Time", ["Europe/Paris"] = "Romance Standard Time", ["Europe/Berlin"] = "W. Europe Standard Time",
            ["America/New_York"] = "Eastern Standard Time", ["America/Chicago"] = "Central Standard Time",
            ["America/Denver"] = "Mountain Standard Time", ["America/Los_Angeles"] = "Pacific Standard Time",
            ["Australia/Sydney"] = "AUS Eastern Standard Time",
        };
        static readonly Dictionary<string, TimeZoneInfo> ZoneCache = new Dictionary<string, TimeZoneInfo>();

        static TimeZoneInfo Zone(string tzid)
        {
            if (string.IsNullOrEmpty(tzid)) return TimeZoneInfo.Local;
            if (ZoneCache.TryGetValue(tzid, out var z)) return z;
            try { z = TimeZoneInfo.FindSystemTimeZoneById(Zones.TryGetValue(tzid, out var w) ? w : tzid); }
            catch { z = TimeZoneInfo.Local; }
            ZoneCache[tzid] = z;
            return z;
        }

        public static List<EventItem> Parse(string text, DateTime from, DateTime to)
        {
            var lines = text.Replace("\r\n", "\n").Replace("\n ", "").Replace("\n\t", "").Split('\n');
            var events = new List<List<Prop>>();
            List<Prop> cur = null;
            int depth = 0;
            foreach (var line in lines)
            {
                if (line == "BEGIN:VEVENT") { cur = new List<Prop>(); depth = 0; continue; }
                if (line == "END:VEVENT") { if (cur != null) events.Add(cur); cur = null; continue; }
                if (cur == null) continue;
                if (line.StartsWith("BEGIN:")) depth++;
                else if (line.StartsWith("END:")) depth--;
                else if (depth == 0) { var p = ParseProp(line); if (p != null) cur.Add(p); }
            }

            Prop First(List<Prop> e, string n) => e.FirstOrDefault(p => p.Name == n);
            var overridden = new HashSet<string>();
            foreach (var e in events)
            {
                var rid = First(e, "RECURRENCE-ID"); var uid = First(e, "UID");
                if (rid == null || uid == null) continue;
                try { overridden.Add(uid.Value + "|" + Dt(rid).t.ToString("s")); } catch { }
            }

            var outp = new List<EventItem>();
            foreach (var e in events)
            {
                try
                {
                    var s = First(e, "DTSTART");
                    if (s == null) continue;
                    if ((First(e, "STATUS")?.Value ?? "").Trim() == "CANCELLED") continue;
                    var (start, allDay) = Dt(s);
                    DateTime end;
                    var de = First(e, "DTEND");
                    var du = First(e, "DURATION");
                    if (de != null) end = Dt(de).t;
                    else if (du != null && TryDuration(du.Value.Trim(), out var span)) end = start + span;
                    else end = allDay ? start.AddDays(1) : start;
                    var dur = end - start;
                    var summary = Unescape(First(e, "SUMMARY")?.Value ?? "");
                    var uidv = First(e, "UID")?.Value ?? summary;
                    bool isOverride = First(e, "RECURRENCE-ID") != null;
                    var rrule = isOverride ? null : First(e, "RRULE")?.Value;
                    var ex = new HashSet<DateTime>();
                    foreach (var p in e.Where(x => x.Name == "EXDATE"))
                        foreach (var v in p.Value.Split(','))
                            try { ex.Add(Dt(new Prop { Name = "EXDATE", Params = p.Params, Value = v }).t); } catch { }
                    var starts = rrule == null ? new List<DateTime> { start } : Expand(start, rrule, to, ex);
                    foreach (var st in starts)
                    {
                        if (rrule != null && overridden.Contains(uidv + "|" + st.ToString("s"))) continue;
                        var en = st + dur;
                        if (st.Date > to) continue;
                        if (en < from) continue;
                        outp.Add(new EventItem { Summary = summary, Start = st, End = en, AllDay = allDay });
                    }
                }
                catch { }
            }
            return outp.OrderBy(x => x.Start).ToList();
        }

        static Prop ParseProp(string line)
        {
            bool q = false; int idx = -1;
            for (int i = 0; i < line.Length; i++)
            {
                if (line[i] == '"') q = !q;
                else if (line[i] == ':' && !q) { idx = i; break; }
            }
            if (idx < 0) return null;
            var parts = line.Substring(0, idx).Split(';');
            var prms = new Dictionary<string, string>();
            foreach (var pp in parts.Skip(1))
            {
                int eq = pp.IndexOf('=');
                if (eq > 0) prms[pp.Substring(0, eq).ToUpperInvariant()] = pp.Substring(eq + 1).Trim('"');
            }
            return new Prop { Name = parts[0].ToUpperInvariant(), Params = prms, Value = line.Substring(idx + 1) };
        }

        /// Local date-time on this PC, and whether it was an all-day DATE value.
        static (DateTime t, bool allDay) Dt(Prop p)
        {
            var v = p.Value.Trim();
            if ((p.Params != null && p.Params.TryGetValue("VALUE", out var vt) && vt == "DATE") || v.Length == 8)
                return (DateTime.ParseExact(v.Substring(0, 8), "yyyyMMdd", CultureInfo.InvariantCulture), true);
            var local = DateTime.ParseExact(v.Substring(0, 15), "yyyyMMdd'T'HHmmss", CultureInfo.InvariantCulture);
            if (v.EndsWith("Z")) return (TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(local, DateTimeKind.Utc), TimeZoneInfo.Local), false);
            if (p.Params != null && p.Params.TryGetValue("TZID", out var tz))
            {
                var zone = Zone(tz);
                if (zone.Id != TimeZoneInfo.Local.Id)
                {
                    var utc = TimeZoneInfo.ConvertTimeToUtc(DateTime.SpecifyKind(local, DateTimeKind.Unspecified), zone);
                    return (TimeZoneInfo.ConvertTimeFromUtc(utc, TimeZoneInfo.Local), false);
                }
            }
            return (local, false);
        }

        static bool TryDuration(string s, out TimeSpan span)
        {
            span = TimeSpan.Zero;
            try { span = System.Xml.XmlConvert.ToTimeSpan(s.TrimStart('+')); return true; } catch { return false; }
        }

        static List<DateTime> Expand(DateTime start, string rule, DateTime to, HashSet<DateTime> ex)
        {
            var r = new Dictionary<string, string>();
            foreach (var part in rule.Split(';'))
            {
                int i = part.IndexOf('=');
                if (i > 0) r[part.Substring(0, i).ToUpperInvariant()] = part.Substring(i + 1);
            }
            var outp = new List<DateTime>();
            if (!r.TryGetValue("FREQ", out var freq)) return new List<DateTime> { start };
            int interval = Math.Max(1, r.TryGetValue("INTERVAL", out var iv) && int.TryParse(iv, out var ivn) ? ivn : 1);
            int? count = r.TryGetValue("COUNT", out var cv) && int.TryParse(cv, out var cn) ? cn : (int?)null;
            DateTime? until = null;
            if (r.TryGetValue("UNTIL", out var uv))
                try { var (t, isDate) = Dt(new Prop { Name = "UNTIL", Params = new Dictionary<string, string>(), Value = uv }); until = isDate ? t.AddDays(1).AddSeconds(-1) : t; } catch { }
            var limit = to.Date.AddDays(1);
            int n = 0;

            bool Accept(DateTime t)
            {
                if (until.HasValue && t > until.Value) return false;
                if (count.HasValue && n >= count.Value) return false;
                if (t >= limit) return false;
                n++;
                if (!ex.Contains(t)) outp.Add(t);
                return true;
            }

            if (freq == "WEEKLY")
            {
                var days = new List<DayOfWeek>();
                if (r.TryGetValue("BYDAY", out var bd))
                    foreach (var d in bd.Split(','))
                    {
                        var k = d.Trim(); k = k.Length >= 2 ? k.Substring(k.Length - 2) : k;
                        if (Days.TryGetValue(k, out var dw)) days.Add(dw);
                    }
                if (days.Count == 0) days.Add(start.DayOfWeek);
                days = days.Distinct().OrderBy(d => ((int)d + 6) % 7).ToList();   // Monday first
                var weekStart = start.Date.AddDays(-(((int)start.DayOfWeek + 6) % 7));
                var tod = start.TimeOfDay;
                for (int guard = 0; guard < 2000; guard++)
                {
                    bool stop = false;
                    foreach (var d in days)
                    {
                        var t = weekStart.AddDays(((int)d + 6) % 7) + tod;
                        if (t < start) continue;
                        if (!Accept(t)) { stop = true; break; }
                    }
                    if (stop) break;
                    weekStart = weekStart.AddDays(7 * interval);
                }
            }
            else
            {
                for (int i = 0; i < 3000; i++)
                {
                    DateTime t;
                    if (freq == "DAILY") t = start.AddDays(i * interval);
                    else if (freq == "MONTHLY") t = start.AddMonths(i * interval);
                    else if (freq == "YEARLY") t = start.AddYears(i * interval);
                    else if (i == 0) t = start;
                    else break;
                    if (!Accept(t)) break;
                }
            }
            return outp;
        }

        static string Unescape(string s) =>
            s.Replace("\\n", " ").Replace("\\N", " ").Replace("\\,", ",").Replace("\\;", ";").Replace("\\\\", "\\").Trim();
    }
}
