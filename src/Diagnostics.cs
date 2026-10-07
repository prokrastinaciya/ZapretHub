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

        static Dictionary<string, object> R(string id, string title, string status, string msg, string fix = null, string fixLabel = null, string link = null)
            => new Dictionary<string, object> { ["id"] = id, ["title"] = title, ["status"] = status, ["msg"] = msg, ["fix"] = fix, ["fixLabel"] = fixLabel, ["link"] = link };

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

            // zapret files
            if (!z.Installed) res.Add(R("zapret", "Файлы zapret", "fail", "zapret не найден в " + z.Root, "install_zapret", "Установить"));
            else if (!Directory.GetFiles(z.Bin, "*.sys").Any()) res.Add(R("zapret", "Файлы zapret", "fail", "WinDivert64.sys не найден — вероятно, удалён антивирусом", "install_zapret", "Переустановить"));
            else res.Add(R("zapret", "Файлы zapret", "ok", $"Версия {z.LocalVersion() ?? "?"} • {z.Root}"));

            // BFE
            var bfe = ZapretManager.SvcState("BFE");
            res.Add(bfe == "Running"
                ? R("bfe", "Base Filtering Engine", "ok", "Служба работает")
                : R("bfe", "Base Filtering Engine", "fail", "Служба BFE не запущена — без неё zapret не работает", "bfe", "Запустить"));

            // system proxy
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings"))
                {
                    var en = Convert.ToInt32(k?.GetValue("ProxyEnable") ?? 0) == 1;
                    res.Add(en
                        ? R("proxy", "Системный прокси", "warn", "Включён прокси: " + k.GetValue("ProxyServer") + ". Убедитесь, что он нужен, иначе отключите его.")
                        : R("proxy", "Системный прокси", "ok", "Не используется"));
                }
            }
            catch { }

            // TCP timestamps
            var ts = Shell.Cmd437("netsh interface tcp show global").Out.Split('\n').FirstOrDefault(l => l.IndexOf("timestamps", StringComparison.OrdinalIgnoreCase) >= 0) ?? "";
            res.Add(ts.IndexOf("enabled", StringComparison.OrdinalIgnoreCase) >= 0
                ? R("tcpts", "TCP timestamps", "ok", "Включены")
                : R("tcpts", "TCP timestamps", "warn", "Отключены — некоторые стратегии (fooling=ts) не будут работать", "tcpts", "Включить"));

            // conflicting software
            res.Add(Process.GetProcessesByName("AdguardSvc").Any()
                ? R("adguard", "Adguard", "fail", "Обнаружен Adguard — он может мешать работе Discord", link: "https://github.com/Flowseal/zapret-discord-youtube/issues/417")
                : R("adguard", "Adguard", "ok", "Не обнаружен"));
            res.Add(AnyActive(svcs, "Killer")
                ? R("killer", "Killer Network", "fail", "Службы Killer конфликтуют с zapret — отключите их", link: "https://github.com/Flowseal/zapret-discord-youtube/issues/2512#issuecomment-2821119513")
                : R("killer", "Killer Network", "ok", "Не обнаружено"));
            res.Add(AnyActive(svcs, "Intel", "Connectivity", "Network")
                ? R("intel", "Intel Connectivity Network Service", "fail", "Служба конфликтует с zapret", link: "https://github.com/ValdikSS/GoodbyeDPI/issues/541#issuecomment-2661670982")
                : R("intel", "Intel Connectivity Network Service", "ok", "Не обнаружено"));
            res.Add(AnyActive(svcs, "TracSrvWrapper") || AnyActive(svcs, "EPWD")
                ? R("checkpoint", "Check Point", "fail", "Службы Check Point конфликтуют с zapret — попробуйте удалить Check Point")
                : R("checkpoint", "Check Point", "ok", "Не обнаружено"));
            res.Add(AnyActive(svcs, "SmartByte")
                ? R("smartbyte", "SmartByte", "fail", "SmartByte конфликтует с zapret — отключите его в services.msc")
                : R("smartbyte", "SmartByte", "ok", "Не обнаружено"));

            var vpns = svcs.Where(s => (s.ServiceName + s.DisplayName).IndexOf("VPN", StringComparison.OrdinalIgnoreCase) >= 0).Select(s => s.DisplayName).ToList();
            res.Add(vpns.Count > 0
                ? R("vpn", "VPN", "warn", "Активные VPN-службы: " + string.Join(", ", vpns) + ". Некоторые VPN конфликтуют с zapret.")
                : R("vpn", "VPN", "ok", "VPN-службы не активны"));

            // install path
            res.Add(Misc.HasCyrillic(z.Root)
                ? R("cyr", "Путь установки", "warn", "Путь содержит кириллицу — если обход не работает, перенесите zapret, например, в C:\\zapret")
                : R("cyr", "Путь установки", "ok", "Без кириллицы"));
            var od = Environment.GetEnvironmentVariable("OneDrive");
            res.Add(!string.IsNullOrEmpty(od) && (z.Root + "\\").StartsWith(od.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)
                ? R("onedrive", "OneDrive", "fail", "zapret лежит в папке OneDrive — перенесите его, например, в C:\\zapret")
                : R("onedrive", "OneDrive", "ok", "Не в OneDrive"));

            // secure DNS
            int doh = 0;
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(@"System\CurrentControlSet\Services\Dnscache\InterfaceSpecificParameters"))
                    if (k != null) doh = CountDoh(k);
            }
            catch { }
            res.Add(doh > 0
                ? R("doh", "Защищённый DNS", "ok", "DNS-over-HTTPS настроен в Windows")
                : R("doh", "Защищённый DNS", "warn", "Настройте зашифрованный DNS (DoH) в браузере или в параметрах Windows 11 — провайдер может подменять DNS", "doh", "Открыть настройки"));

            // hosts
            try
            {
                var hosts = File.ReadAllText(Path.Combine(Paths.System32, @"drivers\etc\hosts"));
                res.Add(Regex.IsMatch(hosts, @"^[^#\r\n]*(youtube\.com|youtu\.be)", RegexOptions.Multiline | RegexOptions.IgnoreCase)
                    ? R("hosts", "Файл hosts", "warn", "hosts содержит записи youtube.com / youtu.be — это может ломать YouTube", "hosts_open", "Открыть hosts")
                    : R("hosts", "Файл hosts", "ok", "Нет записей YouTube"));
            }
            catch { }

            // WinDivert leftovers
            var wd = ZapretManager.SvcState("WinDivert");
            if (!ZapretManager.WinwsRunning() && (wd == "Running" || wd == "StopPending"))
                res.Add(R("windivert", "WinDivert", "warn", "winws не запущен, но драйвер WinDivert активен — его держит другая программа обхода", "windivert", "Удалить WinDivert"));
            else
                res.Add(R("windivert", "WinDivert", "ok", wd == null ? "Драйвер не загружен" : "Состояние: " + wd));
            var zs = ZapretManager.SvcState("zapret");
            if (zs == "StopPending")
                res.Add(R("zsvc", "Служба zapret", "warn", "Служба зависла в STOP_PENDING — вероятно, конфликт с другим обходом", "windivert", "Исправить"));

            var conf = Conflicting.Where(n => ZapretManager.SvcState(n) != null).ToList();
            res.Add(conf.Count > 0
                ? R("conflicts", "Другие обходы DPI", "fail", "Найдены конфликтующие службы: " + string.Join(", ", conf), "conflicts", "Удалить")
                : R("conflicts", "Другие обходы DPI", "ok", "Не обнаружено"));

            // tools
            res.Add(File.Exists(Paths.Curl)
                ? R("curl", "curl.exe", "ok", "Найден — тесты стратегий доступны")
                : R("curl", "curl.exe", "fail", "curl.exe не найден в System32 — тесты стратегий недоступны"));

            // tg-ws-proxy port conflict
            var tg = App.Tg;
            if (tg.Installed && tg.OwnProcesses().Count == 0 && tg.PortOpen())
                res.Add(R("tgport", "Порт TG WS Proxy", "warn", "Порт прокси занят другим процессом — TG WS Proxy не сможет запуститься"));

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

        public static string Fix(string id)
        {
            switch (id)
            {
                case "bfe":
                    Shell.Run("sc.exe", "config BFE start= auto");
                    Shell.Run("sc.exe", "start BFE");
                    return "Служба BFE запущена";
                case "tcpts":
                    var r = Shell.Run("netsh.exe", "interface tcp set global timestamps=enabled");
                    if (r.Code != 0) throw new Exception("Не удалось включить TCP timestamps: " + r.All);
                    return "TCP timestamps включены";
                case "windivert":
                    Shell.Run("sc.exe", "stop WinDivert"); Shell.Run("sc.exe", "delete WinDivert");
                    if (ZapretManager.SvcState("WinDivert") != null)
                    {
                        foreach (var c in Conflicting) RemoveSvc(c);
                        Shell.Run("sc.exe", "stop WinDivert"); Shell.Run("sc.exe", "delete WinDivert");
                    }
                    Shell.Run("sc.exe", "stop WinDivert14"); Shell.Run("sc.exe", "delete WinDivert14");
                    return ZapretManager.SvcState("WinDivert") == null ? "WinDivert удалён" : "WinDivert всё ещё занят — проверьте вручную, какой обход его использует (может потребоваться перезагрузка)";
                case "conflicts":
                    var removed = new List<string>();
                    foreach (var c in Conflicting) if (ZapretManager.SvcState(c) != null) { RemoveSvc(c); removed.Add(c); }
                    Shell.Run("sc.exe", "stop WinDivert"); Shell.Run("sc.exe", "delete WinDivert");
                    Shell.Run("sc.exe", "stop WinDivert14"); Shell.Run("sc.exe", "delete WinDivert14");
                    return "Удалены службы: " + string.Join(", ", removed);
                case "doh":
                    Shell.Open("ms-settings:network-status");
                    return null;
                case "hosts_open":
                    Shell.Detached("notepad.exe", Shell.QuoteArg(Path.Combine(Paths.System32, @"drivers\etc\hosts")), Paths.System32);
                    return null;
                default: throw new Exception("Неизвестное исправление: " + id);
            }
        }

        static void RemoveSvc(string n)
        {
            Shell.Run("sc.exe", "stop " + Shell.QuoteArg(n));
            Shell.Run("sc.exe", "delete " + Shell.QuoteArg(n));
            Log.Info(Src, "Удалена служба " + n);
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
                    try { Directory.Delete(d, true); log.Add("Очищено: " + folder + "\\" + sub); }
                    catch (Exception ex) { log.Add("Ошибка " + folder + "\\" + sub + ": " + ex.Message); }
                }
            }
            if (log.Count == 0) log.Add("Установки Discord не найдены");
            foreach (var l in log) Log.Info(Src, l);
            return log;
        }
    }
}
