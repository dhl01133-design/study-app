// Win32 calls: layered window, click-through, topmost, full-screen test, Ctrl state, light process settings.
using System;
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;

namespace StudyOverlayLite
{
    static class Native
    {
        public const int GWL_EXSTYLE = -20;
        public const int WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_TOPMOST = 0x8, WS_EX_LAYERED = 0x80000, WS_EX_NOACTIVATE = 0x8000000;

        [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; public POINT(int x, int y) { X = x; Y = y; } }
        [StructLayout(LayoutKind.Sequential)] public struct SIZE { public int W, H; public SIZE(int w, int h) { W = w; H = h; } }
        [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
        [StructLayout(LayoutKind.Sequential, Pack = 1)] public struct BLENDFUNCTION { public byte Op, Flags, Alpha, Format; }
        [StructLayout(LayoutKind.Sequential)] public struct MONITORINFO { public int Size; public RECT Monitor, Work; public int Flags; }
        [StructLayout(LayoutKind.Sequential)] struct POWER_THROTTLING { public uint Version, ControlMask, StateMask; }

        [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h, int i);
        [DllImport("user32.dll")] public static extern int SetWindowLong(IntPtr h, int i, int v);
        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool UpdateLayeredWindow(IntPtr h, IntPtr dst, ref POINT pos, ref SIZE size, IntPtr src, ref POINT srcPos, int key, ref BLENDFUNCTION blend, int flags);
        [DllImport("user32.dll")] public static extern IntPtr GetDC(IntPtr h);
        [DllImport("user32.dll")] public static extern int ReleaseDC(IntPtr h, IntPtr dc);
        [DllImport("gdi32.dll")] public static extern IntPtr CreateCompatibleDC(IntPtr dc);
        [DllImport("gdi32.dll")] public static extern bool DeleteDC(IntPtr dc);
        [DllImport("gdi32.dll")] public static extern IntPtr SelectObject(IntPtr dc, IntPtr o);
        [DllImport("gdi32.dll")] public static extern bool DeleteObject(IntPtr o);
        [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
        [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
        [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr m, ref MONITORINFO mi);
        [DllImport("user32.dll")] static extern short GetAsyncKeyState(int vk);
        [DllImport("shell32.dll")] static extern int SHQueryUserNotificationState(out int state);
        [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] public static extern bool DestroyIcon(IntPtr h);
        [DllImport("psapi.dll")] static extern bool EmptyWorkingSet(IntPtr p);
        [DllImport("kernel32.dll")] static extern bool SetProcessInformation(IntPtr p, int cls, ref POWER_THROTTLING info, int size);

        /// Pushes a 32-bit ARGB bitmap to a layered window (per-pixel alpha), with an overall opacity.
        public static void Present(IntPtr hwnd, Bitmap bmp, Point pos, byte opacity)
        {
            IntPtr screen = GetDC(IntPtr.Zero), mem = CreateCompatibleDC(screen), hbmp = IntPtr.Zero, old = IntPtr.Zero;
            try
            {
                hbmp = bmp.GetHbitmap(Color.FromArgb(0));
                old = SelectObject(mem, hbmp);
                var size = new SIZE(bmp.Width, bmp.Height);
                var src = new POINT(0, 0);
                var p = new POINT(pos.X, pos.Y);
                var blend = new BLENDFUNCTION { Op = 0, Flags = 0, Alpha = opacity, Format = 1 };   // AC_SRC_OVER, AC_SRC_ALPHA
                UpdateLayeredWindow(hwnd, screen, ref p, ref size, mem, ref src, 0, ref blend, 2);   // ULW_ALPHA
            }
            finally
            {
                ReleaseDC(IntPtr.Zero, screen);
                if (hbmp != IntPtr.Zero) { SelectObject(mem, old); DeleteObject(hbmp); }
                DeleteDC(mem);
            }
        }

        public static void SetClickThrough(IntPtr h, bool through)
        {
            int s = GetWindowLong(h, GWL_EXSTYLE);
            int want = through ? (s | WS_EX_TRANSPARENT | WS_EX_LAYERED) : (s & ~WS_EX_TRANSPARENT);
            if (want != s) SetWindowLong(h, GWL_EXSTYLE, want);
        }

        public static void SetNoActivate(IntPtr h, bool on)
        {
            int s = GetWindowLong(h, GWL_EXSTYLE);
            int want = on ? (s | WS_EX_NOACTIVATE) : (s & ~WS_EX_NOACTIVATE);
            if (want != s) SetWindowLong(h, GWL_EXSTYLE, want);
        }

        /// HWND_TOPMOST again: borderless full-screen apps make themselves topmost and would cover the overlay.
        public static void KeepOnTop(IntPtr h) => SetWindowPos(h, new IntPtr(-1), 0, 0, 0, 0, 0x1 | 0x2 | 0x10 | 0x200);
        public static void SendToBottom(IntPtr h) => SetWindowPos(h, new IntPtr(-2), 0, 0, 0, 0, 0x1 | 0x2 | 0x10 | 0x200);   // HWND_NOTOPMOST

        public static bool CtrlDown() => (GetAsyncKeyState(0x11) & 0x8000) != 0;

        /// A full-screen game, video or presentation covers the main monitor. The desktop and taskbar don't count
        /// (clicking through the overlay focuses the desktop, which Windows reports as "busy").
        public static bool FullscreenAppRunning()
        {
            try
            {
                if (SHQueryUserNotificationState(out int st) == 0 && (st == 3 || st == 4)) return true;
                var h = GetForegroundWindow();
                if (h == IntPtr.Zero) return false;
                var cls = new StringBuilder(64);
                GetClassName(h, cls, 64);
                var c = cls.ToString();
                if (c == "Progman" || c == "WorkerW" || c == "Shell_TrayWnd" || c == "Shell_SecondaryTrayWnd") return false;
                GetWindowThreadProcessId(h, out uint pid);
                if (pid == (uint)Process.GetCurrentProcess().Id) return false;
                if ((GetWindowLong(h, -16) & 0x00C00000) == 0x00C00000) return false;   // has a title bar
                var mon = MonitorFromWindow(h, 2);
                var mi = new MONITORINFO { Size = Marshal.SizeOf(typeof(MONITORINFO)) };
                if (mon == IntPtr.Zero || !GetMonitorInfo(mon, ref mi) || (mi.Flags & 1) == 0) return false;
                GetWindowRect(h, out var r);
                return r.Left <= mi.Monitor.Left && r.Top <= mi.Monitor.Top && r.Right >= mi.Monitor.Right && r.Bottom >= mi.Monitor.Bottom;
            }
            catch { return false; }
        }

        /// Below-normal priority and Windows 11 efficiency mode (EcoQoS).
        public static void GoLight()
        {
            try
            {
                var p = Process.GetCurrentProcess();
                p.PriorityClass = ProcessPriorityClass.BelowNormal;
                var t = new POWER_THROTTLING { Version = 1, ControlMask = 1, StateMask = 1 };
                SetProcessInformation(p.Handle, 4, ref t, Marshal.SizeOf(typeof(POWER_THROTTLING)));
            }
            catch { }
        }

        public static void TrimMemory()
        {
            try
            {
                GC.Collect();
                GC.WaitForPendingFinalizers();
                GC.Collect();
                EmptyWorkingSet(Process.GetCurrentProcess().Handle);
            }
            catch { }
        }

        public static int ExStyle(IntPtr h) => GetWindowLong(h, GWL_EXSTYLE);
    }
}
