using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace ZapretHub
{
    public class TgProxyManager
    {
        const string Repo = "Flowseal/tg-ws-proxy";
        const string Src = "tg";

        public string Dir => App.Settings.TgDir;
        public string Exe => Path.Combine(Dir, "TgWsProxy.exe");
        /// <summary>Presence of this folder switches TgWsProxy into portable mode, so its config lives next to the exe.</summary>
        public string DataDir => Path.Combine(Dir, "TgWsProxy_data");
        public string ConfigPath => Path.Combine(DataDir, "config.json");
        public string LogPath => Path.Combine(DataDir, "proxy.log");
        public bool Installed => File.Exists(Exe);

        static string AssetName => RuntimeInformation.OSArchitecture == Architecture.Arm64 ? "TgWsProxy_windows_arm64.exe" : "TgWsProxy_windows.exe";

        public List<Process> OwnProcesses()
        {
            var res = new List<Process>();
            foreach (var p in AllProxyProcesses())
            {
                string path = null;
                try { path = p.MainModule.FileName; } catch { }
                if (path != null && string.Equals(Path.GetFullPath(path), Path.GetFullPath(Exe), StringComparison.OrdinalIgnoreCase)) res.Add(p);
            }
            return res;
        }

        static IEnumerable<Process> AllProxyProcesses()
            => Process.GetProcesses().Where(p => p.ProcessName.StartsWith("TgWsProxy", StringComparison.OrdinalIgnoreCase));

        public Dictionary<string, object> Config()
        {
            EnsureConfig();
            return Json.Obj(File.ReadAllText(ConfigPath));
        }

        void EnsureConfig()
        {
            Directory.CreateDirectory(DataDir);
            if (!File.Exists(ConfigPath))
            {
                // reuse the config of a regular (non-portable) install so the user keeps their secret
                var appdata = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TgWsProxy", "config.json");
                Dictionary<string, object> cfg = null;
                if (File.Exists(appdata))
                {
                    try { cfg = Json.Obj(File.ReadAllText(appdata)); Log.Info(Src, L.T("Импортирован конфиг из %APPDATA%\\TgWsProxy", "Imported the config from %APPDATA%\\TgWsProxy")); } catch { }
                }
                cfg = cfg ?? new Dictionary<string, object>
                {
                    ["port"] = 1443,
                    ["host"] = "127.0.0.1",
                    ["secret"] = Misc.RandomHex(16),
                    ["dc_ip"] = new[] { "2:149.154.167.220", "4:149.154.167.220" },
                    ["verbose"] = false,
                    ["log_max_mb"] = 5,
                    ["buf_kb"] = 256,
                    ["pool_size"] = 4,
                    ["cfproxy"] = true,
                    ["h2"] = true,
                    ["cfproxy_user_domain_enabled"] = false,
                    ["cfproxy_user_domain"] = new string[0],
                    ["cfproxy_worker_enabled"] = false,
                    ["cfproxy_worker_domain"] = new string[0],
                    ["force_test_dc"] = false,
                    ["no_secure"] = false,
                    ["appearance"] = "dark",
                    ["language"] = "ru",
                };
                cfg["check_updates"] = false; // Zapret Hub handles updates itself
                cfg["autostart"] = false;
                File.WriteAllText(ConfigPath, Json.Ser(cfg));
            }
            // skip the first-run instruction window: Zapret Hub shows the connection info itself
            var marker = Path.Combine(DataDir, ".first_run_done_mtproto");
            if (!File.Exists(marker)) File.WriteAllText(marker, "");
        }

        public void SaveConfig(Dictionary<string, object> upd)
        {
            var cfg = Config();
            var port = upd.Int("port", cfg.Int("port", 1443));
            if (port < 1 || port > 65535) throw new Exception(L.T("Порт должен быть от 1 до 65535", "The port must be between 1 and 65535"));
            var secret = upd.Str("secret", cfg.Str("secret")).Trim().ToLowerInvariant();
            if (secret.Length != 32 || !secret.All(Uri.IsHexDigit)) throw new Exception(L.T("Secret должен состоять из 32 hex-символов", "The secret must be 32 hex characters"));
            var dcs = upd.ContainsKey("dc_ip") ? upd.List("dc_ip").Select(s => s.Trim()).Where(s => s.Length > 0).ToList() : cfg.List("dc_ip");
            foreach (var d in dcs)
                if (!System.Text.RegularExpressions.Regex.IsMatch(d, @"^\d+:[\d\.]+$")) throw new Exception(L.T("Неверный формат DC:IP — ", "Invalid DC:IP format — ") + d);

            cfg["host"] = upd.Str("host", cfg.Str("host", "127.0.0.1")).Trim();
            cfg["port"] = port;
            cfg["secret"] = secret;
            cfg["dc_ip"] = dcs;
            foreach (var b in new[] { "verbose", "cfproxy", "h2", "cfproxy_user_domain_enabled", "cfproxy_worker_enabled", "no_secure" })
                if (upd.ContainsKey(b)) cfg[b] = upd.Bool(b);
            foreach (var n in new[] { "buf_kb", "pool_size" })
                if (upd.ContainsKey(n)) cfg[n] = Math.Max(1, upd.Int(n));
            if (upd.ContainsKey("log_max_mb")) cfg["log_max_mb"] = Math.Max(1, upd.Dbl("log_max_mb", 5));
            foreach (var l in new[] { "cfproxy_user_domain", "cfproxy_worker_domain" })
                if (upd.ContainsKey(l)) cfg[l] = upd.List(l).Select(s => s.Trim()).Where(s => s.Length > 0).ToList();
            cfg["check_updates"] = false;
            cfg["autostart"] = false;
            File.WriteAllText(ConfigPath, Json.Ser(cfg));
            Log.Ok(Src, L.T("Настройки прокси сохранены", "Proxy settings saved"));
            if (OwnProcesses().Count > 0) Restart();
        }

        public string Link()
        {
            var c = Config();
            var host = c.Str("host", "127.0.0.1");
            if (host == "0.0.0.0") host = "127.0.0.1"; // this link is for Telegram on this PC; LAN devices get theirs from Lan
            return $"tg://proxy?server={host}&port={c.Int("port", 1443)}&secret=dd{c.Str("secret")}";
        }

        public string Version()
        {
            if (!Installed) return null;
            if (!string.IsNullOrEmpty(App.Settings.TgVersion)) return App.Settings.TgVersion;
            try
            {
                var v = FileVersionInfo.GetVersionInfo(Exe);
                return Ver.Clean(v.ProductVersion ?? v.FileVersion);
            }
            catch { return null; }
        }

        public bool PortOpen()
        {
            if (!Installed) return false;
            var c = Config();
            var host = c.Str("host", "127.0.0.1");
            if (host == "0.0.0.0") host = "127.0.0.1";
            try
            {
                using (var tcp = new TcpClient())
                {
                    var t = tcp.ConnectAsync(host, c.Int("port", 1443));
                    return t.Wait(600) && tcp.Connected;
                }
            }
            catch { return false; }
        }

        public Dictionary<string, object> Status()
        {
            var own = OwnProcesses();
            var external = AllProxyProcesses().Count() - own.Count;
            var d = new Dictionary<string, object>
            {
                ["installed"] = Installed,
                ["dir"] = Dir,
                ["version"] = Version(),
                ["running"] = own.Count > 0,
                ["external"] = external > 0,
                ["listening"] = false,
            };
            if (Installed)
            {
                var c = Config();
                d["host"] = c.Str("host");
                d["port"] = c.Int("port");
                d["link"] = Link();
                d["listening"] = own.Count > 0 && PortOpen();
            }
            return d;
        }

        public void Start()
        {
            if (!Installed) throw new Exception(L.T("TG WS Proxy не установлен — установите его кнопкой «Установить».", "TG WS Proxy is not installed — install it with the Install button."));
            if (OwnProcesses().Count > 0) return;
            if (AllProxyProcesses().Any())
                throw new Exception(L.T("Запущен другой экземпляр TgWsProxy (не из Zapret Hub). Остановите его кнопкой «Закрыть сторонний» или через трей.", "Another TgWsProxy instance is running (not started by Zapret Hub). Stop it with \"Close external\" or from its tray icon."));
            EnsureConfig();
            Process.Start(new ProcessStartInfo(Exe) { WorkingDirectory = Dir, UseShellExecute = false })?.Dispose();
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 8000 && !PortOpen()) Thread.Sleep(250);
            if (PortOpen()) Log.Ok(Src, L.T("TG WS Proxy запущен — ", "TG WS Proxy started — ") + Link().Replace("tg://proxy?", ""));
            else Log.Warn(Src, L.T("Процесс запущен, но порт пока не отвечает. Проверьте журнал прокси.", "The process started, but the port does not answer yet. Check the proxy log."));
        }

        public void Stop(bool external = false)
        {
            var list = external ? AllProxyProcesses().ToList() : OwnProcesses();
            foreach (var p in list)
            {
                try { p.Kill(); p.WaitForExit(4000); } catch { }
            }
            try { foreach (var f in Directory.GetFiles(DataDir, "*.lock")) File.Delete(f); } catch { }
            if (list.Count > 0) Log.Info(Src, external ? L.T("Все экземпляры TgWsProxy остановлены", "All TgWsProxy instances stopped") : L.T("TG WS Proxy остановлен", "TG WS Proxy stopped"));
        }

        public void Restart() { Stop(); Thread.Sleep(400); Start(); }

        public void RegenSecret()
        {
            var c = Config();
            c["secret"] = Misc.RandomHex(16);
            SaveConfig(c);
            Log.Info(Src, L.T("Сгенерирован новый secret — переподключите Telegram по новой ссылке", "New secret generated — reconnect Telegram with the new link"));
        }

        public async Task<Dictionary<string, object>> LatestRelease()
        {
            string tag = null, notes = null;
            try
            {
                var rel = Json.Obj(await Net.Text($"https://api.github.com/repos/{Repo}/releases/latest"));
                tag = rel.Str("tag_name"); notes = rel.Str("body");
            }
            catch
            {
                var final = await Net.FinalUrl($"https://github.com/{Repo}/releases/latest");
                var i = final.LastIndexOf("/tag/", StringComparison.Ordinal);
                if (i >= 0) tag = Uri.UnescapeDataString(final.Substring(i + 5));
            }
            if (string.IsNullOrEmpty(tag)) throw new Exception(L.T("Не удалось определить последнюю версию TG WS Proxy", "Could not determine the latest TG WS Proxy version"));
            return new Dictionary<string, object>
            {
                ["version"] = Ver.Clean(tag),
                ["tag"] = tag,
                ["url"] = $"https://github.com/{Repo}/releases/download/{tag}/{AssetName}",
                ["page"] = $"https://github.com/{Repo}/releases/tag/{tag}",
                ["notes"] = notes,
            };
        }

        public async Task<Dictionary<string, object>> CheckUpdate()
        {
            var rel = await LatestRelease();
            var local = Version();
            rel["local"] = local;
            rel["hasUpdate"] = local == null || Ver.Compare((string)rel["version"], local) > 0;
            return rel;
        }

        public async Task<string> InstallOrUpdate(Action<string, double> progress)
        {
            var rel = await LatestRelease();
            var ver = (string)rel["version"];
            var tmp = Path.Combine(Path.GetTempPath(), "TgWsProxy-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".exe");
            await Net.Download((string)rel["url"], tmp, p => progress(L.T("Загрузка TG WS Proxy ", "Downloading TG WS Proxy ") + ver, p * 0.9));
            var wasRunning = OwnProcesses().Count > 0;
            progress(L.T("Установка", "Installing"), 0.92);
            Stop();
            Directory.CreateDirectory(Dir);
            Misc.CopyWithRetry(tmp, Exe);
            try { File.Delete(tmp); } catch { }
            EnsureConfig();
            App.Settings.TgVersion = ver;
            App.Settings.Save();
            if (wasRunning) Start();
            progress(L.T("Готово", "Done"), 1);
            Log.Ok(Src, "TG WS Proxy " + ver + L.T(" установлен в ", " installed to ") + Dir);
            return ver;
        }

        public string ReadLog() => Misc.TailFile(LogPath, 48 * 1024);

        /// <summary>Same check as the tray app: TLS + WebSocket upgrade to kws{dc}.web.telegram.org.</summary>
        public async Task<List<Dictionary<string, object>>> TestDcs()
        {
            var dcs = new[] { 1, 2, 3, 4, 5 };
            var tasks = dcs.Select(dc => Task.Run(() => WsProbe("kws" + dc + ".web.telegram.org", "/apiws"))).ToArray();
            await Task.WhenAll(tasks);
            var res = new List<Dictionary<string, object>>();
            for (int i = 0; i < dcs.Length; i++)
                res.Add(new Dictionary<string, object> { ["dc"] = dcs[i], ["ok"] = tasks[i].Result.ok, ["msg"] = tasks[i].Result.msg, ["ms"] = tasks[i].Result.ms });
            return res;
        }

        static (bool ok, string msg, long ms) WsProbe(string host, string path)
        {
            var sw = Stopwatch.StartNew();
            try
            {
                // prefer IPv4: a dead IPv6 route makes the default dual-stack connect hang
                var addrs = System.Net.Dns.GetHostAddresses(host).OrderBy(a => a.AddressFamily == AddressFamily.InterNetwork ? 0 : 1).ToArray();
                if (addrs.Length == 0) return (false, L.T("DNS не вернул адрес", "DNS returned no address"), sw.ElapsedMilliseconds);
                using (var tcp = new TcpClient(addrs[0].AddressFamily))
                {
                    if (!tcp.ConnectAsync(addrs[0], 443).Wait(5000)) return (false, L.T("таймаут TCP", "TCP timeout"), sw.ElapsedMilliseconds);
                    using (var ssl = new SslStream(tcp.GetStream(), false))
                    {
                        ssl.ReadTimeout = 5000; ssl.WriteTimeout = 5000;
                        if (!ssl.AuthenticateAsClientAsync(host).Wait(5000)) return (false, L.T("таймаут TLS", "TLS timeout"), sw.ElapsedMilliseconds);
                        var key = Convert.ToBase64String(Guid.NewGuid().ToByteArray());
                        var req = $"GET {path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n";
                        var b = Encoding.ASCII.GetBytes(req);
                        ssl.Write(b, 0, b.Length);
                        var buf = new byte[512]; var sb = new StringBuilder();
                        while (!sb.ToString().Contains("\r\n\r\n"))
                        {
                            var n = ssl.Read(buf, 0, buf.Length);
                            if (n <= 0) break;
                            sb.Append(Encoding.ASCII.GetString(buf, 0, n));
                        }
                        var first = sb.ToString().Split('\n')[0].Trim();
                        return (first.Contains(" 101"), first.Length > 0 ? first : L.T("нет ответа", "no response"), sw.ElapsedMilliseconds);
                    }
                }
            }
            catch (Exception ex)
            {
                var e = ex is AggregateException ae ? ae.InnerException : ex;
                var m = e.Message; if (m.Length > 70) m = m.Substring(0, 70);
                return (false, m, sw.ElapsedMilliseconds);
            }
        }
    }
}
