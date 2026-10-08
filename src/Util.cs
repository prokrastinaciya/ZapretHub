using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace ZapretHub
{
    /// <summary>Picks the Russian or English variant of a user-facing string.</summary>
    static class L
    {
        public static bool En => App.Settings != null && App.Settings.EffectiveLang() == "en";
        public static string T(string ru, string en) => En ? en : ru;
    }

    static class Json
    {
        static readonly JavaScriptSerializer S = new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 256 };
        public static string Ser(object o) => S.Serialize(o);
        public static Dictionary<string, object> Obj(string s) => S.Deserialize<Dictionary<string, object>>(s);
        public static T To<T>(string s) => S.Deserialize<T>(s);
        public static object Any(string s) => S.DeserializeObject(s);

        public static string Str(this IDictionary<string, object> d, string k, string def = null)
            => d != null && d.TryGetValue(k, out var v) && v != null ? Convert.ToString(v, CultureInfo.InvariantCulture) : def;

        public static int Int(this IDictionary<string, object> d, string k, int def = 0)
        {
            var s = d.Str(k);
            return int.TryParse(s, NumberStyles.Any, CultureInfo.InvariantCulture, out var r) ? r : def;
        }

        public static double Dbl(this IDictionary<string, object> d, string k, double def = 0)
        {
            var s = d.Str(k);
            return double.TryParse(s, NumberStyles.Any, CultureInfo.InvariantCulture, out var r) ? r : def;
        }

        public static bool Bool(this IDictionary<string, object> d, string k, bool def = false)
        {
            if (d == null || !d.TryGetValue(k, out var v) || v == null) return def;
            if (v is bool b) return b;
            return string.Equals(Convert.ToString(v), "true", StringComparison.OrdinalIgnoreCase);
        }

        public static List<string> List(this IDictionary<string, object> d, string k)
        {
            var res = new List<string>();
            if (d == null || !d.TryGetValue(k, out var v) || v == null) return res;
            if (v is string s) { if (s.Length > 0) res.Add(s); return res; }
            if (v is IEnumerable e)
                foreach (var x in e) if (x != null) res.Add(Convert.ToString(x, CultureInfo.InvariantCulture));
            return res;
        }

        public static Dictionary<string, object> Dict(this IDictionary<string, object> d, string k)
            => d != null && d.TryGetValue(k, out var v) ? v as Dictionary<string, object> : null;
    }

    static class Paths
    {
        public static readonly string Data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ZapretHub");
        public static string File(string name) { Directory.CreateDirectory(Data); return Path.Combine(Data, name); }
        public static string Exe => Process.GetCurrentProcess().MainModule.FileName;
        public static string System32 => Environment.GetFolderPath(Environment.SpecialFolder.System);
        public static string Curl => Path.Combine(System32, "curl.exe");
    }

    static class Log
    {
        public static event Action<Dictionary<string, object>> OnLine;
        static readonly object Lock = new object();
        static readonly LinkedList<Dictionary<string, object>> Ring = new LinkedList<Dictionary<string, object>>();

        public static void Info(string src, string msg) => Write("info", src, msg);
        public static void Ok(string src, string msg) => Write("ok", src, msg);
        public static void Warn(string src, string msg) => Write("warn", src, msg);
        public static void Err(string src, string msg) => Write("err", src, msg);

        public static void Write(string level, string src, string msg)
        {
            var line = new Dictionary<string, object> { ["t"] = DateTime.Now.ToString("HH:mm:ss"), ["level"] = level, ["src"] = src, ["msg"] = msg };
            lock (Lock)
            {
                Ring.AddLast(line);
                while (Ring.Count > 600) Ring.RemoveFirst();
            }
            try { System.IO.File.AppendAllText(Paths.File("hub.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{level}] {src}: {msg}\r\n"); } catch { }
            try { OnLine?.Invoke(line); } catch { }
        }

        public static List<Dictionary<string, object>> All() { lock (Lock) return Ring.ToList(); }
    }

    static class Shell
    {
        public class Result { public int Code; public string Out = ""; public string Err = ""; public string All => (Out + "\n" + Err).Trim(); }

        public static Result Run(string file, string args, int timeoutMs = 60000, string cwd = null, CancellationToken ct = default)
        {
            var psi = new ProcessStartInfo(file, args ?? "")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = cwd ?? Paths.System32,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            var r = new Result();
            using (var p = new Process { StartInfo = psi })
            {
                var o = new StringBuilder(); var e = new StringBuilder();
                p.OutputDataReceived += (s, a) => { if (a.Data != null) lock (o) o.AppendLine(a.Data); };
                p.ErrorDataReceived += (s, a) => { if (a.Data != null) lock (e) e.AppendLine(a.Data); };
                p.Start();
                p.BeginOutputReadLine(); p.BeginErrorReadLine();
                var sw = Stopwatch.StartNew();
                while (!p.WaitForExit(100))
                {
                    if (sw.ElapsedMilliseconds > timeoutMs || ct.IsCancellationRequested)
                    {
                        try { p.Kill(); } catch { }
                        r.Code = -1;
                        break;
                    }
                }
                if (r.Code != -1) { p.WaitForExit(); r.Code = p.ExitCode; }
                lock (o) r.Out = o.ToString();
                lock (e) r.Err = e.ToString();
            }
            return r;
        }

        /// <summary>Runs a command through cmd with English (cp437) console output.</summary>
        public static Result Cmd437(string command, int timeoutMs = 30000)
            => Run("cmd.exe", "/d /s /c \"chcp 437 >nul & " + command + "\"", timeoutMs);

        public static void Detached(string file, string args, string cwd)
        {
            var psi = new ProcessStartInfo(file, args) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = cwd };
            Process.Start(psi)?.Dispose();
        }

        public static void Open(string target)
        {
            try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true })?.Dispose(); }
            catch (Exception ex) { Log.Err("app", L.T("Не удалось открыть ", "Could not open ") + target + ": " + ex.Message); }
        }

        /// <summary>Quotes one argument following the MSVCRT / CommandLineToArgvW rules.</summary>
        public static string QuoteArg(string a)
        {
            if (a.Length > 0 && a.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return a;
            var sb = new StringBuilder("\"");
            int bs = 0;
            foreach (var c in a)
            {
                if (c == '\\') { bs++; continue; }
                if (c == '"') { sb.Append('\\', bs * 2 + 1); sb.Append('"'); bs = 0; continue; }
                sb.Append('\\', bs); bs = 0; sb.Append(c);
            }
            sb.Append('\\', bs * 2);
            sb.Append('"');
            return sb.ToString();
        }
    }

    static class Net
    {
        public static readonly HttpClient Http;

        static Net()
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | (SecurityProtocolType)12288;
            var h = new HttpClientHandler { AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate, AllowAutoRedirect = true };
            Http = new HttpClient(h) { Timeout = TimeSpan.FromSeconds(40) };
            Http.DefaultRequestHeaders.UserAgent.ParseAdd("ZapretHub/" + System.Reflection.Assembly.GetExecutingAssembly().GetName().Version.ToString(3));
        }

        public static async Task<string> Text(string url, int timeoutSec = 15)
        {
            using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSec)))
            using (var req = new HttpRequestMessage(HttpMethod.Get, url))
            {
                req.Headers.CacheControl = new System.Net.Http.Headers.CacheControlHeaderValue { NoCache = true };
                if (url.Contains("api.github.com")) req.Headers.Accept.ParseAdd("application/vnd.github+json");
                using (var resp = await Http.SendAsync(req, cts.Token).ConfigureAwait(false))
                {
                    if (!resp.IsSuccessStatusCode) throw new Exception($"HTTP {(int)resp.StatusCode} — {url}");
                    return await resp.Content.ReadAsStringAsync().ConfigureAwait(false);
                }
            }
        }

        /// <summary>Follows a redirect and returns the final URL (used to resolve releases/latest without the API).</summary>
        public static async Task<string> FinalUrl(string url)
        {
            using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15)))
            using (var req = new HttpRequestMessage(HttpMethod.Head, url))
            using (var resp = await Http.SendAsync(req, cts.Token).ConfigureAwait(false))
                return resp.RequestMessage.RequestUri.ToString();
        }

        public static async Task Download(string url, string dest, Action<double> progress = null, CancellationToken ct = default)
        {
            using (var resp = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false))
            {
                if (!resp.IsSuccessStatusCode) throw new Exception($"HTTP {(int)resp.StatusCode}" + L.T(" при загрузке ", " while downloading ") + url);
                var total = resp.Content.Headers.ContentLength ?? -1;
                Directory.CreateDirectory(Path.GetDirectoryName(dest));
                var tmp = dest + ".part";
                using (var src = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false))
                using (var fs = new FileStream(tmp, FileMode.Create, FileAccess.Write))
                {
                    var buf = new byte[81920]; long done = 0; int n; var last = Stopwatch.StartNew();
                    while ((n = await src.ReadAsync(buf, 0, buf.Length, ct).ConfigureAwait(false)) > 0)
                    {
                        await fs.WriteAsync(buf, 0, n, ct).ConfigureAwait(false);
                        done += n;
                        if (total > 0 && last.ElapsedMilliseconds > 150) { progress?.Invoke((double)done / total); last.Restart(); }
                    }
                }
                if (File.Exists(dest)) File.Delete(dest);
                File.Move(tmp, dest);
                progress?.Invoke(1);
            }
        }
    }

    static class Ver
    {
        public static string Clean(string v) => (v ?? "").Trim().TrimStart('v', 'V');

        /// <summary>Returns &gt;0 when a is newer than b.</summary>
        public static int Compare(string a, string b)
        {
            var pa = Clean(a).Split('.', '-'); var pb = Clean(b).Split('.', '-');
            for (int i = 0; i < Math.Max(pa.Length, pb.Length); i++)
            {
                var sa = i < pa.Length ? pa[i] : "0"; var sb = i < pb.Length ? pb[i] : "0";
                if (int.TryParse(sa, out var ia) && int.TryParse(sb, out var ib)) { if (ia != ib) return ia.CompareTo(ib); }
                else { var c = string.Compare(sa, sb, StringComparison.OrdinalIgnoreCase); if (c != 0) return c; }
            }
            return 0;
        }
    }

    static class Misc
    {
        public static string NaturalKey(string s) => Regex.Replace(s, @"\d+", m => m.Value.PadLeft(8, '0'));

        public static string GitBlobSha(byte[] content)
        {
            var header = Encoding.ASCII.GetBytes("blob " + content.Length + "\0");
            using (var sha = SHA1.Create())
            {
                sha.TransformBlock(header, 0, header.Length, null, 0);
                sha.TransformFinalBlock(content, 0, content.Length);
                return BitConverter.ToString(sha.Hash).Replace("-", "").ToLowerInvariant();
            }
        }

        public static string Sha256(string file)
        {
            using (var s = SHA256.Create()) using (var f = File.OpenRead(file))
                return BitConverter.ToString(s.ComputeHash(f)).Replace("-", "");
        }

        public static string RandomHex(int bytes)
        {
            var b = new byte[bytes];
            using (var r = RandomNumberGenerator.Create()) r.GetBytes(b);
            return BitConverter.ToString(b).Replace("-", "").ToLowerInvariant();
        }

        public static string TailFile(string path, int maxBytes = 64 * 1024)
        {
            if (!File.Exists(path)) return "";
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                var start = Math.Max(0, fs.Length - maxBytes);
                fs.Seek(start, SeekOrigin.Begin);
                var buf = new byte[fs.Length - start];
                int read = 0; while (read < buf.Length) { var n = fs.Read(buf, read, buf.Length - read); if (n <= 0) break; read += n; }
                var text = Encoding.UTF8.GetString(buf, 0, read);
                if (start > 0) { var nl = text.IndexOf('\n'); if (nl >= 0) text = text.Substring(nl + 1); }
                return text;
            }
        }

        public static void CopyDir(string src, string dst, Func<string, bool> skip = null)
        {
            Directory.CreateDirectory(dst);
            foreach (var f in Directory.GetFiles(src))
            {
                var target = Path.Combine(dst, Path.GetFileName(f));
                if (skip != null && skip(target)) continue;
                CopyWithRetry(f, target);
            }
            foreach (var d in Directory.GetDirectories(src))
                CopyDir(d, Path.Combine(dst, Path.GetFileName(d)), skip);
        }

        public static void CopyWithRetry(string src, string dst)
        {
            for (int i = 0; ; i++)
            {
                try { File.Copy(src, dst, true); return; }
                catch (IOException) when (i < 15)
                {
                    // The file may still be held by a driver or a process that is shutting down.
                    if (File.Exists(dst) && FilesEqual(src, dst)) return;
                    Thread.Sleep(400);
                }
                catch (UnauthorizedAccessException) when (i < 15)
                {
                    try { File.SetAttributes(dst, FileAttributes.Normal); } catch { }
                    Thread.Sleep(400);
                }
            }
        }

        public static bool FilesEqual(string a, string b)
        {
            try
            {
                var fa = new FileInfo(a); var fb = new FileInfo(b);
                return fa.Length == fb.Length && File.ReadAllBytes(a).SequenceEqual(File.ReadAllBytes(b));
            }
            catch { return false; }
        }

        public static bool HasCyrillic(string s) => Regex.IsMatch(s ?? "", "[Ѐ-ӿ]");

        public static bool IsPrivate(IPAddress a)
        {
            if (a.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) return false;
            var b = a.GetAddressBytes();
            return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168);
        }

        public static string Redact(string text, string secret)
            => string.IsNullOrEmpty(secret) || text == null ? text : text.Replace(secret, "<secret>");
    }
}
