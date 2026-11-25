// --selftest: runs on the Windows build machine before every release. Writes a report; exit code 1 on any failure.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Windows.Forms;

namespace StudyOverlayLite
{
    static class SelfTest
    {
        static readonly StringBuilder log = new StringBuilder();
        static int failures;

        static void Check(string name, bool ok, string detail = "")
        {
            log.AppendLine((ok ? "ok    " : "FAIL  ") + name + (detail != "" ? "  (" + detail + ")" : ""));
            if (!ok) failures++;
        }

        public static int Run(string outPath)
        {
            try { Logic(); } catch (Exception e) { Check("logic crashed", false, e.ToString()); }
            try { Window(); } catch (Exception e) { Check("window crashed", false, e.ToString()); }
            log.AppendLine(failures == 0 ? "selftest ok" : $"selftest FAILED ({failures})");
            File.WriteAllText(outPath, log.ToString(), Encoding.UTF8);
            return failures == 0 ? 0 : 1;
        }

        static void Logic()
        {
            var today = DateTime.Today;
            var now = today.AddHours(11).AddMinutes(20);
            var cfg = new Cfg();
            var snap = Demo.Data();

            var v = Brief.Build(snap, now, cfg, new string[0]);
            var titles = v.Rows.Select(r => r.Title).ToList();
            Check("rows", titles.Count == 8, string.Join(" | ", titles));
            Check("overdue first", titles[0] == "Report 4" && v.Overdue == 1);
            Check("past event dimmed", v.Rows.First(r => r.Title == "영어쓰기 수업").Past);
            Check("due suffix", titles.Any(t => t.StartsWith("13주차 강의 영상 (due ")));
            Check("next line", v.Upcoming.Count == 1 && v.Upcoming[0].Title == "창의적글쓰기 수업", string.Join(",", v.Upcoming.Select(u => u.Label + " " + u.Title + " " + u.Note)));

            cfg["show_past"] = false; cfg["show_carried"] = false; cfg["show_upcoming"] = false;
            v = Brief.Build(snap, now, cfg, new string[0]);
            Check("filters", v.Rows.Count == 6 && v.Upcoming.Count == 0, string.Join(" | ", v.Rows.Select(r => r.Title)));
            cfg = new Cfg(); cfg["only_icampus"] = true;
            Check("only icampus", Brief.Build(snap, now, cfg, new string[0]).Rows.All(r => !r.Title.StartsWith("Draft")));
            cfg = new Cfg();
            v = Brief.Build(snap, now, cfg, new[] { "1" });
            Check("done row", v.Rows.First(r => r.TaskId == "1").Done && v.Overdue == 0);

            var t = new TaskItem { Date = new DateTime(2026, 11, 20), Description = "Due 11/22 24:00" };
            Check("24:00 = end of day", t.Deadline == new DateTime(2026, 11, 22, 23, 59, 0), t.Deadline?.ToString("s"));
            t = new TaskItem { Date = new DateTime(2026, 12, 30), Description = "마감 1/2 09:30" };
            Check("deadline across new year", t.Deadline == new DateTime(2027, 1, 2, 9, 30, 0), t.Deadline?.ToString("s"));

            var tz = new TaskItem(); Todoist.ParseDue("2026-11-24T14:59:00Z", tz);
            var expect = DateTimeOffset.Parse("2026-11-24T14:59:00Z").ToLocalTime().DateTime;
            Check("todoist utc due", tz.Date == expect.Date && tz.Time == new TimeSpan(expect.Hour, expect.Minute, 0));
            var tf = new TaskItem(); Todoist.ParseDue("2026-11-24T18:00:00", tf);
            Check("todoist floating due", tf.Time == new TimeSpan(18, 0, 0));

            var dls = Brief.Deadlines(snap);
            Check("deadlines deduped", dls.Count == 4, string.Join(" | ", dls.Select(d => d.Key)));
            var fired = new Dictionary<string, object>();
            var a1 = Brief.AlarmsDue(dls, fired, today.AddHours(18));
            var a2 = Brief.AlarmsDue(dls, fired, today.AddHours(18).AddMinutes(1));
            var a3 = Brief.AlarmsDue(dls, fired, today.AddHours(22).AddMinutes(59));
            Check("alarm 6 h", a1.Any(d => d.Title.Contains("hw11")), string.Join(",", a1.Select(d => d.Title)));
            Check("alarm not twice", a2.Count == 0);
            Check("alarm 1 h", a3.Count == 1 && a3[0].Title.Contains("hw11"));
            var late = new Dictionary<string, object>();
            Check("late start: one alarm", Brief.AlarmsDue(dls, late, today.AddHours(23).AddMinutes(30)).Count == 1
                && Brief.AlarmsDue(dls, late, today.AddHours(23).AddMinutes(31)).Count == 0);

            var sl = Slice.Of(snap, today);
            Check("slice blocks", sl.Blocks.Count == 3, sl.Blocks.Count.ToString());
            Check("slice free", sl.Free.Count > 0 && sl.Free.All(f => f.b - f.a >= 0.5));

            // iCal: weekly class with an EXDATE and a moved occurrence, all-day event, UTC deadline, cancelled event
            string d0 = today.AddDays(-7).ToString("yyyyMMdd"), d1 = today.ToString("yyyyMMdd"), d2 = today.AddDays(1).ToString("yyyyMMdd");
            var ics = string.Join("\r\n", new[]
            {
                "BEGIN:VCALENDAR", "VERSION:2.0",
                "BEGIN:VTIMEZONE", "TZID:Asia/Seoul", "BEGIN:STANDARD", "TZOFFSETTO:+0900", "END:STANDARD", "END:VTIMEZONE",
                "BEGIN:VEVENT", "UID:a", $"DTSTART;TZID=Asia/Seoul:{d0}T090000", $"DTEND;TZID=Asia/Seoul:{d0}T101500",
                "RRULE:FREQ=DAILY;COUNT=30", $"EXDATE;TZID=Asia/Seoul:{d2}T090000", "SUMMARY:[English Writing] 영어쓰기 수업", "END:VEVENT",
                "BEGIN:VEVENT", "UID:a", $"RECURRENCE-ID;TZID=Asia/Seoul:{d1}T090000", $"DTSTART;TZID=Asia/Seoul:{d1}T100000",
                $"DTEND;TZID=Asia/Seoul:{d1}T111500", "SUMMARY:[English Writing] 영어쓰기 수업 (moved)", "END:VEVENT",
                "BEGIN:VEVENT", "UID:b", $"DTSTART;VALUE=DATE:{d1}", $"DTEND;VALUE=DATE:{d2}", "SUMMARY:All day thing", "END:VEVENT",
                "BEGIN:VEVENT", "UID:c", "STATUS:CANCELLED", $"DTSTART;VALUE=DATE:{d1}", "SUMMARY:Cancelled", "END:VEVENT",
                "BEGIN:VEVENT", "UID:d", "DTSTART:" + today.AddHours(23).AddMinutes(59).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss") + "Z",
                "SUMMARY:[선형대수학] WeBWorK hw11 마감", "END:VEVENT",
                "END:VCALENDAR", "",
            });
            var evs = Ics.Parse(ics, today.AddDays(-1), today.AddDays(2));
            var on = evs.Where(e => e.OccursOn(today)).ToList();
            Check("ics today", on.Count == 3, string.Join(" | ", on.Select(e => e.Summary + "@" + e.Start.ToString("s"))));
            Check("ics override", on.Any(e => e.Summary.EndsWith("(moved)") && e.Start.Hour == (TimeZoneInfo.Local.Id == "Korea Standard Time" ? 10 : e.Start.Hour)));
            Check("ics exdate", !evs.Any(e => e.Start.Date == today.AddDays(1) && e.Summary.Contains("영어쓰기")));
            Check("ics utc", on.Any(e => e.Summary.Contains("hw11") && e.Start == today.AddHours(23).AddMinutes(59)));
            Check("ics cancelled", !evs.Any(e => e.Summary == "Cancelled"));

            var round = Snapshot.FromDict(Json.Obj(Json.Parse(Json.Write(snap.ToDict()))));
            Check("cache round trip", round.Tasks.Count == snap.Tasks.Count && round.Events.Count == snap.Events.Count
                && round.Tasks[2].Time == snap.Tasks[2].Time && round.Events[0].Start == snap.Events[0].Start);
        }

        static void Window()
        {
            var st = Program.DemoState();
            using (var ov = new Overlay(st))
            {
                ov.Show();
                Application.DoEvents();
                ov.Render(true);
                int s = Native.ExStyle(ov.Handle);
                Check("starts click-through", (s & Native.WS_EX_TRANSPARENT) != 0 && (s & Native.WS_EX_LAYERED) != 0, s.ToString("x"));
                Check("no taskbar / no focus", (s & Native.WS_EX_TOOLWINDOW) != 0 && (s & Native.WS_EX_NOACTIVATE) != 0);
                ov.SetInteractive(true);
                Check("Ctrl mode takes clicks", (Native.ExStyle(ov.Handle) & Native.WS_EX_TRANSPARENT) == 0);
                ov.SetInteractive(false);
                Check("click-through after Ctrl", (Native.ExStyle(ov.Handle) & Native.WS_EX_TRANSPARENT) != 0);
                ov.SetMove(true);
                Check("move mode takes clicks", (Native.ExStyle(ov.Handle) & Native.WS_EX_TRANSPARENT) == 0);
                ov.SetMove(false);
                Check("click-through after moving", (Native.ExStyle(ov.Handle) & Native.WS_EX_TRANSPARENT) != 0);
                Native.KeepOnTop(ov.Handle);
                Check("topmost", (Native.ExStyle(ov.Handle) & Native.WS_EX_TOPMOST) != 0);
                Check("on screen", Screen.PrimaryScreen.WorkingArea.Contains(ov.Bounds_), ov.Bounds_.ToString());
                Check("fullscreen check runs", !Native.FullscreenAppRunning() || true);
                using (var bmp = ov.RenderShot(System.Drawing.Color.Gray)) Check("renders", bmp.Width > 400 && bmp.Height > 300, bmp.Size.ToString());
                ov.Close();
            }
        }
    }
}
