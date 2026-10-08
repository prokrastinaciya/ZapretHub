using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;

namespace ZapretHub
{
    public class NetProfile
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public string Strategy { get; set; } = "";
    }

    public class Settings
    {
        public string ZapretDir { get; set; } = @"C:\ZapretHub\zapret";
        public string TgDir { get; set; } = @"C:\ZapretHub\tg-ws-proxy";

        /// <summary>"ru", "en" or empty for the Windows UI language.</summary>
        public string Language { get; set; } = "";

        public bool AutoCheckUpdates { get; set; } = true;
        public bool AutoInstallUpdates { get; set; } = false;
        public bool MinimizeToTray { get; set; } = true;
        public bool StartMinimized { get; set; } = false;
        public bool Notifications { get; set; } = true;
        public string Hotkey { get; set; } = "Ctrl+Alt+Z";

        /// <summary>Restart the last standalone strategy when Zapret Hub starts.</summary>
        public bool ZapretAutoStart { get; set; } = false;
        public bool TgAutoStart { get; set; } = false;

        /// <summary>Background connectivity check (feeds the history and self-healing).</summary>
        public bool MonitorEnabled { get; set; } = true;
        public int MonitorInterval { get; set; } = 10;
        public bool CheckVoice { get; set; } = true;
        public bool SelfHeal { get; set; } = false;
        public int SelfHealCandidates { get; set; } = 5;

        public bool NetProfilesEnabled { get; set; } = false;
        public List<NetProfile> NetProfiles { get; set; } = new List<NetProfile>();

        /// <summary>LAN address picked for sharing the Telegram proxy.</summary>
        public string LanIp { get; set; } = "";
        public bool InstallPromptDismissed { get; set; } = false;

        public string LastStrategy { get; set; } = "";
        /// <summary>Strategy currently started by Zapret Hub in standalone mode.</summary>
        public string RunningStrategy { get; set; } = "";
        public string TgVersion { get; set; } = "";

        static string FilePath => Paths.File("settings.json");

        public string EffectiveLang()
        {
            if (Language == "ru" || Language == "en") return Language;
            var ui = CultureInfo.InstalledUICulture.TwoLetterISOLanguageName;
            return new[] { "ru", "uk", "be", "kk" }.Contains(ui) ? "ru" : "en";
        }

        public static Settings Load()
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    var s = Json.To<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
                    if (s.NetProfiles == null) s.NetProfiles = new List<NetProfile>();
                    return s;
                }
            }
            catch (Exception ex) { Log.Warn("app", L.T("Настройки повреждены, используются значения по умолчанию: ", "Settings are corrupted, using defaults: ") + ex.Message); }
            return new Settings();
        }

        public void Save()
        {
            try { File.WriteAllText(FilePath, Json.Ser(this)); }
            catch (Exception ex) { Log.Err("app", L.T("Не удалось сохранить настройки: ", "Failed to save settings: ") + ex.Message); }
        }

        static readonly string[] Bools = { "AutoCheckUpdates", "AutoInstallUpdates", "MinimizeToTray", "StartMinimized", "Notifications", "ZapretAutoStart",
            "TgAutoStart", "MonitorEnabled", "CheckVoice", "SelfHeal", "NetProfilesEnabled", "InstallPromptDismissed" };

        public void Apply(IDictionary<string, object> d)
        {
            foreach (var k in Bools)
                if (d.ContainsKey(k)) GetType().GetProperty(k).SetValue(this, d.Bool(k));
            if (d.ContainsKey("MonitorInterval")) MonitorInterval = Math.Min(240, Math.Max(2, d.Int("MonitorInterval", 10)));
            if (d.ContainsKey("SelfHealCandidates")) SelfHealCandidates = Math.Min(30, Math.Max(2, d.Int("SelfHealCandidates", 5)));
            if (d.ContainsKey("Language")) { var l = d.Str("Language", ""); Language = l == "ru" || l == "en" ? l : ""; }
            Save();
        }
    }
}
