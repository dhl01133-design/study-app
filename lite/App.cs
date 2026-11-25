// Tray icon, timers, syncing, Ctrl+click completion, deadline alarms and updates.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Timer = System.Windows.Forms.Timer;

namespace StudyOverlayLite
{
    class App : ApplicationContext
    {
        readonly AppState st;
        readonly Overlay ov;
        readonly NotifyIcon tray;
        readonly ContextMenuStrip menu = new ContextMenuStrip();
        readonly IcalCache ical = new IcalCache();
        readonly Dictionary<string, object> fired;
        readonly Timer tick = new Timer(), syncT = new Timer(), ctrlT = new Timer(), visT = new Timer(), updT = new Timer(), retryT = new Timer();
        readonly Mutex mutex;
        SettingsForm dialog;
        bool busy;
        int retrySec;
        DateTime lastDay;
        const int UndoMs = 8000;

        public App(AppState s, Mutex m)
        {
            st = s; mutex = m;
            L.En = st.Cfg.S("lang") != "ko";
            fired = st.IsDemo ? new Dictionary<string, object>() : (Cfg.ReadJson(Cfg.AlarmFile) ?? new Dictionary<string, object>());
            lastDay = st.Now.Date;

            ov = new Overlay(st) { OnTaskClick = TaskClick, OnMoveDone = () => { BuildMenu(); dialog?.SyncCorner(); } };
            var icon = TrayIcon();
            tray = new NotifyIcon { Icon = icon, Text = "Study Overlay Lite build " + BuildInfo.Build, Visible = true, ContextMenuStrip = menu };
            tray.MouseClick += (o, e) => { if (e.Button == MouseButtons.Left) Sync(); };
            BuildMenu();

            // once a minute, just after the minute starts, so the clock and Now/Next change on time
            tick.Tick += (o, e) => { tick.Interval = 60000; Tick(); };
            tick.Interval = Math.Max(1, (60 - DateTime.Now.Second) * 1000 + 300);
            tick.Start();
            syncT.Tick += (o, e) => Sync();
            syncT.Interval = Math.Max(5, st.Cfg.I("sync_min")) * 60000;
            syncT.Start();
            ctrlT.Tick += (o, e) => PollCtrl();
            ctrlT.Interval = 400;
            ctrlT.Start();
            visT.Tick += (o, e) => Visibility();
            visT.Interval = 2000;
            visT.Start();
            updT.Tick += (o, e) => { updT.Interval = 6 * 3600 * 1000; CheckUpdates(false); };
            updT.Interval = 20000;
            updT.Start();
            retryT.Tick += (o, e) => { retryT.Stop(); Sync(); };

            ov.Show();
            ov.Render(true);
            Visibility();
            var trim = new Timer { Interval = 15000 };
            trim.Tick += (o, e) => { trim.Stop(); trim.Dispose(); Native.TrimMemory(); };
            trim.Start();
            if (!st.IsDemo && st.Cfg.S("todoist_token") == "" && st.Cfg.S("ical_url") == "")
                BeginInvoke(OpenSettings);
            else Sync();
        }

        void BeginInvoke(Action a) { var t = new Timer { Interval = 300 }; t.Tick += (o, e) => { t.Stop(); t.Dispose(); a(); }; t.Start(); }

        void Ui(Action a) { if (ov.IsHandleCreated) ov.BeginInvoke(a); }

        static Icon TrayIcon()
        {
            using (var bmp = new Bitmap(32, 32))
            {
                using (var g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                    g.Clear(Color.Transparent);
                    using (var pen = new Pen(Color.FromArgb(0x3B, 0x6E, 0xF5), 5)) g.DrawEllipse(pen, 5, 5, 22, 22);
                }
                var h = bmp.GetHicon();
                var icon = (Icon)Icon.FromHandle(h).Clone();
                Native.DestroyIcon(h);
                return icon;
            }
        }

        // ---------------------------------------------------------------- menu

        void BuildMenu()
        {
            menu.Items.Clear();
            menu.Items.Add(L.Tr("동기화", "Sync"), null, (o, e) => Sync());
            var shown = new ToolStripMenuItem(L.Tr("표시", "Show")) { Checked = st.Cfg.B("visible"), CheckOnClick = true };
            shown.CheckedChanged += (o, e) => { st.Cfg["visible"] = shown.Checked; Save(); Visibility(); };
            menu.Items.Add(shown);
            if (ov.Moving) menu.Items.Add(L.Tr("옮기기 완료", "Done moving"), null, (o, e) => ov.SetMove(false));
            else menu.Items.Add(L.Tr("옮기기", "Move"), null, (o, e) => StartMove());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(L.Tr("업데이트 확인", "Check for updates"), null, (o, e) => CheckUpdates(true));
            menu.Items.Add(L.Tr("설정", "Settings"), null, (o, e) => OpenSettings());
            menu.Items.Add(L.Tr("종료", "Quit"), null, (o, e) => Quit());
        }

        void Save() { if (!st.IsDemo) st.Cfg.Save(); }

        void Quit()
        {
            tray.Visible = false;
            ExitThread();
        }

        protected override void ExitThreadCore()
        {
            tray.Visible = false;
            tray.Dispose();
            ov.Close();
            base.ExitThreadCore();
        }

        void StartMove()
        {
            st.Cfg["visible"] = true;
            Visibility();
            ov.SetMove(true);
            BuildMenu();
        }

        // ---------------------------------------------------------------- settings

        void Apply()
        {
            L.En = st.Cfg.S("lang") != "ko";
            BuildMenu();
            if (st.Cfg.B("on_top")) Native.KeepOnTop(ov.Handle); else Native.SendToBottom(ov.Handle);
            syncT.Interval = Math.Max(5, st.Cfg.I("sync_min")) * 60000;
            ov.Render();
            Visibility();
        }

        void OpenSettings()
        {
            if (dialog != null) { dialog.Activate(); return; }
            var accountsBefore = (st.Cfg.S("todoist_token"), st.Cfg.S("ical_url"), st.Cfg.S("github_token"));
            bool autoBefore = Autostart.IsOn();
            dialog = new SettingsForm(st, Apply, StartMove);
            dialog.FormClosed += (o, e) =>
            {
                var d = dialog;
                dialog = null;
                ov.SetMove(false);
                if (d.DialogResult != DialogResult.OK)
                {
                    st.Cfg.V = new Dictionary<string, object>(d.Original);   // Cancel undoes every preview, including a drag
                    Save();
                    Apply();
                    return;
                }
                var v = d.Values();
                st.Cfg["todoist_token"] = v.todoist; st.Cfg["ical_url"] = v.ical; st.Cfg["github_token"] = v.github;
                st.Cfg["auto_update"] = v.autoUpdate; st.Cfg["start_with_windows"] = v.autostart;
                Save();
                if (v.autostart != autoBefore) Autostart.Set(v.autostart);   // the registry only on an explicit change
                Apply();
                if ((v.todoist, v.ical, v.github) != accountsBefore) { Sync(); CheckUpdates(false); }
            };
            dialog.Show();
            dialog.Activate();
        }

        // ---------------------------------------------------------------- sync

        void Sync()
        {
            if (st.IsDemo || busy) return;
            busy = true;
            string tok = st.Cfg.S("todoist_token"), url = st.Cfg.S("ical_url");
            var today = st.Now.Date;
            Task.Run(() =>
            {
                List<TaskItem> tasks = null; List<EventItem> events = null; var errors = new List<string>();
                if (tok != "")
                    try { tasks = Todoist.Tasks(tok); }
                    catch (Exception e) { errors.Add(e.Message.StartsWith("Todoist") ? e.Message : "Todoist: " + e.Message); }
                if (url != "")
                    try { events = ical.Fetch(url, today); }
                    catch (Exception e) { errors.Add(L.Tr("캘린더: ", "Calendar: ") + e.GetType().Name); }
                Ui(() => Fetched(tasks, events, errors, tok != "", url != ""));
            });
        }

        void Fetched(List<TaskItem> tasks, List<EventItem> events, List<string> errors, bool hadTok, bool hadUrl)
        {
            busy = false;
            var snap = new Snapshot
            {
                Fetched = (DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalSeconds, Errors = errors,
                Tasks = tasks ?? (hadTok ? st.Snap.Tasks : new List<TaskItem>()),   // keep the last good data when one source fails
                Events = events ?? (hadUrl ? st.Snap.Events : new List<EventItem>()),
            };
            bool changed = !ReferenceEquals(snap.Events, st.Snap.Events) || Json.Write(snap.ToDict()["tasks"]) != Json.Write(st.Snap.ToDict()["tasks"])
                           || string.Join("|", errors) != string.Join("|", st.Snap.Errors);
            st.Snap = snap;
            if (changed)
            {
                st.Bump();
                Cfg.WriteJson(Cfg.CacheFile, snap.ToDict());
            }
            CheckAlarms();
            if (errors.Count > 0)   // e.g. just after login, before Wi-Fi is up: try again soon, backing off to 10 min
            {
                retrySec = retrySec == 0 ? 60 : Math.Min(retrySec * 2, 600);
                retryT.Interval = retrySec * 1000;
                retryT.Start();
            }
            else { retrySec = 0; retryT.Stop(); }
            ov.Render();
            Native.TrimMemory();
        }

        void Tick()
        {
            if (st.Now.Date != lastDay) { lastDay = st.Now.Date; Sync(); }
            ov.Render();
            CheckAlarms();
        }

        // ---------------------------------------------------------------- visibility, Ctrl+click

        void Visibility()
        {
            bool want = ov.Moving || (st.Cfg.B("visible") && !(st.Cfg.B("hide_fullscreen") && Native.FullscreenAppRunning()));
            if (want && !ov.Visible) { ov.Show(); ov.Render(true); }
            else if (!want && ov.Visible) ov.Hide();
            if (want && st.Cfg.B("on_top")) Native.KeepOnTop(ov.Handle);
        }

        void PollCtrl()
        {
            bool over = !ov.Moving && ov.PointerOver();
            ov.SetInteractive(over && Native.CtrlDown());
            int want = over ? 60 : 400;   // quick near the list, idle elsewhere
            if (ctrlT.Interval != want) ctrlT.Interval = want;
        }

        void TaskClick(string id)
        {
            string op = st.Done.ContainsKey(id) ? "reopen" : "close";
            if (op == "close") st.Done[id] = DateTime.Now; else st.Done.Remove(id);
            ov.Render();
            if (op == "close") { var t = new Timer { Interval = UndoMs }; t.Tick += (o, e) => { t.Stop(); t.Dispose(); Purge(id); }; t.Start(); }
            if (st.IsDemo) return;
            var tok = st.Cfg.S("todoist_token");
            if (tok == "") return;
            Task.Run(() =>
            {
                try { Todoist.Post(tok, $"/tasks/{id}/{op}"); }
                catch (Exception e)
                {
                    Ui(() =>
                    {
                        if (op == "close") st.Done.Remove(id); else st.Done[id] = DateTime.Now;
                        tray.ShowBalloonTip(5000, "Study Overlay Lite", e.Message, ToolTipIcon.Warning);
                        ov.Render();
                    });
                }
            });
        }

        void Purge(string id)
        {
            if (!st.Done.TryGetValue(id, out var t0) || (DateTime.Now - t0).TotalMilliseconds < UndoMs - 500) return;   // undone, or completed again later
            st.Done.Remove(id);
            st.Snap.Tasks = st.Snap.Tasks.Where(t => t.Id != id).ToList();
            st.Bump();
            if (!st.IsDemo) Cfg.WriteJson(Cfg.CacheFile, st.Snap.ToDict());
            ov.Render();
        }

        // ---------------------------------------------------------------- alarms

        void CheckAlarms()
        {
            if (st.IsDemo || !st.Cfg.B("alarms")) return;
            var now = st.Now;
            int before = fired.Count;
            var due = Brief.AlarmsDue(Brief.Deadlines(st.Snap), fired, now);
            if (due.Count > 0 || fired.Count != before) Cfg.WriteJson(Cfg.AlarmFile, fired);
            if (due.Count == 0) return;
            var first = due[0];
            var body = string.Join("\n", due.Take(5).Select(d => (d.Course != "" ? $"[{d.Course}] " : "") + d.Title + (d == first ? "" : "  " + L.Hm(d.At))));
            tray.ShowBalloonTip(15000, L.Tr("마감 ", "Due ") + L.Until(now, first.At), body, ToolTipIcon.Info);
        }

        // ---------------------------------------------------------------- updates

        void CheckUpdates(bool manual)
        {
            if (st.IsDemo) return;
            var tok = st.Cfg.S("github_token");
            if (tok == "")
            {
                if (manual) tray.ShowBalloonTip(5000, "Study Overlay Lite", L.Tr("설정 → 계정에 GitHub 토큰을 입력하세요", "Add a GitHub token in Settings → Accounts"), ToolTipIcon.Info);
                return;
            }
            bool auto = st.Cfg.B("auto_update");
            Task.Run(() =>
            {
                try
                {
                    var (build, id, size) = Updater.Check(tok);
                    if (build <= BuildInfo.Build)
                    {
                        if (manual) Ui(() => tray.ShowBalloonTip(5000, "Study Overlay Lite", L.Tr($"최신 버전 (build {BuildInfo.Build})", $"Up to date (build {BuildInfo.Build})"), ToolTipIcon.Info));
                        return;
                    }
                    if (!(manual || auto)) return;
                    var path = Updater.Install(tok, id, size);
                    Ui(() => Restart(path));
                }
                catch (Exception e)
                {
                    if (manual) Ui(() => tray.ShowBalloonTip(5000, "Study Overlay Lite", L.Tr("업데이트 실패: ", "Update failed: ") + e.Message, ToolTipIcon.Warning));
                }
            });
        }

        void Restart(string path)
        {
            tray.Visible = false;
            try { mutex.ReleaseMutex(); } catch { }
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(path) });
            ExitThread();
        }
    }
}
