// Settings: Appearance / List / Behavior / Accounts. Non-modal; changes preview live and Cancel undoes them.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;

namespace StudyOverlayLite
{
    class SettingsForm : Form
    {
        readonly AppState st;
        readonly Action apply, startMove;
        public readonly Dictionary<string, object> Original;
        ComboBox corner;
        Button colorBtn;
        TextBox token, ical, gh;
        CheckBox auto, upd;
        readonly (string key, string name)[] corners;

        public SettingsForm(AppState s, Action apply, Action startMove)
        {
            st = s; this.apply = apply; this.startMove = startMove;
            Original = new Dictionary<string, object>(s.Cfg.V);
            Text = L.Tr("설정", "Settings");
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            Font = new Font("Segoe UI", 9f);
            ClientSize = new Size(560, 380);
            ShowInTaskbar = true;
            corners = new[] { ("top-left", L.Tr("왼쪽 위", "Top left")), ("top-right", L.Tr("오른쪽 위", "Top right")),
                ("bottom-left", L.Tr("왼쪽 아래", "Bottom left")), ("bottom-right", L.Tr("오른쪽 아래", "Bottom right")), ("custom", L.Tr("직접 지정", "Custom")) };

            var tabs = new TabControl { Dock = DockStyle.Fill };
            tabs.TabPages.Add(Page(L.Tr("모양", "Appearance"), Appearance));
            tabs.TabPages.Add(Page(L.Tr("목록", "List"), ListTab));
            tabs.TabPages.Add(Page(L.Tr("동작", "Behavior"), Behavior));
            tabs.TabPages.Add(Page(L.Tr("계정", "Accounts"), Accounts));

            var save = new Button { Text = L.Tr("저장", "Save"), DialogResult = DialogResult.OK, Width = 90 };
            var cancel = new Button { Text = L.Tr("취소", "Cancel"), DialogResult = DialogResult.Cancel, Width = 90 };
            var bar = new FlowLayoutPanel { Dock = DockStyle.Bottom, FlowDirection = FlowDirection.RightToLeft, Height = 42, Padding = new Padding(6) };
            bar.Controls.Add(cancel); bar.Controls.Add(save);
            AcceptButton = save; CancelButton = cancel;
            Controls.Add(tabs);
            Controls.Add(bar);
        }

        static TabPage Page(string title, Action<TableLayoutPanel> fill)
        {
            var t = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Padding = new Padding(10), AutoScroll = true };
            t.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            fill(t);
            var p = new TabPage(title);
            p.Controls.Add(t);
            return p;
        }

        void Live(string k, object v) { st.Cfg[k] = v; apply(); }

        static void Row(TableLayoutPanel t, string label, Control c)
        {
            t.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(3, 8, 12, 3) });
            c.Anchor = AnchorStyles.Left | AnchorStyles.Right;
            t.Controls.Add(c);
        }

        ComboBox Choice(TableLayoutPanel t, string label, string key, (string key, string name)[] opts, Action<string> onChange = null)
        {
            var cb = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList };
            foreach (var o in opts) cb.Items.Add(o.name);
            var cur = st.Cfg.S(key);
            int i = Array.FindIndex(opts, o => o.key == cur);
            cb.SelectedIndex = i >= 0 ? i : 0;
            cb.SelectedIndexChanged += (s, e) => { var k = opts[cb.SelectedIndex].key; if (onChange != null) onChange(k); else Live(key, k); };
            Row(t, label, cb);
            return cb;
        }

        CheckBox Check(TableLayoutPanel t, string label, string key)
        {
            var c = new CheckBox { Text = label, AutoSize = true, Checked = st.Cfg.B(key) };
            c.CheckedChanged += (s, e) => Live(key, c.Checked);
            t.Controls.Add(new Label());
            t.Controls.Add(c);
            return c;
        }

        Control Slider(int lo, int hi, int val, Action<int> change)
        {
            var p = new TableLayoutPanel { ColumnCount = 2, Height = 32, Dock = DockStyle.Fill };
            p.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            p.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 50));
            var tb = new TrackBar { Minimum = lo, Maximum = hi, Value = Math.Max(lo, Math.Min(hi, val)), TickStyle = TickStyle.None, Dock = DockStyle.Fill };
            var lab = new Label { Text = tb.Value + "%", AutoSize = true, Anchor = AnchorStyles.Left };
            tb.ValueChanged += (s, e) => { lab.Text = tb.Value + "%"; change(tb.Value); };
            p.Controls.Add(tb); p.Controls.Add(lab);
            return p;
        }

        void Appearance(TableLayoutPanel t)
        {
            Choice(t, L.Tr("배경", "Background"), "background", new[] { ("none", L.Tr("없음", "None")), ("white", L.Tr("흰색", "White")), ("dark", L.Tr("어두운색", "Dark")) },
                k => { st.Cfg["background"] = k; var m = k == "white" ? "#111111" : k == "dark" ? "#F4F4F5" : null; if (m != null) SetColor(m); else apply(); });

            var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false };
            colorBtn = new Button { Width = 140, TextImageRelation = TextImageRelation.ImageBeforeText };
            colorBtn.Click += (s, e) =>
            {
                using (var d = new ColorDialog { Color = Pal.ParseHex(st.Cfg.S("text_color"), Color.Black), FullOpen = true })
                    if (d.ShowDialog(this) == DialogResult.OK) SetColor(ColorTranslator.ToHtml(Color.FromArgb(255, d.Color)));
            };
            var def = new Button { Text = L.Tr("기본값", "Default"), AutoSize = true };
            def.Click += (s, e) => SetColor(st.Cfg.S("background") == "dark" ? "#F4F4F5" : "#111111");
            row.Controls.Add(colorBtn); row.Controls.Add(def);
            Row(t, L.Tr("글자색", "Text color"), row);
            ShowColor();

            Choice(t, L.Tr("글자 테두리", "Text outline"), "outline", new[] { ("shadow", L.Tr("그림자", "Soft shadow")), ("thin", L.Tr("얇게", "Thin")),
                ("medium", L.Tr("보통", "Medium")), ("thick", L.Tr("두껍게", "Thick")) });
            Row(t, L.Tr("크기", "Size"), Slider(60, 200, (int)Math.Round(st.Cfg.D("scale") * 100), v => Live("scale", v / 100.0)));
            Row(t, L.Tr("불투명도", "Opacity"), Slider(25, 100, (int)Math.Round(st.Cfg.D("opacity") * 100), v => Live("opacity", v / 100.0)));

            var prow = new FlowLayoutPanel { AutoSize = true, WrapContents = false };
            corner = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 150 };
            foreach (var c in corners) corner.Items.Add(c.name);
            SyncCorner();
            corner.SelectedIndexChanged += (s, e) => Live("corner", corners[corner.SelectedIndex].key);
            var move = new Button { Text = L.Tr("끌어서 옮기기", "Drag to move"), AutoSize = true };
            move.Click += (s, e) => startMove();
            prow.Controls.Add(corner); prow.Controls.Add(move);
            Row(t, L.Tr("위치", "Position"), prow);
            Check(t, L.Tr("오늘 시간 막대", "Today's time bar"), "show_bar");
        }

        void ListTab(TableLayoutPanel t)
        {
            Check(t, L.Tr("끝난 일정", "Finished events"), "show_past");
            Check(t, L.Tr("지난 날로 계획된 할 일", "Tasks planned for earlier days"), "show_carried");
            Check(t, L.Tr("지금 / 다음", "Now / Next"), "show_upcoming");
            Check(t, L.Tr("iCampus 할 일만", "Only iCampus tasks"), "only_icampus");
        }

        void Behavior(TableLayoutPanel t)
        {
            var mins = new[] { 5, 15, 30, 60 };
            Choice(t, L.Tr("동기화", "Sync"), "sync_min_s", mins.Select(m => (m.ToString(), L.Tr($"{m}분마다", $"Every {m} min"))).ToArray(),
                k => Live("sync_min", int.Parse(k)));
            var sync = (ComboBox)t.Controls[t.Controls.Count - 1];
            sync.SelectedIndex = Math.Max(0, Array.IndexOf(mins, st.Cfg.I("sync_min")));
            Check(t, L.Tr("마감 알림 (6시간·1시간 전)", "Deadline alarms (6 h and 1 h before)"), "alarms");
            Choice(t, L.Tr("언어", "Language"), "lang", new[] { ("en", "English"), ("ko", "한국어") });
            Check(t, L.Tr("항상 위에", "Always on top"), "on_top");
            Check(t, L.Tr("전체 화면일 때 숨기기", "Hide in full screen"), "hide_fullscreen");
            auto = new CheckBox { Text = L.Tr("Windows 시작 시 실행", "Start with Windows"), AutoSize = true, Checked = Autostart.IsOn() };
            t.Controls.Add(new Label());
            t.Controls.Add(auto);
        }

        void Accounts(TableLayoutPanel t)
        {
            token = Secret(t, L.Tr("Todoist 토큰", "Todoist token"), st.Cfg.S("todoist_token"));
            ical = Secret(t, L.Tr("캘린더 iCal 주소", "Calendar iCal address"), st.Cfg.S("ical_url"));
            gh = Secret(t, L.Tr("GitHub 토큰", "GitHub token"), st.Cfg.S("github_token"));
            upd = new CheckBox { Text = L.Tr("자동 업데이트", "Update automatically"), AutoSize = true, Checked = st.Cfg.B("auto_update") };
            t.Controls.Add(new Label());
            t.Controls.Add(upd);
        }

        TextBox Secret(TableLayoutPanel t, string label, string value)
        {
            var p = new TableLayoutPanel { ColumnCount = 2, Height = 30, Dock = DockStyle.Fill };
            p.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            p.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            var tb = new TextBox { Text = value, UseSystemPasswordChar = true, Dock = DockStyle.Fill };
            var b = new Button { Text = L.Tr("보기", "Show"), AutoSize = true, FlatStyle = FlatStyle.Flat };
            b.FlatAppearance.BorderSize = 0;
            b.Click += (s, e) => { tb.UseSystemPasswordChar = !tb.UseSystemPasswordChar; b.Text = tb.UseSystemPasswordChar ? L.Tr("보기", "Show") : L.Tr("숨기기", "Hide"); };
            p.Controls.Add(tb); p.Controls.Add(b);
            Row(t, label, p);
            return tb;
        }

        void SetColor(string hex) { st.Cfg["text_color"] = hex; ShowColor(); apply(); }

        void ShowColor()
        {
            var c = Pal.ParseHex(st.Cfg.S("text_color"), Color.Black);
            var bmp = new Bitmap(14, 14);
            using (var g = Graphics.FromImage(bmp)) { g.Clear(c); g.DrawRectangle(Pens.Gray, 0, 0, 13, 13); }
            colorBtn.Image?.Dispose();
            colorBtn.Image = bmp;
            colorBtn.Text = ColorTranslator.ToHtml(Color.FromArgb(255, c)).ToUpperInvariant();
        }

        public void SyncCorner()
        {
            if (corner == null) return;
            int i = Array.FindIndex(corners, c => c.key == st.Cfg.S("corner"));
            int want = i >= 0 ? i : 3;
            if (corner.SelectedIndex != want) corner.SelectedIndex = want;
        }

        static string Clean(string s) => s.Trim().Trim('\'', '"').Replace(" ", "");

        public (string todoist, string ical, string github, bool autoUpdate, bool autostart) Values() =>
            (Clean(token.Text), Clean(ical.Text), Clean(gh.Text), upd.Checked, auto.Checked);
    }

    /// The HKCU Run key, written only when the user changes "Start with Windows" (AhnLab flagged automatic autostart).
    static class Autostart
    {
        const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run", Name = "StudyOverlayLite";

        public static bool IsOn()
        {
            try { using (var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(Key)) return k?.GetValue(Name) != null; }
            catch { return false; }
        }

        public static void Set(bool on)
        {
            try
            {
                using (var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(Key, true))
                {
                    if (k == null) return;
                    if (on) k.SetValue(Name, "\"" + Updater.Exe + "\"");
                    else if (k.GetValue(Name) != null) k.DeleteValue(Name);
                }
            }
            catch { }
        }
    }
}
