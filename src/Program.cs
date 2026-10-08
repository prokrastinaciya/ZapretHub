using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace ZapretHub
{
    static class Program
    {
        public const string ShowEventName = "ZapretHub.ShowWindow";
        public const string QuitEventName = "ZapretHub.Quit";
        const string MutexName = "Global\\ZapretHub.SingleInstance";
        public static bool JustUpdated;

        [STAThread]
        static void Main(string[] args)
        {
            // WebView2 assemblies are embedded so the app ships as a single exe.
            AppDomain.CurrentDomain.AssemblyResolve += (s, e) =>
            {
                var name = new AssemblyName(e.Name).Name + ".dll";
                using (var st = typeof(Program).Assembly.GetManifestResourceStream("lib." + name))
                {
                    if (st == null) return null;
                    var buf = new byte[st.Length];
                    st.Read(buf, 0, buf.Length);
                    return Assembly.Load(buf);
                }
            };
            Run(args);
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        static void Run(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            if (args.Contains("--uninstall")) { Installer.UninstallCli(); return; }
            if (Installer.IsSetupLaunch()) { Installer.SetupCli(); return; }

            using (var mutex = new Mutex(false, MutexName))
            {
                // after a self-update or an install the previous process may still be shutting down
                JustUpdated = args.Contains("--updated");
                if (!Acquire(mutex, JustUpdated ? 20000 : 0))
                {
                    try { EventWaitHandle.OpenExisting(ShowEventName).Set(); } catch { }
                    return;
                }
                try
                {
                    Application.ThreadException += (s, e) => Log.Err("app", e.Exception.Message);
                    AppDomain.CurrentDomain.UnhandledException += (s, e) => Log.Err("app", (e.ExceptionObject as Exception)?.ToString());

                    App.Init();
                    Log.Info("app", "Zapret Hub " + App.Version + L.T(" запущен", " started"));
                    PrepareLoader();
                    Application.Run(new MainForm(args.Contains("--tray") || App.Settings.StartMinimized));
                }
                finally { try { mutex.ReleaseMutex(); } catch { } }
            }
        }

        static bool Acquire(Mutex m, int ms)
        {
            try { return m.WaitOne(ms); }
            catch (AbandonedMutexException) { return true; }
        }

        /// <summary>Asks a running Zapret Hub to quit and waits until it has released the single-instance mutex.</summary>
        public static bool StopRunningInstance(int ms)
        {
            using (var m = new Mutex(false, MutexName))
            {
                if (Acquire(m, 0)) { m.ReleaseMutex(); return true; }
                try { EventWaitHandle.OpenExisting(QuitEventName).Set(); } catch { }
                if (!Acquire(m, ms)) return false;
                m.ReleaseMutex();
                return true;
            }
        }

        /// <summary>Extracts the native WebView2Loader.dll matching the OS architecture.</summary>
        static void PrepareLoader()
        {
            var arch = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : Environment.Is64BitProcess ? "x64" : "x86";
            var dir = Path.Combine(Paths.Data, "runtime", arch);
            var dst = Path.Combine(dir, "WebView2Loader.dll");
            using (var st = typeof(Program).Assembly.GetManifestResourceStream("native." + arch + ".WebView2Loader.dll"))
            {
                if (st == null) return;
                var buf = new byte[st.Length];
                st.Read(buf, 0, buf.Length);
                Directory.CreateDirectory(dir);
                if (!File.Exists(dst) || !File.ReadAllBytes(dst).SequenceEqual(buf)) File.WriteAllBytes(dst, buf);
            }
            MainForm.LoaderDir = dir;
        }
    }
}
