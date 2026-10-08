using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Threading;

namespace ZapretHub
{
    /// <summary>
    /// Remembers a strategy per network (Wi-Fi, cable, phone hotspot) and switches to it when the network changes.
    /// Networks are identified by the Windows network profile (Network List Manager), the same one that decides Public/Private.
    /// </summary>
    public static class NetProfiles
    {
        const string Src = "net";
        static Timer debounce;
        static string lastId;
        static readonly object Lock = new object();

        public class NetInfo { public string Id, Name, Adapter; public bool Wifi; }
        public static NetInfo Current { get; private set; }

        public static void Init()
        {
            debounce = new Timer(_ => Check(), null, Timeout.Infinite, Timeout.Infinite);
            NetworkChange.NetworkAddressChanged += (s, e) => debounce.Change(4000, Timeout.Infinite);
            NetworkChange.NetworkAvailabilityChanged += (s, e) => debounce.Change(4000, Timeout.Infinite);
            Check();
        }

        static void Check()
        {
            NetInfo cur;
            try { cur = Detect(); } catch (Exception ex) { Log.Warn(Src, "NLM: " + ex.Message); return; }
            lock (Lock)
            {
                if (cur?.Id == lastId) return;
                var first = lastId == null && Current == null;
                lastId = cur?.Id; Current = cur;
                if (cur != null && !first) Log.Info(Src, L.T("Сеть: ", "Network: ") + cur.Name + (cur.Adapter != null ? " (" + cur.Adapter + ")" : ""));
            }
            App.Emit("net", State());
            if (cur != null) Apply(cur);
        }

        static void Apply(NetInfo cur)
        {
            var s = App.Settings;
            if (!s.NetProfilesEnabled || App.Tester.Running) return;
            var p = s.NetProfiles.FirstOrDefault(x => x.Id == cur.Id);
            if (p == null || string.IsNullOrEmpty(p.Strategy)) return;
            var z = App.Zapret;
            if (!z.Installed || !z.Strategies().Contains(p.Strategy)) return;
            var st = z.Status();
            if ((string)st["mode"] == "off" || z.CurrentStrategy() == p.Strategy) return;
            try
            {
                Log.Info(Src, L.T($"Сеть «{cur.Name}»: переключаю на {p.Strategy}", $"Network \"{cur.Name}\": switching to {p.Strategy}"));
                z.SwitchTo(p.Strategy);
                History.Event("switch", p.Strategy, "net");
                App.Notify(L.T("Стратегия для сети «", "Strategy for \"") + cur.Name + L.T("»", "\""), Pretty(p.Strategy));
                App.Emit("refresh", null);
            }
            catch (Exception ex) { Log.Err(Src, ex.Message); }
        }

        static string Pretty(string bat) => System.IO.Path.GetFileNameWithoutExtension(bat ?? "");

        /// <summary>Saves the strategy the user (or self-healing) just picked for the current network.</summary>
        public static void Remember(string bat)
        {
            var s = App.Settings; var cur = Current;
            if (!s.NetProfilesEnabled || cur == null || string.IsNullOrEmpty(bat)) return;
            lock (Lock)
            {
                var p = s.NetProfiles.FirstOrDefault(x => x.Id == cur.Id);
                if (p == null) s.NetProfiles.Add(p = new NetProfile { Id = cur.Id });
                p.Name = cur.Name;
                if (p.Strategy == bat) { s.Save(); return; }
                p.Strategy = bat;
                s.Save();
            }
            Log.Info(Src, L.T($"Для сети «{cur.Name}» запомнена стратегия {bat}", $"Remembered {bat} for network \"{cur.Name}\""));
            App.Emit("net", State());
        }

        public static void SetProfile(string id, string name, string strategy)
        {
            var s = App.Settings;
            lock (Lock)
            {
                var p = s.NetProfiles.FirstOrDefault(x => x.Id == id);
                if (string.IsNullOrEmpty(strategy)) { if (p != null) s.NetProfiles.Remove(p); }
                else
                {
                    if (p == null) s.NetProfiles.Add(p = new NetProfile { Id = id, Name = name ?? id });
                    if (!string.IsNullOrEmpty(name)) p.Name = name;
                    p.Strategy = strategy;
                }
                s.Save();
            }
            if (Current != null && Current.Id == id && !string.IsNullOrEmpty(strategy)) Apply(Current);
        }

        public static Dictionary<string, object> State() => new Dictionary<string, object>
        {
            ["enabled"] = App.Settings.NetProfilesEnabled,
            ["current"] = Current == null ? null : new Dictionary<string, object> { ["id"] = Current.Id, ["name"] = Current.Name, ["adapter"] = Current.Adapter, ["wifi"] = Current.Wifi },
            ["profiles"] = App.Settings.NetProfiles,
        };

        // ───────────── detection ─────────────

        public static NetInfo Detect()
        {
            var adapters = NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up).ToList();
            var main = MainAdapter(adapters);
            var nlm = (INetworkListManager)new NetworkListManager();
            try
            {
                var en = nlm.GetNetworkConnections();
                NetInfo best = null;
                while (en.Next(1, out var conn, out var fetched) == 0 && fetched == 1)
                {
                    try
                    {
                        var adapterId = conn.GetAdapterId().ToString("B");
                        var ni = adapters.FirstOrDefault(a => string.Equals(a.Id, adapterId, StringComparison.OrdinalIgnoreCase));
                        var net = conn.GetNetwork();
                        var info = new NetInfo
                        {
                            Id = net.GetNetworkId().ToString(),
                            Name = net.GetName(),
                            Adapter = ni?.Name,
                            Wifi = ni?.NetworkInterfaceType == NetworkInterfaceType.Wireless80211,
                        };
                        Marshal.ReleaseComObject(net);
                        if (main != null && string.Equals(main.Id, adapterId, StringComparison.OrdinalIgnoreCase)) return info;
                        if (best == null && conn.IsConnectedToInternet) best = info;
                    }
                    finally { Marshal.ReleaseComObject(conn); }
                }
                return best;
            }
            finally { Marshal.ReleaseComObject(nlm); }
        }

        /// <summary>The adapter that carries the default IPv4 route.</summary>
        static NetworkInterface MainAdapter(List<NetworkInterface> adapters)
        {
            try
            {
                if (GetBestInterface(BitConverter.ToUInt32(IPAddress.Parse("8.8.8.8").GetAddressBytes(), 0), out var idx) != 0) return null;
                return adapters.FirstOrDefault(a => { try { return a.GetIPProperties().GetIPv4Properties()?.Index == idx; } catch { return false; } });
            }
            catch { return null; }
        }

        [DllImport("iphlpapi.dll")] static extern int GetBestInterface(uint destAddr, out int bestIfIndex);

        [ComImport, Guid("DCB00C01-570F-4A9B-8D69-199FDBA5723B")] class NetworkListManager { }

        [ComImport, Guid("DCB00000-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
        interface INetworkListManager
        {
            [return: MarshalAs(UnmanagedType.Interface)] object GetNetworks(int flags);
            [return: MarshalAs(UnmanagedType.Interface)] INetwork GetNetwork(Guid id);
            [return: MarshalAs(UnmanagedType.Interface)] IEnumNetworkConnections GetNetworkConnections();
        }

        [ComImport, Guid("DCB00006-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
        interface IEnumNetworkConnections
        {
            [return: MarshalAs(UnmanagedType.Interface)] object NewEnum();
            [PreserveSig] int Next(int celt, [MarshalAs(UnmanagedType.Interface)] out INetworkConnection conn, out int fetched);
        }

        [ComImport, Guid("DCB00005-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
        interface INetworkConnection
        {
            [return: MarshalAs(UnmanagedType.Interface)] INetwork GetNetwork();
            bool IsConnectedToInternet { [return: MarshalAs(UnmanagedType.VariantBool)] get; }
            bool IsConnected { [return: MarshalAs(UnmanagedType.VariantBool)] get; }
            int GetConnectivity();
            Guid GetConnectionId();
            Guid GetAdapterId();
        }

        [ComImport, Guid("DCB00002-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
        interface INetwork
        {
            [return: MarshalAs(UnmanagedType.BStr)] string GetName();
            void SetName([MarshalAs(UnmanagedType.BStr)] string name);
            [return: MarshalAs(UnmanagedType.BStr)] string GetDescription();
            void SetDescription([MarshalAs(UnmanagedType.BStr)] string d);
            Guid GetNetworkId();
        }
    }
}
