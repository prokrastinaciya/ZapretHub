using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.ServiceProcess;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace ZapretHub
{
    /// <summary>Native port of service.bat → Run Diagnostics, plus a few extra checks. Fixes are applied only on request.</summary>
    public static class Diagnostics
    {
        const string Src = "diag";
        static readonly string[] Conflicting = { "GoodbyeDPI", "discordfix_zapret", "winws1", "winws2" };
        /// <summary>Fixes that change system state without user input — the ones "Fix all" applies.</summary>
        public static readonly string[] AutoFixes = { "bfe", "tcpts", "windivert", "conflicts" };

        static Dictionary<string, object> R(string id, string title, string status, string msg, string fix = null, string fixLabel = null, string link = null)
            => new Dictionary<string, object> { ["id"] = id, ["title"] = title, ["status"] = status, ["msg"] = msg, ["fix"] = fix, ["fixLabel"] = fixLabel, ["link"] = link };

        static string T(string ru, string en) => L.T(ru, en);

        static List<ServiceController> ActiveServices()
        {
            try { return ServiceController.GetServices().Where(s => { try { return s.Status != ServiceControllerStatus.Stopped; } catch { return false; } }).ToList(); }
            catch { return new List<ServiceController>(); }
        }

        static bool AnyActive(List<ServiceController> svcs, params string[] words)
            => svcs.Any(s => words.All(w => (s.ServiceName + " " + s.DisplayName).IndexOf(w, StringComparison.OrdinalIgnoreCase) >= 0));

        public static List<Dictionary<string, object>> Run()
        {
            var z = App.Zapret;
            var res = new List<Dictionary<string, object>>();
            var svcs = ActiveServices();
            string notFound = T("Не обнаружено", "Not found");

            // zapret files
            if (!z.Installed) res.Add(R("zapret", T("Файлы zapret", "zapret files"), "fail", T("zapret не найден в ", "zapret not found in ") + z.Root, "install_zapret", T("Установить", "Install")));
            else if (!Directory.GetFiles(z.Bin, "*.sys").Any()) res.Add(R("zapret", T("Файлы zapret", "zapret files"), "fail", T("WinDivert64.sys не найден — вероятно, удалён антивирусом", "WinDivert64.sys is missing — probably removed by an antivirus"), "install_zapret", T("Переустановить", "Reinstall")));
            else res.Add(R("zapret", T("Файлы zapret", "zapret files"), "ok", T("Версия ", "Version ") + $"{z.LocalVersion() ?? "?"} • {z.Root}"));

            // BFE
            var bfe = ZapretManager.SvcState("BFE");
            res.Add(bfe == "Running"
                ? R("bfe", "Base Filtering Engine", "ok", T("Служба работает", "The service is running"))
                : R("bfe", "Base Filtering Engine", "fail", T("Служба BFE не запущена — без неё zapret не работает", "The BFE service is not running — zapret does not work without it"), "bfe", T("Запустить", "Start")));

            // system proxy
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings"))
                {
                    var en = Convert.ToInt32(k?.GetValue("ProxyEnable") ?? 0) == 1;
                    res.Add(en
                        ? R("proxy", T("Системный прокси", "System proxy"), "warn", T("Включён прокси: ", "Proxy enabled: ") + k.GetValue("ProxyServer") + T(". Убедитесь, что он нужен, иначе отключите его.", ". Make sure you need it, otherwise turn it off."))
                        : R("proxy", T("Системный прокси", "System proxy"), "ok", T("Не используется", "Not used")));
                }
            }
            catch { }

            // TCP timestamps
            var ts = Shell.Cmd437("netsh interface tcp show global").Out.Split('\n').FirstOrDefault(l => l.IndexOf("timestamps", StringComparison.OrdinalIgnoreCase) >= 0) ?? "";
            res.Add(ts.IndexOf("enabled", StringComparison.OrdinalIgnoreCase) >= 0
                ? R("tcpts", "TCP timestamps", "ok", T("Включены", "Enabled"))
                : R("tcpts", "TCP timestamps", "warn", T("Отключены — некоторые стратегии (fooling=ts) не будут работать", "Disabled — strategies with fooling=ts will not work"), "tcpts", T("Включить", "Enable")));

            // conflicting software
            res.Add(Process.GetProcessesByName("AdguardSvc").Any()
                ? R("adguard", "Adguard", "fail", T("Обнаружен Adguard — он может мешать работе Discord", "Adguard detected — it can break Discord"), link: "https://github.com/Flowseal/zapret-discord-youtube/issues/417")
                : R("adguard", "Adguard", "ok", notFound));
            res.Add(AnyActive(svcs, "Killer")
                ? R("killer", "Killer Network", "fail", T("Службы Killer конфликтуют с zapret — отключите их", "Killer services conflict with zapret — disable them"), link: "https://github.com/Flowseal/zapret-discord-youtube/issues/2512#issuecomment-2821119513")
                : R("killer", "Killer Network", "ok", notFound));
            res.Add(AnyActive(svcs, "Intel", "Connectivity", "Network")
                ? R("intel", "Intel Connectivity Network Service", "fail", T("Служба конфликтует с zapret", "The service conflicts with zapret"), link: "https://github.com/ValdikSS/GoodbyeDPI/issues/541#issuecomment-2661670982")
                : R("intel", "Intel Connectivity Network Service", "ok", notFound));
            res.Add(AnyActive(svcs, "TracSrvWrapper") || AnyActive(svcs, "EPWD")
                ? R("checkpoint", "Check Point", "fail", T("Службы Check Point конфликтуют с zapret — попробуйте удалить Check Point", "Check Point services conflict with zapret — try uninstalling Check Point"))
                : R("checkpoint", "Check Point", "ok", notFound));
            res.Add(AnyActive(svcs, "SmartByte")
                ? R("smartbyte", "SmartByte", "fail", T("SmartByte конфликтует с zapret — отключите его в services.msc", "SmartByte conflicts with zapret — disable it in services.msc"))
                : R("smartbyte", "SmartByte", "ok", notFound));

            var vpns = svcs.Where(s => (s.ServiceName + s.DisplayName).IndexOf("VPN", StringComparison.OrdinalIgnoreCase) >= 0).Select(s => s.DisplayName).ToList();
            res.Add(vpns.Count > 0
                ? R("vpn", "VPN", "warn", T("Активные VPN-службы: ", "Active VPN services: ") + string.Join(", ", vpns) + T(". Некоторые VPN конфликтуют с zapret.", ". Some VPNs conflict with zapret."))
                : R("vpn", "VPN", "ok", T("VPN-службы не активны", "No active VPN services")));

            // install path
            res.Add(Misc.HasCyrillic(z.Root)
                ? R("cyr", T("Путь установки", "Install path"), "warn", T("Путь содержит кириллицу — если обход не работает, перенесите zapret, например, в C:\\zapret", "The path contains Cyrillic letters — if the bypass does not work, move zapret to e.g. C:\\zapret"))
                : R("cyr", T("Путь установки", "Install path"), "ok", T("Без кириллицы", "No Cyrillic letters")));
            var od = Environment.GetEnvironmentVariable("OneDrive");
            res.Add(!string.IsNullOrEmpty(od) && (z.Root + "\\").StartsWith(od.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)
                ? R("onedrive", "OneDrive", "fail", T("zapret лежит в папке OneDrive — перенесите его, например, в C:\\zapret", "zapret is inside OneDrive — move it to e.g. C:\\zapret"))
                : R("onedrive", "OneDrive", "ok", T("Не в OneDrive", "Not in OneDrive")));

            // secure DNS
            int doh = 0;
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(@"System\CurrentControlSet\Services\Dnscache\InterfaceSpecificParameters"))
                    if (k != null) doh = CountDoh(k);
            }
            catch { }
            res.Add(doh > 0
                ? R("doh", T("Защищённый DNS", "Secure DNS"), "ok", T("DNS-over-HTTPS настроен в Windows", "DNS over HTTPS is configured in Windows"))
                : R("doh", T("Защищённый DNS", "Secure DNS"), "warn", T("Настройте зашифрованный DNS (DoH) в браузере или в параметрах Windows 11 — провайдер может подменять DNS", "Set up encrypted DNS (DoH) in your browser or in Windows 11 settings — your provider may spoof DNS"), "doh", T("Открыть настройки", "Open settings")));

            // hosts
            try
            {
                var hosts = File.ReadAllText(Path.Combine(Paths.System32, @"drivers\etc\hosts"));
                res.Add(Regex.IsMatch(hosts, @"^[^#\r\n]*(youtube\.com|youtu\.be)", RegexOptions.Multiline | RegexOptions.IgnoreCase)
                    ? R("hosts", T("Файл hosts", "hosts file"), "warn", T("hosts содержит записи youtube.com / youtu.be — это может ломать YouTube", "hosts has youtube.com / youtu.be entries — they can break YouTube"), "hosts_open", T("Открыть hosts", "Open hosts"))
                    : R("hosts", T("Файл hosts", "hosts file"), "ok", T("Нет записей YouTube", "No YouTube entries")));
            }
            catch { }

            // WinDivert leftovers
            var wd = ZapretManager.SvcState("WinDivert");
            if (!ZapretManager.WinwsRunning() && (wd == "Running" || wd == "StopPending"))
                res.Add(R("windivert", "WinDivert", "warn", T("winws не запущен, но драйвер WinDivert активен — его держит другая программа обхода", "winws is not running, but the WinDivert driver is active — another bypass tool holds it"), "windivert", T("Удалить WinDivert", "Remove WinDivert")));
            else
                res.Add(R("windivert", "WinDivert", "ok", wd == null ? T("Драйвер не загружен", "The driver is not loaded") : T("Состояние: ", "State: ") + wd));
            var zs = ZapretManager.SvcState("zapret");
            if (zs == "StopPending")
                res.Add(R("zsvc", T("Служба zapret", "zapret service"), "warn", T("Служба зависла в STOP_PENDING — вероятно, конфликт с другим обходом", "The service is stuck in STOP_PENDING — probably a conflict with another bypass"), "windivert", T("Исправить", "Fix")));

            var conf = Conflicting.Where(n => ZapretManager.SvcState(n) != null).ToList();
            res.Add(conf.Count > 0
                ? R("conflicts", T("Другие обходы DPI", "Other DPI bypasses"), "fail", T("Найдены конфликтующие службы: ", "Conflicting services found: ") + string.Join(", ", conf), "conflicts", T("Удалить", "Remove"))
                : R("conflicts", T("Другие обходы DPI", "Other DPI bypasses"), "ok", notFound));

            // tools
            res.Add(File.Exists(Paths.Curl)
                ? R("curl", "curl.exe", "ok", T("Найден — тесты стратегий доступны", "Found — strategy tests are available"))
                : R("curl", "curl.exe", "fail", T("curl.exe не найден в System32 — тесты стратегий недоступны", "curl.exe not found in System32 — strategy tests are unavailable")));

            // tg-ws-proxy port conflict
            var tg = App.Tg;
            if (tg.Installed && tg.OwnProcesses().Count == 0 && tg.PortOpen())
                res.Add(R("tgport", T("Порт TG WS Proxy", "TG WS Proxy port"), "warn", T("Порт прокси занят другим процессом — TG WS Proxy не сможет запуститься", "The proxy port is used by another process — TG WS Proxy will not start")));

            foreach (var s in svcs) s.Dispose();
            return res;
        }

        static int CountDoh(RegistryKey k)
        {
            int n = 0;
            foreach (var name in k.GetSubKeyNames())
            {
                using (var sub = k.OpenSubKey(name))
                {
                    if (sub == null) continue;
                    var v = sub.GetValue("DohFlags");
                    if (v != null && Convert.ToInt64(v) > 0) n++;
                    n += CountDoh(sub);
                }
            }
            return n;
        }

        /// <summary>Applies every automatic fix the current diagnostics call for. Returns one line per action.</summary>
        public static List<string> FixAll()
        {
            var fixes = Run().Where(r => (string)r["status"] != "ok" && r["fix"] is string f && AutoFixes.Contains(f)).Select(r => (string)r["fix"]).Distinct().ToList();
            var log = new List<string>();
            foreach (var f in fixes)
            {
                try { var m = Fix(f); if (m != null) log.Add(m); }
                catch (Exception ex) { log.Add(ex.Message); }
            }
            if (log.Count == 0) log.Add(T("Нечего исправлять автоматически", "Nothing to fix automatically"));
            foreach (var l in log) Log.Info(Src, l);
            return log;
        }

        public static string Fix(string id)
        {
            switch (id)
            {
                case "bfe":
                    Shell.Run("sc.exe", "config BFE start= auto");
                    Shell.Run("sc.exe", "start BFE");
                    return T("Служба BFE запущена", "BFE service started");
                case "tcpts":
                    var r = Shell.Run("netsh.exe", "interface tcp set global timestamps=enabled");
                    if (r.Code != 0) throw new Exception(T("Не удалось включить TCP timestamps: ", "Failed to enable TCP timestamps: ") + r.All);
                    return T("TCP timestamps включены", "TCP timestamps enabled");
                case "windivert":
                    Shell.Run("sc.exe", "stop WinDivert"); Shell.Run("sc.exe", "delete WinDivert");
                    if (ZapretManager.SvcState("WinDivert") != null)
                    {
                        foreach (var c in Conflicting) RemoveSvc(c);
                        Shell.Run("sc.exe", "stop WinDivert"); Shell.Run("sc.exe", "delete WinDivert");
                    }
                    Shell.Run("sc.exe", "stop WinDivert14"); Shell.Run("sc.exe", "delete WinDivert14");
                    return ZapretManager.SvcState("WinDivert") == null
                        ? T("WinDivert удалён", "WinDivert removed")
                        : T("WinDivert всё ещё занят — проверьте вручную, какой обход его использует (может потребоваться перезагрузка)", "WinDivert is still busy — check which bypass uses it (a reboot may be needed)");
                case "conflicts":
                    var removed = new List<string>();
                    foreach (var c in Conflicting) if (ZapretManager.SvcState(c) != null) { RemoveSvc(c); removed.Add(c); }
                    Shell.Run("sc.exe", "stop WinDivert"); Shell.Run("sc.exe", "delete WinDivert");
                    Shell.Run("sc.exe", "stop WinDivert14"); Shell.Run("sc.exe", "delete WinDivert14");
                    return T("Удалены службы: ", "Removed services: ") + string.Join(", ", removed);
                case "doh":
                    Shell.Open("ms-settings:network-status");
                    return null;
                case "hosts_open":
                    Shell.Detached("notepad.exe", Shell.QuoteArg(Path.Combine(Paths.System32, @"drivers\etc\hosts")), Paths.System32);
                    return null;
                default: throw new Exception(T("Неизвестное исправление: ", "Unknown fix: ") + id);
            }
        }

        static void RemoveSvc(string n)
        {
            Shell.Run("sc.exe", "stop " + Shell.QuoteArg(n));
            Shell.Run("sc.exe", "delete " + Shell.QuoteArg(n));
            Log.Info(Src, T("Удалена служба ", "Removed service ") + n);
        }

        public static List<string> ClearDiscordCache()
        {
            var appdata = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var variants = new[] { ("Discord", "discord"), ("DiscordPTB", "discordptb"), ("DiscordCanary", "discordcanary"), ("DiscordDevelopment", "discorddevelopment") };
            var log = new List<string>();
            foreach (var (proc, folder) in variants)
            {
                var dir = Path.Combine(appdata, folder);
                if (!Directory.Exists(dir)) continue;
                foreach (var p in Process.GetProcessesByName(proc)) { try { p.Kill(); p.WaitForExit(3000); } catch { } }
                foreach (var sub in new[] { "Cache", "Code Cache", "GPUCache" })
                {
                    var d = Path.Combine(dir, sub);
                    if (!Directory.Exists(d)) continue;
                    try { Directory.Delete(d, true); log.Add(T("Очищено: ", "Cleared: ") + folder + "\\" + sub); }
                    catch (Exception ex) { log.Add(T("Ошибка ", "Error ") + folder + "\\" + sub + ": " + ex.Message); }
                }
            }
            if (log.Count == 0) log.Add(T("Установки Discord не найдены", "No Discord installations found"));
            foreach (var l in log) Log.Info(Src, l);
            return log;
        }
    }
}
