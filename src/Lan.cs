using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading.Tasks;

namespace ZapretHub
{
    /// <summary>Shares the Telegram proxy with phones and other devices on the local network.</summary>
    public static class Lan
    {
        const string Src = "tg";
        const string RuleName = "Zapret Hub - TG WS Proxy";

        /// <summary>Private IPv4 addresses of the active adapters; the one carrying the default route comes first.</summary>
        public static List<Dictionary<string, object>> Candidates()
        {
            var routed = RoutedIp();
            var res = new List<Dictionary<string, object>>();
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback || ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                IPInterfaceProperties p;
                try { p = ni.GetIPProperties(); } catch { continue; }
                var gw = p.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                foreach (var a in p.UnicastAddresses)
                {
                    if (!Misc.IsPrivate(a.Address)) continue;
                    var ip = a.Address.ToString();
                    res.Add(new Dictionary<string, object>
                    {
                        ["ip"] = ip,
                        ["adapter"] = ni.Name,
                        ["wifi"] = ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211,
                        ["gateway"] = gw,
                        ["main"] = ip == routed,
                    });
                }
            }
            return res.OrderByDescending(d => (bool)d["main"]).ThenByDescending(d => (bool)d["gateway"]).ThenByDescending(d => (bool)d["wifi"]).ToList();
        }

        /// <summary>The source address Windows would use for internet traffic.</summary>
        public static string RoutedIp()
        {
            try
            {
                using (var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
                {
                    s.Connect("8.8.8.8", 80);
                    return ((IPEndPoint)s.LocalEndPoint).Address.ToString();
                }
            }
            catch { return null; }
        }

        public static string PickIp()
        {
            var c = Candidates();
            var saved = App.Settings.LanIp;
            if (!string.IsNullOrEmpty(saved) && c.Any(x => (string)x["ip"] == saved)) return saved;
            return c.Count > 0 ? (string)c[0]["ip"] : null;
        }

        static int Port() => App.Tg.Config().Int("port", 1443);

        public static bool Shared => App.Tg.Installed && App.Tg.Config().Str("host") == "0.0.0.0";

        public static Dictionary<string, object> Status()
        {
            var tg = App.Tg;
            var d = new Dictionary<string, object> { ["installed"] = tg.Installed, ["shared"] = false };
            if (!tg.Installed) return d;
            var cfg = tg.Config();
            var port = cfg.Int("port", 1443);
            var ip = PickIp();
            d["shared"] = cfg.Str("host") == "0.0.0.0";
            d["running"] = tg.OwnProcesses().Count > 0;
            d["port"] = port;
            d["ip"] = ip;
            d["candidates"] = Candidates();
            var fw = FirewallState();
            d["firewall"] = fw.exists;
            d["blockRules"] = fw.blocks;
            d["link"] = ip == null ? null : $"https://t.me/proxy?server={ip}&port={port}&secret=dd{cfg.Str("secret")}";
            d["clients"] = Clients(port);
            return d;
        }

        /// <summary>Makes the proxy listen on all interfaces and opens its port for the local subnet.</summary>
        public static Dictionary<string, object> Enable(string ip)
        {
            var tg = App.Tg;
            if (!tg.Installed) throw new Exception(L.T("TG WS Proxy не установлен", "TG WS Proxy is not installed"));
            if (!string.IsNullOrEmpty(ip)) { App.Settings.LanIp = ip; App.Settings.Save(); }
            var cfg = tg.Config();
            if (cfg.Str("host") != "0.0.0.0") { cfg["host"] = "0.0.0.0"; tg.SaveConfig(cfg); }
            OpenFirewall(cfg.Int("port", 1443));
            if (tg.OwnProcesses().Count == 0) tg.Start();
            Log.Ok(Src, L.T("Прокси раздаётся в локальной сети: ", "Proxy is shared on the local network: ") + PickIp() + ":" + cfg.Int("port", 1443));
            return Status();
        }

        public static Dictionary<string, object> Disable()
        {
            var tg = App.Tg;
            var cfg = tg.Config();
            if (cfg.Str("host") == "0.0.0.0") { cfg["host"] = "127.0.0.1"; tg.SaveConfig(cfg); }
            CloseFirewall();
            Log.Info(Src, L.T("Раздача прокси в локальной сети выключена", "Local network sharing of the proxy is off"));
            return Status();
        }

        // ───────────── Windows Firewall (HNetCfg COM) ─────────────

        static dynamic Policy() => Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FwPolicy2"));

        static IEnumerable<dynamic> Rules()
        {
            var list = new List<dynamic>();
            try { foreach (var r in (IEnumerable)Policy().Rules) list.Add(r); } catch { }
            return list;
        }

        static (bool exists, int blocks) fwCache; static DateTime fwAt;

        /// <summary>Enumerating all firewall rules takes a while, so the UI poll reads a cached value.</summary>
        static (bool exists, int blocks) FirewallState()
        {
            if ((DateTime.Now - fwAt).TotalSeconds > 15)
            {
                var rules = Rules().ToList();
                fwCache = (rules.Any(r => { try { return (string)r.Name == RuleName; } catch { return false; } }), BlockRules(rules).Count);
                fwAt = DateTime.Now;
            }
            return fwCache;
        }

        public static bool FirewallRuleExists()
        {
            try { return Rules().Any(r => (string)r.Name == RuleName); } catch { return false; }
        }

        /// <summary>Inbound block rules for TgWsProxy.exe (created when the Windows Firewall prompt was dismissed); they override allow rules.</summary>
        static List<dynamic> BlockRules(IEnumerable<dynamic> rules = null)
        {
            var exe = App.Tg.Exe;
            try
            {
                return (rules ?? Rules()).Where(r =>
                {
                    try { return (int)r.Direction == 1 && (int)r.Action == 0 && (bool)r.Enabled && string.Equals((string)r.ApplicationName, exe, StringComparison.OrdinalIgnoreCase); }
                    catch { return false; }
                }).ToList();
            }
            catch { return new List<dynamic>(); }
        }

        public static void OpenFirewall(int port)
        {
            var pol = Policy();
            CloseFirewall();
            foreach (var b in BlockRules())
            {
                try { b.Enabled = false; Log.Info(Src, L.T("Отключено блокирующее правило брандмауэра: ", "Disabled a blocking firewall rule: ") + (string)b.Name); } catch { }
            }
            dynamic rule = Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FWRule"));
            rule.Name = RuleName;
            rule.Description = L.T("Доступ к TG WS Proxy с устройств локальной сети (создано Zapret Hub)", "Access to TG WS Proxy from devices on the local network (created by Zapret Hub)");
            rule.Protocol = 6;                       // TCP
            rule.LocalPorts = port.ToString();
            rule.RemoteAddresses = "LocalSubnet";
            rule.Direction = 1;                      // inbound
            rule.Action = 1;                         // allow
            rule.Profiles = 0x7FFFFFFF;              // all profiles: home networks are often marked Public
            rule.Enabled = true;
            pol.Rules.Add(rule);
            fwAt = DateTime.MinValue;
            Log.Ok(Src, L.T($"Порт {port} открыт в брандмауэре Windows для локальной сети", $"Port {port} opened in Windows Firewall for the local network"));
        }

        public static void CloseFirewall()
        {
            var pol = Policy();
            for (int i = 0; i < 10 && FirewallRuleExists(); i++) pol.Rules.Remove(RuleName);
            fwAt = DateTime.MinValue;
        }

        // ───────────── connected clients ─────────────

        static readonly ConcurrentDictionary<string, string> names = new ConcurrentDictionary<string, string>();

        public static List<Dictionary<string, object>> Clients(int port)
        {
            TcpConnectionInformation[] conns;
            try { conns = IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpConnections(); }
            catch { return new List<Dictionary<string, object>>(); }
            var local = new HashSet<string>(Candidates().Select(c => (string)c["ip"]));
            return conns.Where(c => c.LocalEndPoint.Port == port && c.State == TcpState.Established)
                .GroupBy(c => c.RemoteEndPoint.Address.ToString())
                .Select(g =>
                {
                    var ip = g.Key;
                    var self = IPAddress.IsLoopback(g.First().RemoteEndPoint.Address) || local.Contains(ip);
                    return new Dictionary<string, object> { ["ip"] = ip, ["connections"] = g.Count(), ["self"] = self, ["name"] = self ? null : HostName(ip) };
                })
                .OrderBy(d => (bool)d["self"]).ThenBy(d => (string)d["ip"]).ToList();
        }

        static string HostName(string ip)
        {
            if (names.TryGetValue(ip, out var n)) return n;
            names[ip] = null;
            Task.Run(async () =>
            {
                try
                {
                    var t = Dns.GetHostEntryAsync(ip);
                    if (await Task.WhenAny(t, Task.Delay(2500)) == t && t.Result.HostName != ip) names[ip] = t.Result.HostName.Split('.')[0];
                }
                catch { }
            });
            return null;
        }
    }
}
