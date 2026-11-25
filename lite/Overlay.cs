// The overlay window: a click-through layered window drawn with GDI+ into one bitmap per change.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Linq;
using System.Windows.Forms;

namespace StudyOverlayLite
{
    class AppState
    {
        public Cfg Cfg;
        public Snapshot Snap = new Snapshot();
        public Dictionary<string, DateTime> Done = new Dictionary<string, DateTime>();   // just completed: struck through until purged
        public bool IsDemo;
        public DateTime? FixedNow;
        public int Version;
        string viewKey; View view;
        string sliceKey; Slice slice;

        public DateTime Now => FixedNow ?? DateTime.Now;
        public void Bump() => Version++;

        public string ViewKey()
        {
            var n = Now;
            return $"{Version}|{n:yyyyMMddHHmm}|{Cfg.B("show_past")}{Cfg.B("show_carried")}{Cfg.B("show_upcoming")}{Cfg.B("only_icampus")}|{Cfg.S("lang")}|{string.Join(",", Done.Keys.OrderBy(k => k))}";
        }

        /// Built once per minute or data change, not on every layout or paint.
        public View GetView()
        {
            var k = ViewKey();
            if (k != viewKey) { view = Brief.Build(Snap, Now, Cfg, Done.Keys); viewKey = k; }
            return view;
        }

        public Slice GetSlice()
        {
            var k = $"{Version}|{Now:yyyyMMdd}";
            if (k != sliceKey) { slice = Slice.Of(Snap, Now.Date); sliceKey = k; }
            return slice;
        }

        public string Status()
        {
            if (IsDemo) return "";
            if (Cfg.S("todoist_token") == "" && Cfg.S("ical_url") == "") return L.Tr("트레이 아이콘 → 설정", "Tray icon → Settings");
            var e = string.Join("; ", Snap.Errors);
            return e.Length > 120 ? e.Substring(0, 120) : e;
        }
    }

    class Pal
    {
        public Color Text, Sub, Faint, Edge, Halo, Accent, OnAccent, Danger, P1, P2, Good, GoodSoft, Track, Busy, OnBusy, Hatch;
        public Color? Panel; public Color PanelLine;
        public bool Light; public float Stroke;

        static Color Mix(Color a, Color b, double f) => Color.FromArgb((int)(a.R + (b.R - a.R) * f), (int)(a.G + (b.G - a.G) * f), (int)(a.B + (b.B - a.B) * f));
        static Color A(Color c, double a) => Color.FromArgb((int)(a * 255), c);

        public static Color ParseHex(string s, Color def)
        {
            try { return ColorTranslator.FromHtml(s); } catch { return def; }
        }

        public static Pal Of(Cfg cfg)
        {
            var t = ParseHex(cfg.S("text_color"), Color.FromArgb(0x11, 0x11, 0x11));
            bool light = t.GetBrightness() >= 0.5f;
            Color P(int onDark, int onLight) => Color.FromArgb(255, Color.FromArgb(light ? onDark : onLight));
            var p = new Pal { Text = t, Light = light };
            var o = cfg.S("outline");
            p.Stroke = o == "thin" ? 2.4f : o == "medium" ? 3.6f : o == "thick" ? 5.2f : 0f;
            p.Edge = light ? Color.Black : Color.White;   // always the opposite of the text
            p.Sub = p.Stroke > 0 ? Mix(t, p.Edge, .25) : A(t, .74);
            p.Faint = p.Stroke > 0 ? Mix(t, p.Edge, .48) : A(t, .45);
            p.Halo = light ? Color.FromArgb(85, 0, 0, 0) : Color.FromArgb(90, 255, 255, 255);
            p.Accent = P(0x7FA6FF, 0x3B6EF5); p.OnAccent = P(0x0E0E10, 0xFFFFFF); p.Danger = P(0xFF6B6E, 0xD93036);
            p.P1 = P(0xFF9F43, 0xE8740C); p.P2 = p.Accent; p.Good = P(0x4CC98A, 0x1F9D63); p.GoodSoft = A(p.Good, .28);
            p.Track = A(t, .14); p.Busy = A(t, .80); p.OnBusy = P(0x0E0E10, 0xFFFFFF); p.Hatch = A(t, .38);
            var bg = cfg.S("background");
            p.Panel = bg == "white" ? Color.White : bg == "dark" ? Color.FromArgb(22, 22, 26) : (Color?)null;
            p.PanelLine = bg == "white" ? Color.FromArgb(30, 0, 0, 0) : Color.FromArgb(30, 255, 255, 255);
            return p;
        }

        public static Color CourseColor(string c, bool light)
        {
            var col = Courses.Colors.TryGetValue(c, out var v) ? Color.FromArgb(255, Color.FromArgb(v)) : (light ? Color.FromArgb(0xB8, 0xBC, 0xC4) : Color.FromArgb(0x5A, 0x62, 0x70));
            return light ? Color.FromArgb(col.R / 2 + 127, col.G / 2 + 127, col.B / 2 + 127) : col;   // lighter on dark wallpapers
        }
    }

    class Overlay : Form
    {
        public const int W = 462, PAD = 14, MAX_ROWS = 16, MARGIN = 28;
        readonly AppState S;
        public bool Interactive, Moving;
        public string Hover;
        public Action<string> OnTaskClick;
        public Action OnMoveDone;
        public Rectangle Bounds_;    // where the layered window is, in screen pixels
        readonly List<(RectangleF r, string id)> hits = new List<(RectangleF, string)>();
        string picKey;
        Bitmap pic;
        Point dragOff;
        bool dragging;
        public readonly float Dpi;

        readonly FontFamily fam;
        Font fHead, fRow, fUp, fSmall, fBar;
        readonly StringFormat sfDraw, sfMeasure;

        public Overlay(AppState s)
        {
            S = s;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            using (var g = Graphics.FromHwnd(IntPtr.Zero)) Dpi = g.DpiX / 96f;
            fam = FontFamily.Families.Any(f => f.Name == "Malgun Gothic") ? new FontFamily("Malgun Gothic") : new FontFamily("Segoe UI");   // Malgun has Hangul and Latin
            fHead = new Font(fam, 26, FontStyle.Bold, GraphicsUnit.Pixel);
            fRow = new Font(fam, 15, FontStyle.Regular, GraphicsUnit.Pixel);
            fUp = new Font(fam, 14, FontStyle.Regular, GraphicsUnit.Pixel);
            fSmall = new Font(fam, 12, FontStyle.Regular, GraphicsUnit.Pixel);
            fBar = new Font(fam, 10, FontStyle.Regular, GraphicsUnit.Pixel);
            sfDraw = (StringFormat)StringFormat.GenericTypographic.Clone();
            sfMeasure = (StringFormat)StringFormat.GenericTypographic.Clone();
            sfMeasure.FormatFlags |= StringFormatFlags.MeasureTrailingSpaces;
        }

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= Native.WS_EX_LAYERED | Native.WS_EX_TOOLWINDOW | Native.WS_EX_NOACTIVATE | Native.WS_EX_TRANSPARENT;
                if (S != null && S.Cfg.B("on_top")) cp.ExStyle |= Native.WS_EX_TOPMOST;   // S is null while the base constructor runs
                return cp;
            }
        }

        protected override bool ShowWithoutActivation => true;
        protected override void OnPaintBackground(PaintEventArgs e) { }
        protected override void OnPaint(PaintEventArgs e) { }

        public float K => Dpi * (float)Math.Max(0.6, Math.Min(2.0, S.Cfg.D("scale")));

        // ---------------------------------------------------------------- layout

        float FixedHeight(View v) => PAD + 38 + v.Upcoming.Count * 22 + (S.Cfg.B("show_bar") ? 48 : 0) + 10 + (S.Status() != "" ? 22 : 0) + PAD;

        public int MaxRows(View v)
        {
            float avail = Screen.PrimaryScreen.WorkingArea.Height / K - FixedHeight(v);
            int fit = Math.Max((int)(avail / 25), 2);
            int n = v.Rows.Count;
            if (n <= Math.Min(MAX_ROWS, fit)) return n;
            return Math.Max(Math.Min(MAX_ROWS, fit) - 1, 1);   // one line goes to "+N more"
        }

        public float LayoutHeight(View v)
        {
            int shown = MaxRows(v);
            return FixedHeight(v) + Math.Max(shown + (v.Rows.Count > shown ? 1 : 0), 1) * 25;
        }

        Point Place(Size sz)
        {
            var r = Screen.PrimaryScreen.WorkingArea;
            var c = S.Cfg;
            int x, y, m = (int)(MARGIN * Dpi);
            if (c.S("corner") == "custom")
            {
                x = r.Left + c.I("pos_x");
                y = c.S("pos_anchor") == "bottom" ? r.Bottom - sz.Height - c.I("pos_y") : r.Top + c.I("pos_y");
            }
            else
            {
                x = c.S("corner").Contains("right") ? r.Right - sz.Width - m : r.Left + m;
                y = c.S("corner").Contains("bottom") ? r.Bottom - sz.Height - m : r.Top + m;
            }
            return Clamp(new Point(x, y), sz);
        }

        Point Clamp(Point p, Size sz)
        {
            var r = Screen.PrimaryScreen.WorkingArea;
            return new Point(Math.Min(Math.Max(p.X, r.Left), r.Right - sz.Width), Math.Min(Math.Max(p.Y, r.Top), r.Bottom - sz.Height));
        }

        /// Re-renders only when the picture would differ (minute ticked, data or settings changed, hover…).
        public void Render(bool force = false)
        {
            if (!IsHandleCreated) return;
            var v = S.GetView();
            float k = K;
            var sz = new Size((int)Math.Ceiling(W * k), (int)Math.Ceiling(LayoutHeight(v) * k));
            var pos = dragging ? Bounds_.Location : Place(sz);
            string key = $"{S.ViewKey()}|{S.Cfg.Fingerprint()}|{Interactive}|{Hover}|{Moving}|{sz}|{S.Status()}";
            if (!force && key == picKey && pos == Bounds_.Location) return;
            if (key != picKey || pic == null)
            {
                pic?.Dispose();
                pic = new Bitmap(sz.Width, sz.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                using (var g = Graphics.FromImage(pic))
                {
                    g.Clear(Color.Transparent);
                    g.ScaleTransform(k, k);
                    Paint(g, v);
                }
                picKey = key;
            }
            Bounds_ = new Rectangle(pos, sz);
            Native.Present(Handle, pic, pos, (byte)Math.Round(255 * Math.Max(0.25, Math.Min(1, S.Cfg.D("opacity")))));
        }

        public Bitmap RenderShot(Color bg)
        {
            var v = S.GetView();
            float k = K * 2 / Dpi;
            var bmp = new Bitmap((int)(W * k) + 80, (int)(LayoutHeight(v) * k) + 80);
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(bg);
                g.TranslateTransform(40, 40);
                g.ScaleTransform(k, k);
                Paint(g, v);
            }
            return bmp;
        }

        // ---------------------------------------------------------------- drawing

        float Measure(Graphics g, string s, Font f) => string.IsNullOrEmpty(s) ? 0 : g.MeasureString(s, f, PointF.Empty, sfMeasure).Width;

        string Elide(Graphics g, string s, Font f, float width)
        {
            if (Measure(g, s, f) <= width) return s;
            for (int n = s.Length - 1; n > 0; n--)
            {
                var t = s.Substring(0, n).TrimEnd() + "…";
                if (Measure(g, t, f) <= width) return t;
            }
            return "…";
        }

        /// Outlined text: the glyph outlines are stroked in the opposite color, then filled. align: 0 left, 1 right, 2 center.
        float Text(Graphics g, Pal c, float x, float y, string s, Font f, Color col, float width = -1, int align = 0, bool strike = false)
        {
            if (string.IsNullOrEmpty(s)) return 0;
            if (width >= 0) s = Elide(g, s, f, width);
            float adv = Measure(g, s, f);
            float w = width >= 0 ? width : adv;
            float x0 = align == 1 ? x + w - adv : align == 2 ? x + (w - adv) / 2 : x;
            float top = y + (f.Size * 1.5f - f.Size * 1.33f) / 2;   // a 1.5-line box like the Qt version
            using (var path = new GraphicsPath())
            {
                path.AddString(s, f.FontFamily, (int)f.Style, f.Size, new PointF(x0, top), sfDraw);
                if (strike) path.AddRectangle(new RectangleF(x0, top + f.Size * 0.62f, adv, Math.Max(1.2f, f.Size / 14)));
                if (c.Stroke > 0)
                {
                    float sw = c.Stroke * Math.Min(Math.Max(f.Size / 15f, 0.7f), 1.4f);
                    using (var pen = new Pen(c.Edge, sw) { LineJoin = LineJoin.Round })
                        g.DrawPath(pen, path);
                }
                else
                {
                    using (var hb = new SolidBrush(c.Halo))
                        foreach (var (dx, dy) in new[] { (1, 1), (-1, 1), (1, -1), (-1, -1), (0, 2), (2, 0) })
                        {
                            g.TranslateTransform(dx, dy);
                            g.FillPath(hb, path);
                            g.TranslateTransform(-dx, -dy);
                        }
                }
                using (var b = new SolidBrush(col)) g.FillPath(b, path);
            }
            return adv;
        }

        static GraphicsPath Round(RectangleF r, float rad)
        {
            var p = new GraphicsPath();
            float d = Math.Min(rad * 2, Math.Min(r.Width, r.Height));
            if (d <= 0.5f) { p.AddRectangle(r); return p; }
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        static void FillRound(Graphics g, RectangleF r, float rad, Color fill, Color? line = null, float lw = 1)
        {
            using (var p = Round(r, rad))
            {
                using (var b = new SolidBrush(fill)) g.FillPath(b, p);
                if (line.HasValue) using (var pen = new Pen(line.Value, lw)) g.DrawPath(pen, p);
            }
        }

        void Paint(Graphics g, View v)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;   // never ClearType on a transparent bitmap
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            var c = Pal.Of(S.Cfg);
            var now = S.Now;
            float right = W - PAD, H = LayoutHeight(v);
            hits.Clear();

            if (c.Panel.HasValue) FillRound(g, new RectangleF(0.5f, 0.5f, W - 1, H - 1), 12, c.Panel.Value, c.PanelLine);
            if (Moving) using (var pen = new Pen(c.Accent, 1.5f) { DashStyle = DashStyle.Dash })
                {
                    FillRound(g, new RectangleF(1, 1, W - 2, H - 2), 10, Color.FromArgb(30, c.Accent));
                    using (var p = Round(new RectangleF(1, 1, W - 2, H - 2), 10)) g.DrawPath(pen, p);
                }
            else if (Interactive && !c.Panel.HasValue) FillRound(g, new RectangleF(0, 0, W, H), 10, Color.FromArgb(40, c.Light ? Color.Black : Color.White));

            float y = PAD;
            Text(g, c, PAD, y - 4, L.Day(now), fHead, c.Text);
            if (v.Overdue > 0) Text(g, c, PAD, y + 4, L.Tr($"기한 지남 {v.Overdue}", $"{v.Overdue} overdue"), fRow, c.Danger, right - PAD, 1);
            y += 38;
            foreach (var u in v.Upcoming)
            {
                float x = PAD + Text(g, c, PAD, y, u.Label + "  ", fUp, c.Sub);
                float nw = Measure(g, u.Note, fUp) + 4;
                Text(g, c, x, y, u.Title, fUp, c.Text, right - x - nw - 10);
                Text(g, c, right - nw, y, u.Note, fUp, c.Accent, nw, 1);
                y += 22;
            }
            if (S.Cfg.B("show_bar")) { PaintBar(g, c, PAD, y + 8, right - PAD, now); y += 48; }
            y += 10;

            if (v.Rows.Count == 0) { Text(g, c, PAD + 22, y, L.Tr("없음", "Nothing"), fRow, c.Sub); y += 25; }
            int shown = MaxRows(v);
            foreach (var r in v.Rows.Take(shown))
            {
                if (r.TaskId != null)
                {
                    hits.Add((new RectangleF(0, y, W, 25), r.TaskId));
                    if (Interactive && Hover == r.TaskId) FillRound(g, new RectangleF(PAD - 2, y, W - 2 * PAD + 4, 25), 5, Color.FromArgb(26, c.Text));
                }
                if (r.Kind == "task" || r.Kind == "overdue")   // priority circle: orange p1, blue p2; filled once completed
                {
                    var ring = r.Priority == 4 ? c.P1 : r.Priority == 3 ? c.P2 : c.Sub;
                    var circle = new RectangleF(PAD + 2, y + 6, 12, 12);
                    using (var pen = new Pen(c.Stroke > 0 ? c.Edge : c.Halo, 1.6f + (c.Stroke > 0 ? c.Stroke : 1.6f))) g.DrawEllipse(pen, circle);
                    using (var pen = new Pen(ring, 1.6f)) g.DrawEllipse(pen, circle);
                    if (r.Done) using (var b = new SolidBrush(ring)) g.FillEllipse(b, circle);
                }
                bool dim = r.Past || r.Done;
                var tcol = dim ? c.Faint : (r.Kind == "overdue" || r.Kind == "deadline") ? c.Danger : c.Sub;
                Text(g, c, PAD + 24, y, r.Time, fRow, tcol, 92);
                float tx = PAD + 24 + 100;
                float cw = string.IsNullOrEmpty(r.Course) ? 0 : Math.Min(Measure(g, r.Course, fSmall) + 4, 130);
                float tw = Text(g, c, tx, y, r.Title, fRow, dim ? c.Faint : c.Text, right - tx - (cw > 0 ? cw + 10 : 0), 0, r.Done);
                if (cw > 0) Text(g, c, tx + tw + 10, y + 1, r.Course, fSmall, dim ? c.Faint : Pal.CourseColor(r.Course, c.Light), cw);
                y += 25;
            }
            if (v.Rows.Count > shown)
            {
                int more = v.Rows.Count - shown;
                Text(g, c, PAD + 124, y, L.Tr($"외 {more}개", $"+{more} more"), fRow, c.Sub);
                y += 25;
            }
            var st = S.Status();
            if (st != "") Text(g, c, PAD + 124, y, st, fSmall, c.Sub, right - PAD - 124);
        }

        /// Today's time bar, 08–24: classes blue, other events solid, travel hatched, free green, and "now".
        void PaintBar(Graphics g, Pal c, float x0, float y0, float w, DateTime now)
        {
            var sl = S.GetSlice();
            const float bh = 22, rad = 4;
            float X(double h) => x0 + (float)((Math.Min(Math.Max(h, 8), 24) - 8) / 16 * w);
            var track = new RectangleF(x0, y0, w, bh);
            FillRound(g, track, rad, c.Track, c.Stroke > 0 ? c.Edge : (Color?)null, 1.6f);

            using (var hb = new HatchBrush(HatchStyle.WideUpwardDiagonal, c.Hatch, Color.Transparent))
                foreach (var (a, b) in sl.Travel)
                {
                    if (b <= 8) continue;
                    g.FillRectangle(hb, X(a), y0, X(b) - X(a), bh);
                }
            foreach (var (a, b) in sl.Free)
                FillRound(g, new RectangleF(X(a) + 1, y0 + 1, Math.Max(X(b) - X(a) - 2, 1), bh - 2), rad, c.GoodSoft, c.Good);
            foreach (var bl in sl.Blocks)
            {
                if (bl.b <= 8) continue;
                float l = X(bl.a), rt = Math.Max(X(bl.b), X(bl.a) + 1);
                FillRound(g, new RectangleF(l, y0, rt - l, bh), rad, bl.cls ? c.Accent : c.Busy);
                if (rt - l > 22)
                    using (var b = new SolidBrush(bl.cls ? c.OnAccent : c.OnBusy))
                        g.DrawString(Elide(g, bl.title, fBar, rt - l - 5), fBar, b, new RectangleF(l + 3, y0 + (bh - fBar.Size * 1.3f) / 2, rt - l - 5, bh), sfDraw);
            }
            double hn = now.Hour + now.Minute / 60.0;
            if (hn >= 8 && hn <= 24)
            {
                if (c.Stroke > 0) using (var pen = new Pen(c.Edge, 4)) g.DrawLine(pen, X(hn), y0 - 3, X(hn), y0 + bh + 3);
                using (var pen = new Pen(c.Accent, 2)) g.DrawLine(pen, X(hn), y0 - 2, X(hn), y0 + bh + 2);
            }
            for (int hr = 8; hr <= 24; hr += 4)
            {
                float hx = Math.Min(Math.Max(X(hr), x0 + 7), x0 + w - 7);
                Text(g, c, hx - 12, y0 + bh + 2, (hr % 24).ToString("00"), fBar, c.Faint, 24, 2);
            }
        }

        // ---------------------------------------------------------------- input

        public void SetInteractive(bool on)
        {
            if (!IsHandleCreated || Moving || on == Interactive) return;
            Interactive = on;
            Hover = null;
            Native.SetClickThrough(Handle, !on);
            Cursor = on ? Cursors.Hand : Cursors.Default;
            Render();
        }

        public void SetMove(bool on)
        {
            if (on == Moving) return;
            Moving = on;
            Interactive = false;
            Native.SetClickThrough(Handle, !on);
            Native.SetNoActivate(Handle, !on);
            Cursor = on ? Cursors.SizeAll : Cursors.Default;
            if (on) { Native.KeepOnTop(Handle); Activate(); Focus(); }
            Render();
            if (!on) OnMoveDone?.Invoke();
        }

        string HitAt(Point p)
        {
            float k = K;
            var pt = new PointF(p.X / k, p.Y / k);
            foreach (var (r, id) in hits) if (r.Contains(pt)) return id;
            return null;
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            if (e.Button != MouseButtons.Left) return;
            if (Moving) { dragging = true; dragOff = new Point(Cursor.Position.X - Bounds_.X, Cursor.Position.Y - Bounds_.Y); }
            else if (Interactive) { var id = HitAt(e.Location); if (id != null) OnTaskClick?.Invoke(id); }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (Moving && dragging)
            {
                var p = Clamp(new Point(Cursor.Position.X - dragOff.X, Cursor.Position.Y - dragOff.Y), Bounds_.Size);
                Bounds_.Location = p;
                Native.SetWindowPos(Handle, IntPtr.Zero, p.X, p.Y, 0, 0, 0x1 | 0x4 | 0x10);   // NOSIZE | NOZORDER | NOACTIVATE
            }
            else if (Interactive)
            {
                var h = HitAt(e.Location);
                if (h != Hover) { Hover = h; Render(); }
            }
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            if (!(Moving && dragging)) return;
            dragging = false;
            var r = Screen.PrimaryScreen.WorkingArea;
            bool bottomHalf = Bounds_.Top + Bounds_.Height / 2 > r.Top + r.Height / 2;
            S.Cfg["corner"] = "custom";
            S.Cfg["pos_x"] = Bounds_.Left - r.Left;
            S.Cfg["pos_anchor"] = bottomHalf ? "bottom" : "top";
            S.Cfg["pos_y"] = bottomHalf ? r.Bottom - Bounds_.Bottom : Bounds_.Top - r.Top;
            if (!S.IsDemo) S.Cfg.Save();
        }

        protected override void OnMouseDoubleClick(MouseEventArgs e) { if (Moving) SetMove(false); }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (Moving && (e.KeyCode == Keys.Escape || e.KeyCode == Keys.Enter)) SetMove(false);
        }

        public bool PointerOver() => Visible && Bounds_.Contains(Cursor.Position);

        protected override void Dispose(bool disposing)
        {
            if (disposing) { pic?.Dispose(); fHead.Dispose(); fRow.Dispose(); fUp.Dispose(); fSmall.Dispose(); fBar.Dispose(); }
            base.Dispose(disposing);
        }
    }
}
