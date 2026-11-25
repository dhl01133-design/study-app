// Data model and list logic: a port of desktop/overlay.py (itself following the phone app's Brief/Model/DaySlice).
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

namespace StudyOverlayLite
{
    static class L
    {
        public static bool En = true;
        public static string Tr(string ko, string en) => En ? en : ko;
        static readonly string DowKo = "월화수목금토일";
        static readonly string[] DowEn = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        static int Dow(DateTime d) => ((int)d.DayOfWeek + 6) % 7;   // Monday = 0
        public static string Md(DateTime d) => $"{d.Month}/{d.Day}";
        public static string Day(DateTime d) => En ? $"{DowEn[Dow(d)]} {Md(d)}" : $"{Md(d)} ({DowKo[Dow(d)]})";
        public static string Hm(DateTime t) => t.ToString("HH:mm", CultureInfo.InvariantCulture);

        public static string Until(DateTime now, DateTime to)
        {
            long m = Math.Max(0, (long)(to - now).TotalMinutes);
            string s;
            if (m < 60) s = Tr($"{m}분", $"{m} min");
            else if (m < 1440) s = Tr($"{m / 60}시간", $"{m / 60} h") + (m % 60 > 0 ? Tr($" {m % 60}분", $" {m % 60} min") : "");
            else s = Tr($"{m / 1440}일", $"{m / 1440} d") + (m % 1440 >= 60 ? Tr($" {m % 1440 / 60}시간", $" {m % 1440 / 60} h") : "");
            return Tr($"{s} 후", $"in {s}");
        }
    }

    static class Courses
    {
        public static readonly Dictionary<string, int> Colors = new Dictionary<string, int>
        {
            ["경제학입문"] = 0x2F6FDF, ["창의적글쓰기"] = 0xC96A14, ["일반화학2"] = 0x23874D, ["General Chemistry 2"] = 0x23874D,
            ["창의적융합디자인"] = 0x7A45C4, ["선형대수학"] = 0xB4420F, ["미적분2"] = 0x0B6C84, ["Calculus 2"] = 0x0B6C84,
            ["영어쓰기"] = 0xB0175A, ["영어작문"] = 0xB0175A, ["English Writing"] = 0xB0175A,
        };
        static readonly Regex Prefix = new Regex(@"^\[([^\]]+)]\s*");

        public static string Of(string s)
        {
            var m = Prefix.Match(s ?? "");
            return m.Success && Colors.ContainsKey(m.Groups[1].Value) ? m.Groups[1].Value : null;
        }

        public static string Strip(string s)
        {
            if (Of(s) == null) return s;
            var r = Prefix.Replace(s, "", 1);
            return r.Length > 0 ? r : s;
        }
    }

    class TaskItem
    {
        public string Id = "", Content = "", Description = "", Project = "";
        public List<string> Labels = new List<string>();
        public int Priority = 1;          // Todoist API: 4 = p1 … 1 = p4
        public DateTime? Date;            // date only
        public TimeSpan? Time;

        static readonly Regex DueRe = new Regex(@"(?:Due|due|마감)\s*:?\s*(\d{1,2})/(\d{1,2})(?:\s*(?:at\s*)?(\d{1,2}):(\d{2}))?");

        public string Course => Courses.Of(Content) ?? (Project == "" || Project == "Inbox" ? "" : Project);
        public string Title => Courses.Strip(Content);
        public bool Bookkeeping => Content.StartsWith("🤖", StringComparison.Ordinal);
        public bool Icampus => Labels.Contains("icampus");
        public DateTime? Planned => Date.HasValue ? Date.Value + (Time ?? new TimeSpan(23, 59, 0)) : (DateTime?)null;

        /// Real deadline: "Due 11/22 [HH:MM]" / "마감 11/22" in the description, else the Todoist date.
        public DateTime? Deadline
        {
            get
            {
                var m = DueRe.Match(Description ?? "");
                if (!m.Success) return Planned;
                int mo = int.Parse(m.Groups[1].Value), dy = int.Parse(m.Groups[2].Value);
                var b = Date ?? DateTime.Today;
                DateTime? best = null;
                for (int y = b.Year - 1; y <= b.Year + 1; y++)
                {
                    if (mo < 1 || mo > 12 || dy < 1 || dy > DateTime.DaysInMonth(y, mo)) continue;
                    var c = new DateTime(y, mo, dy);
                    if (best == null || Math.Abs((c - b).TotalDays) < Math.Abs((best.Value - b).TotalDays)) best = c;
                }
                if (best == null) return Planned;
                int h = 23, mi = 59;
                if (m.Groups[3].Success)
                {
                    h = int.Parse(m.Groups[3].Value); mi = int.Parse(m.Groups[4].Value);
                    if (h >= 24) { h = 23; mi = 59; }   // "24:00" is the end of that day
                    if (mi > 59) mi = 59;
                }
                return best.Value.AddHours(h).AddMinutes(mi);
            }
        }

        public Dictionary<string, object> ToDict() => new Dictionary<string, object>
        {
            ["id"] = Id, ["content"] = Content, ["description"] = Description, ["project"] = Project, ["labels"] = Labels,
            ["priority"] = Priority, ["date"] = Date?.ToString("yyyy-MM-dd"), ["time"] = Time.HasValue ? $"{Time.Value.Hours:00}:{Time.Value.Minutes:00}" : null,
        };

        public static TaskItem FromDict(Dictionary<string, object> d)
        {
            var t = new TaskItem
            {
                Id = Json.Str(d, "id"), Content = Json.Str(d, "content"), Description = Json.Str(d, "description"),
                Project = Json.Str(d, "project"), Priority = Json.Int(d, "priority", 1),
                Labels = Json.List(d, "labels").Select(x => x?.ToString() ?? "").ToList(),
            };
            var ds = Json.Str(d, "date");
            if (ds.Length >= 10 && DateTime.TryParseExact(ds.Substring(0, 10), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var dt)) t.Date = dt;
            var ts = Json.Str(d, "time");
            if (ts.Length == 5 && TimeSpan.TryParseExact(ts, @"hh\:mm", CultureInfo.InvariantCulture, out var tm)) t.Time = tm;
            return t;
        }
    }

    class EventItem
    {
        public string Summary = "";
        public DateTime Start, End;
        public bool AllDay;
        static readonly Regex ClassRe = new Regex(@"수업\s*$|출석");
        static readonly Regex DeadlineRe = new Regex(@"마감|제출|\bdue\b|\bdeadline\b", RegexOptions.IgnoreCase);

        public string Course => Courses.Of(Summary) ?? "";
        public string Title => Courses.Strip(Summary);
        public bool IsClass => ClassRe.IsMatch(Summary);
        public bool IsDeadline => !IsClass && DeadlineRe.IsMatch(Summary);

        public bool OccursOn(DateTime d)
        {
            if (AllDay)
            {
                var last = End.Date > Start.Date ? End.Date.AddDays(-1) : Start.Date;
                return Start.Date <= d && d <= last;
            }
            if (Start == End) return Start.Date == d;
            return Start < d.AddDays(1) && End > d;
        }

        public Dictionary<string, object> ToDict() => new Dictionary<string, object>
        {
            ["summary"] = Summary, ["start"] = Start.ToString("s"), ["end"] = End.ToString("s"), ["all_day"] = AllDay,
        };

        public static EventItem FromDict(Dictionary<string, object> d) => new EventItem
        {
            Summary = Json.Str(d, "summary"), AllDay = Json.Bool(d, "all_day", false),
            Start = DateTime.Parse(Json.Str(d, "start"), CultureInfo.InvariantCulture),
            End = DateTime.Parse(Json.Str(d, "end"), CultureInfo.InvariantCulture),
        };
    }

    class Snapshot
    {
        public List<TaskItem> Tasks = new List<TaskItem>();
        public List<EventItem> Events = new List<EventItem>();
        public List<string> Errors = new List<string>();
        public double Fetched;

        public Dictionary<string, object> ToDict() => new Dictionary<string, object>
        {
            ["fetched"] = Fetched, ["errors"] = Errors,
            ["tasks"] = Tasks.Select(t => (object)t.ToDict()).ToList(), ["events"] = Events.Select(e => (object)e.ToDict()).ToList(),
        };

        public static Snapshot FromDict(Dictionary<string, object> d)
        {
            var s = new Snapshot();
            if (d == null) return s;
            foreach (var x in Json.List(d, "tasks")) if (x is Dictionary<string, object> td) try { s.Tasks.Add(TaskItem.FromDict(td)); } catch { }
            foreach (var x in Json.List(d, "events")) if (x is Dictionary<string, object> ed) try { s.Events.Add(EventItem.FromDict(ed)); } catch { }
            s.Errors = Json.List(d, "errors").Select(x => x?.ToString() ?? "").ToList();
            return s;
        }
    }

    class Row
    {
        public string Time, Title, Course, Kind;   // kind: overdue | event | deadline | task
        public int Priority = 1;
        public bool Past, Done;
        public string TaskId;
    }

    class Upcoming { public string Label, Title, Note; }

    class View
    {
        public List<Row> Rows = new List<Row>();
        public int Overdue;
        public List<Upcoming> Upcoming = new List<Upcoming>();
    }

    class Deadline { public string Key, Course, Title; public DateTime At; }

    static class Brief
    {
        /// Rows for today, like the app's Brief.todayLines plus overdue and carried-over tasks.
        public static View Build(Snapshot s, DateTime now, Cfg cfg, ICollection<string> done)
        {
            var v = new View();
            var today = now.Date;
            bool onlyI = cfg.B("only_icampus");
            var tasks = s.Tasks.Where(t => !t.Bookkeeping && (!onlyI || t.Icampus)).ToList();

            var overdue = tasks.Select(t => new { t, due = t.Deadline }).Where(x => x.due.HasValue && x.due.Value.Date < today)
                .OrderBy(x => x.due.Value).ToList();
            foreach (var x in overdue)
                v.Rows.Add(new Row { Time = L.Md(x.due.Value), Title = x.t.Title, Course = x.t.Course, Kind = "overdue",
                    Priority = x.t.Priority, TaskId = x.t.Id, Done = done.Contains(x.t.Id) });

            foreach (var e in s.Events.Where(e => e.OccursOn(today)).OrderBy(e => e.AllDay ? 0 : 1).ThenBy(e => e.Start))
            {
                string label;
                if (e.AllDay) label = L.Tr("종일", "All day");
                else if (e.IsDeadline) label = "~" + L.Hm(e.Start);
                else if (e.Start.Date == today) label = L.Hm(e.Start) + "–" + L.Hm(e.End);
                else label = "~" + L.Hm(e.End);
                bool past = !e.AllDay && !e.IsDeadline && e.End <= now;
                if (past && !cfg.B("show_past")) continue;
                v.Rows.Add(new Row { Time = label, Title = e.Title, Course = e.Course, Kind = e.IsDeadline ? "deadline" : "event", Past = past });
            }

            var overdueIds = new HashSet<string>(overdue.Select(x => x.t.Id));
            var carried = cfg.B("show_carried")
                ? tasks.Where(t => t.Date.HasValue && t.Date.Value < today && !overdueIds.Contains(t.Id)).OrderBy(t => t.Planned).ToList()
                : new List<TaskItem>();
            var todays = tasks.Where(t => t.Date == today && !overdueIds.Contains(t.Id)).OrderBy(t => t.Planned);
            foreach (var t in carried.Concat(todays))
            {
                string label = t.Date.Value < today ? L.Md(t.Date.Value) : (t.Time.HasValue ? "~" + $"{t.Time.Value.Hours:00}:{t.Time.Value.Minutes:00}" : "");
                string title = t.Title;
                var dl = t.Deadline;
                if (dl.HasValue && dl.Value.Date != t.Date) title += L.Tr($" (마감 {L.Md(dl.Value)})", $" (due {L.Md(dl.Value)})");
                v.Rows.Add(new Row { Time = label, Title = title, Course = t.Course, Kind = "task", Priority = t.Priority, TaskId = t.Id, Done = done.Contains(t.Id) });
            }

            if (cfg.B("show_upcoming"))
            {
                var timed = s.Events.Where(e => !e.AllDay && !e.IsDeadline && e.Start != e.End).OrderBy(e => e.Start).ToList();
                var cur = timed.FirstOrDefault(e => e.Start <= now && now < e.End);
                var nxt = timed.FirstOrDefault(e => e.Start > now && e.Start.Date == today);
                if (cur != null) v.Upcoming.Add(new Upcoming { Label = L.Tr("지금", "Now"), Title = cur.Title, Note = L.Tr("~" + L.Hm(cur.End), "until " + L.Hm(cur.End)) });
                if (nxt != null) v.Upcoming.Add(new Upcoming { Label = L.Tr("다음", "Next"), Title = nxt.Title, Note = L.Until(now, nxt.Start) });
            }
            v.Overdue = overdue.Count(x => !done.Contains(x.t.Id));
            return v;
        }

        static readonly Regex Hw = new Regex(@"hw\s*0*(\d+)", RegexOptions.IgnoreCase);

        /// Real deadlines like the app's Brief.deadlines: open `icampus` tasks and calendar deadline events, deduplicated.
        public static List<Deadline> Deadlines(Snapshot s)
        {
            var items = new List<Deadline>();
            foreach (var t in s.Tasks)
                if (!t.Bookkeeping && t.Icampus && t.Deadline.HasValue)
                    items.Add(new Deadline { Course = t.Course, Title = t.Title, At = t.Deadline.Value });
            foreach (var e in s.Events)
                if (e.IsDeadline)
                    items.Add(new Deadline { Course = e.Course, Title = e.Title, At = e.AllDay ? e.Start.Date.AddHours(23).AddMinutes(59) : e.Start });
            var seen = new HashSet<string>();
            var outp = new List<Deadline>();
            foreach (var d in items.OrderBy(x => x.At))
            {
                var hw = Hw.Match(d.Title);
                d.Key = $"{d.Course}|{d.At:yyyy-MM-dd}|{(hw.Success ? hw.Groups[1].Value : d.Title)}";
                if (seen.Add(d.Key)) outp.Add(d);
            }
            return outp;
        }

        public static readonly int[] Leads = { 6, 1 };

        /// Deadlines whose 6 h or 1 h alarm is due and hasn't fired; after a late start only one notification.
        public static List<Deadline> AlarmsDue(List<Deadline> dls, Dictionary<string, object> fired, DateTime now)
        {
            var due = new List<Deadline>();
            foreach (var d in dls)
            {
                if (d.At <= now) continue;
                var open = Leads.Where(h => now >= d.At.AddHours(-h)).ToList();
                if (open.Count == 0) continue;
                bool fresh = open.Any(h => !fired.ContainsKey($"{d.Key}|{h}"));
                foreach (var h in open) fired[$"{d.Key}|{h}"] = d.At.ToString("s");
                if (fresh) due.Add(d);
            }
            foreach (var k in fired.Keys.ToList())
                if (DateTime.TryParse(fired[k]?.ToString(), CultureInfo.InvariantCulture, DateTimeStyles.None, out var at) && at < now.AddDays(-2))
                    fired.Remove(k);
            return due;
        }
    }

    class Slice
    {
        public List<(double a, double b, string title, bool cls)> Blocks = new List<(double, double, string, bool)>();
        public List<(double a, double b)> Travel = new List<(double, double)>(), Free = new List<(double, double)>();

        const double TravelH = 1, DayStart = 9, DayEnd = 24, MinFree = 0.5, Eps = 1e-6;

        static List<(double a, double b)> Merge(IEnumerable<(double a, double b)> spans)
        {
            var o = new List<(double a, double b)>();
            foreach (var s in spans.Where(x => x.b > x.a).OrderBy(x => x.a).ThenBy(x => x.b))
            {
                if (o.Count > 0 && s.a <= o[o.Count - 1].b + Eps) o[o.Count - 1] = (o[o.Count - 1].a, Math.Max(o[o.Count - 1].b, s.b));
                else o.Add(s);
            }
            return o;
        }

        static List<(double a, double b)> Subtract(List<(double a, double b)> spans, IEnumerable<(double a, double b)> cuts)
        {
            foreach (var (x, z) in cuts)
            {
                var n = new List<(double a, double b)>();
                foreach (var (a, b) in spans)
                {
                    if (z <= a || x >= b) n.Add((a, b));
                    else { n.Add((a, Math.Min(b, x))); n.Add((Math.Max(a, z), b)); }
                }
                spans = n.Where(p => p.b - p.a > Eps).ToList();
            }
            return spans;
        }

        /// Same rules as brief/DaySlice.kt: 1 h travel around the day's classes (first/last) and other events; free time 09–24 in 30+ min blocks.
        public static Slice Of(Snapshot s, DateTime d)
        {
            var sl = new Slice();
            var day1 = d.AddDays(1);
            double H(DateTime t) => t.Hour + t.Minute / 60.0;
            var raw = new List<(double a, double b, string t, bool cls, bool fy, bool it)>();
            foreach (var e in s.Events)
            {
                if (e.AllDay || e.IsDeadline || e.Start == e.End || !e.OccursOn(d)) continue;
                bool fy = e.Start < d, it = e.End > day1;
                raw.Add((fy ? 0 : H(e.Start), it || e.End == day1 ? 24 : H(e.End), e.Title, e.IsClass, fy, it));
            }
            var travel = new List<(double a, double b)>();
            var classes = raw.Where(b => b.cls).ToList();
            if (classes.Count > 0)
            {
                var first = classes.OrderBy(b => b.a).First();
                var last = classes.OrderByDescending(b => b.b).First();
                if (!first.fy) travel.Add((first.a - TravelH, first.a));
                if (!last.it) travel.Add((last.b, last.b + TravelH));
            }
            foreach (var b in raw.Where(b => !b.cls))
            {
                if (!b.fy) travel.Add((b.a - TravelH, b.a));
                if (!b.it) travel.Add((b.b, b.b + TravelH));
            }
            var busy = Merge(raw.Select(b => (b.a, b.b)));
            sl.Travel = Merge(Subtract(Merge(travel), busy));
            sl.Free = Subtract(new List<(double a, double b)> { (DayStart, DayEnd) }, Merge(busy.Concat(sl.Travel)))
                .Where(f => f.b - f.a >= MinFree - Eps).ToList();
            sl.Blocks = raw.Select(b => (b.a, b.b, b.t, b.cls)).ToList();
            return sl;
        }
    }

    static class Demo
    {
        public static Snapshot Data()
        {
            var t = DateTime.Today;
            Func<int, int, DateTime> at = (h, m) => t.AddHours(h).AddMinutes(m);
            var s = new Snapshot { Fetched = 1 };
            s.Events.Add(new EventItem { Summary = "[English Writing] 영어쓰기 수업", Start = at(9, 0), End = at(10, 15) });
            s.Events.Add(new EventItem { Summary = "[창의적글쓰기] 창의적글쓰기 수업", Start = at(12, 0), End = at(13, 15) });
            s.Events.Add(new EventItem { Summary = "[선형대수학] WeBWorK hw11 마감", Start = at(23, 59), End = at(23, 59) });
            s.Events.Add(new EventItem { Summary = "Dentist", Start = at(17, 0), End = at(17, 30) });
            s.Tasks.Add(new TaskItem { Id = "1", Content = "Report 4", Project = "General Chemistry 2", Priority = 4, Date = t.AddDays(-2), Labels = { "icampus" } });
            s.Tasks.Add(new TaskItem { Id = "2", Content = "[Calculus 2] 13주차 강의 영상", Project = "Inbox", Priority = 3, Date = t,
                Description = "Due " + L.Md(t.AddDays(3)), Labels = { "icampus" } });
            s.Tasks.Add(new TaskItem { Id = "3", Content = "연습문제 12주차", Project = "경제학입문", Date = t, Time = new TimeSpan(18, 0, 0), Labels = { "icampus" } });
            s.Tasks.Add(new TaskItem { Id = "4", Content = "Draft team slides", Project = "창의적융합디자인", Date = t.AddDays(-1), Description = "Due " + L.Md(t.AddDays(2)) });
            return s;
        }
    }

    static class Json
    {
        static readonly JavaScriptSerializer Ser = new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 64 };
        public static object Parse(string s) => Ser.DeserializeObject(s);
        public static string Write(object o) => Ser.Serialize(o);
        public static string Str(Dictionary<string, object> d, string k) => d != null && d.TryGetValue(k, out var v) && v != null ? v.ToString() : "";
        public static int Int(Dictionary<string, object> d, string k, int def) =>
            d != null && d.TryGetValue(k, out var v) && v != null && int.TryParse(v.ToString(), out var i) ? i : def;
        public static bool Bool(Dictionary<string, object> d, string k, bool def) => d != null && d.TryGetValue(k, out var v) && v is bool b ? b : def;
        public static List<object> List(Dictionary<string, object> d, string k)
        {
            if (d == null || !d.TryGetValue(k, out var v) || v == null) return new List<object>();
            if (v is object[] a) return a.ToList();
            if (v is System.Collections.ArrayList al) return al.Cast<object>().ToList();
            if (v is System.Collections.IEnumerable e && !(v is string)) return e.Cast<object>().ToList();
            return new List<object>();
        }
        public static Dictionary<string, object> Obj(object o) => o as Dictionary<string, object>;
    }
}
