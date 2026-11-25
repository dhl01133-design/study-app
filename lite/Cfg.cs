// Settings with defaults, stored as JSON in %APPDATA%\StudyOverlayLite\config.json.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace StudyOverlayLite
{
    class Cfg
    {
        public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "StudyOverlayLite");
        public static readonly string File_ = Path.Combine(Dir, "config.json");
        public static readonly string CacheFile = Path.Combine(Dir, "cache.json");
        public static readonly string AlarmFile = Path.Combine(Dir, "alarms.json");
        static readonly string OldFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "StudyOverlay", "config.json");

        public static readonly Dictionary<string, object> Defaults = new Dictionary<string, object>
        {
            ["todoist_token"] = "", ["ical_url"] = "", ["github_token"] = "", ["auto_update"] = true, ["lang"] = "en",
            ["text_color"] = "#111111", ["background"] = "white", ["outline"] = "medium", ["scale"] = 1.0, ["opacity"] = 0.95,
            ["show_bar"] = true, ["corner"] = "bottom-right", ["pos_x"] = 0, ["pos_y"] = 0, ["pos_anchor"] = "bottom",
            ["on_top"] = true, ["hide_fullscreen"] = false, ["start_with_windows"] = false, ["visible"] = true,
            ["sync_min"] = 15, ["alarms"] = true,
            ["show_past"] = true, ["show_carried"] = true, ["show_upcoming"] = true, ["only_icampus"] = false,
        };

        public Dictionary<string, object> V = new Dictionary<string, object>(Defaults);

        public object this[string k] { get => V.TryGetValue(k, out var v) ? v : (Defaults.TryGetValue(k, out var d) ? d : null); set => V[k] = value; }
        public string S(string k) => this[k]?.ToString() ?? "";
        public bool B(string k) => this[k] is bool b ? b : (Defaults.TryGetValue(k, out var d) && d is bool db && db);
        public double D(string k) => double.TryParse(Convert.ToString(this[k], CultureInfo.InvariantCulture), NumberStyles.Float, CultureInfo.InvariantCulture, out var x) ? x
            : Convert.ToDouble(Defaults[k], CultureInfo.InvariantCulture);
        public int I(string k) => (int)Math.Round(D(k));

        public Cfg Clone() => new Cfg { V = new Dictionary<string, object>(V) };

        /// A short fingerprint of everything that changes the picture (tokens excluded).
        public string Fingerprint()
        {
            var sb = new StringBuilder();
            foreach (var kv in V)
                if (kv.Key != "todoist_token" && kv.Key != "ical_url" && kv.Key != "github_token")
                    sb.Append(kv.Key).Append('=').Append(Convert.ToString(kv.Value, CultureInfo.InvariantCulture)).Append(';');
            return sb.ToString();
        }

        public static Cfg Load()
        {
            var c = new Cfg();
            string path = File.Exists(File_) ? File_ : (File.Exists(OldFile) ? OldFile : null);   // first run: take over the Python app's settings
            if (path == null) return c;
            try
            {
                if (Json.Parse(File.ReadAllText(path, Encoding.UTF8)) is Dictionary<string, object> d)
                    foreach (var kv in d)
                        if (Defaults.ContainsKey(kv.Key)) c.V[kv.Key] = kv.Value;
            }
            catch { }
            if (path == OldFile) c.Save();
            return c;
        }

        public void Save() => WriteJson(File_, V);

        public static void WriteJson(string path, object o)
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var tmp = path + ".tmp";
                File.WriteAllText(tmp, Json.Write(o), new UTF8Encoding(false));
                if (File.Exists(path)) File.Replace(tmp, path, null); else File.Move(tmp, path);
            }
            catch { }
        }

        public static Dictionary<string, object> ReadJson(string path)
        {
            try { return Json.Parse(File.ReadAllText(path, Encoding.UTF8)) as Dictionary<string, object>; }
            catch { return null; }
        }
    }
}
