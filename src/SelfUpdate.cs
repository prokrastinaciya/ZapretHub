using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace ZapretHub
{
    /// <summary>Updates Zapret Hub itself from the GitHub releases of its public repository.</summary>
    public static class SelfUpdate
    {
        public const string Repo = "prokrastinaciya/ZapretHub";
        const string Asset = "ZapretHub.exe";
        const string Src = "app";

        public static async Task<Dictionary<string, object>> LatestRelease()
        {
            string tag = null, notes = null, url = null;
            try
            {
                var rel = Json.Obj(await Net.Text($"https://api.github.com/repos/{Repo}/releases/latest"));
                tag = rel.Str("tag_name"); notes = rel.Str("body");
                foreach (var a in (rel.ContainsKey("assets") ? rel["assets"] as System.Collections.IEnumerable : null) ?? new object[0])
                    if (a is Dictionary<string, object> ad && string.Equals(ad.Str("name"), Asset, StringComparison.OrdinalIgnoreCase)) url = ad.Str("browser_download_url");
            }
            catch
            {
                // the API is rate limited: resolve releases/latest through the redirect instead
                var final = await Net.FinalUrl($"https://github.com/{Repo}/releases/latest");
                var i = final.LastIndexOf("/tag/", StringComparison.Ordinal);
                if (i >= 0) tag = Uri.UnescapeDataString(final.Substring(i + 5));
            }
            if (string.IsNullOrEmpty(tag)) throw new Exception(L.T("Не удалось определить последнюю версию Zapret Hub", "Could not determine the latest Zapret Hub version"));
            return new Dictionary<string, object>
            {
                ["version"] = Ver.Clean(tag),
                ["url"] = url ?? $"https://github.com/{Repo}/releases/download/{tag}/{Asset}",
                ["page"] = $"https://github.com/{Repo}/releases/tag/{tag}",
                ["notes"] = notes,
            };
        }

        public static async Task<Dictionary<string, object>> CheckUpdate()
        {
            var rel = await LatestRelease();
            rel["local"] = App.Version;
            rel["hasUpdate"] = Ver.Compare((string)rel["version"], App.Version) > 0;
            return rel;
        }

        /// <summary>Downloads the new exe, swaps it with the running one and restarts into it.</summary>
        public static async Task<string> Install(Action<string, double> progress)
        {
            var rel = await LatestRelease();
            var ver = (string)rel["version"];
            if (Ver.Compare(ver, App.Version) <= 0) throw new Exception(L.T("Установлена последняя версия", "You already have the latest version"));
            var tmp = Path.Combine(Paths.Data, "update", Asset);
            await Net.Download((string)rel["url"], tmp, p => progress(L.T("Загрузка Zapret Hub ", "Downloading Zapret Hub ") + ver, p * 0.9));

            progress(L.T("Проверка файла", "Verifying the file"), 0.92);
            var head = new byte[2];
            using (var f = File.OpenRead(tmp)) f.Read(head, 0, 2);
            var fv = FileVersionInfo.GetVersionInfo(tmp);
            if (head[0] != 'M' || head[1] != 'Z' || fv.ProductName != "Zapret Hub" || Ver.Compare(fv.FileVersion, ver) < 0)
                throw new Exception(L.T("Загруженный файл не похож на Zapret Hub " + ver, "The downloaded file does not look like Zapret Hub " + ver));

            progress(L.T("Замена файла", "Replacing the file"), 0.96);
            var exe = Paths.Exe; var old = exe + ".old";
            if (File.Exists(old)) File.Delete(old);
            // a running exe cannot be overwritten, but it can be renamed
            File.Move(exe, old);
            try { File.Copy(tmp, exe); }
            catch { File.Move(old, exe); throw; }
            try { File.Delete(tmp); } catch { }

            Log.Ok(Src, L.T("Zapret Hub обновлён до ", "Zapret Hub updated to ") + ver + L.T(" — перезапуск", " — restarting"));
            progress(L.T("Перезапуск", "Restarting"), 1);
            var hidden = App.Form != null && !App.Form.Visible;
            Process.Start(new ProcessStartInfo(exe, "--updated" + (hidden ? " --tray" : "")) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe) })?.Dispose();
            _ = Task.Delay(600).ContinueWith(_ => App.Form?.Quit());
            return ver;
        }
    }
}
