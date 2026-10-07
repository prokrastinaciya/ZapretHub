using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace ZapretHub
{
    /// <summary>
    /// Port of utils/test zapret.ps1: runs every strategy in turn and probes the targets with curl,
    /// then ranks strategies so the user can pick a working ALT in one click.
    /// </summary>
    public class Tester
    {
        const string Src = "test";
        CancellationTokenSource cts;
        public bool Running { get; private set; }
        public List<Dictionary<string, object>> Last { get; private set; } = new List<Dictionary<string, object>>();
        public string LastMode { get; private set; }
        static string TestBackup => Path.Combine(App.Zapret.Lists, "ipset-all.zh-test-backup.txt");

        class Target { public string Name, Url, Ping; }
        class Probe { public string Target, Label, Status; }

        public void Cancel() { cts?.Cancel(); }

        /// <summary>Restores ipset if a previous DPI test was interrupted (app crash / power loss).</summary>
        public static void RecoverIpset()
        {
            try
            {
                if (App.Zapret.Installed && File.Exists(TestBackup))
                {
                    File.Copy(TestBackup, Path.Combine(App.Zapret.Lists, "ipset-all.txt"), true);
                    File.Delete(TestBackup);
                    Log.Warn(Src, "Восстановлен ipset после прерванного теста");
                }
            }
            catch { }
        }

        List<Target> StandardTargets()
        {
            var list = new List<Target>();
            var f = Path.Combine(App.Zapret.Utils, "targets.txt");
            if (File.Exists(f))
                foreach (var line in File.ReadAllLines(f))
                {
                    var m = Regex.Match(line, "^\\s*(\\w+)\\s*=\\s*\"(.+)\"\\s*$");
                    if (!m.Success) continue;
                    list.Add(Convert(m.Groups[1].Value, m.Groups[2].Value));
                }
            if (list.Count == 0)
                foreach (var (n, v) in new[] {
                    ("DiscordMain","https://discord.com"),("DiscordGateway","https://gateway.discord.gg"),("DiscordCDN","https://cdn.discordapp.com"),
                    ("YouTubeWeb","https://www.youtube.com"),("YouTubeImage","https://i.ytimg.com"),("YouTubeVideoRedirect","https://redirector.googlevideo.com"),
                    ("GoogleMain","https://www.google.com"),("CloudflareWeb","https://www.cloudflare.com"),("CloudflareDNS1111","PING:1.1.1.1") })
                    list.Add(Convert(n, v));
            return list;
        }

        static Target Convert(string name, string val)
        {
            if (val.StartsWith("PING:", StringComparison.OrdinalIgnoreCase)) return new Target { Name = name, Ping = val.Substring(5).Trim() };
            var host = Regex.Replace(Regex.Replace(val, "^https?://", ""), "/.*$", "");
            return new Target { Name = name, Url = val, Ping = host };
        }

        static readonly (string label, string args)[] TlsVariants =
        {
            ("HTTP", "--http1.1"),
            ("TLS1.2", "--tlsv1.2 --tls-max 1.2"),
            ("TLS1.3", "--tlsv1.3 --tls-max 1.3"),
        };

        static string ClassifyCurl(Shell.Result r)
        {
            var err = r.Err ?? "";
            if (Regex.IsMatch(err, "Could not resolve host|certificate|SSL certificate problem|self[- ]?signed|certificate verify failed|unable to get local issuer certificate", RegexOptions.IgnoreCase)) return "SSL";
            if (r.Code == 35 || Regex.IsMatch(err, "does not support|not supported|unsupported protocol|Unrecognized option|Unknown option|unsupported option|unsupported feature", RegexOptions.IgnoreCase)) return "UNSUP";
            return r.Code == 0 ? "OK" : "ERROR";
        }

        static Probe CurlStandard(Target t, (string label, string args) v, CancellationToken ct)
        {
            var r = Shell.Run(Paths.Curl, $"--noproxy * -I -s -m 4 --connect-timeout 2 -o NUL -w %{{http_code}} --show-error {v.args} {Shell.QuoteArg(t.Url)}", 9000, ct: ct);
            return new Probe { Target = t.Name, Label = v.label, Status = ClassifyCurl(r) };
        }

        static string PingMs(string host)
        {
            try
            {
                using (var p = new Ping())
                {
                    var r = p.Send(host, 1000);
                    return r.Status == IPStatus.Success ? r.RoundtripTime + " ms" : "timeout";
                }
            }
            catch { return "timeout"; }
        }

        /// <summary>Probes all targets against whatever bypass is currently active.</summary>
        Dictionary<string, object> ProbeStandard(List<Target> targets, CancellationToken ct)
        {
            var jobs = new List<Task<Probe>>();
            var pings = new Dictionary<string, Task<string>>();
            using (var sem = new SemaphoreSlim(Math.Min(16, Math.Max(8, Environment.ProcessorCount * 2))))
            {
                foreach (var t in targets)
                {
                    if (t.Url != null)
                        foreach (var v in TlsVariants)
                            jobs.Add(Task.Run(() => { sem.Wait(ct); try { return CurlStandard(t, v, ct); } finally { sem.Release(); } }, ct));
                    if (t.Ping != null) pings[t.Name] = Task.Run(() => PingMs(t.Ping), ct);
                }
                Task.WaitAll(jobs.Cast<Task>().Concat(pings.Values).ToArray());
            }
            var probes = jobs.Select(j => j.Result).ToList();
            var rows = targets.Select(t => new Dictionary<string, object>
            {
                ["name"] = t.Name,
                ["url"] = t.Url,
                ["checks"] = probes.Where(p => p.Target == t.Name).ToDictionary(p => p.Label, p => (object)p.Status),
                ["ping"] = pings.ContainsKey(t.Name) ? pings[t.Name].Result : null,
            }).ToList();
            int ok = probes.Count(p => p.Status == "OK");
            int total = probes.Count(p => p.Status != "UNSUP");
            var pingVals = rows.Select(r => r["ping"] as string).Where(p => p != null && p.EndsWith(" ms")).Select(p => int.Parse(p.Replace(" ms", ""))).ToList();
            return new Dictionary<string, object>
            {
                ["ok"] = ok,
                ["total"] = total,
                ["score"] = total == 0 ? 0 : Math.Round(100.0 * ok / total),
                ["ping"] = pingVals.Count > 0 ? (object)(int)pingVals.Average() : null,
                ["pingOk"] = pingVals.Count,
                ["rows"] = rows,
            };
        }

        async Task<List<Dictionary<string, object>>> DpiSuite()
        {
            var custom = Environment.GetEnvironmentVariable("MONITOR_HOST");
            if (!string.IsNullOrEmpty(custom)) return new List<Dictionary<string, object>> { new Dictionary<string, object> { ["id"] = "CUSTOM", ["provider"] = "Custom", ["host"] = custom } };
            var arr = Json.Any(await Net.Text("https://hyperion-cs.github.io/dpi-checkers/ru/tcp-16-20/suite.v2.json", 10)) as object[];
            return (arr ?? new object[0]).OfType<Dictionary<string, object>>().ToList();
        }

        Dictionary<string, object> ProbeDpi(List<Dictionary<string, object>> suite, string payload, CancellationToken ct)
        {
            var jobs = new List<Task<Dictionary<string, object>>>();
            using (var sem = new SemaphoreSlim(Math.Min(16, Math.Max(8, Environment.ProcessorCount * 2))))
            {
                foreach (var s in suite)
                    foreach (var v in TlsVariants)
                    {
                        var host = s.Str("host"); var id = s.Str("id"); var prov = s.Str("provider");
                        jobs.Add(Task.Run(() =>
                        {
                            sem.Wait(ct);
                            try
                            {
                                var r = Shell.Run(Paths.Curl, $"--noproxy * --range 0-65535 -m 5 --connect-timeout 3 -w \"%{{http_code}} %{{size_upload}} %{{size_download}} %{{time_total}}\" -o NUL -X POST --data-binary @{Shell.QuoteArg(payload)} -s {v.args} https://{host}", 12000, ct: ct);
                                var m = Regex.Match(r.Out.Trim(), @"^(\d{3})\s+(\d+)\s+(\d+)\s+([\d\.]+)$");
                                long up = m.Success ? long.Parse(m.Groups[2].Value) : 0, down = m.Success ? long.Parse(m.Groups[3].Value) : 0;
                                double time = m.Success ? double.Parse(m.Groups[4].Value, System.Globalization.CultureInfo.InvariantCulture) : -1;
                                string st;
                                if (!m.Success && (r.Code == 35 || Regex.IsMatch(r.All, "not supported|unsupported|schannel|SSL", RegexOptions.IgnoreCase))) st = "UNSUP";
                                else if (r.Code != 0 || !m.Success) st = "FAIL"; else st = "OK";
                                if (up > 0 && down == 0 && time >= 5 && r.Code != 0) st = "BLOCKED";
                                return new Dictionary<string, object> { ["id"] = id, ["provider"] = prov, ["label"] = v.label, ["status"] = st, ["down"] = down };
                            }
                            finally { sem.Release(); }
                        }, ct));
                    }
                Task.WaitAll(jobs.ToArray());
            }
            var probes = jobs.Select(j => j.Result).ToList();
            int ok = probes.Count(p => (string)p["status"] == "OK");
            int total = probes.Count(p => (string)p["status"] != "UNSUP");
            var rows = probes.GroupBy(p => (string)p["id"]).Select(g => new Dictionary<string, object>
            {
                ["name"] = g.Key + " • " + g.First()["provider"],
                ["checks"] = g.ToDictionary(p => (string)p["label"], p => p["status"]),
            }).ToList();
            return new Dictionary<string, object>
            {
                ["ok"] = ok,
                ["total"] = total,
                ["blocked"] = probes.Count(p => (string)p["status"] == "BLOCKED"),
                ["score"] = total == 0 ? 0 : Math.Round(100.0 * ok / total),
                ["rows"] = rows,
            };
        }

        /// <summary>One-shot health check of the currently active configuration (no strategy switching).</summary>
        public async Task<Dictionary<string, object>> Quick()
        {
            if (!File.Exists(Paths.Curl)) throw new Exception("curl.exe не найден");
            var targets = App.Zapret.Installed ? StandardTargets() : new List<Target>();
            if (targets.Count == 0) targets = new[] { ("Discord", "https://discord.com"), ("DiscordGateway", "https://gateway.discord.gg"), ("YouTube", "https://www.youtube.com"), ("GoogleVideo", "https://redirector.googlevideo.com") }.Select(x => Convert(x.Item1, x.Item2)).ToList();
            var res = await Task.Run(() => ProbeStandard(targets, CancellationToken.None));
            Log.Info(Src, $"Проверка связи: {res["ok"]}/{res["total"]} успешных запросов");
            return res;
        }

        public void Start(string mode, List<string> bats)
        {
            if (Running) throw new Exception("Тест уже выполняется");
            if (!App.Zapret.Installed) throw new Exception("zapret не установлен");
            if (!File.Exists(Paths.Curl)) throw new Exception("curl.exe не найден — тесты недоступны");
            if (bats == null || bats.Count == 0) bats = App.Zapret.Strategies();
            Running = true;
            cts = new CancellationTokenSource();
            var ct = cts.Token;
            Task.Run(() => RunAll(mode, bats, ct));
        }

        async Task RunAll(string mode, List<string> bats, CancellationToken ct)
        {
            var z = App.Zapret;
            var results = new List<Dictionary<string, object>>();
            var svcWasRunning = ZapretManager.SvcState("zapret") == "Running";
            var prevStandalone = !svcWasRunning && ZapretManager.WinwsRunning() ? App.Settings.RunningStrategy : null;
            string payload = null;
            bool ipsetSwitched = false;
            LastMode = mode;
            void Emit(string type, object data) => App.Emit("test", new Dictionary<string, object> { ["type"] = type, ["data"] = data });

            try
            {
                Log.Info(Src, $"Тест стратегий ({(mode == "dpi" ? "DPI 16-20KB" : "стандартный")}): {bats.Count} шт.");
                List<Target> targets = null; List<Dictionary<string, object>> suite = null;
                if (mode == "dpi")
                {
                    suite = await DpiSuite();
                    if (suite.Count == 0) throw new Exception("Не удалось загрузить набор DPI-целей");
                    payload = Path.GetTempFileName();
                    var bytes = new byte[65536]; new Random().NextBytes(bytes); File.WriteAllBytes(payload, bytes);
                    if (z.IpsetStatus() == "loaded" || z.IpsetStatus() == "none")
                    {
                        File.Copy(Path.Combine(z.Lists, "ipset-all.txt"), TestBackup, true);
                        File.WriteAllText(Path.Combine(z.Lists, "ipset-all.txt"), "");
                        ipsetSwitched = true;
                    }
                }
                else targets = StandardTargets();

                if (svcWasRunning)
                {
                    Log.Info(Src, "Служба zapret временно остановлена на время теста");
                    Shell.Run("sc.exe", "stop zapret");
                    Thread.Sleep(1500);
                }
                Emit("begin", new Dictionary<string, object> { ["total"] = bats.Count, ["mode"] = mode });

                for (int i = 0; i < bats.Count; i++)
                {
                    ct.ThrowIfCancellationRequested();
                    var bat = bats[i];
                    Emit("progress", new Dictionary<string, object> { ["index"] = i, ["total"] = bats.Count, ["name"] = bat });
                    z.KillWinws();
                    Dictionary<string, object> r;
                    try
                    {
                        z.Launch(bat, Paths.File("winws-test.log"));
                        if (!ZapretManager.WaitWinws(5000)) throw new Exception("winws не запустился");
                        Thread.Sleep(800);
                        r = mode == "dpi" ? ProbeDpi(suite, payload, ct) : ProbeStandard(targets, ct);
                    }
                    catch (OperationCanceledException) { throw; }
                    catch (AggregateException ae) when (ae.InnerExceptions.Any(e => e is OperationCanceledException)) { throw new OperationCanceledException(); }
                    catch (Exception ex)
                    {
                        r = new Dictionary<string, object> { ["ok"] = 0, ["total"] = 0, ["score"] = 0.0, ["error"] = ex.Message, ["rows"] = new List<object>() };
                    }
                    r["name"] = bat;
                    results.Add(r);
                    Emit("result", r);
                    Log.Write((double)r["score"] >= 90 ? "ok" : (double)r["score"] >= 50 ? "warn" : "err", Src, $"{bat}: {r["ok"]}/{r["total"]}" + (r.ContainsKey("error") ? " — " + r["error"] : ""));
                }
                Log.Ok(Src, "Тест завершён");
            }
            catch (Exception ex) when (ex is OperationCanceledException || ct.IsCancellationRequested)
            {
                Log.Warn(Src, "Тест прерван");
            }
            catch (Exception ex)
            {
                Log.Err(Src, "Ошибка теста: " + ex.Message);
                Emit("error", ex.Message);
            }
            finally
            {
                z.KillWinws();
                if (ipsetSwitched && File.Exists(TestBackup))
                {
                    File.Copy(TestBackup, Path.Combine(z.Lists, "ipset-all.txt"), true);
                    File.Delete(TestBackup);
                }
                if (payload != null) try { File.Delete(payload); } catch { }
                try
                {
                    if (svcWasRunning) { Shell.Run("sc.exe", "start zapret"); Log.Info(Src, "Служба zapret снова запущена"); }
                    else if (!string.IsNullOrEmpty(prevStandalone)) z.StartStandalone(prevStandalone);
                    else { App.Settings.RunningStrategy = ""; App.Settings.Save(); }
                }
                catch (Exception ex) { Log.Err(Src, "Не удалось восстановить прежний режим: " + ex.Message); }

                var ranked = results.OrderByDescending(r => (double)r["score"]).ThenByDescending(r => r.ContainsKey("pingOk") ? (int)r["pingOk"] : 0).ToList();
                Last = ranked;
                try { File.WriteAllText(Paths.File("last-test.json"), Json.Ser(new { mode, time = DateTime.Now.ToString("dd.MM.yyyy HH:mm"), results = ranked })); } catch { }
                Running = false;
                Emit("done", new Dictionary<string, object> { ["results"] = ranked, ["mode"] = mode });
                App.Notify("Тест стратегий завершён", ranked.Count > 0 ? $"Лучшая: {ranked[0]["name"]} ({ranked[0]["score"]}%)" : "Нет результатов");
            }
        }

        public object LastSaved()
        {
            try { var f = Paths.File("last-test.json"); return File.Exists(f) ? Json.Any(File.ReadAllText(f)) : null; } catch { return null; }
        }
    }
}
