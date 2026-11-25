// Todoist API v1, Google Calendar's secret iCal address (with a download cache), GitHub release updates.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace StudyOverlayLite
{
    class HttpResult { public int Code; public byte[] Body = new byte[0]; public WebHeaderCollection Headers = new WebHeaderCollection(); }

    static class Http
    {
        public static HttpResult Send(string url, string method = "GET", Dictionary<string, string> headers = null, int timeoutMs = 30000, Stream saveTo = null, bool redirect = true)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Method = method;
            req.Timeout = timeoutMs;
            req.ReadWriteTimeout = timeoutMs;
            req.UserAgent = "StudyOverlayLite";
            req.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            req.AllowAutoRedirect = redirect;
            if (headers != null)
                foreach (var kv in headers)
                {
                    if (kv.Key == "Accept") req.Accept = kv.Value;
                    else if (kv.Key == "If-Modified-Since") { if (DateTime.TryParse(kv.Value, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal, out var d)) req.IfModifiedSince = d; }
                    else req.Headers[kv.Key] = kv.Value;
                }
            if (method == "POST") req.ContentLength = 0;
            HttpWebResponse resp;
            try { resp = (HttpWebResponse)req.GetResponse(); }
            catch (WebException we) when (we.Response is HttpWebResponse r) { resp = r; }
            using (resp)
            {
                var res = new HttpResult { Code = (int)resp.StatusCode, Headers = resp.Headers };
                using (var s = resp.GetResponseStream())
                {
                    if (saveTo != null && res.Code < 300) { s.CopyTo(saveTo); }
                    else { var ms = new MemoryStream(); s.CopyTo(ms); res.Body = ms.ToArray(); }
                }
                return res;
            }
        }

        public static string Text(HttpResult r) => Encoding.UTF8.GetString(r.Body);
    }

    static class Todoist
    {
        const string Base = "https://api.todoist.com/api/v1";

        static Dictionary<string, string> Auth(string token) => new Dictionary<string, string> { ["Authorization"] = "Bearer " + token };

        static List<Dictionary<string, object>> Pages(string token, string path)
        {
            var outp = new List<Dictionary<string, object>>();
            string cursor = null;
            do
            {
                var url = Base + path + "?limit=200" + (cursor != null ? "&cursor=" + Uri.EscapeDataString(cursor) : "");
                var r = Http.Send(url, headers: Auth(token));
                if (r.Code == 401) throw new Exception(L.Tr("Todoist 토큰이 올바르지 않음", "Todoist token is invalid"));
                if (r.Code >= 300) throw new Exception("Todoist: HTTP " + r.Code);
                var root = Json.Obj(Json.Parse(Http.Text(r)));
                foreach (var x in Json.List(root, "results")) if (x is Dictionary<string, object> d) outp.Add(d);
                cursor = root != null && root.TryGetValue("next_cursor", out var c) ? c as string : null;
            } while (!string.IsNullOrEmpty(cursor) && outp.Count < 5000);
            return outp;
        }

        public static List<TaskItem> Tasks(string token)
        {
            var projects = Pages(token, "/projects").ToDictionary(p => Json.Str(p, "id"), p => Json.Str(p, "name"));
            var outp = new List<TaskItem>();
            foreach (var t in Pages(token, "/tasks"))
            {
                var task = new TaskItem
                {
                    Id = Json.Str(t, "id"), Content = Json.Str(t, "content"), Description = Json.Str(t, "description"),
                    Priority = Json.Int(t, "priority", 1), Labels = Json.List(t, "labels").Select(x => x?.ToString() ?? "").ToList(),
                };
                projects.TryGetValue(Json.Str(t, "project_id"), out task.Project);
                task.Project = task.Project ?? "";
                if (t.TryGetValue("due", out var dueO) && dueO is Dictionary<string, object> due)
                {
                    var raw = Json.Str(due, "datetime");
                    if (raw == "") raw = Json.Str(due, "date");
                    ParseDue(raw, task);
                }
                outp.Add(task);
            }
            return outp;
        }

        /// "2026-11-20", "2026-11-20T23:59:00" (floating) or "2026-11-20T14:59:00Z" (fixed) → local date and time.
        public static void ParseDue(string raw, TaskItem t)
        {
            if (string.IsNullOrEmpty(raw)) return;
            if (!raw.Contains("T"))
            {
                if (DateTime.TryParseExact(raw.Substring(0, Math.Min(10, raw.Length)), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) t.Date = d;
                return;
            }
            DateTime local;
            if (raw.EndsWith("Z") || Regex.IsMatch(raw, @"[+-]\d\d:\d\d$"))
                local = DateTimeOffset.Parse(raw, CultureInfo.InvariantCulture).ToLocalTime().DateTime;
            else
                local = DateTime.ParseExact(raw.Substring(0, 19), "yyyy-MM-dd'T'HH:mm:ss", CultureInfo.InvariantCulture);
            t.Date = local.Date;
            t.Time = new TimeSpan(local.Hour, local.Minute, 0);
        }

        public static void Post(string token, string path)
        {
            var r = Http.Send(Base + path, "POST", Auth(token));
            if (r.Code == 401) throw new Exception(L.Tr("Todoist 토큰이 올바르지 않음", "Todoist token is invalid"));
            if (r.Code >= 300) throw new Exception("Todoist: HTTP " + r.Code);
        }
    }

    /// Google's feed holds the whole calendar history: download only when it changed (ETag / Last-Modified), and parse
    /// only when the content hash or the day changed.
    class IcalCache
    {
        string etag, lastModified, hash, day;
        List<EventItem> events;

        public List<EventItem> Fetch(string url, DateTime today)
        {
            var headers = new Dictionary<string, string>();
            string dayKey = today.ToString("yyyy-MM-dd");
            if (events != null && day == dayKey)
            {
                if (etag != null) headers["If-None-Match"] = etag;
                if (lastModified != null) headers["If-Modified-Since"] = lastModified;
            }
            var r = Http.Send(url.Trim(), headers: headers);
            if (r.Code == 304 && events != null) return events;
            if (r.Code >= 300) throw new Exception("HTTP " + r.Code);
            etag = r.Headers["ETag"];
            lastModified = r.Headers["Last-Modified"];
            string h;
            using (var sha = SHA1.Create()) h = Convert.ToBase64String(sha.ComputeHash(r.Body));
            if (h == hash && day == dayKey && events != null) return events;
            events = Ics.Parse(Encoding.UTF8.GetString(r.Body), today.AddDays(-1), today.AddDays(2));
            hash = h; day = dayKey;
            return events;
        }
    }

    /// In-place self-update from the private repo's release "lite" (named "Study Overlay Lite build N").
    /// The exe never copies itself elsewhere and never registers autostart on its own (AhnLab, 2026-11-24).
    static class Updater
    {
        const string Repo = "dhl01133-design/study-app", Tag = "lite", Asset = "StudyOverlayLite.exe";
        public static string Exe => Process.GetCurrentProcess().MainModule.FileName;

        static Dictionary<string, string> H(string token, bool raw) => new Dictionary<string, string>
        {
            ["Authorization"] = "Bearer " + token, ["X-GitHub-Api-Version"] = "2022-11-28",
            ["Accept"] = raw ? "application/octet-stream" : "application/vnd.github+json",
        };

        public static (int build, long id, long size) Check(string token)
        {
            var r = Http.Send($"https://api.github.com/repos/{Repo}/releases/tags/{Tag}", headers: H(token, false));
            if (r.Code == 401 || r.Code == 403 || r.Code == 404) throw new Exception(L.Tr("GitHub 토큰으로 릴리스를 읽을 수 없음", "GitHub token can't read the release"));
            if (r.Code >= 300) throw new Exception("GitHub: HTTP " + r.Code);
            var j = Json.Obj(Json.Parse(Http.Text(r)));
            var m = Regex.Match(Json.Str(j, "name"), @"build\s+(\d+)");
            var asset = Json.List(j, "assets").OfType<Dictionary<string, object>>().FirstOrDefault(a => Json.Str(a, "name") == Asset);
            if (!m.Success || asset == null) throw new Exception("release has no build");
            return (int.Parse(m.Groups[1].Value), long.Parse(Json.Str(asset, "id")), long.Parse(Json.Str(asset, "size")));
        }

        /// Downloads next to the running exe, renames the running one to .old.exe, swaps the new one in. Returns its path.
        public static string Install(string token, long id, long size)
        {
            string target = Exe, tmp = target + ".new", old = target.Substring(0, target.Length - 4) + ".old.exe";
            using (var f = File.Create(tmp))
            {
                // GitHub answers with a redirect to a signed storage URL, which must be fetched without the token
                var r = Http.Send($"https://api.github.com/repos/{Repo}/releases/assets/{id}", headers: H(token, true), timeoutMs: 120000, saveTo: f, redirect: false);
                if (r.Code >= 300 && r.Code < 400 && r.Headers["Location"] is string loc)
                    r = Http.Send(loc, timeoutMs: 120000, saveTo: f);
                if (r.Code >= 300) throw new Exception("GitHub: HTTP " + r.Code);
            }
            if (new FileInfo(tmp).Length != size) { File.Delete(tmp); throw new Exception("download incomplete"); }
            if (File.Exists(old)) File.Delete(old);
            File.Move(target, old);   // a running exe can be renamed, not overwritten
            File.Move(tmp, target);
            return target;
        }

        public static void Cleanup()
        {
            try
            {
                string target = Exe, old = target.Substring(0, target.Length - 4) + ".old.exe";
                if (File.Exists(old)) File.Delete(old);
                if (File.Exists(target + ".new")) File.Delete(target + ".new");
            }
            catch { }
        }
    }
}
