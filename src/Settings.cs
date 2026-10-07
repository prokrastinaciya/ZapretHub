using System;
using System.Collections.Generic;
using System.IO;

namespace ZapretHub
{
    public class Settings
    {
        public string ZapretDir { get; set; } = @"C:\ZapretHub\zapret";
        public string TgDir { get; set; } = @"C:\ZapretHub\tg-ws-proxy";

        public bool AutoCheckUpdates { get; set; } = true;
        public bool AutoInstallUpdates { get; set; } = false;
        public bool MinimizeToTray { get; set; } = true;
        public bool StartMinimized { get; set; } = false;
        public bool Notifications { get; set; } = true;

        /// <summary>Restart the last standalone strategy when Zapret Hub starts.</summary>
        public bool ZapretAutoStart { get; set; } = false;
        public bool TgAutoStart { get; set; } = false;

        public string LastStrategy { get; set; } = "";
        /// <summary>Strategy currently started by Zapret Hub in standalone mode.</summary>
        public string RunningStrategy { get; set; } = "";
        public string TgVersion { get; set; } = "";

        static string FilePath => Paths.File("settings.json");

        public static Settings Load()
        {
            try
            {
                if (File.Exists(FilePath)) return Json.To<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
            }
            catch (Exception ex) { Log.Warn("app", "Настройки повреждены, используются значения по умолчанию: " + ex.Message); }
            return new Settings();
        }

        public void Save()
        {
            try { File.WriteAllText(FilePath, Json.Ser(this)); }
            catch (Exception ex) { Log.Err("app", "Не удалось сохранить настройки: " + ex.Message); }
        }

        public void Apply(IDictionary<string, object> d)
        {
            if (d.ContainsKey("AutoCheckUpdates")) AutoCheckUpdates = d.Bool("AutoCheckUpdates");
            if (d.ContainsKey("AutoInstallUpdates")) AutoInstallUpdates = d.Bool("AutoInstallUpdates");
            if (d.ContainsKey("MinimizeToTray")) MinimizeToTray = d.Bool("MinimizeToTray");
            if (d.ContainsKey("StartMinimized")) StartMinimized = d.Bool("StartMinimized");
            if (d.ContainsKey("Notifications")) Notifications = d.Bool("Notifications");
            if (d.ContainsKey("ZapretAutoStart")) ZapretAutoStart = d.Bool("ZapretAutoStart");
            if (d.ContainsKey("TgAutoStart")) TgAutoStart = d.Bool("TgAutoStart");
            Save();
        }
    }
}
