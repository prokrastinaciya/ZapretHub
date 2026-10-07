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
                    Log.Ok("zapret", "Найдена существующая установка zapret: " + found);
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
            try
            {
                if (Settings.TgAutoStart && Tg.Installed && Tg.OwnProcesses().Count == 0) await Task.Run(() => Tg.Start());
            }
            catch (Exception ex) { Log.Err("tg", "Автозапуск прокси: " + ex.Message); }
            try
            {
                if (Settings.ZapretAutoStart && !string.IsNullOrEmpty(Settings.LastStrategy) && Zapret.Installed
                    && ZapretManager.SvcState("zapret") != "Running" && !ZapretManager.WinwsRunning())
                    await Task.Run(() => Zapret.StartStandalone(Settings.LastStrategy));
            }
            catch (Exception ex) { Log.Err("zapret", "Автозапуск стратегии: " + ex.Message); }

            if (Settings.AutoCheckUpdates)
            {
                await Task.Delay(4000);
                try
                {
                    var u = await CheckUpdates();
                    var z = u["zapret"] as Dictionary<string, object>; var t = u["tg"] as Dictionary<string, object>;
                    bool zu = z != null && z.Bool("hasUpdate") && z.Str("local") != null;
                    bool tu = t != null && t.Bool("hasUpdate") && t.Str("local") != null;
                    if (Settings.AutoInstallUpdates)
                    {
                        if (zu) await Call("zapret.update", null);
                        if (tu) await Call("tg.update", null);
                    }
                    else if (zu || tu)
                        Notify("Доступны обновления", string.Join(", ", new[] { zu ? "zapret " + z.Str("version") : null, tu ? "TG WS Proxy " + t.Str("version") : null }.Where(s => s != null)));
                }
                catch (Exception ex) { Log.Warn("app", "Проверка обновлений: " + ex.Message); }
            }
        }

        public static async Task<Dictionary<string, object>> CheckUpdates()
        {
            var zt = Zapret.CheckUpdate();
            var tt = Tg.CheckUpdate();
            var res = new Dictionary<string, object>();
            try { res["zapret"] = await zt; } catch (Exception ex) { res["zapret"] = new Dictionary<string, object> { ["error"] = ex.Message, ["local"] = Zapret.LocalVersion() }; }
            try { res["tg"] = await tt; } catch (Exception ex) { res["tg"] = new Dictionary<string, object> { ["error"] = ex.Message, ["local"] = Tg.Version() }; }
            res["checked"] = DateTime.Now.ToString("HH:mm");
            lastUpdates = res;
            Emit("updates", res);
            return res;
        }

        static async Task<object> Exclusive(string key, Func<Task<object>> f)
        {
            lock (busy) { if (busy.ContainsKey(key)) throw new Exception("Операция уже выполняется"); busy[key] = true; }
            try { return await f(); }
            finally { lock (busy) busy.Remove(key); }
        }

        static Task<object> Sync(Func<object> f) => Task.Run(f);
        static Task<object> Sync(Action f) => Task.Run(() => { f(); return (object)true; });

        public static async Task<object> Call(string cmd, Dictionary<string, object> a)
        {
            switch (cmd)
            {
                // ── app ──
                case "app.state":
                    return new Dictionary<string, object>
                    {
                        ["version"] = Version,
                        ["settings"] = Settings,
                        ["autostart"] = Autostart.IsEnabled(),
                        ["updates"] = lastUpdates,
                        ["log"] = Log.All(),
                        ["dataDir"] = Paths.Data,
                        ["testing"] = Tester.Running,
                    };
                case "app.saveSettings": Settings.Apply(a); return Settings;
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
                            default: throw new Exception("Неизвестный путь");
                        }
                        if (Directory.Exists(p)) Shell.Open(p);
                        else if (File.Exists(p)) Process.Start("explorer.exe", "/select,\"" + p + "\"")?.Dispose();
                        else throw new Exception("Не найдено: " + p);
                        return true;
                    }
                case "app.pickFolder":
                    {
                        var path = Form.PickFolder(a.Str("title", "Выберите папку"));
                        if (path == null) return null;
                        if (a.Str("for") == "zapret")
                        {
                            if (!File.Exists(Path.Combine(path, "bin", "winws.exe")) && Directory.EnumerateFileSystemEntries(path).Any())
                                throw new Exception("В этой папке нет zapret (bin\\winws.exe). Выберите распакованную папку zapret-discord-youtube или пустую папку для установки.");
                            if (Misc.HasCyrillic(path)) Log.Warn("zapret", "Путь содержит кириллицу — это может мешать работе zapret");
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

                // ── zapret ──
                case "zapret.status": return await Sync(() => Zapret.Status());
                case "zapret.start": return await Exclusive("zapret", () => Sync(() => Zapret.StartStandalone(a.Str("bat"))));
                case "zapret.stop": return await Exclusive("zapret", () => Sync(() => Zapret.Stop()));
                case "zapret.installService": return await Exclusive("zapret", () => Sync(() => Zapret.InstallService(a.Str("bat"))));
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
                case "zapret.log": return await Sync(() => Misc.TailFile(ZapretManager.WinwsLog, 32 * 1024));
                case "zapret.update":
                    return await Exclusive("zapret-update", async () =>
                    {
                        var v = await Zapret.InstallOrUpdate(Progress("zapret"));
                        Notify("zapret обновлён", "Установлена версия " + v);
                        _ = CheckUpdates();
                        return v;
                    });
                case "zapret.repoScan": return await Zapret.RepoScan();
                case "zapret.repoDownload": return await Zapret.RepoDownload(a.List("names"));

                // ── diagnostics & tests ──
                case "diag.run": return await Sync(() => Diagnostics.Run());
                case "diag.fix": return await Sync(() => Diagnostics.Fix(a.Str("id")));
                case "diag.discord": return await Sync(() => Diagnostics.ClearDiscordCache());
                case "test.start": Tester.Start(a.Str("mode", "standard"), a.List("bats")); return true;
                case "test.cancel": Tester.Cancel(); return true;
                case "test.last": return Tester.LastSaved();
                case "test.quick": return await Tester.Quick();
                case "test.tgdc": return await Tg.TestDcs();

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
                        Notify("TG WS Proxy обновлён", "Установлена версия " + v);
                        _ = CheckUpdates();
                        return v;
                    });
            }
            throw new Exception("Неизвестная команда: " + cmd);
        }
    }

    static class Autostart
    {
        const string Task = "ZapretHub";

        public static bool IsEnabled() => Shell.Run("schtasks.exe", "/Query /TN " + Task, 8000).Code == 0;

        public static void Set(bool on)
        {
            if (on)
            {
                // Task Scheduler is the only way to auto-start an elevated app without a UAC prompt.
                var xml = $@"<?xml version=""1.0"" encoding=""UTF-16""?>
<Task version=""1.2"" xmlns=""http://schemas.microsoft.com/windows/2004/02/mit/task"">
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{System.Security.SecurityElement.Escape(Environment.UserDomainName + "\\" + Environment.UserName)}</UserId><Delay>PT5S</Delay></LogonTrigger></Triggers>
  <Principals><Principal id=""Author""><UserId>{System.Security.SecurityElement.Escape(Environment.UserDomainName + "\\" + Environment.UserName)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><ExecutionTimeLimit>PT0S</ExecutionTimeLimit><Priority>5</Priority></Settings>
  <Actions Context=""Author""><Exec><Command>{System.Security.SecurityElement.Escape(Paths.Exe)}</Command><Arguments>--tray</Arguments></Exec></Actions>
</Task>";
                var f = Paths.File("task.xml");
                File.WriteAllText(f, xml, System.Text.Encoding.Unicode);
                var r = Shell.Run("schtasks.exe", $"/Create /TN {Task} /XML \"{f}\" /F", 15000);
                try { File.Delete(f); } catch { }
                if (r.Code != 0) throw new Exception("Не удалось создать задачу автозапуска: " + r.All);
                Log.Ok("app", "Автозапуск Zapret Hub при входе в Windows включён");
            }
            else
            {
                Shell.Run("schtasks.exe", "/Delete /TN " + Task + " /F", 8000);
                Log.Info("app", "Автозапуск выключен");
            }
        }
    }
}
