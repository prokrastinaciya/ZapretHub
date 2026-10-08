using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.ServiceProcess;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Win32;

namespace ZapretHub
{
    public class ZapretManager
    {
        const string Repo = "Flowseal/zapret-discord-youtube";
        const string RawMain = "https://raw.githubusercontent.com/" + Repo + "/main/";
        const string Src = "zapret";
        const string IpsetNone = "203.0.113.113/32";
        const string HostsBegin = "# >>> ZapretHub (zapret-discord-youtube hosts) >>>";
        const string HostsEnd = "# <<< ZapretHub <<<";

        readonly object opLock = new object();

        public string Root => App.Settings.ZapretDir;
        public string Bin => Path.Combine(Root, "bin") + "\\";
        public string Lists => Path.Combine(Root, "lists") + "\\";
        public string Utils => Path.Combine(Root, "utils") + "\\";
        public bool Installed => File.Exists(Path.Combine(Root, "bin", "winws.exe"));
        public static string WinwsLog => Paths.File("winws.log");

        // ───────────────────────── info ─────────────────────────

        public string LocalVersion()
        {
            try
            {
                var svc = Path.Combine(Root, "service.bat");
                if (!File.Exists(svc)) return null;
                var m = Regex.Match(File.ReadAllText(svc), "set \"LOCAL_VERSION=([^\"]+)\"");
                return m.Success ? m.Groups[1].Value.Trim() : null;
            }
            catch { return null; }
        }

        public List<string> Strategies()
        {
            if (!Directory.Exists(Root)) return new List<string>();
            return Directory.GetFiles(Root, "*.bat")
                .Select(Path.GetFileName)
                .Where(n => !n.StartsWith("service", StringComparison.OrdinalIgnoreCase))
                .OrderBy(Misc.NaturalKey, StringComparer.OrdinalIgnoreCase)
                .ToList();
        }

        public static string SvcState(string name)
        {
            try { using (var sc = new ServiceController(name)) return sc.Status.ToString(); }
            catch { return null; }
        }

        /// <summary>Finds a zapret folder the user already runs: from the service's ImagePath or a running winws.exe.</summary>
        public static string DetectExisting()
        {
            var candidates = new List<string>();
            try
            {
                using (var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(@"System\CurrentControlSet\Services\zapret"))
                {
                    var img = k?.GetValue("ImagePath") as string;
                    var m = img == null ? null : Regex.Match(img, "^\\s*\"?([^\"]*?winws\\.exe)", RegexOptions.IgnoreCase);
                    if (m != null && m.Success) candidates.Add(m.Groups[1].Value);
                }
            }
            catch { }
            foreach (var p in Process.GetProcessesByName("winws"))
            {
                try { candidates.Add(p.MainModule.FileName); } catch { }
                p.Dispose();
            }
            foreach (var exe in candidates)
            {
                var root = Path.GetDirectoryName(Path.GetDirectoryName(exe));
                if (root != null && File.Exists(Path.Combine(root, "service.bat"))) return root;
            }
            return null;
        }

        public static bool WinwsRunning() => Process.GetProcessesByName("winws").Length > 0;

        public static bool IsCustom(string bat) => Regex.IsMatch(bat ?? "", @"^custom \(.+\)\.bat$", RegexOptions.IgnoreCase);

        /// <summary>The .bat that is running right now (service or standalone), or null.</summary>
        public string CurrentStrategy()
        {
            if (SvcState("zapret") == "Running") { var s = ServiceStrategy(); return string.IsNullOrEmpty(s) ? null : s + ".bat"; }
            return WinwsRunning() && !string.IsNullOrEmpty(App.Settings.RunningStrategy) ? App.Settings.RunningStrategy : null;
        }

        /// <summary>Switches to another strategy keeping the current mode: a running service is reinstalled, otherwise winws is started.</summary>
        public void SwitchTo(string bat)
        {
            if (SvcState("zapret") == "Running") InstallService(bat);
            else StartStandalone(bat);
        }

        /// <summary>Hotkey / tray toggle: stops the bypass, or starts the installed service or the last strategy.</summary>
        public string Toggle()
        {
            lock (opLock)
            {
                if ((string)Status()["mode"] != "off") { Stop(); return null; }
                if (SvcState("zapret") != null && !string.IsNullOrEmpty(ServiceStrategy()))
                {
                    Shell.Run("sc.exe", "start zapret");
                    WaitSvc("zapret", ServiceControllerStatus.Running, 6000);
                    if (SvcState("zapret") != "Running") throw new Exception(L.T("Служба zapret не запустилась", "The zapret service did not start"));
                    Log.Ok(Src, L.T("Служба zapret запущена", "The zapret service started"));
                    return ServiceStrategy() + ".bat";
                }
                var bat = App.Settings.LastStrategy;
                if (string.IsNullOrEmpty(bat) || !File.Exists(Path.Combine(Root, bat))) bat = Strategies().FirstOrDefault();
                if (bat == null) throw new Exception(L.T("Нет стратегий для запуска", "No strategies to start"));
                StartStandalone(bat);
                return bat;
            }
        }

        public string ServiceStrategy()
        {
            try
            {
                using (var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(@"System\CurrentControlSet\Services\zapret"))
                    return k?.GetValue("zapret-discord-youtube") as string;
            }
            catch { return null; }
        }

        public Dictionary<string, object> Status()
        {
            var svc = SvcState("zapret");
            var running = WinwsRunning();
            string mode = "off", strategy = null;
            if (svc == "Running") { mode = "service"; strategy = ServiceStrategy(); }
            else if (running)
            {
                mode = "standalone";
                strategy = string.IsNullOrEmpty(App.Settings.RunningStrategy) ? L.T("внешний запуск", "external launch") : App.Settings.RunningStrategy;
            }
            if (!running && !string.IsNullOrEmpty(App.Settings.RunningStrategy) && svc != "Running")
            {
                App.Settings.RunningStrategy = "";
                App.Settings.Save();
            }
            var gf = ReadGameFilter();
            return new Dictionary<string, object>
            {
                ["installed"] = Installed,
                ["root"] = Root,
                ["version"] = LocalVersion(),
                ["service"] = svc,
                ["serviceStrategy"] = ServiceStrategy(),
                ["windivert"] = SvcState("WinDivert"),
                ["winws"] = running,
                ["mode"] = mode,
                ["strategy"] = strategy,
                ["lastStrategy"] = App.Settings.LastStrategy,
                ["strategies"] = Strategies(),
                ["custom"] = Strategies().Where(IsCustom).ToList(),
                ["gameFilter"] = gf,
                ["ipset"] = IpsetStatus(),
                ["ipsetBackup"] = File.Exists(Lists + "ipset-all.txt.backup"),
                ["checkUpdatesFlag"] = File.Exists(Utils + "check_updates.enabled"),
            };
        }

        // ───────────────────────── settings files ─────────────────────────

        public Dictionary<string, object> ReadGameFilter()
        {
            string mode = "disabled", tcp = "1024-65535", udp = "1024-65535";
            var f = Utils + "game_filter.enabled";
            if (File.Exists(f))
            {
                foreach (var raw in File.ReadAllLines(f))
                {
                    var line = raw.Trim(); if (line.Length == 0) continue;
                    var idx = line.IndexOf('=');
                    var key = (idx < 0 ? line : line.Substring(0, idx)).Trim().ToLowerInvariant();
                    var val = idx < 0 ? "" : line.Substring(idx + 1).Trim();
                    if (key == "mode") mode = val.ToLowerInvariant();
                    else if (key == "all") mode = "all";
                    else if (key == "tcp") { if (val == "") mode = "tcp"; else if (ValidRange(val)) tcp = val.Replace(" ", ""); }
                    else if (key == "udp") { if (val == "") mode = "udp"; else if (ValidRange(val)) udp = val.Replace(" ", ""); }
                }
                if (mode != "all" && mode != "tcp" && mode != "udp") mode = "disabled";
            }
            return new Dictionary<string, object> { ["mode"] = mode, ["tcp"] = tcp, ["udp"] = udp };
        }

        public static bool ValidRange(string s)
        {
            s = (s ?? "").Replace(" ", "");
            if (s.Length == 0) return false;
            foreach (var item in s.Split(','))
            {
                var m = Regex.Match(item, @"^([1-9]\d{0,4})(?:-([1-9]\d{0,4}))?$");
                if (!m.Success) return false;
                int a = int.Parse(m.Groups[1].Value), b = m.Groups[2].Success ? int.Parse(m.Groups[2].Value) : a;
                if (a > 65535 || b > 65535 || a > b) return false;
            }
            return true;
        }

        public void SaveGameFilter(string mode, string tcp, string udp)
        {
            if (!ValidRange(tcp) || !ValidRange(udp)) throw new Exception(L.T("Неверный диапазон портов. Пример: 1024-1934,1936-65535", "Invalid port range. Example: 1024-1934,1936-65535"));
            Directory.CreateDirectory(Utils);
            File.WriteAllText(Utils + "game_filter.enabled", $"mode={mode}\r\ntcp={tcp.Replace(" ", "")}\r\nudp={udp.Replace(" ", "")}\r\n");
            Log.Ok(Src, $"Game Filter: {mode} (TCP {tcp}, UDP {udp})" + L.T(". Перезапустите стратегию для применения.", ". Restart the strategy to apply."));
        }

        (string tcp, string udp) GameFilterPorts()
        {
            var gf = ReadGameFilter();
            var mode = (string)gf["mode"];
            string tcp = "12", udp = "12";
            if (mode == "all" || mode == "tcp") tcp = (string)gf["tcp"];
            if (mode == "all" || mode == "udp") udp = (string)gf["udp"];
            return (tcp, udp);
        }

        public string IpsetStatus()
        {
            var f = Lists + "ipset-all.txt";
            if (!File.Exists(f)) return "missing";
            var raw = File.ReadAllText(f);
            if (string.IsNullOrWhiteSpace(raw)) return "any";
            return raw.Contains(IpsetNone) ? "none" : "loaded";
        }

        public void SetIpset(string target)
        {
            var list = Lists + "ipset-all.txt";
            var backup = list + ".backup";
            var cur = IpsetStatus();
            if (cur == target) return;
            switch (target)
            {
                case "none":
                    if (cur == "loaded") { if (File.Exists(backup)) File.Delete(backup); File.Move(list, backup); }
                    File.WriteAllText(list, IpsetNone + "\r\n");
                    break;
                case "any":
                    if (cur == "loaded") { if (File.Exists(backup)) File.Delete(backup); File.Move(list, backup); }
                    File.WriteAllText(list, "");
                    break;
                case "loaded":
                    if (!File.Exists(backup)) throw new Exception(L.T("Нет резервной копии списка — сначала обновите IPSet-список.", "No list backup — update the IPSet list first."));
                    if (File.Exists(list)) File.Delete(list);
                    File.Move(backup, list);
                    break;
                default: throw new Exception(L.T("Неизвестный режим IPSet: ", "Unknown IPSet mode: ") + target);
            }
            Log.Ok(Src, "IPSet → " + target + L.T(". Перезапустите стратегию для применения.", ". Restart the strategy to apply."));
        }

        public void SetCheckUpdatesFlag(bool on)
        {
            var f = Utils + "check_updates.enabled";
            if (on) File.WriteAllText(f, "ENABLED \r\n"); else if (File.Exists(f)) File.Delete(f);
        }

        public void LoadUserLists()
        {
            Directory.CreateDirectory(Lists);
            if (!File.Exists(Lists + "ipset-exclude-user.txt")) File.WriteAllText(Lists + "ipset-exclude-user.txt", "203.0.113.113/32\r\n");
            if (!File.Exists(Lists + "list-general-user.txt")) File.WriteAllText(Lists + "list-general-user.txt", "# Never leave this file empty\r\ndomain.example.abc\r\n");
            if (!File.Exists(Lists + "list-exclude-user.txt")) File.WriteAllText(Lists + "list-exclude-user.txt", "domain.example.abc\r\n");
        }

        static readonly Dictionary<string, string> EditableLists = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["list-general-user.txt"] = "lists",
            ["list-exclude-user.txt"] = "lists",
            ["ipset-exclude-user.txt"] = "lists",
            ["list-general.txt"] = "lists",
            ["list-google.txt"] = "lists",
            ["list-exclude.txt"] = "lists",
            ["ipset-exclude.txt"] = "lists",
            ["targets.txt"] = "utils",
        };

        string ListPath(string name)
        {
            if (!EditableLists.TryGetValue(name, out var dir)) throw new Exception(L.T("Этот файл нельзя редактировать: ", "This file cannot be edited: ") + name);
            return Path.Combine(Root, dir, name);
        }

        public string ReadList(string name) { LoadUserLists(); var p = ListPath(name); return File.Exists(p) ? File.ReadAllText(p) : ""; }

        public void SaveList(string name, string text)
        {
            var p = ListPath(name);
            text = (text ?? "").Replace("\r\n", "\n").Replace("\n", "\r\n");
            if (name.EndsWith("-user.txt", StringComparison.OrdinalIgnoreCase) && string.IsNullOrWhiteSpace(text))
                text = name.StartsWith("ipset") ? "203.0.113.113/32\r\n" : "domain.example.abc\r\n"; // winws fails on empty lists
            File.WriteAllText(p, text);
            Log.Ok(Src, L.T("Сохранён ", "Saved ") + name + L.T(". Перезапустите стратегию для применения.", ". Restart the strategy to apply."));
        }

        public void TcpEnable()
        {
            var r = Shell.Cmd437("netsh interface tcp show global");
            var line = r.Out.Split('\n').FirstOrDefault(l => l.IndexOf("timestamps", StringComparison.OrdinalIgnoreCase) >= 0) ?? "";
            if (line.IndexOf("enabled", StringComparison.OrdinalIgnoreCase) < 0)
            {
                Shell.Run("netsh.exe", "interface tcp set global timestamps=enabled");
                Log.Info(Src, L.T("Включены TCP timestamps", "TCP timestamps enabled"));
            }
        }

        // ───────────────────────── fakes ─────────────────────────

        public Dictionary<string, object> Fakes()
        {
            var files = Directory.Exists(Bin) ? Directory.GetFiles(Bin, "*.bin").Where(f => !Path.GetFileName(f).StartsWith("ACTIVE_", StringComparison.OrdinalIgnoreCase)).OrderBy(f => f).ToList() : new List<string>();
            var hashes = files.ToDictionary(f => f, Misc.Sha256);
            string Cur(string active)
            {
                var p = Bin + active;
                if (!File.Exists(p)) return null;
                var h = Misc.Sha256(p);
                var match = hashes.FirstOrDefault(kv => kv.Value == h);
                return match.Key == null ? L.T("(свой файл)", "(custom file)") : Path.GetFileNameWithoutExtension(match.Key);
            }
            return new Dictionary<string, object>
            {
                ["files"] = files.Select(Path.GetFileNameWithoutExtension).ToList(),
                ["discord"] = Cur("ACTIVE_DISCORD_UDP.bin"),
                ["game"] = Cur("ACTIVE_GAME_UDP.bin"),
            };
        }

        public void ReplaceFake(string type, string name)
        {
            var src = Bin + name + ".bin";
            if (!File.Exists(src) || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) throw new Exception(L.T("Файл не найден: ", "File not found: ") + name);
            var dst = Bin + (type == "game" ? "ACTIVE_GAME_UDP.bin" : "ACTIVE_DISCORD_UDP.bin");
            File.Copy(src, dst, true);
            Log.Ok(Src, L.T($"Активный фейк {(type == "game" ? "GameFilter UDP" : "Discord UDP")} → {name}. Перезапустите стратегию.", $"Active fake {(type == "game" ? "GameFilter UDP" : "Discord UDP")} → {name}. Restart the strategy."));
        }

        // ───────────────────────── strategy parsing & launch ─────────────────────────

        /// <summary>Extracts the winws.exe argument string from a strategy .bat, keeping %BIN% / %LISTS% / %GameFilter*% as written.</summary>
        public string RawArgs(string bat)
        {
            var path = Path.Combine(Root, bat);
            if (!File.Exists(path) || bat.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) throw new Exception(L.T("Стратегия не найдена: ", "Strategy not found: ") + bat);
            var lines = File.ReadAllText(path).Replace("\r\n", "\n").Split('\n');
            int i = Array.FindIndex(lines, l => l.IndexOf("winws.exe", StringComparison.OrdinalIgnoreCase) >= 0);
            if (i < 0) throw new Exception(L.T("В файле нет вызова winws.exe: ", "The file does not call winws.exe: ") + bat);

            var first = lines[i];
            var p = first.IndexOf("winws.exe\"", StringComparison.OrdinalIgnoreCase);
            var cur = p >= 0 ? first.Substring(p + "winws.exe\"".Length) : first.Substring(first.IndexOf("winws.exe", StringComparison.OrdinalIgnoreCase) + "winws.exe".Length);
            var sb = new StringBuilder();
            while (true)
            {
                var t = cur.TrimEnd();
                if (t.EndsWith("^") && i + 1 < lines.Length) { sb.Append(t, 0, t.Length - 1).Append(' '); cur = lines[++i]; }
                else { sb.Append(t); break; }
            }

            // cmd removes carets outside of quotes (e.g. --dpi-desync-fake-tls=^!)
            var raw = sb.ToString();
            var outp = new StringBuilder(raw.Length);
            bool q = false;
            for (int k = 0; k < raw.Length; k++)
            {
                var c = raw[k];
                if (c == '"') q = !q;
                if (c == '^' && !q && k + 1 < raw.Length) { outp.Append(raw[++k]); continue; }
                outp.Append(c);
            }
            return outp.ToString().Trim();
        }

        /// <summary>Extracts the winws.exe argument string from a strategy .bat, expanding the variables cmd would expand.</summary>
        public string BuildArgs(string bat)
        {
            var (tcp, udp) = GameFilterPorts();
            var args = RawArgs(bat)
                .Replace("%BIN%", Bin).Replace("%LISTS%", Lists)
                .Replace("%~dp0", Root.TrimEnd('\\') + "\\")
                .Replace("%GameFilterTCP%", tcp).Replace("%GameFilterUDP%", udp).Replace("%GameFilter%", tcp == "12" ? udp : tcp)
                .Trim();
            var unknown = Regex.Matches(args, "%[A-Za-z_][A-Za-z0-9_]*%").Cast<Match>().Select(m => m.Value).Distinct().ToList();
            if (unknown.Count > 0) Log.Warn(Src, L.T($"{bat}: неизвестные переменные {string.Join(", ", unknown)} — стратегия может работать некорректно", $"{bat}: unknown variables {string.Join(", ", unknown)} — the strategy may misbehave"));
            return args;
        }

        // ───────────────────────── strategy editor ─────────────────────────

        public Dictionary<string, object> EditorInfo() => new Dictionary<string, object>
        {
            ["bins"] = Directory.Exists(Bin) ? Directory.GetFiles(Bin, "*.bin").Select(Path.GetFileName).OrderBy(n => n).ToList() : new List<string>(),
            ["lists"] = Directory.Exists(Lists) ? Directory.GetFiles(Lists, "*.txt").Select(Path.GetFileName).OrderBy(n => n).ToList() : new List<string>(),
        };

        static string EscapeForBat(string s)
        {
            var sb = new StringBuilder(s.Length + 8);
            bool q = false;
            foreach (var c in s)
            {
                if (c == '"') q = !q;
                if (!q && "^&|<>".IndexOf(c) >= 0) sb.Append('^');
                sb.Append(c);
            }
            return sb.ToString();
        }

        /// <summary>Writes "custom (name).bat" in the same layout as the bundled strategies, so service.bat and the tests treat it alike.</summary>
        public string SaveCustom(string name, string args, bool overwrite)
        {
            EnsureInstalled();
            name = (name ?? "").Trim();
            if (!Regex.IsMatch(name, @"^[\p{L}\p{N} _.+\-]{1,40}$"))
                throw new Exception(L.T("Название: буквы, цифры, пробел и символы _ . + -, до 40 знаков", "Name: letters, digits, spaces and _ . + -, up to 40 characters"));
            args = Regex.Replace(args ?? "", @"\s+", " ").Trim();
            if (args.Length == 0) throw new Exception(L.T("Пустой набор аргументов", "The argument set is empty"));
            if (args.Count(c => c == '"') % 2 != 0) throw new Exception(L.T("Непарная кавычка в аргументах", "Unbalanced quote in the arguments"));
            if (!Regex.IsMatch(args, @"(^|\s)--(filter-tcp|filter-udp|wf-tcp|wf-udp)")) throw new Exception(L.T("Нужен хотя бы один фильтр --filter-tcp или --filter-udp", "At least one --filter-tcp or --filter-udp is required"));
            var file = "custom (" + name + ").bat";
            var path = Path.Combine(Root, file);
            if (File.Exists(path) && !overwrite) throw new Exception(L.T("Стратегия с таким названием уже есть", "A strategy with this name already exists"));

            var blocks = Regex.Split(args, @"\s--new(?=\s|$)").Select(b => b.Trim()).Where(b => b.Length > 0).ToList();
            var sb = new StringBuilder();
            sb.Append("@echo off\r\nchcp 65001 > nul\r\n:: 65001 - UTF-8\r\n:: Created by Zapret Hub\r\n\r\n");
            sb.Append("cd /d \"%~dp0\"\r\ncall service.bat status_zapret\r\ncall service.bat check_updates\r\ncall service.bat load_game_filter\r\ncall service.bat load_user_lists\r\necho:\r\n\r\n");
            sb.Append("set \"BIN=%~dp0bin\\\"\r\nset \"LISTS=%~dp0lists\\\"\r\ncd /d %BIN%\r\n\r\n");
            sb.Append("start \"zapret: %~n0\" /min \"%BIN%winws.exe\" ");
            sb.Append(string.Join(" --new ^\r\n", blocks.Select(EscapeForBat)));
            sb.Append("\r\n");
            File.WriteAllText(path, sb.ToString(), new UTF8Encoding(false));
            RawArgs(file); // must parse back
            Log.Ok(Src, L.T("Сохранена стратегия ", "Saved strategy ") + file);
            return file;
        }

        public void DeleteCustom(string bat)
        {
            if (!IsCustom(bat) || bat.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) throw new Exception(L.T("Удалять можно только свои стратегии", "Only your own strategies can be deleted"));
            if (CurrentStrategy() == bat) throw new Exception(L.T("Стратегия сейчас запущена — сначала переключитесь на другую", "The strategy is running — switch to another one first"));
            File.Delete(Path.Combine(Root, bat));
            Log.Info(Src, L.T("Удалена стратегия ", "Deleted strategy ") + bat);
        }

        public void KillWinws()
        {
            foreach (var p in Process.GetProcessesByName("winws"))
            {
                try { p.Kill(); p.WaitForExit(4000); } catch { }
                p.Dispose();
            }
        }

        /// <summary>Starts winws with the strategy's arguments, detached from Zapret Hub so it survives a restart of the app.</summary>
        public void Launch(string bat, string logFile)
        {
            var args = BuildArgs(bat);
            LoadUserLists();
            var exe = Bin + "winws.exe";
            var cmd = $"/d /s /c \"\"{exe}\" {args} > \"{logFile}\" 2>&1\"";
            Shell.Detached("cmd.exe", cmd, Bin);
        }

        public static bool WaitWinws(int ms)
        {
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < ms)
            {
                if (WinwsRunning()) return true;
                Thread.Sleep(150);
            }
            return false;
        }

        public void StartStandalone(string bat)
        {
            lock (opLock)
            {
                EnsureInstalled();
                if (SvcState("zapret") != null)
                {
                    Log.Info(Src, L.T("Служба zapret установлена — удаляю её для переключения на ручной запуск", "The zapret service is installed — removing it to switch to manual mode"));
                    RemoveService("zapret");
                }
                KillWinws();
                TcpEnable();
                Log.Info(Src, L.T("Запуск стратегии ", "Starting strategy ") + bat);
                Launch(bat, WinwsLog);
                if (!WaitWinws(5000)) throw new Exception(L.T("winws.exe не запустился. Смотрите журнал winws и запустите диагностику.", "winws.exe did not start. Check the winws log and run diagnostics."));
                Thread.Sleep(700);
                if (!WinwsRunning()) throw new Exception(L.T("winws.exe завершился сразу после запуска:\n", "winws.exe exited right after starting:\n") + Misc.TailFile(WinwsLog, 2000));
                App.Settings.LastStrategy = bat;
                App.Settings.RunningStrategy = bat;
                App.Settings.Save();
                Log.Ok(Src, L.T("Стратегия " + bat + " запущена", "Strategy " + bat + " started"));
            }
        }

        public void Stop()
        {
            lock (opLock)
            {
                if (SvcState("zapret") == "Running")
                {
                    Shell.Run("sc.exe", "stop zapret");
                    WaitSvc("zapret", ServiceControllerStatus.Stopped, 8000);
                }
                KillWinws();
                App.Settings.RunningStrategy = "";
                App.Settings.Save();
                Log.Info(Src, L.T("Обход остановлен", "Bypass stopped"));
            }
        }

        static void WaitSvc(string name, ServiceControllerStatus st, int ms)
        {
            try { using (var sc = new ServiceController(name)) sc.WaitForStatus(st, TimeSpan.FromMilliseconds(ms)); } catch { }
        }

        static void RemoveService(string name)
        {
            if (SvcState(name) == null) return;
            Shell.Run("sc.exe", "stop " + Shell.QuoteArg(name));
            WaitSvc(name, ServiceControllerStatus.Stopped, 8000);
            Shell.Run("sc.exe", "delete " + Shell.QuoteArg(name));
        }

        public void InstallService(string bat)
        {
            lock (opLock)
            {
                EnsureInstalled();
                var args = BuildArgs(bat);
                LoadUserLists();
                TcpEnable();
                RemoveService("zapret");
                KillWinws();
                var binPath = "\"" + Bin + "winws.exe\" " + args;
                var r = Shell.Run("sc.exe", "create zapret binPath= " + Shell.QuoteArg(binPath) + " DisplayName= zapret start= auto");
                if (r.Code != 0) throw new Exception("sc create: " + r.All);
                Shell.Run("sc.exe", "description zapret \"Zapret DPI bypass software\"");
                using (var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(@"System\CurrentControlSet\Services\zapret", true))
                    k?.SetValue("zapret-discord-youtube", Path.GetFileNameWithoutExtension(bat), RegistryValueKind.String);
                r = Shell.Run("sc.exe", "start zapret");
                WaitSvc("zapret", ServiceControllerStatus.Running, 6000);
                App.Settings.LastStrategy = bat;
                App.Settings.RunningStrategy = "";
                App.Settings.Save();
                if (SvcState("zapret") != "Running")
                    throw new Exception(L.T("Служба создана, но не запустилась: ", "The service was created but did not start: ") + r.All);
                Log.Ok(Src, L.T("Служба zapret установлена со стратегией " + bat + " (автозапуск с Windows)", "zapret service installed with " + bat + " (starts with Windows)"));
            }
        }

        public void RemoveServices()
        {
            lock (opLock)
            {
                RemoveService("zapret");
                KillWinws();
                RemoveService("WinDivert");
                Shell.Run("sc.exe", "stop WinDivert14"); Shell.Run("sc.exe", "delete WinDivert14");
                App.Settings.RunningStrategy = "";
                App.Settings.Save();
                Log.Ok(Src, L.T("Службы zapret и WinDivert удалены", "zapret and WinDivert services removed"));
            }
        }

        void EnsureInstalled()
        {
            if (!Installed) throw new Exception(L.T("zapret не установлен. Установите его на вкладке «Обновления» или укажите папку в настройках.", "zapret is not installed. Install it on the Updates page or pick its folder in Settings."));
        }

        // ───────────────────────── lists & hosts updates ─────────────────────────

        public async Task UpdateIpsetList()
        {
            EnsureInstalled();
            var text = await Net.Text(RawMain + ".service/ipset-service.txt", 30);
            if (string.IsNullOrWhiteSpace(text)) throw new Exception(L.T("Получен пустой список", "Received an empty list"));
            text = text.Replace("\r\n", "\n").Replace("\n", "\r\n");
            var status = IpsetStatus();
            // keep the user's mode: when ipset is switched off, refresh the backup instead
            var target = status == "loaded" || status == "missing" ? Lists + "ipset-all.txt" : Lists + "ipset-all.txt.backup";
            File.WriteAllText(target, text);
            Log.Ok(Src, L.T($"IPSet-список обновлён ({text.Split('\n').Length} записей){(status == "loaded" ? "" : ", режим «" + status + "» сохранён")}",
                            $"IPSet list updated ({text.Split('\n').Length} entries){(status == "loaded" ? "" : ", mode \"" + status + "\" kept")}"));
        }

        static string HostsPath => Path.Combine(Paths.System32, @"drivers\etc\hosts");

        static List<string> HostLines(string text) => text.Replace("\r\n", "\n").Split('\n')
            .Select(l => Regex.Replace(l.Trim(), @"\s+", " ")).Where(l => l.Length > 0 && !l.StartsWith("#")).ToList();

        public async Task<Dictionary<string, object>> HostsCheck()
        {
            var repo = HostLines(await Net.Text(RawMain + ".service/hosts?t=" + DateTime.Now.Ticks, 20));
            var cur = new HashSet<string>(HostLines(File.Exists(HostsPath) ? File.ReadAllText(HostsPath) : ""));
            var missing = repo.Where(l => !cur.Contains(l)).ToList();
            var hostsText = File.Exists(HostsPath) ? File.ReadAllText(HostsPath) : "";
            return new Dictionary<string, object>
            {
                ["total"] = repo.Count,
                ["missing"] = missing,
                ["upToDate"] = missing.Count == 0,
                ["managed"] = hostsText.Contains(HostsBegin),
                ["youtube"] = Regex.IsMatch(hostsText, @"^[^#\r\n]*(youtube\.com|youtu\.be)", RegexOptions.Multiline | RegexOptions.IgnoreCase),
            };
        }

        public async Task HostsApply()
        {
            var repoText = (await Net.Text(RawMain + ".service/hosts?t=" + DateTime.Now.Ticks, 20)).Replace("\r\n", "\n").Trim();
            var text = StripManagedBlock(File.Exists(HostsPath) ? File.ReadAllText(HostsPath) : "");
            File.Copy(HostsPath, Paths.File("hosts.backup"), true);
            text = text.TrimEnd() + "\r\n\r\n" + HostsBegin + "\r\n" + repoText.Replace("\n", "\r\n") + "\r\n" + HostsEnd + "\r\n";
            WriteHosts(text);
            Shell.Run("ipconfig.exe", "/flushdns");
            Log.Ok(Src, L.T("hosts обновлён (резервная копия: %LOCALAPPDATA%\\ZapretHub\\hosts.backup)", "hosts updated (backup: %LOCALAPPDATA%\\ZapretHub\\hosts.backup)"));
        }

        public void HostsRemove()
        {
            var text = File.ReadAllText(HostsPath);
            if (!text.Contains(HostsBegin)) return;
            WriteHosts(StripManagedBlock(text).TrimEnd() + "\r\n");
            Shell.Run("ipconfig.exe", "/flushdns");
            Log.Ok(Src, L.T("Блок Zapret Hub удалён из hosts", "Zapret Hub block removed from hosts"));
        }

        static string StripManagedBlock(string text)
            => Regex.Replace(text, Regex.Escape(HostsBegin) + ".*?" + Regex.Escape(HostsEnd) + @"\r?\n?", "", RegexOptions.Singleline);

        static void WriteHosts(string text)
        {
            var attr = File.GetAttributes(HostsPath);
            if ((attr & FileAttributes.ReadOnly) != 0) File.SetAttributes(HostsPath, attr & ~FileAttributes.ReadOnly);
            File.WriteAllText(HostsPath, text, new UTF8Encoding(false));
        }

        // ───────────────────────── install / update ─────────────────────────

        public async Task<Dictionary<string, object>> LatestRelease()
        {
            string version = null, zip = null, notes = null, page = null;
            try
            {
                var rel = Json.Obj(await Net.Text($"https://api.github.com/repos/{Repo}/releases/latest"));
                version = Ver.Clean(rel.Str("tag_name"));
                notes = rel.Str("body");
                page = rel.Str("html_url");
                foreach (var a in (rel.ContainsKey("assets") ? rel["assets"] as System.Collections.IEnumerable : null) ?? new object[0])
                {
                    var ad = a as Dictionary<string, object>;
                    if (ad != null && ad.Str("name", "").EndsWith(".zip")) zip = ad.Str("browser_download_url");
                }
            }
            catch (Exception ex)
            {
                Log.Warn(Src, L.T("GitHub API недоступен (", "GitHub API unavailable (") + ex.Message + L.T("), использую version.txt", "), using version.txt"));
            }
            if (version == null)
            {
                version = (await Net.Text(RawMain + ".service/version.txt")).Trim();
                page = $"https://github.com/{Repo}/releases/tag/{version}";
            }
            if (zip == null) zip = $"https://github.com/{Repo}/releases/download/{version}/zapret-discord-youtube-{version}.zip";
            return new Dictionary<string, object> { ["version"] = version, ["zip"] = zip, ["notes"] = notes, ["page"] = page };
        }

        public async Task<Dictionary<string, object>> CheckUpdate()
        {
            var local = LocalVersion();
            var rel = await LatestRelease();
            var remote = (string)rel["version"];
            rel["local"] = local;
            rel["hasUpdate"] = local == null || Ver.Compare(remote, local) > 0;
            return rel;
        }

        public async Task<string> InstallOrUpdate(Action<string, double> progress)
        {
            var rel = await LatestRelease();
            var version = (string)rel["version"];
            var tmp = Path.Combine(Path.GetTempPath(), "ZapretHub-" + Guid.NewGuid().ToString("N").Substring(0, 8));
            Directory.CreateDirectory(tmp);
            try
            {
                var zipPath = Path.Combine(tmp, "zapret.zip");
                progress(L.T("Загрузка zapret ", "Downloading zapret ") + version, 0);
                await Net.Download((string)rel["zip"], zipPath, p => progress(L.T("Загрузка zapret ", "Downloading zapret ") + version, p * 0.8));
                progress(L.T("Распаковка", "Extracting"), 0.82);
                var ex = Path.Combine(tmp, "x");
                ZipFile.ExtractToDirectory(zipPath, ex);
                var srcRoot = Directory.GetFiles(ex, "winws.exe", SearchOption.AllDirectories).Select(f => Path.GetDirectoryName(Path.GetDirectoryName(f))).FirstOrDefault();
                if (srcRoot == null) throw new Exception(L.T("В архиве нет bin\\winws.exe", "The archive has no bin\\winws.exe"));

                lock (opLock)
                {
                    var wasInstalled = Installed;
                    // On a fresh install leave a zapret the user runs from another folder untouched;
                    // switching to the new copy is an explicit action on the strategies page.
                    var svcStrategy = wasInstalled && SvcState("zapret") != null ? ServiceStrategy() : null;
                    var standalone = wasInstalled && WinwsRunning() && SvcState("zapret") != "Running" ? App.Settings.RunningStrategy : null;
                    if (!wasInstalled && (SvcState("zapret") != null || WinwsRunning()))
                        Log.Info(Src, L.T("Обнаружен zapret из другой папки — он не изменён. Чтобы перейти на новую копию, запустите стратегию на вкладке «Стратегии».",
                                          "Found zapret running from another folder — it was left untouched. Start a strategy on the Strategies page to switch to the new copy."));

                    var keep = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
                    if (wasInstalled)
                    {
                        progress(L.T("Остановка обхода", "Stopping the bypass"), 0.86);
                        RemoveService("zapret");
                        KillWinws();
                        RemoveService("WinDivert");
                        foreach (var f in Directory.GetFiles(Lists, "*-user.txt")) keep[f] = File.ReadAllBytes(f);
                        foreach (var f in new[] { Lists + "ipset-all.txt", Lists + "ipset-all.txt.backup", Utils + "game_filter.enabled" })
                            if (File.Exists(f)) keep[f] = File.ReadAllBytes(f);
                        // bundled strategies are replaced by the new release; the user's own ones stay
                        foreach (var b in Directory.GetFiles(Root, "*.bat"))
                            if (!Path.GetFileName(b).StartsWith("service", StringComparison.OrdinalIgnoreCase) && !IsCustom(Path.GetFileName(b))) File.Delete(b);
                    }

                    progress(L.T("Копирование файлов", "Copying files"), 0.9);
                    Directory.CreateDirectory(Root);
                    Misc.CopyDir(srcRoot, Root);
                    foreach (var kv in keep) File.WriteAllBytes(kv.Key, kv.Value);
                    LoadUserLists();

                    progress(L.T("Перезапуск", "Restarting"), 0.96);
                    if (!string.IsNullOrEmpty(svcStrategy))
                    {
                        var bat = File.Exists(Path.Combine(Root, svcStrategy + ".bat")) ? svcStrategy + ".bat" : "general.bat";
                        InstallService(bat); // opLock is re-entrant
                    }
                    else if (!string.IsNullOrEmpty(standalone))
                    {
                        var bat = File.Exists(Path.Combine(Root, standalone)) ? standalone : "general.bat";
                        StartStandalone(bat);
                    }
                }
                progress(L.T("Готово", "Done"), 1);
                Log.Ok(Src, "zapret " + version + L.T(" установлен в ", " installed to ") + Root);
                return version;
            }
            finally
            {
                try { Directory.Delete(tmp, true); } catch { }
            }
        }

        // ───────────────────────── fresh strategies from the main branch ─────────────────────────

        Dictionary<string, string> repoUrls = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public async Task<List<Dictionary<string, object>>> RepoScan()
        {
            var arr = Json.Any(await Net.Text($"https://api.github.com/repos/{Repo}/contents/?ref=main")) as object[];
            if (arr == null) throw new Exception(L.T("Неожиданный ответ GitHub", "Unexpected GitHub response"));
            var res = new List<Dictionary<string, object>>();
            var remoteNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (Dictionary<string, object> it in arr)
            {
                var name = it.Str("name");
                if (it.Str("type") != "file" || !name.EndsWith(".bat", StringComparison.OrdinalIgnoreCase) || name.StartsWith("service", StringComparison.OrdinalIgnoreCase)) continue;
                remoteNames.Add(name);
                repoUrls[name] = it.Str("download_url");
                var local = Path.Combine(Root, name);
                string state = "new";
                if (File.Exists(local))
                {
                    var bytes = Encoding.UTF8.GetBytes(File.ReadAllText(local).Replace("\r\n", "\n"));
                    var bytesRaw = File.ReadAllBytes(local);
                    state = Misc.GitBlobSha(bytes) == it.Str("sha") || Misc.GitBlobSha(bytesRaw) == it.Str("sha") ? "same" : "changed";
                }
                res.Add(new Dictionary<string, object> { ["name"] = name, ["state"] = state });
            }
            foreach (var s in Strategies().Where(s => !remoteNames.Contains(s)))
                res.Add(new Dictionary<string, object> { ["name"] = s, ["state"] = "local" });
            return res.OrderBy(r => Misc.NaturalKey((string)r["name"])).ToList();
        }

        public async Task<int> RepoDownload(List<string> names)
        {
            EnsureInstalled();
            if (repoUrls.Count == 0) await RepoScan();
            int n = 0;
            var needed = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var name in names)
            {
                if (!repoUrls.TryGetValue(name, out var url)) continue;
                var text = (await Net.Text(url, 20)).Replace("\r\n", "\n").Replace("\n", "\r\n");
                File.WriteAllText(Path.Combine(Root, name), text);
                foreach (Match m in Regex.Matches(text, @"%BIN%([\w\.\-]+)")) if (!File.Exists(Bin + m.Groups[1].Value)) needed.Add("bin/" + m.Groups[1].Value);
                foreach (Match m in Regex.Matches(text, @"%LISTS%([\w\.\-]+)")) if (!File.Exists(Lists + m.Groups[1].Value)) needed.Add("lists/" + m.Groups[1].Value);
                n++;
                Log.Ok(Src, L.T("Загружена стратегия ", "Downloaded strategy ") + name);
            }
            foreach (var rel in needed)
            {
                try
                {
                    await Net.Download(RawMain + rel, Path.Combine(Root, rel.Replace('/', '\\')));
                    Log.Info(Src, L.T("Догружен файл ", "Downloaded file ") + rel);
                }
                catch (Exception ex) { Log.Warn(Src, L.T("Не удалось загрузить ", "Failed to download ") + rel + ": " + ex.Message); }
            }
            return n;
        }
    }
}
