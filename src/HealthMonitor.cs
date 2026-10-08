using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace ZapretHub
{
    /// <summary>Connectivity history: one JSON object per line in history.jsonl.</summary>
    public static class History
    {
        static readonly object Lock = new object();
        static string FilePath => Paths.File("history.jsonl");
        const int KeepDays = 14;

        public static long Now => DateTimeOffset.Now.ToUnixTimeMilliseconds();

        public static void Add(Dictionary<string, object> rec)
        {
            rec["t"] = Now;
            lock (Lock)
            {
                try
                {
                    File.AppendAllText(FilePath, Json.Ser(rec) + "\n");
                    if (new FileInfo(FilePath).Length > 3 * 1024 * 1024) Trim();
                }
                catch { }
            }
            App.Emit("history", rec);
        }

        public static void Event(string kind, string strategy, string reason, string from = null)
            => Add(new Dictionary<string, object> { ["e"] = kind, ["s"] = strategy, ["why"] = reason, ["from"] = from });

        static void Trim()
        {
            var min = DateTimeOffset.Now.AddDays(-KeepDays).ToUnixTimeMilliseconds();
            var keep = File.ReadAllLines(FilePath).Where(l => { try { return Convert.ToInt64(Json.Obj(l)["t"]) >= min; } catch { return false; } }).ToList();
            File.WriteAllText(FilePath, string.Join("\n", keep) + (keep.Count > 0 ? "\n" : ""));
        }

        public static List<Dictionary<string, object>> Read(double hours)
        {
            var min = DateTimeOffset.Now.AddHours(-hours).ToUnixTimeMilliseconds();
            var res = new List<Dictionary<string, object>>();
            lock (Lock)
            {
                if (!File.Exists(FilePath)) return res;
                foreach (var l in File.ReadAllLines(FilePath))
                {
                    try { var d = Json.Obj(l); if (Convert.ToInt64(d["t"]) >= min) res.Add(d); } catch { }
                }
            }
            return res;
        }

        public static void Clear() { lock (Lock) try { File.Delete(FilePath); } catch { } }
    }

    /// <summary>
    /// Discord voice check: Discord RTC servers answer the voice "IP discovery" packet on UDP 19302,
    /// the same request the client sends before a call.
    /// </summary>
    public static class Voice
    {
        static List<string> servers; static DateTime fetched;
        static string CacheFile => Paths.File("voice-servers.json");

        static async Task<List<string>> Servers()
        {
            if (servers != null && (DateTime.Now - fetched).TotalHours < 6) return servers;
            try
            {
                // ordered by proximity to the client; one address from each of the nearest regions
                var arr = Json.Any(await Net.Text("https://latency.discord.media/rtc", 8)) as object[];
                var list = (arr ?? new object[0]).OfType<Dictionary<string, object>>()
                    .Select(r => r.List("ips").FirstOrDefault()).Where(ip => ip != null).Take(4).ToList();
                if (list.Count > 0)
                {
                    servers = list; fetched = DateTime.Now;
                    try { File.WriteAllText(CacheFile, Json.Ser(list)); } catch { }
                    return list;
                }
            }
            catch { }
            try { if (File.Exists(CacheFile)) return servers = Json.To<List<string>>(File.ReadAllText(CacheFile)); } catch { }
            return new List<string>();
        }

        /// <summary>Returns null when the server list is unavailable (no verdict), otherwise whether any server answered.</summary>
        public static async Task<Dictionary<string, object>> Check(CancellationToken ct = default)
        {
            var ips = await Servers();
            if (ips.Count == 0) return null;
            var tasks = ips.Select(ip => Task.Run(() => Probe(ip), ct)).ToArray();
            await Task.WhenAll(tasks);
            var ok = tasks.Where(t => t.Result >= 0).Select(t => t.Result).ToList();
            return new Dictionary<string, object> { ["ok"] = ok.Count > 0, ["answered"] = ok.Count, ["total"] = ips.Count, ["ms"] = ok.Count > 0 ? (object)ok.Min() : null };
        }

        static long Probe(string ip)
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                try
                {
                    using (var u = new UdpClient(AddressFamily.InterNetwork))
                    {
                        u.Client.ReceiveTimeout = 1200;
                        var p = new byte[74];
                        p[1] = 1; p[3] = 70;                                      // type 0x1 (request), length 70
                        var ssrc = BitConverter.GetBytes(new Random().Next(1, int.MaxValue));
                        Array.Copy(ssrc, 0, p, 4, 4);
                        var sw = Stopwatch.StartNew();
                        u.Send(p, p.Length, new IPEndPoint(IPAddress.Parse(ip), 19302));
                        var from = new IPEndPoint(IPAddress.Any, 0);
                        var r = u.Receive(ref from);
                        if (r.Length >= 8 && r[1] == 2) return sw.ElapsedMilliseconds; // type 0x2 (response)
                    }
                }
                catch { }
            }
            return -1;
        }
    }

    /// <summary>Periodic connectivity check of Discord / YouTube; records history and repairs the bypass when it stops working.</summary>
    public static class HealthMonitor
    {
        const string Src = "heal";
        static Timer timer;
        static DateTime next = DateTime.Now.AddMinutes(2);
        static int checking;
        public static Dictionary<string, object> Last { get; private set; }
        public static bool Healing { get; private set; }
        static DateTime lastHeal = DateTime.MinValue;

        public static void Init()
        {
            timer = new Timer(_ => Tick(), null, TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(30));
        }

        public static void Reschedule() => next = DateTime.Now.AddMinutes(Math.Max(2, App.Settings.MonitorInterval));

        static void Tick()
        {
            if (!App.Settings.MonitorEnabled || DateTime.Now < next) return;
            Reschedule();
            _ = Run("auto");
        }

        static readonly (string name, string url, string group)[] Targets =
        {
            ("DiscordMain", "https://discord.com", "d"),
            ("DiscordGateway", "https://gateway.discord.gg", "d"),
            ("DiscordCDN", "https://cdn.discordapp.com", "d"),
            ("YouTubeWeb", "https://www.youtube.com", "y"),
            ("YouTubeImage", "https://i.ytimg.com", "y"),
            ("YouTubeVideoRedirect", "https://redirector.googlevideo.com", "y"),
        };
        // never blocked in Russia and reachable elsewhere: tells "the bypass broke" from "the internet is down"
        static readonly string[] Control = { "https://ya.ru", "https://www.google.com" };

        public static List<string> HealTargets() => Targets.Select(t => t.name + "|" + t.url).ToList();

        /// <summary>Runs one check. kind: auto | manual.</summary>
        public static async Task<Dictionary<string, object>> Run(string kind)
        {
            if (Interlocked.Exchange(ref checking, 1) == 1) return Last;
            try
            {
                var res = await Task.Run(() => Probe());
                Last = res;
                var z = App.Zapret;
                var st = z.Installed ? z.Status() : null;
                var mode = st == null ? "off" : (string)st["mode"];
                if (!App.Tester.Running)
                    History.Add(new Dictionary<string, object>
                    {
                        ["k"] = kind,
                        ["s"] = mode == "off" ? null : z.CurrentStrategy(),
                        ["m"] = mode,
                        ["d"] = res["discord"],
                        ["y"] = res["youtube"],
                        ["v"] = res.ContainsKey("voice") && res["voice"] is Dictionary<string, object> v ? (object)((bool)v["ok"] ? 1 : 0) : null,
                        ["n"] = (bool)res["online"] ? 1 : 0,
                    });
                App.Emit("health", res);
                if (kind == "auto" && mode != "off" && App.Settings.SelfHeal && !App.Tester.Running && Broken(res))
                {
                    // confirm: a single failed round is often a hiccup of Wi-Fi or the provider
                    await Task.Delay(20000);
                    var again = await Task.Run(() => Probe());
                    Last = again;
                    if (Broken(again)) await Heal(again);
                }
                return Last;
            }
            finally { Interlocked.Exchange(ref checking, 0); }
        }

        static bool Broken(Dictionary<string, object> r)
            => (bool)r["online"] && (Convert.ToDouble(r["discord"]) < 0.5 || Convert.ToDouble(r["youtube"]) < 0.5);

        static Dictionary<string, object> Probe()
        {
            var probe = Tester.ProbeUrls(Targets.Select(t => (t.name, t.url)).ToList(), CancellationToken.None);
            var rows = (List<Dictionary<string, object>>)probe["rows"];
            double Ratio(string g)
            {
                int ok = 0, tot = 0;
                foreach (var r in rows.Where(r => Targets.First(t => t.name == (string)r["name"]).group == g))
                    foreach (var v in ((Dictionary<string, object>)r["checks"]).Values) { if ((string)v == "UNSUP") continue; tot++; if ((string)v == "OK") ok++; }
                return tot == 0 ? 0 : Math.Round((double)ok / tot, 2);
            }
            var online = Control.Any(u => Shell.Run(Paths.Curl, $"--noproxy * -I -s -m 5 --connect-timeout 3 -o NUL {u}", 9000).Code == 0);
            var res = new Dictionary<string, object>
            {
                ["discord"] = Ratio("d"),
                ["youtube"] = Ratio("y"),
                ["online"] = online,
                ["rows"] = rows,
                ["time"] = DateTime.Now.ToString("HH:mm"),
            };
            if (App.Settings.CheckVoice)
                try { res["voice"] = Voice.Check().GetAwaiter().GetResult(); } catch { }
            return res;
        }

        /// <summary>Tests the best-ranked strategies and switches to the first one that brings Discord and YouTube back.</summary>
        static async Task Heal(Dictionary<string, object> state)
        {
            if ((DateTime.Now - lastHeal).TotalMinutes < 15) { Log.Info(Src, L.T("Самовосстановление уже запускалось недавно — жду", "Self-healing ran recently — waiting")); return; }
            lastHeal = DateTime.Now;
            var z = App.Zapret;
            var cur = z.CurrentStrategy();
            var cands = Candidates(cur, App.Settings.SelfHealCandidates);
            if (cands.Count == 0) return;
            Healing = true;
            try
            {
                var what = string.Join(", ", new[] { Convert.ToDouble(state["discord"]) < 0.5 ? "Discord" : null, Convert.ToDouble(state["youtube"]) < 0.5 ? "YouTube" : null }.Where(x => x != null));
                Log.Warn(Src, L.T($"Не работает {what} на {cur} — проверяю {cands.Count} стратегий", $"{what} is down on {cur} — testing {cands.Count} strategies"));
                App.Notify(L.T("Связь пропала", "Connection lost"), L.T($"{what} не открывается. Ищу рабочую стратегию…", $"{what} does not open. Looking for a working strategy…"));
                History.Event("down", cur, what);
                var ranked = await App.Tester.RunHeal(cands);
                var best = ranked.FirstOrDefault();
                if (best != null && Convert.ToDouble(best["score"]) >= 90)
                {
                    var bat = (string)best["name"];
                    History.Event("switch", bat, "heal", cur);
                    NetProfiles.Remember(bat);
                    Log.Ok(Src, L.T($"Связь восстановлена: переключено на {bat}", $"Connection restored: switched to {bat}"));
                    App.Notify(L.T("Связь восстановлена", "Connection restored"), L.T("Новая стратегия: ", "New strategy: ") + Path.GetFileNameWithoutExtension(bat));
                }
                else
                {
                    History.Event("heal-fail", cur, "heal");
                    Log.Err(Src, L.T("Рабочая стратегия среди лучших не найдена — запустите полный поиск ALT", "No working strategy among the top ones — run a full ALT search"));
                    App.Notify(L.T("Не удалось восстановить связь", "Could not restore the connection"), L.T("Запустите полный поиск ALT в Zapret Hub", "Run a full ALT search in Zapret Hub"));
                }
                App.Emit("refresh", null);
            }
            catch (Exception ex) { Log.Err(Src, ex.Message); }
            finally { Healing = false; }
        }

        /// <summary>Strategies ranked by the last full ALT search, then the remaining ones in natural order.</summary>
        public static List<string> Candidates(string exclude, int count)
        {
            var all = App.Zapret.Strategies();
            var ranked = new List<string>();
            try
            {
                if (App.Tester.LastSaved() is Dictionary<string, object> last && last.ContainsKey("results"))
                    foreach (var r in ((System.Collections.IEnumerable)last["results"]).OfType<Dictionary<string, object>>())
                        if (r.Dbl("score") >= 50) ranked.Add(r.Str("name"));
            }
            catch { }
            return ranked.Concat(all).Distinct(StringComparer.OrdinalIgnoreCase)
                .Where(b => all.Contains(b) && !string.Equals(b, exclude, StringComparison.OrdinalIgnoreCase))
                .Take(count).ToList();
        }
    }
}
