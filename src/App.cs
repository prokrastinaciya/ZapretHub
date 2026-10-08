using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading.Tasks;

namespace ZapretHub
{
    /// <summary>Application state and the command surface exposed to the web UI.</summary>
    public static class App
    {
        public static Settings Settings;
        public static ZapretManager Zapret;
        public static TgProxyManager Tg;
        public static Tester Tester;
        public static MainForm Form;
        public static string Version => Assembly.GetExecutingAssembly().GetName().Version.ToString(3);

        static Dictionary<string, object> lastUpdates;
        static readonly Dictionary<string, bool> busy = new Dictionary<string, bool>();

        public static void Init()
        {
            Settings = Settings.Load();
            Zapret = new ZapretManager();
            Tg = new TgProxyManager();
            Tester = new Tester();
            if (!Zapret.Installed)
            {
                var found = ZapretManager.DetectExisting();
                if (found != null)
                {
                    Settings.ZapretDir = found;
                    Settings.Save();
                    Log.Ok("zapret", L.T("Найдена существующая установка zapret: ", "Found an existing zapret install: ") + found);
                }
            }
            Tester.RecoverIpset();
            Log.OnLine += l => Emit("log", l);
        }

        public static void Emit(string evt, object data) => Form?.Post(new Dictionary<string, object> { ["event"] = evt, ["data"] = data });

        public static void Notify(string title, string text)
        {
            if (Settings.Notifications) Form?.Balloon(title, text);
        }

        static Action<string, double> Progress(string task) => (text, pct) =>
            Emit("progress", new Dictionary<string, object> { ["task"] = task, ["text"] = text, ["pct"] = Math.Round(pct * 100) });

        /// <summary>Runs on application start (after the window is created).</summary>
        public static async Task Startup()
        {
            Installer.OnStartup();
            if (Program.JustUpdated) Notify("Zapret Hub " + Version, L.T("Приложение обновлено и снова работает", "The app has been updated and is running again"));
            try { NetProfiles.Init(); } catch (Exception ex) { Log.Warn("net", ex.Message); }
            try
            {
                if (Settings.TgAutoStart && Tg.Installed && Tg.OwnProcesses().Count == 0) await Task.Run(() => Tg.Start());
            }
            catch (Exception ex) { Log.Err("tg", L.T("Автозапуск прокси: ", "Proxy autostart: ") + ex.Message); }
            try
            {
                if (Settings.ZapretAutoStart && !string.IsNullOrEmpty(Settings.LastStrategy) && Zapret.Installed
                    && ZapretManager.SvcState("zapret") != "Running" && !ZapretManager.WinwsRunning())
                {
                    // a network profile wins over the last used strategy
                    var p = Settings.NetProfilesEnabled && NetProfiles.Current != null ? Settings.NetProfiles.FirstOrDefault(x => x.Id == NetProfiles.Current.Id) : null;
                    var bat = p != null && Zapret.Strategies().Contains(p.Strategy) ? p.Strategy : Settings.LastStrategy;
                    await Task.Run(() => Zapret.StartStandalone(bat));
                }
            }
            catch (Exception ex) { Log.Err("zapret", L.T("Автозапуск стратегии: ", "Strategy autostart: ") + ex.Message); }
            HealthMonitor.Init();

            if (Settings.AutoCheckUpdates)
            {
                await Task.Delay(4000);
                try
                {
                    var u = await CheckUpdates();
                    bool Has(string k) => u[k] is Dictionary<string, object> d && d.Bool("hasUpdate") && d.Str("local") != null;
                    var names = new[] { Has("zapret") ? "zapret " + ((Dictionary<string, object>)u["zapret"]).Str("version") : null,
                                        Has("tg") ? "TG WS Proxy " + ((Dictionary<string, object>)u["tg"]).Str("version") : null,
                                        Has("hub") ? "Zapret Hub " + ((Dictionary<string, object>)u["hub"]).Str("version") : null }.Where(s => s != null).ToList();
                    if (Settings.AutoInstallUpdates)
                    {
                        if (Has("zapret")) await Call("zapret.update", null);
                        if (Has("tg")) await Call("tg.update", null);
                        if (Has("hub")) await Call("hub.update", null); // restarts the app, so it goes last
                    }
                    else if (names.Count > 0)
                        Notify(L.T("Доступны обновления", "Updates available"), string.Join(", ", names));
                }
                catch (Exception ex) { Log.Warn("app", L.T("Проверка обновлений: ", "Update check: ") + ex.Message); }
            }
        }

        public static async Task<Dictionary<string, object>> CheckUpdates()
        {
            var zt = Zapret.CheckUpdate();
            var tt = Tg.CheckUpdate();
            var ht = SelfUpdate.CheckUpdate();
            var res = new Dictionary<string, object>();
            try { res["zapret"] = await zt; } catch (Exception ex) { res["zapret"] = new Dictionary<string, object> { ["error"] = ex.Message, ["local"] = Zapret.LocalVersion() }; }
            try { res["tg"] = await tt; } catch (Exception ex) { res["tg"] = new Dictionary<string, object> { ["error"] = ex.Message, ["local"] = Tg.Version() }; }
            try { res["hub"] = await ht; } catch (Exception ex) { res["hub"] = new Dictionary<string, object> { ["error"] = ex.Message, ["local"] = Version }; }
            res["checked"] = DateTime.Now.ToString("HH:mm");
            lastUpdates = res;
            Emit("updates", res);
            return res;
        }

        static async Task<object> Exclusive(string key, Func<Task<object>> f)
        {
            lock (busy) { if (busy.ContainsKey(key)) throw new Exception(L.T("Операция уже выполняется", "The operation is already running")); busy[key] = true; }
            try { return await f(); }
            finally { lock (busy) busy.Remove(key); }
        }

        static Task<object> Sync(Func<object> f) => Task.Run(f);
        static Task<object> Sync(Action f) => Task.Run(() => { f(); return (object)true; });

        /// <summary>A strategy the user picked by hand: remember it for the current network and mark it in the history.</summary>
        static void Picked(string bat)
        {
            NetProfiles.Remember(bat);
            History.Event("switch", bat, "manual");
        }

        public static async Task<object> Call(string cmd, Dictionary<string, object> a)
        {
            a = a ?? new Dictionary<string, object>();
            switch (cmd)
            {
                // ── app ──
                case "app.state":
                    return new Dictionary<string, object>
                    {
                        ["version"] = Version,
                        ["lang"] = Settings.EffectiveLang(),
                        ["settings"] = Settings,
                        ["autostart"] = Autostart.IsEnabled(),
                        ["updates"] = lastUpdates,
                        ["log"] = Log.All(),
                        ["dataDir"] = Paths.Data,
                        ["testing"] = Tester.Running,
                        ["install"] = Installer.State(),
                        ["hotkeyOk"] = Form?.HotkeyRegistered ?? false,
                        ["net"] = NetProfiles.State(),
                        ["health"] = HealthMonitor.Last,
                    };
                case "app.saveSettings":
                    {
                        var lang = Settings.EffectiveLang();
                        Settings.Apply(a);
                        if (a.ContainsKey("MonitorInterval") || a.ContainsKey("MonitorEnabled")) HealthMonitor.Reschedule();
                        if (Settings.EffectiveLang() != lang) Form?.LanguageChanged();
                        if (a.ContainsKey("NetProfilesEnabled")) Emit("net", NetProfiles.State());
                        return Settings;
                    }
                case "app.setHotkey":
                    {
                        var hk = (a.Str("hotkey") ?? "").Trim();
                        if (!Form.SetHotkey(hk))
                            throw new Exception(L.T("Сочетание ", "The shortcut ") + hk + L.T(" занято другой программой или недопустимо", " is used by another app or is invalid"));
                        Settings.Hotkey = hk; Settings.Save();
                        return hk;
                    }
                case "app.setAutostart": return await Sync(() => { Autostart.Set(a.Bool("on")); return Autostart.IsEnabled(); });
                case "app.open": Shell.Open(a.Str("target")); return true;
                case "app.openPath":
                    {
                        var p = a.Str("path");
                        switch (p)
                        {
                            case "zapret": p = Zapret.Root; break;
                            case "tg": p = Tg.Dir; break;
                            case "data": p = Paths.Data; break;
                            case "winwslog": p = ZapretManager.WinwsLog; break;
                            case "tglog": p = Tg.LogPath; break;
                            default: throw new Exception(L.T("Неизвестный путь", "Unknown path"));
                        }
                        if (Directory.Exists(p)) Shell.Open(p);
                        else if (File.Exists(p)) Process.Start("explorer.exe", "/select,\"" + p + "\"")?.Dispose();
                        else throw new Exception(L.T("Не найдено: ", "Not found: ") + p);
                        return true;
                    }
                case "app.pickFolder":
                    {
                        var path = Form.PickFolder(a.Str("title", L.T("Выберите папку", "Choose a folder")));
                        if (path == null) return null;
                        if (a.Str("for") == "zapret")
                        {
                            if (!File.Exists(Path.Combine(path, "bin", "winws.exe")) && Directory.EnumerateFileSystemEntries(path).Any())
                                throw new Exception(L.T("В этой папке нет zapret (bin\\winws.exe). Выберите распакованную папку zapret-discord-youtube или пустую папку для установки.",
                                                        "This folder has no zapret (bin\\winws.exe). Choose an extracted zapret-discord-youtube folder or an empty folder to install into."));
                            if (Misc.HasCyrillic(path)) Log.Warn("zapret", L.T("Путь содержит кириллицу — это может мешать работе zapret", "The path contains Cyrillic letters — this may break zapret"));
                            Settings.ZapretDir = path;
                        }
                        else if (a.Str("for") == "tg")
                        {
                            Tg.Stop();
                            Settings.TgDir = path;
                            Settings.TgVersion = "";
                        }
                        Settings.Save();
                        return path;
                    }
                case "app.window": Form.WindowCommand(a.Str("op")); return true;
                case "app.log": return Log.All();
                case "app.copy": Form.Copy(a.Str("text") ?? ""); return true;
                case "app.checkUpdates": return await CheckUpdates();
                case "app.export":
                    {
                        var path = Form.SaveFile(L.T("Экспорт настроек", "Export settings"), "Zapret Hub (*.zhub)|*.zhub", "ZapretHub-settings-" + DateTime.Now.ToString("yyyy-MM-dd") + ".zhub");
                        return path == null ? null : await Sync(() => Backup.Export(path));
                    }
                case "app.import":
                    {
                        var path = Form.OpenFile(L.T("Импорт настроек", "Import settings"), "Zapret Hub (*.zhub;*.zip)|*.zhub;*.zip");
                        if (path == null) return null;
                        var lang = Settings.EffectiveLang();
                        var res = await Sync(() => Backup.Import(path));
                        Form.SetHotkey(Settings.Hotkey);
                        HealthMonitor.Reschedule();
                        if (Settings.EffectiveLang() != lang) Form.LanguageChanged();
                        return res;
                    }
                case "app.report":
                    {
                        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                        var path = Form.SaveFile(L.T("Отчёт для баг-репорта", "Bug report"), "ZIP (*.zip)|*.zip", "ZapretHub-report-" + DateTime.Now.ToString("yyyyMMdd-HHmm") + ".zip", desktop);
                        if (path == null) return null;
                        await Sync(() => Backup.Report(path));
                        Process.Start("explorer.exe", "/select,\"" + path + "\"")?.Dispose();
                        return path;
                    }
                case "app.install": return await Sync(() => Installer.InstallFromApp());
                case "app.uninstall": return await Sync(() => Installer.UninstallFromApp());
                case "hub.update":
                    return await Exclusive("hub-update", async () => await SelfUpdate.Install(Progress("hub")));

                // ── zapret ──
                case "zapret.status": return await Sync(() => Zapret.Status());
                case "zapret.start":
                    return await Exclusive("zapret", () => Sync(() => { Zapret.StartStandalone(a.Str("bat")); Picked(a.Str("bat")); }));
                case "zapret.stop": return await Exclusive("zapret", () => Sync(() => Zapret.Stop()));
                case "zapret.switch":
                    return await Exclusive("zapret", () => Sync(() => { Zapret.SwitchTo(a.Str("bat")); Picked(a.Str("bat")); }));
                case "zapret.toggle":
                    return await Exclusive("zapret", () => Sync(() => (object)Zapret.Toggle()));
                case "zapret.installService":
                    return await Exclusive("zapret", () => Sync(() => { Zapret.InstallService(a.Str("bat")); Picked(a.Str("bat")); }));
                case "zapret.removeServices": return await Exclusive("zapret", () => Sync(() => Zapret.RemoveServices()));
                case "zapret.restart":
                    return await Exclusive("zapret", () => Sync(() =>
                    {
                        var st = Zapret.Status();
                        if ((string)st["mode"] == "service") Zapret.InstallService(Zapret.ServiceStrategy() + ".bat");
                        else if (!string.IsNullOrEmpty(Settings.LastStrategy)) Zapret.StartStandalone(Settings.LastStrategy);
                    }));
                case "zapret.setGameFilter": return await Sync(() => Zapret.SaveGameFilter(a.Str("mode"), a.Str("tcp"), a.Str("udp")));
                case "zapret.setIpset": return await Sync(() => Zapret.SetIpset(a.Str("mode")));
                case "zapret.updateIpset": return await Exclusive("ipset", async () => { await Zapret.UpdateIpsetList(); return true; });
                case "zapret.hostsCheck": return await Zapret.HostsCheck();
                case "zapret.hostsApply": await Zapret.HostsApply(); return true;
                case "zapret.hostsRemove": return await Sync(() => Zapret.HostsRemove());
                case "zapret.setCheckUpdatesFlag": return await Sync(() => Zapret.SetCheckUpdatesFlag(a.Bool("on")));
                case "zapret.fakes": return await Sync(() => Zapret.Fakes());
                case "zapret.replaceFake": return await Sync(() => Zapret.ReplaceFake(a.Str("type"), a.Str("name")));
                case "zapret.readList": return await Sync(() => Zapret.ReadList(a.Str("name")));
                case "zapret.saveList": return await Sync(() => Zapret.SaveList(a.Str("name"), a.Str("text")));
                case "zapret.args": return await Sync(() => Zapret.BuildArgs(a.Str("bat")));
                case "zapret.rawArgs": return await Sync(() => Zapret.RawArgs(a.Str("bat")));
                case "zapret.editorInfo": return await Sync(() => Zapret.EditorInfo());
                case "zapret.saveCustom": return await Sync(() => Zapret.SaveCustom(a.Str("name"), a.Str("args"), a.Bool("overwrite")));
                case "zapret.deleteCustom": return await Sync(() => Zapret.DeleteCustom(a.Str("bat")));
                case "zapret.log": return await Sync(() => Misc.TailFile(ZapretManager.WinwsLog, 32 * 1024));
                case "zapret.update":
                    return await Exclusive("zapret-update", async () =>
                    {
                        var v = await Zapret.InstallOrUpdate(Progress("zapret"));
                        Notify(L.T("zapret обновлён", "zapret updated"), L.T("Установлена версия ", "Installed version ") + v);
                        _ = CheckUpdates();
                        return v;
                    });
                case "zapret.repoScan": return await Zapret.RepoScan();
                case "zapret.repoDownload": return await Zapret.RepoDownload(a.List("names"));

                // ── diagnostics, tests, monitoring ──
                case "diag.run": return await Sync(() => Diagnostics.Run());
                case "diag.fix": return await Sync(() => Diagnostics.Fix(a.Str("id")));
                case "diag.fixAll": return await Sync(() => Diagnostics.FixAll());
                case "diag.discord": return await Sync(() => Diagnostics.ClearDiscordCache());
                case "test.start": Tester.Start(a.Str("mode", "standard"), a.List("bats"), a.Str("site")); return true;
                case "test.cancel": Tester.Cancel(); return true;
                case "test.last": return Tester.LastSaved(a.Bool("site"));
                case "test.quick": return await Tester.Quick();
                case "test.tgdc": return await Tg.TestDcs();
                case "health.check": return await HealthMonitor.Run("manual");
                case "history.get": return await Sync(() => History.Read(a.Dbl("hours", 24)));
                case "history.clear": return await Sync(() => History.Clear());
                case "net.state": return NetProfiles.State();
                case "net.setProfile": return await Sync(() => { NetProfiles.SetProfile(a.Str("id"), a.Str("name"), a.Str("strategy")); return NetProfiles.State(); });

                // ── tg-ws-proxy ──
                case "tg.status": return await Sync(() => Tg.Status());
                case "tg.start": return await Exclusive("tg", () => Sync(() => Tg.Start()));
                case "tg.stop": return await Exclusive("tg", () => Sync(() => Tg.Stop(a.Bool("external"))));
                case "tg.restart": return await Exclusive("tg", () => Sync(() => Tg.Restart()));
                case "tg.config": return await Sync(() => Tg.Config());
                case "tg.saveConfig": return await Exclusive("tg", () => Sync(() => Tg.SaveConfig(a)));
                case "tg.regenSecret": return await Exclusive("tg", () => Sync(() => Tg.RegenSecret()));
                case "tg.openTelegram": Shell.Open(Tg.Link()); return true;
                case "tg.log": return await Sync(() => Tg.ReadLog());
                case "tg.update":
                    return await Exclusive("tg-update", async () =>
                    {
                        var v = await Tg.InstallOrUpdate(Progress("tg"));
                        Notify(L.T("TG WS Proxy обновлён", "TG WS Proxy updated"), L.T("Установлена версия ", "Installed version ") + v);
                        _ = CheckUpdates();
                        return v;
                    });
                case "lan.status": return await Sync(() => Lan.Status());
                case "lan.enable": return await Exclusive("tg", () => Sync(() => Lan.Enable(a.Str("ip"))));
                case "lan.disable": return await Exclusive("tg", () => Sync(() => Lan.Disable()));
                case "lan.setIp": return await Sync(() => { Settings.LanIp = a.Str("ip") ?? ""; Settings.Save(); return Lan.Status(); });
            }
            throw new Exception(L.T("Неизвестная команда: ", "Unknown command: ") + cmd);
        }
    }

    static class Autostart
    {
        const string Task = "ZapretHub";

        public static bool IsEnabled() => Shell.Run("schtasks.exe", "/Query /TN " + Task, 8000).Code == 0;

        public static void Set(bool on, string exe = null)
        {
            if (on)
            {
                exe = exe ?? Paths.Exe;
                // Task Scheduler is the only way to auto-start an elevated app without a UAC prompt.
                var xml = $@"<?xml version=""1.0"" encoding=""UTF-16""?>
<Task version=""1.2"" xmlns=""http://schemas.microsoft.com/windows/2004/02/mit/task"">
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{System.Security.SecurityElement.Escape(Environment.UserDomainName + "\\" + Environment.UserName)}</UserId><Delay>PT5S</Delay></LogonTrigger></Triggers>
  <Principals><Principal id=""Author""><UserId>{System.Security.SecurityElement.Escape(Environment.UserDomainName + "\\" + Environment.UserName)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><ExecutionTimeLimit>PT0S</ExecutionTimeLimit><Priority>5</Priority></Settings>
  <Actions Context=""Author""><Exec><Command>{System.Security.SecurityElement.Escape(exe)}</Command><Arguments>--tray</Arguments></Exec></Actions>
</Task>";
                var f = Paths.File("task.xml");
                File.WriteAllText(f, xml, System.Text.Encoding.Unicode);
                var r = Shell.Run("schtasks.exe", $"/Create /TN {Task} /XML \"{f}\" /F", 15000);
                try { File.Delete(f); } catch { }
                if (r.Code != 0) throw new Exception(L.T("Не удалось создать задачу автозапуска: ", "Failed to create the autostart task: ") + r.All);
                Log.Ok("app", L.T("Автозапуск Zapret Hub при входе в Windows включён", "Zapret Hub now starts when you sign in to Windows"));
            }
            else
            {
                Shell.Run("schtasks.exe", "/Delete /TN " + Task + " /F", 8000);
                Log.Info("app", L.T("Автозапуск выключен", "Autostart disabled"));
            }
        }
    }
}
