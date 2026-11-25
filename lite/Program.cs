// StudyOverlayLite: today's to-do list on the main monitor, like the phone widget. A lighter rewrite of desktop/overlay.py.
//   StudyOverlayLite.exe                 normal run
//   StudyOverlayLite.exe --demo          demo data, nothing is fetched or saved
//   StudyOverlayLite.exe --shot a.png [--lang ko] [--bg none|white|dark] [--color #hex]   render demo data to a PNG
//   StudyOverlayLite.exe --selftest out.txt   logic and window checks (used by the build machine)
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Threading;
using System.Windows.Forms;

namespace StudyOverlayLite
{
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            Native.SetProcessDPIAware();
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            string Arg(string k) { int i = Array.IndexOf(args, k); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
            bool demo = Array.IndexOf(args, "--demo") >= 0;

            if (Arg("--selftest") != null) return SelfTest.Run(Arg("--selftest"));

            if (Arg("--shot") != null)
            {
                var st = DemoState();
                if (Arg("--lang") != null) st.Cfg["lang"] = Arg("--lang");
                if (Arg("--bg") != null) st.Cfg["background"] = Arg("--bg");
                if (Arg("--color") != null) st.Cfg["text_color"] = Arg("--color");
                L.En = st.Cfg.S("lang") != "ko";
                using (var ov = new Overlay(st))
                if (Arg("--outline") != null) st.Cfg["outline"] = Arg("--outline");
                using (var bmp = ov.RenderShot(Color.FromArgb(0x2B, 0x3A, 0x4A), Array.IndexOf(args, "--busy") >= 0))
                    bmp.Save(Arg("--shot"), ImageFormat.Png);
                return 0;
            }

            var mutex = new Mutex(true, demo ? @"Local\StudyOverlayLiteDemo" : @"Local\StudyOverlayLite", out bool created);
            if (!created) return 0;   // already running
            Native.GoLight();
            Updater.Cleanup();
            var s = demo ? DemoState(live: true) : new AppState { Cfg = Cfg.Load() };
            if (!demo)
            {
                var cache = Cfg.ReadJson(Cfg.CacheFile);
                if (cache != null) s.Snap = Snapshot.FromDict(cache);
            }
            Application.Run(new App(s, mutex));
            GC.KeepAlive(mutex);
            return 0;
        }

        public static AppState DemoState(bool live = false) => new AppState
        {
            Cfg = new Cfg(), IsDemo = true, Snap = Demo.Data(),
            FixedNow = live ? (DateTime?)null : DateTime.Today.AddHours(11).AddMinutes(20),
        };
    }
}
