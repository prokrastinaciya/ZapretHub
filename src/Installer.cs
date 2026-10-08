using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace ZapretHub
{
    /// <summary>
    /// Built-in installer: the same exe runs portable, installs itself into Program Files
    /// (Start menu shortcut + entry in "Installed apps") and uninstalls itself with --uninstall.
    /// A copy named *Setup*.exe starts the install right away.
    /// </summary>
    public static class Installer
    {
        const string UninstKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\ZapretHub";
        const string Title = "Zapret Hub";

        public static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Zapret Hub");
        public static string InstalledExe => Path.Combine(Dir, "ZapretHub.exe");
        static string StartMenuLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms), "Zapret Hub.lnk");
        static string DesktopLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), "Zapret Hub.lnk");

        static bool SamePath(string a, string b)
        {
            try { return string.Equals(Path.GetFullPath(a), Path.GetFullPath(b), StringComparison.OrdinalIgnoreCase); } catch { return false; }
        }

        public static bool IsInstalled => SamePath(Paths.Exe, InstalledExe);
        public static bool InstalledElsewhere => !IsInstalled && File.Exists(InstalledExe);

        public static Dictionary<string, object> State() => new Dictionary<string, object>
        {
            ["installed"] = IsInstalled,
            ["installedElsewhere"] = InstalledElsewhere,
            ["dir"] = Dir,
            ["exe"] = Paths.Exe,
        };

        public static bool IsSetupLaunch()
            => Path.GetFileNameWithoutExtension(Paths.Exe).IndexOf("setup", StringComparison.OrdinalIgnoreCase) >= 0 && !IsInstalled;

        /// <summary>Runs on every start: removes the exe left over by a self-update and keeps the uninstall entry current.</summary>
        public static void OnStartup()
        {
            var old = Paths.Exe + ".old";
            if (File.Exists(old))
                Task.Run(() =>
                {
                    for (int i = 0; i < 20 && File.Exists(old); i++) { try { File.Delete(old); } catch { Thread.Sleep(500); } }
                });
            if (IsInstalled)
                try
                {
                    using (var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(UninstKey, true))
                        if (k != null && (k.GetValue("DisplayVersion") as string) != App.Version) WriteUninstallEntry(k);
                }
                catch { }
        }

        // ───────────── install ─────────────

        /// <summary>Copies the running exe into Program Files and registers it. Returns the installed exe path.</summary>
        public static string Install()
        {
            Directory.CreateDirectory(Dir);
            if (!IsInstalled) Misc.CopyWithRetry(Paths.Exe, InstalledExe);
            CreateLink(StartMenuLink, InstalledExe, Dir);
            CreateLink(DesktopLink, InstalledExe, Dir);
            using (var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).CreateSubKey(UninstKey, true))
                WriteUninstallEntry(k);
            try { if (Autostart.IsEnabled()) Autostart.Set(true, InstalledExe); } catch (Exception ex) { Log.Warn("app", ex.Message); }
            Log.Ok("app", L.T("Zapret Hub установлен в ", "Zapret Hub installed to ") + Dir);
            return InstalledExe;
        }

        static void WriteUninstallEntry(RegistryKey k)
        {
            k.SetValue("DisplayName", "Zapret Hub");
            k.SetValue("DisplayVersion", App.Version);
            k.SetValue("Publisher", "prokrastinaciya");
            k.SetValue("DisplayIcon", InstalledExe + ",0");
            k.SetValue("InstallLocation", Dir);
            k.SetValue("UninstallString", "\"" + InstalledExe + "\" --uninstall");
            k.SetValue("URLInfoAbout", "https://github.com/" + SelfUpdate.Repo);
            k.SetValue("NoModify", 1, RegistryValueKind.DWord);
            k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            try { k.SetValue("EstimatedSize", (int)(new FileInfo(InstalledExe).Length / 1024), RegistryValueKind.DWord); } catch { }
        }

        /// <summary>Install requested from the running app: installs, starts the installed copy and closes this one.</summary>
        public static void InstallFromApp()
        {
            if (IsInstalled) throw new Exception(L.T("Zapret Hub уже установлен", "Zapret Hub is already installed"));
            var exe = Install();
            Process.Start(new ProcessStartInfo(exe, "--updated") { UseShellExecute = true, WorkingDirectory = Dir })?.Dispose();
            App.Form?.Quit();
        }

        public static void SetupCli()
        {
            App.Settings = Settings.Load();
            if (MessageBox.Show(L.T(
                    $"Установить Zapret Hub {App.Version}?\n\nПапка: {Dir}\nЯрлыки появятся в меню «Пуск» и на рабочем столе, удалить приложение можно в «Параметры → Приложения».",
                    $"Install Zapret Hub {App.Version}?\n\nFolder: {Dir}\nShortcuts will be added to the Start menu and the desktop; uninstall it from Settings → Apps."),
                    Title, MessageBoxButtons.OKCancel, MessageBoxIcon.Information) != DialogResult.OK) return;
            try
            {
                if (!Program.StopRunningInstance(15000))
                    throw new Exception(L.T("Не удалось закрыть запущенный Zapret Hub. Закройте его через меню в трее и повторите.",
                                            "Could not close the running Zapret Hub. Quit it from the tray menu and try again."));
                var exe = Install();
                Process.Start(new ProcessStartInfo(exe, "--updated") { UseShellExecute = true, WorkingDirectory = Dir })?.Dispose();
            }
            catch (Exception ex)
            {
                MessageBox.Show(L.T("Установка не удалась: ", "Installation failed: ") + ex.Message, Title, MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        // ───────────── uninstall ─────────────

        public static void UninstallFromApp()
        {
            if (!File.Exists(InstalledExe)) throw new Exception(L.T("Zapret Hub не установлен в систему", "Zapret Hub is not installed"));
            Process.Start(new ProcessStartInfo(InstalledExe, "--uninstall") { UseShellExecute = true, WorkingDirectory = Paths.System32 })?.Dispose();
        }

        public static void UninstallCli()
        {
            App.Init();
            if (MessageBox.Show(L.T("Удалить Zapret Hub из системы?", "Uninstall Zapret Hub?"), Title, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            if (!Program.StopRunningInstance(15000))
            {
                MessageBox.Show(L.T("Не удалось закрыть запущенный Zapret Hub. Закройте его через меню в трее и повторите.",
                                    "Could not close the running Zapret Hub. Quit it from the tray menu and try again."), Title, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            var full = MessageBox.Show(L.T(
                    "Также удалить службы zapret и WinDivert, остановить TG WS Proxy и удалить настройки и журналы Zapret Hub?\n\nПапки zapret и tg-ws-proxy будут удалены, только если они лежат в C:\\ZapretHub.",
                    "Also remove the zapret and WinDivert services, stop TG WS Proxy and delete Zapret Hub settings and logs?\n\nThe zapret and tg-ws-proxy folders are deleted only if they are inside C:\\ZapretHub."),
                    Title, MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes;
            var cleanup = new List<string>();
            try
            {
                try { Autostart.Set(false); } catch { }
                try { Lan.CloseFirewall(); } catch { }
                foreach (var l in new[] { StartMenuLink, DesktopLink }) try { File.Delete(l); } catch { }
                try { RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).DeleteSubKeyTree(UninstKey, false); } catch { }
                if (full)
                {
                    try { App.Zapret.RemoveServices(); } catch { }
                    try { App.Tg.Stop(true); } catch { }
                    foreach (var d in new[] { App.Settings.ZapretDir, App.Settings.TgDir })
                        if (!string.IsNullOrEmpty(d) && Path.GetFullPath(d).StartsWith(@"C:\ZapretHub\", StringComparison.OrdinalIgnoreCase)) cleanup.Add(d);
                    cleanup.Add(Paths.Data);
                }
                if (IsInstalled) cleanup.Add(Dir);
                if (full && Directory.Exists(@"C:\ZapretHub")) cleanup.Add(@"C:\ZapretHub"); // removed only if empty (no /s)
            }
            catch (Exception ex)
            {
                MessageBox.Show(L.T("Ошибка удаления: ", "Uninstall error: ") + ex.Message, Title, MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            MessageBox.Show(L.T("Zapret Hub удалён.", "Zapret Hub has been uninstalled."), Title, MessageBoxButtons.OK, MessageBoxIcon.Information);
            if (cleanup.Count > 0)
            {
                // the exe and the WebView2 profile are still in use: delete them once this process has exited
                var sb = new StringBuilder("/d /c ping 127.0.0.1 -n 4 >nul");
                foreach (var d in cleanup)
                    sb.Append(d == @"C:\ZapretHub" ? $" & rmdir \"{d}\"" : $" & rmdir /s /q \"{d}\"");
                Shell.Detached("cmd.exe", sb.ToString(), Paths.System32);
            }
        }

        // ───────────── shortcuts ─────────────

        static void CreateLink(string lnk, string target, string workDir)
        {
            var link = (IShellLinkW)new ShellLink();
            link.SetPath(target);
            link.SetWorkingDirectory(workDir);
            link.SetIconLocation(target, 0);
            link.SetDescription(L.T("Менеджер zapret-discord-youtube и tg-ws-proxy", "Manager for zapret-discord-youtube and tg-ws-proxy"));
            Directory.CreateDirectory(Path.GetDirectoryName(lnk));
            ((IPersistFile)link).Save(lnk, false);
            Marshal.ReleaseComObject(link);
        }

        [ComImport, Guid("00021401-0000-0000-C000-000000000046")] class ShellLink { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
        interface IShellLinkW
        {
            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cch, IntPtr pfd, uint fFlags);
            void GetIDList(out IntPtr ppidl);
            void SetIDList(IntPtr pidl);
            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cch);
            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cch);
            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cch);
            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
            void GetHotkey(out short pwHotkey);
            void SetHotkey(short wHotkey);
            void GetShowCmd(out int piShowCmd);
            void SetShowCmd(int iShowCmd);
            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cch, out int piIcon);
            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved);
            void Resolve(IntPtr hwnd, uint fFlags);
            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
        }
    }
}
