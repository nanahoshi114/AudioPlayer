using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Xml.Linq;

namespace PlayerDemo.Playback;

/// <summary>在局域网上找 DLNA 播放设备。找到或消失时通知界面刷新设备列表。</summary>
public sealed class DlnaDiscovery : IDisposable
{
    private static readonly string[] Targets =
    [
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "upnp:rootdevice",
    ];

    private readonly object _gate = new();
    private readonly Dictionary<string, DlnaRenderer> _renderers = new(StringComparer.OrdinalIgnoreCase);
    private readonly HttpClient _http;
    private readonly CancellationTokenSource _cts = new();
    private readonly Thread _thread;
    private volatile bool _disposed;
    private volatile bool _searched;
    private string? _signature;

    public event EventHandler? Changed;

    public bool HasSearched => _searched;

    public DlnaDiscovery()
    {
        var handler = new HttpClientHandler { UseProxy = false };
        _http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(4) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("PlayerDemo/1.0 UPnP/1.1");
        _thread = new Thread(Loop) { IsBackground = true, Name = "DLNA Search" };
        _thread.Start();
    }

    public IReadOnlyList<DlnaRenderer> Renderers
    {
        get
        {
            lock (_gate)
            {
                return _renderers.Values
                    .OrderBy(item => item.Name, StringComparer.CurrentCultureIgnoreCase)
                    .ToList();
            }
        }
    }

    public DlnaRenderer? Find(string udn)
    {
        lock (_gate)
            return _renderers.TryGetValue(udn, out var renderer) ? renderer : null;
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        _cts.Cancel();
        _http.Dispose();
    }

    private void Loop()
    {
        while (!_disposed && !_cts.IsCancellationRequested)
        {
            try
            {
                SearchOnce();
            }
            catch
            {
                // 这一轮网络失败，下一轮再找。
            }

            if (!_searched)
                Publish();

            try
            {
                Task.Delay(8000, _cts.Token).Wait();
            }
            catch
            {
                if (_disposed || _cts.IsCancellationRequested)
                    break;
            }
        }
    }

    private void SearchOnce()
    {
        var addresses = LanAddress.LocalInterfaces();
        if (addresses.Count == 0)
        {
            Publish();
            return;
        }

        var locations = new ConcurrentDictionary<string, byte>(StringComparer.OrdinalIgnoreCase);
        Parallel.ForEach(addresses, address =>
        {
            try
            {
                Probe(address, locations, 2000);
            }
            catch
            {
                // 这块网卡发不出多播，换下一块。
            }
        });

        var now = Environment.TickCount64;
        var fresh = new List<DlnaRenderer>();
        foreach (var location in locations.Keys)
        {
            if (!Uri.TryCreate(location, UriKind.Absolute, out _))
                continue;
            DlnaRenderer? known;
            lock (_gate)
                known = _renderers.Values.FirstOrDefault(item => item.Location.Equals(location, StringComparison.OrdinalIgnoreCase));
            if (known != null)
            {
                known.Touch(now);
                continue;
            }

            var document = LoadXml(location);
            if (document == null)
                continue;
            fresh.AddRange(DlnaDescriptions.Read(document, location));
        }

        lock (_gate)
        {
            foreach (var renderer in fresh)
            {
                renderer.Touch(now);
                _renderers[renderer.Udn] = renderer;
            }

            foreach (var pair in _renderers.ToList())
            {
                if (now - pair.Value.SeenAt > 45000)
                    _renderers.Remove(pair.Key);
            }
        }

        Publish();
    }

    private static void Probe(IPAddress local, ConcurrentDictionary<string, byte> locations, int waitMs)
    {
        using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        socket.Bind(new IPEndPoint(local, 0));
        try
        {
            socket.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 2);
        }
        catch
        {
            // 有的网卡不接受这个选项，绑定地址后仍然可以发。
        }

        var remote = new IPEndPoint(IPAddress.Parse("239.255.255.250"), 1900);
        foreach (var target in Targets)
            socket.SendTo(Encoding.ASCII.GetBytes(SsdpHeaders.Search(target)), remote);

        var buffer = new byte[8192];
        var deadline = Environment.TickCount64 + waitMs;
        while (Environment.TickCount64 < deadline)
        {
            socket.ReceiveTimeout = (int)Math.Max(1, deadline - Environment.TickCount64);
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            int count;
            try
            {
                count = socket.ReceiveFrom(buffer, ref from);
            }
            catch (SocketException)
            {
                break;
            }

            var message = Encoding.ASCII.GetString(buffer, 0, count);
            if (SsdpHeaders.TryHeader(message, "LOCATION", out var location))
                locations.TryAdd(location, 0);
        }
    }

    private XDocument? LoadXml(string location)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, location);
            using var cts = new CancellationTokenSource(2500);
            using var response = _http.Send(request, cts.Token);
            if (!response.IsSuccessStatusCode)
                return null;
            using var remote = response.Content.ReadAsStream();
            using var memory = new MemoryStream();
            var buffer = new byte[8192];
            while (memory.Length < 1_000_000)
            {
                var count = remote.Read(buffer, 0, buffer.Length);
                if (count <= 0)
                    break;
                memory.Write(buffer, 0, count);
            }

            if (memory.Length == 0 || memory.Length >= 1_000_000)
                return null;
            memory.Position = 0;
            return XDocument.Load(memory);
        }
        catch
        {
            return null;
        }
    }

    private void Publish()
    {
        if (_disposed)
            return;
        _searched = true;
        string signature;
        lock (_gate)
        {
            signature = string.Join(
                "\n",
                _renderers.Values
                    .OrderBy(item => item.Udn, StringComparer.OrdinalIgnoreCase)
                    .Select(item => item.Udn + "\n" + item.Name + "\n" + item.Location));
        }

        if (signature == _signature)
            return;
        _signature = signature;
        try
        {
            Changed?.Invoke(this, EventArgs.Empty);
        }
        catch
        {
            // 界面正在关，这次通知丢掉。
        }
    }
}
