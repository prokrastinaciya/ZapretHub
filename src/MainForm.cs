using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace ZapretHub
{
    public class MainForm : Form
    {
        public static string LoaderDir;
        const int Grip = 6;

        readonly WebView2 web = new WebView2();
        readonly NotifyIcon tray = new NotifyIcon();
        readonly List<string> pending = new List<string>();
        readonly bool startHidden;
        bool ready, quitting, trayHintShown;
        EventWaitHandle showEvent;

        public MainForm(bool hidden)
        {
            startHidden = hidden;
            App.Form = this;
            Text = "Zapret Hub";
            FormBorderStyle = FormBorderStyle.None;
            BackColor = Color.FromArgb(14, 12, 26);
            Padding = new Padding(1);
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            Size = new Size(1220, 800);
            MinimumSize = new Size(960, 640);
            using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("ui.app.ico")) Icon = new Icon(s);

            web.Dock = DockStyle.Fill;
            web.DefaultBackgroundColor = BackColor;
            Controls.Add(web);

            BuildTray();
            if (startHidden) { ShowInTaskbar = false; WindowState = FormWindowState.Minimized; }

            showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ShowEventName);
            new Thread(() => { while (showEvent.WaitOne()) BeginInvoke((Action)ShowFromTray); }) { IsBackground = true }.Start();
        }

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.Style |= 0x00020000;               // WS_MINIMIZEBOX: minimize from the taskbar
                cp.ClassStyle |= 0x00020000;          // CS_DROPSHADOW
                return cp;
            }
        }

        protected override async void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            try { int pref = 2; DwmSetWindowAttribute(Handle, 33, ref pref, 4); } catch { } // rounded corners on Win11
            try { int dark = 1; DwmSetWindowAttribute(Handle, 20, ref dark, 4); } catch { }
            if (startHidden) BeginInvoke((Action)(() => { Hide(); WindowState = FormWindowState.Normal; ShowInTaskbar = true; }));
            await InitWeb();
            _ = App.Startup();
        }

        async Task InitWeb()
        {
            try
            {
                if (LoaderDir != null) CoreWebView2Environment.SetLoaderDllFolderPath(LoaderDir);
                var opts = new CoreWebView2EnvironmentOptions("--disable-features=msSmartScreenProtection --force-dark-mode");
                var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Paths.Data, "WebView2"), opts);
                await web.EnsureCoreWebView2Async(env);
            }
            catch (WebView2RuntimeNotFoundException)
            {
                if (MessageBox.Show("Для интерфейса нужен Microsoft Edge WebView2 Runtime (входит в Windows 11). Открыть страницу загрузки?",
                    "Zapret Hub", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
                    Shell.Open("https://go.microsoft.com/fwlink/p/?LinkId=2124703");
                quitting = true; Close(); return;
            }
            var s = web.CoreWebView2.Settings;
            var dev = Environment.GetEnvironmentVariable("ZAPRETHUB_DEVTOOLS") == "1";
            s.AreDevToolsEnabled = dev;
            s.AreDefaultContextMenusEnabled = dev;
            s.IsStatusBarEnabled = false;
            s.IsZoomControlEnabled = false;
            s.AreBrowserAcceleratorKeysEnabled = dev;
            s.IsPasswordAutosaveEnabled = false;
            s.IsGeneralAutofillEnabled = false;
            web.CoreWebView2.WebMessageReceived += OnMessage;
            web.CoreWebView2.NewWindowRequested += (o, a) => { a.Handled = true; Shell.Open(a.Uri); };
            web.CoreWebView2.NavigationStarting += (o, a) =>
            {
                if (a.Uri.StartsWith("http", StringComparison.OrdinalIgnoreCase)) { a.Cancel = true; Shell.Open(a.Uri); }
            };
            web.CoreWebView2.NavigationCompleted += (o, a) =>
            {
                ready = true;
                lock (pending) { foreach (var p in pending) web.CoreWebView2.PostWebMessageAsJson(p); pending.Clear(); }
            };
            string html;
            using (var st = typeof(MainForm).Assembly.GetManifestResourceStream("ui.index.html"))
            using (var r = new StreamReader(st)) html = r.ReadToEnd();
            web.CoreWebView2.NavigateToString(html);
        }

        async void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            Dictionary<string, object> msg;
            try { msg = Json.Obj(e.WebMessageAsJson); } catch { return; }
            var id = msg.Str("id");
            var cmd = msg.Str("cmd");
            var args = msg.Dict("args") ?? new Dictionary<string, object>();
            object result = null; string error = null;
            try { result = await App.Call(cmd, args); }
            catch (Exception ex)
            {
                var inner = ex is AggregateException ae && ae.InnerException != null ? ae.InnerException : ex;
                error = inner.Message;
                if (!cmd.EndsWith("status") && !cmd.StartsWith("app.window")) Log.Err("app", error);
            }
            Post(new Dictionary<string, object> { ["id"] = id, ["ok"] = error == null, ["result"] = result, ["error"] = error });
        }

        public void Post(object o)
        {
            string json;
            try { json = Json.Ser(o); } catch (Exception ex) { json = Json.Ser(new { error = "serialize: " + ex.Message }); }
            if (IsDisposed) return;
            if (InvokeRequired) { try { BeginInvoke((Action)(() => PostOnUi(json))); } catch { } }
            else PostOnUi(json);
        }

        void PostOnUi(string json)
        {
            if (!ready || web.CoreWebView2 == null) { lock (pending) pending.Add(json); return; }
            try { web.CoreWebView2.PostWebMessageAsJson(json); } catch { }
        }

        // ───────────── window chrome ─────────────

        public void WindowCommand(string op)
        {
            switch (op)
            {
                case "min": WindowState = FormWindowState.Minimized; break;
                case "max":
                    MaximizedBounds = Screen.FromHandle(Handle).WorkingArea;
                    WindowState = WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized;
                    break;
                case "close": Close(); break;
                case "quit": quitting = true; Close(); break;
                case "drag":
                    ReleaseCapture();
                    SendMessage(Handle, 0xA1 /*WM_NCLBUTTONDOWN*/, (IntPtr)2 /*HTCAPTION*/, IntPtr.Zero);
                    break;
            }
        }

        protected override void WndProc(ref Message m)
        {
            const int WM_NCHITTEST = 0x84;
            if (m.Msg == WM_NCHITTEST && WindowState == FormWindowState.Normal)
            {
                var p = PointToClient(Cursor.Position);
                int g = (int)(Grip * DeviceDpi / 96.0);
                bool l = p.X < g, r = p.X >= ClientSize.Width - g, t = p.Y < g, b = p.Y >= ClientSize.Height - g;
                int hit = t && l ? 13 : t && r ? 14 : b && l ? 16 : b && r ? 17 : l ? 10 : r ? 11 : t ? 12 : b ? 15 : 0;
                if (hit != 0) { m.Result = (IntPtr)hit; return; }
            }
            base.WndProc(ref m);
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            int g = WindowState == FormWindowState.Maximized ? 0 : (int)(Grip * DeviceDpi / 96.0);
            Padding = new Padding(g);
            if (ready) Post(new Dictionary<string, object> { ["event"] = "window", ["data"] = WindowState.ToString() });
        }

        // ───────────── tray ─────────────

        void BuildTray()
        {
            tray.Icon = Icon;
            tray.Text = "Zapret Hub";
            tray.Visible = true;
            var menu = new ContextMenuStrip { Renderer = new DarkRenderer(), ShowImageMargin = false, Font = new Font("Segoe UI", 9.5f) };
            var open = new ToolStripMenuItem("Открыть Zapret Hub", null, (s, e) => ShowFromTray()) { Font = new Font("Segoe UI", 9.5f, FontStyle.Bold) };
            var zStart = new ToolStripMenuItem("▶  Запустить обход", null, (s, e) => Fire("zapret.start", new Dictionary<string, object> { ["bat"] = App.Settings.LastStrategy }));
            var zStop = new ToolStripMenuItem("■  Остановить обход", null, (s, e) => Fire("zapret.stop", null));
            var tStart = new ToolStripMenuItem("▶  Запустить TG-прокси", null, (s, e) => Fire("tg.start", null));
            var tStop = new ToolStripMenuItem("■  Остановить TG-прокси", null, (s, e) => Fire("tg.stop", null));
            var tLink = new ToolStripMenuItem("Подключить Telegram", null, (s, e) => Fire("tg.openTelegram", null));
            var quit = new ToolStripMenuItem("Выход", null, (s, e) => { quitting = true; Close(); });
            var zHead = new ToolStripMenuItem("zapret") { Enabled = false };
            var tHead = new ToolStripMenuItem("TG WS Proxy") { Enabled = false };
            menu.Items.AddRange(new ToolStripItem[] { open, new ToolStripSeparator(), zHead, zStart, zStop, new ToolStripSeparator(), tHead, tStart, tStop, tLink, new ToolStripSeparator(), quit });
            menu.Opening += (s, e) =>
            {
                var z = App.Zapret.Status();
                var on = (string)z["mode"] != "off";
                zHead.Text = "zapret — " + (on ? "работает (" + z["strategy"] + ")" : "выключен");
                zStart.Enabled = !on && !string.IsNullOrEmpty(App.Settings.LastStrategy) && App.Zapret.Installed;
                zStart.Text = "▶  Запустить " + (string.IsNullOrEmpty(App.Settings.LastStrategy) ? "обход" : App.Settings.LastStrategy);
                zStop.Enabled = on;
                var tRun = App.Tg.Installed && App.Tg.OwnProcesses().Count > 0;
                tHead.Text = "TG WS Proxy — " + (!App.Tg.Installed ? "не установлен" : tRun ? "работает" : "выключен");
                tStart.Enabled = App.Tg.Installed && !tRun;
                tStop.Enabled = tRun;
                tLink.Enabled = tRun;
            };
            tray.ContextMenuStrip = menu;
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowFromTray(); };
        }

        async void Fire(string cmd, Dictionary<string, object> args)
        {
            try { await App.Call(cmd, args ?? new Dictionary<string, object>()); Post(new Dictionary<string, object> { ["event"] = "refresh" }); }
            catch (Exception ex) { Balloon("Zapret Hub", ex.Message); Log.Err("app", ex.Message); }
        }

        public void Balloon(string title, string text)
        {
            if (InvokeRequired) { BeginInvoke((Action)(() => Balloon(title, text))); return; }
            tray.ShowBalloonTip(4000, title, text, ToolTipIcon.None);
        }

        void ShowFromTray()
        {
            Show();
            ShowInTaskbar = true;
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
            Post(new Dictionary<string, object> { ["event"] = "refresh" });
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!quitting && App.Settings.MinimizeToTray && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                if (!trayHintShown) { trayHintShown = true; Balloon("Zapret Hub работает в трее", "Обход и прокси продолжают работать. Выход — через меню значка."); }
                return;
            }
            tray.Visible = false;
            if (App.Tester.Running) App.Tester.Cancel();
            base.OnFormClosing(e);
        }

        public void Copy(string text)
        {
            if (InvokeRequired) { Invoke((Action)(() => Copy(text))); return; }
            if (text.Length > 0) Clipboard.SetText(text);
        }

        public string PickFolder(string title)
        {
            if (InvokeRequired) return (string)Invoke((Func<string>)(() => PickFolder(title)));
            using (var d = new FolderBrowserDialog { Description = title, ShowNewFolderButton = true })
                return d.ShowDialog(this) == DialogResult.OK ? d.SelectedPath : null;
        }

        [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int val, int size);
        [DllImport("user32.dll")] static extern bool ReleaseCapture();
        [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr h, int msg, IntPtr w, IntPtr l);

        class DarkRenderer : ToolStripProfessionalRenderer
        {
            static readonly Color Bg = Color.FromArgb(24, 22, 38), Hi = Color.FromArgb(52, 44, 92), Fg = Color.FromArgb(232, 230, 245), Dim = Color.FromArgb(130, 126, 160);
            public DarkRenderer() : base(new DarkColors()) { RoundedEdges = false; }
            protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e) { e.TextColor = e.Item.Enabled ? Fg : Dim; base.OnRenderItemText(e); }
            protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
            {
                using (var b = new SolidBrush(e.Item.Selected && e.Item.Enabled ? Hi : Bg)) e.Graphics.FillRectangle(b, new Rectangle(Point.Empty, e.Item.Size));
            }
            protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
            {
                using (var p = new Pen(Color.FromArgb(50, 46, 72))) e.Graphics.DrawLine(p, 8, e.Item.Height / 2, e.Item.Width - 8, e.Item.Height / 2);
            }
            class DarkColors : ProfessionalColorTable
            {
                public override Color ToolStripDropDownBackground => Bg;
                public override Color MenuBorder => Color.FromArgb(60, 54, 96);
                public override Color MenuItemBorder => Hi;
                public override Color ImageMarginGradientBegin => Bg;
                public override Color ImageMarginGradientMiddle => Bg;
                public override Color ImageMarginGradientEnd => Bg;
            }
        }
    }
}
