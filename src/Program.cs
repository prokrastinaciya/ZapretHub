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
            using (var mutex = new Mutex(true, "Global\\ZapretHub.SingleInstance", out var created))
            {
                if (!created)
                {
                    try { EventWaitHandle.OpenExisting(ShowEventName).Set(); } catch { }
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.ThreadException += (s, e) => Log.Err("app", e.Exception.Message);
                AppDomain.CurrentDomain.UnhandledException += (s, e) => Log.Err("app", (e.ExceptionObject as Exception)?.ToString());

                App.Init();
                Log.Info("app", "Zapret Hub " + App.Version + " запущен");
                PrepareLoader();
                Application.Run(new MainForm(args.Contains("--tray") || App.Settings.StartMinimized));
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
