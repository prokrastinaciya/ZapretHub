using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;

namespace ZapretHub
{
    /// <summary>Settings export/import for moving to another PC, and the bug-report archive.</summary>
    public static class Backup
    {
        const string Src = "app";
        const string Manifest = "zaprethub.json";

        // settings that describe this particular PC and must not travel
        static readonly string[] LocalOnly = { "ZapretDir", "TgDir", "RunningStrategy", "TgVersion", "LanIp", "InstallPromptDismissed" };

        static void AddText(ZipArchive zip, string name, string text)
        {
            var e = zip.CreateEntry(name, CompressionLevel.Optimal);
            using (var w = new StreamWriter(e.Open(), new UTF8Encoding(false))) w.Write(text);
        }

        static void AddFile(ZipArchive zip, string name, string path, int tailBytes = 0)
        {
            if (!File.Exists(path)) return;
            if (tailBytes > 0) { AddText(zip, name, Misc.TailFile(path, tailBytes)); return; }
            using (var src = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var dst = zip.CreateEntry(name, CompressionLevel.Optimal).Open()) src.CopyTo(dst);
        }

        // ───────────── export / import ─────────────

        public static string Export(string path)
        {
            var z = App.Zapret;
            var settings = Json.Obj(Json.Ser(App.Settings));
            foreach (var k in LocalOnly) settings.Remove(k);
            if (File.Exists(path)) File.Delete(path);
            var files = new List<string>();
            using (var zip = ZipFile.Open(path, ZipArchiveMode.Create))
            {
                if (z.Installed)
                {
                    foreach (var f in Directory.GetFiles(z.Lists, "*-user.txt")) { AddFile(zip, "lists/" + Path.GetFileName(f), f); files.Add(Path.GetFileName(f)); }
                    foreach (var f in new[] { "targets.txt", "game_filter.enabled" })
                        if (File.Exists(z.Utils + f)) { AddFile(zip, "utils/" + f, z.Utils + f); files.Add(f); }
                    foreach (var b in z.Strategies().Where(ZapretManager.IsCustom)) { AddFile(zip, "strategies/" + b, Path.Combine(z.Root, b)); files.Add(b); }
                }
                if (File.Exists(App.Tg.ConfigPath)) { AddFile(zip, "tg/config.json", App.Tg.ConfigPath); files.Add("tg/config.json"); }
                AddText(zip, Manifest, Json.Ser(new Dictionary<string, object>
                {
                    ["app"] = "Zapret Hub",
                    ["version"] = App.Version,
                    ["created"] = DateTime.Now.ToString("s"),
                    ["settings"] = settings,
                    ["files"] = files,
                }));
            }
            Log.Ok(Src, L.T("Настройки экспортированы: ", "Settings exported: ") + path);
            return path;
        }

        public static List<string> Import(string path)
        {
            var z = App.Zapret;
            var done = new List<string>();
            using (var zip = ZipFile.OpenRead(path))
            {
                var man = zip.GetEntry(Manifest) ?? throw new Exception(L.T("Это не файл настроек Zapret Hub", "This is not a Zapret Hub settings file"));
                Dictionary<string, object> m;
                using (var r = new StreamReader(man.Open())) m = Json.Obj(r.ReadToEnd());

                if (m.Dict("settings") is Dictionary<string, object> sd)
                {
                    var cur = Json.Obj(Json.Ser(App.Settings));
                    foreach (var kv in sd) if (!LocalOnly.Contains(kv.Key)) cur[kv.Key] = kv.Value;
                    var s = Json.To<Settings>(Json.Ser(cur));
                    if (s.NetProfiles == null) s.NetProfiles = new List<NetProfile>();
                    App.Settings = s;
                    s.Save();
                    done.Add(L.T("настройки приложения", "app settings"));
                }

                foreach (var e in zip.Entries)
                {
                    var name = Path.GetFileName(e.FullName);
                    if (name.Length == 0 || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) continue;
                    string dst = null;
                    if (e.FullName.StartsWith("lists/") && name.EndsWith("-user.txt", StringComparison.OrdinalIgnoreCase)) dst = z.Lists + name;
                    else if (e.FullName == "utils/targets.txt" || e.FullName == "utils/game_filter.enabled") dst = z.Utils + name;
                    else if (e.FullName.StartsWith("strategies/") && ZapretManager.IsCustom(name)) dst = Path.Combine(z.Root, name);
                    if (dst == null || !z.Installed) continue;
                    Directory.CreateDirectory(Path.GetDirectoryName(dst));
                    e.ExtractToFile(dst, true);
                    done.Add(name);
                }

                var tg = zip.GetEntry("tg/config.json");
                if (tg != null && App.Tg.Installed)
                {
                    var wasRunning = App.Tg.OwnProcesses().Count > 0;
                    App.Tg.Stop();
                    Directory.CreateDirectory(App.Tg.DataDir);
                    tg.ExtractToFile(App.Tg.ConfigPath, true);
                    if (wasRunning) App.Tg.Start();
                    done.Add(L.T("конфиг TG WS Proxy", "TG WS Proxy config"));
                }
            }
            Log.Ok(Src, L.T("Импортировано: ", "Imported: ") + string.Join(", ", done));
            return done;
        }

        // ───────────── bug report ─────────────

        public static string Report(string path)
        {
            var z = App.Zapret; var tg = App.Tg;
            string secret = null;
            try { if (tg.Installed) secret = tg.Config().Str("secret"); } catch { }
            string Clean(string s) => Misc.Redact(s, secret);
            if (File.Exists(path)) File.Delete(path);
            using (var zip = ZipFile.Open(path, ZipArchiveMode.Create))
            {
                var sys = new StringBuilder();
                sys.AppendLine("Zapret Hub " + App.Version + (Installer.IsInstalled ? " (installed)" : " (portable)"));
                sys.AppendLine("OS: " + Environment.OSVersion.VersionString + (Environment.Is64BitOperatingSystem ? " x64" : " x86") + ", " + System.Runtime.InteropServices.RuntimeInformation.OSArchitecture);
                sys.AppendLine("Culture: " + System.Globalization.CultureInfo.InstalledUICulture.Name + ", UI language: " + App.Settings.EffectiveLang());
                sys.AppendLine("Time: " + DateTime.Now.ToString("s"));
                sys.AppendLine("zapret: " + (z.LocalVersion() ?? "not installed") + " in " + z.Root);
                sys.AppendLine("TG WS Proxy: " + (tg.Version() ?? "not installed") + " in " + tg.Dir);
                try { var n = NetProfiles.Current; if (n != null) sys.AppendLine("Network: " + (n.Wifi ? "Wi-Fi" : "wired") + ", adapter " + n.Adapter); } catch { }
                AddText(zip, "system.txt", sys.ToString());

                try { AddText(zip, "diagnostics.json", Json.Ser(Diagnostics.Run())); } catch (Exception ex) { AddText(zip, "diagnostics.json", ex.ToString()); }
                try
                {
                    var tst = tg.Status(); tst.Remove("link");
                    AddText(zip, "status.json", Clean(Json.Ser(new Dictionary<string, object> { ["zapret"] = z.Status(), ["tg"] = tst, ["health"] = HealthMonitor.Last })));
                }
                catch { }
                if (z.Installed)
                    try { var cur = z.CurrentStrategy(); if (cur != null) AddText(zip, "strategy-args.txt", cur + "\r\n" + z.BuildArgs(cur)); } catch { }
                var settings = Json.Obj(Json.Ser(App.Settings));
                settings.Remove("NetProfiles"); // network names are personal
                AddText(zip, "settings.json", Json.Ser(settings));
                if (tg.Installed) try { var c = tg.Config(); c["secret"] = "<secret>"; AddText(zip, "tg-config.json", Json.Ser(c)); } catch { }

                AddText(zip, "logs/hub.log", Clean(Misc.TailFile(Paths.File("hub.log"), 1024 * 1024)));
                AddFile(zip, "logs/winws.log", ZapretManager.WinwsLog, 256 * 1024);
                AddFile(zip, "logs/winws-test.log", Paths.File("winws-test.log"), 64 * 1024);
                if (File.Exists(tg.LogPath)) AddText(zip, "logs/proxy.log", Clean(Misc.TailFile(tg.LogPath, 512 * 1024)));
                AddFile(zip, "last-test.json", Paths.File("last-test.json"));
                AddFile(zip, "history.jsonl", Paths.File("history.jsonl"), 512 * 1024);
            }
            Log.Ok(Src, L.T("Отчёт сохранён: ", "Report saved: ") + path);
            return path;
        }
    }
}
