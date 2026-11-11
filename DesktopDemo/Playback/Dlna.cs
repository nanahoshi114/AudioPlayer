using System.Globalization;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Xml.Linq;

namespace PlayerDemo.Playback;

public sealed class DlnaException : Exception
{
    public DlnaException(string message, int code = 0)
        : base(message)
    {
        Code = code;
    }

    public int Code { get; }
}

/// <summary>局域网上的一台 DLNA 播放设备。本程序把音频推给它。</summary>
public sealed class DlnaRenderer
{
    public required string Udn { get; init; }
    public required string Name { get; init; }
    public required string Location { get; init; }
    public required Uri AvTransport { get; init; }
    public required string AvType { get; init; }
    public Uri? RenderingControl { get; init; }
    public string? RenderingType { get; init; }
    public Uri? ConnectionManager { get; init; }
    public string? ConnectionType { get; init; }
    public required IPAddress Address { get; init; }
    public long SeenAt { get; private set; }

    public void Touch(long now) => SeenAt = now;
}

public enum DlnaServeKind
{
    Original,
    Wav,
    L16,
}

public struct PcmLayout
{
    public int SampleRate { get; init; }
    public int Channels { get; init; }
    public int BitsPerSample { get; init; }
    public bool Float { get; init; }
    public int BlockAlign { get; init; }
    public long DataBytes { get; init; }
}

public static class DlnaDescriptions
{
    public static IReadOnlyList<DlnaRenderer> Read(XDocument document, string location)
    {
        if (!Uri.TryCreate(location, UriKind.Absolute, out var locationUri))
            return [];

        var urlBase = document.Descendants().FirstOrDefault(item => item.Name.LocalName == "URLBase")?.Value.Trim();
        var found = new Dictionary<string, DlnaRenderer>(StringComparer.OrdinalIgnoreCase);
        foreach (var device in document.Descendants().Where(item => item.Name.LocalName == "device"))
        {
            if (!TryReadDevice(device, location, locationUri, urlBase, out var renderer))
                continue;
            var deviceType = device.Elements().FirstOrDefault(item => item.Name.LocalName == "deviceType")?.Value ?? "";
            if (!found.ContainsKey(renderer.Udn) || deviceType.Contains("MediaRenderer", StringComparison.OrdinalIgnoreCase))
                found[renderer.Udn] = renderer;
        }

        return found.Values.ToList();
    }

    private static bool TryReadDevice(XElement device, string location, Uri locationUri, string? urlBase, out DlnaRenderer renderer)
    {
        renderer = null!;
        Uri? av = null;
        string? avType = null;
        Uri? rendering = null;
        string? renderingType = null;
        Uri? connection = null;
        string? connectionType = null;
        foreach (var service in Services(device))
        {
            var type = Text(service, "serviceType");
            var control = Text(service, "controlURL");
            if (type.Length == 0 || control.Length == 0 || !TryResolve(locationUri, urlBase, control, out var uri))
                continue;
            if (type.Contains(":AVTransport:", StringComparison.OrdinalIgnoreCase) && av == null)
            {
                av = uri;
                avType = type;
            }
            else if (type.Contains(":RenderingControl:", StringComparison.OrdinalIgnoreCase) && rendering == null)
            {
                rendering = uri;
                renderingType = type;
            }
            else if (type.Contains(":ConnectionManager:", StringComparison.OrdinalIgnoreCase) && connection == null)
            {
                connection = uri;
                connectionType = type;
            }
        }

        if (av == null || avType == null || !TryAddress(av, out var address))
            return false;

        var name = Text(device, "friendlyName");
        if (name.Length == 0)
            name = address.ToString();
        if (name.Length > 60)
            name = name[..60];
        var udn = Text(device, "UDN");
        if (udn.Length == 0)
            udn = location;

        renderer = new DlnaRenderer
        {
            Udn = udn,
            Name = name,
            Location = location,
            AvTransport = av,
            AvType = avType,
            RenderingControl = rendering,
            RenderingType = renderingType,
            ConnectionManager = connection,
            ConnectionType = connectionType,
            Address = address,
        };
        return true;
    }

    private static IEnumerable<XElement> Services(XElement device)
    {
        foreach (var list in device.Elements().Where(item => item.Name.LocalName == "serviceList"))
        {
            foreach (var service in list.Elements().Where(item => item.Name.LocalName == "service"))
                yield return service;
        }
    }

    private static string Text(XElement parent, string name)
    {
        var value = parent.Elements().FirstOrDefault(item => item.Name.LocalName == name)?.Value ?? "";
        return string.Join(" ", value.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
    }

    private static bool TryResolve(Uri location, string? urlBase, string control, out Uri uri)
    {
        if (Uri.TryCreate(control, UriKind.Absolute, out uri!))
            return uri.Scheme is "http" or "https";
        var root = string.IsNullOrWhiteSpace(urlBase) ? location : new Uri(urlBase);
        return Uri.TryCreate(root, control, out uri!);
    }

    private static bool TryAddress(Uri control, out IPAddress address)
    {
        if (IPAddress.TryParse(control.Host, out address!))
            return address.AddressFamily == AddressFamily.InterNetwork;
        try
        {
            foreach (var candidate in Dns.GetHostAddresses(control.Host))
            {
                if (candidate.AddressFamily != AddressFamily.InterNetwork)
                    continue;
                address = candidate;
                return true;
            }
        }
        catch
        {
            // 名字解析不了就不当作可用设备。
        }

        address = IPAddress.None;
        return false;
    }
}

public static class DlnaProtocols
{
    public static string MimeOf(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".mp3" => "audio/mpeg",
        ".flac" => "audio/flac",
        ".wav" => "audio/wav",
        ".m4a" or ".mp4" or ".m4s" => "audio/mp4",
        ".aac" => "audio/aac",
        ".ogg" => "audio/ogg",
        ".opus" => "audio/opus",
        ".webm" => "audio/webm",
        ".aiff" or ".aif" => "audio/aiff",
        _ => "application/octet-stream",
    };

    public static string ExtensionFor(string path, DlnaServeKind kind) => kind switch
    {
        DlnaServeKind.Wav => ".wav",
        DlnaServeKind.L16 => ".pcm",
        _ => SafeExtension(path),
    };

    public static string OriginalLabel(string path)
    {
        var label = Path.GetExtension(path).TrimStart('.').ToUpperInvariant();
        return label.Length == 0 ? "原文件" : label;
    }

    public static HashSet<string> SinkMimes(string sink)
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(sink))
            return set;
        foreach (var entry in sink.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var parts = entry.Split(':', 4);
            if (parts.Length < 3)
                continue;
            var mime = parts[2].Trim();
            if (mime is "*" or "*/*" or "audio/*")
            {
                set.Add("*");
                continue;
            }

            var semi = mime.IndexOf(';');
            if (semi >= 0)
                mime = mime[..semi];
            if (mime.Length > 0)
                set.Add(mime);
        }

        return set;
    }

    public static DlnaServeKind Choose(string mime, IReadOnlyCollection<string> sinks)
    {
        if (!Known(sinks))
        {
            return mime is "audio/opus" or "audio/webm" or "audio/aiff" or "application/octet-stream"
                ? DlnaServeKind.Wav
                : DlnaServeKind.Original;
        }

        if (Accepts(sinks, mime))
            return DlnaServeKind.Original;
        if (Accepts(sinks, "audio/wav") || Accepts(sinks, "audio/x-wav"))
            return DlnaServeKind.Wav;
        if (sinks.Contains("audio/L16"))
            return DlnaServeKind.L16;
        return DlnaServeKind.Original;
    }

    public static string Features(bool converted) =>
        // OP 和 FLAGS 都要带上按时间和按字节拖动。只写字节时，山灵会把进度条锁住。
        "DLNA.ORG_OP=11;DLNA.ORG_CI=" + (converted ? "1" : "0") + ";DLNA.ORG_FLAGS=61F00000000000000000000000000000";

    public static string ContentType(DlnaServeKind kind, string mime, PcmLayout layout) => kind switch
    {
        DlnaServeKind.Wav => "audio/wav",
        DlnaServeKind.L16 => "audio/L16;rate=" + layout.SampleRate.ToString(CultureInfo.InvariantCulture)
            + ";channels=" + layout.Channels.ToString(CultureInfo.InvariantCulture),
        _ => mime,
    };

    public static string ProtocolInfo(DlnaServeKind kind, string mime, PcmLayout layout, bool converted)
    {
        var extra = Features(converted);
        if (kind == DlnaServeKind.L16)
        {
            return "http-get:*:audio/L16;rate=" + layout.SampleRate.ToString(CultureInfo.InvariantCulture)
                + ";channels=" + layout.Channels.ToString(CultureInfo.InvariantCulture) + ":" + extra;
        }

        var content = kind == DlnaServeKind.Wav ? "audio/wav" : mime;
        return "http-get:*:" + content + ":" + extra;
    }

    private static bool Known(IReadOnlyCollection<string> sinks) =>
        sinks.Count > 0 && !sinks.Contains("*");

    private static bool Accepts(IReadOnlyCollection<string> sinks, string mime)
    {
        if (sinks.Contains(mime))
            return true;
        if (mime.Equals("audio/wav", StringComparison.OrdinalIgnoreCase))
            return sinks.Contains("audio/x-wav");
        if (mime.Equals("audio/flac", StringComparison.OrdinalIgnoreCase))
            return sinks.Contains("audio/x-flac");
        if (mime.Equals("audio/mpeg", StringComparison.OrdinalIgnoreCase))
            return sinks.Contains("audio/mp3");
        if (mime.Equals("audio/mp4", StringComparison.OrdinalIgnoreCase))
            return sinks.Contains("audio/aac") || sinks.Contains("audio/x-m4a") || sinks.Contains("audio/mp4a-latm");
        return false;
    }

    private static string SafeExtension(string path)
    {
        var ext = Path.GetExtension(path).ToLowerInvariant();
        return ext is ".mp3" or ".flac" or ".wav" or ".m4a" or ".aac" or ".ogg" or ".opus" or ".webm" or ".aiff" or ".aif" or ".mp4" or ".m4s"
            ? ext
            : ".bin";
    }
}

public static class DlnaSoap
{
    public const string AvTransport1 = "urn:schemas-upnp-org:service:AVTransport:1";
    public const string RenderingControl1 = "urn:schemas-upnp-org:service:RenderingControl:1";
    public const string ConnectionManager1 = "urn:schemas-upnp-org:service:ConnectionManager:1";

    public static string Envelope(string service, string action, IReadOnlyList<(string Name, string Value)> fields)
    {
        var body = new StringBuilder();
        body.Append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        body.Append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>");
        body.Append("<u:").Append(action).Append(" xmlns:u=\"").Append(Xml(service)).Append("\">");
        foreach (var field in fields)
        {
            body.Append('<').Append(field.Name).Append('>');
            body.Append(Xml(field.Value));
            body.Append("</").Append(field.Name).Append('>');
        }

        body.Append("</u:").Append(action).Append("></s:Body></s:Envelope>");
        return body.ToString();
    }

    public static string Metadata(string url, string? title, string? artist, string protocolInfo, long size, TimeSpan duration, string? album = null, string? artUrl = null, string? lyricsUrl = null, string? lyricsText = null)
    {
        var safeTitle = Xml(string.IsNullOrWhiteSpace(title) ? "音频" : title.Trim());
        var safeArtist = Xml(artist?.Trim() ?? "");
        var durationText = duration > TimeSpan.Zero ? " duration=\"" + FormatTime(duration) + "\"" : "";
        var sizeText = size > 0 ? " size=\"" + size.ToString(CultureInfo.InvariantCulture) + "\"" : "";
        var creator = safeArtist.Length == 0
            ? ""
            : "<dc:creator>" + safeArtist + "</dc:creator><upnp:artist>" + safeArtist + "</upnp:artist>";
        var albumText = string.IsNullOrWhiteSpace(album) ? "" : "<upnp:album>" + Xml(album.Trim()) + "</upnp:album>";
        var art = string.IsNullOrWhiteSpace(artUrl)
            ? ""
            : "<upnp:albumArtURI dlna:profileID=\"JPEG_SM\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" + Xml(artUrl) + "</upnp:albumArtURI>";
        var lyricsUri = string.IsNullOrWhiteSpace(lyricsUrl) ? "" : "<upnp:lyricsURI>" + Xml(lyricsUrl) + "</upnp:lyricsURI>";
        var lyricsBody = LyricsBody(lyricsText);
        var lyrics = lyricsBody.Length == 0 ? "" : "<upnp:lyrics>" + lyricsBody + "</upnp:lyrics>";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
            + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
            + "<dc:title>" + safeTitle + "</dc:title>"
            + creator
            + albumText
            + "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
            + art
            + lyricsUri
            + lyrics
            + "<res protocolInfo=\"" + Xml(protocolInfo) + "\"" + durationText + sizeText + ">" + Xml(url) + "</res>"
            + "</item></DIDL-Lite>";
    }

    /// <summary>
    /// 山灵和 ONIX 有的读标准地址，有的把 upnp:lyrics 当成歌词正文。正文过长时只留地址。
    /// </summary>
    private static string LyricsBody(string? lyricsText)
    {
        if (string.IsNullOrWhiteSpace(lyricsText))
            return "";
        var text = lyricsText.Trim().TrimStart('\uFEFF');
        if (text.Length == 0 || text.Length > 96000)
            return "";
        return Xml(text);
    }

    public static bool IsFault(string xml)
    {
        if (string.IsNullOrWhiteSpace(xml))
            return false;
        try
        {
            var document = XDocument.Parse(xml);
            return document.Descendants().Any(item => item.Name.LocalName is "Fault" or "errorCode");
        }
        catch
        {
            return xml.Contains("Fault", StringComparison.OrdinalIgnoreCase);
        }
    }

    public static int FaultCode(string xml)
    {
        var text = Value(xml, "errorCode");
        return int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var code) ? code : 0;
    }

    public static string? FaultText(string xml)
    {
        var code = FaultCode(xml);
        if (code == 714)
            return "这台设备不接受这个音频格式";
        if (code == 701)
            return "这台设备现在不能执行这个操作";
        if (code == 716)
            return "设备找不到音频地址";
        var description = Value(xml, "errorDescription");
        if (!string.IsNullOrWhiteSpace(description))
            return "DLNA：" + Trim(description);
        var fault = Value(xml, "faultstring");
        if (!string.IsNullOrWhiteSpace(fault) && !fault.Contains("UPnPError", StringComparison.OrdinalIgnoreCase))
            return "DLNA：" + Trim(fault);
        return null;
    }

    public static string? Value(string xml, string localName)
    {
        try
        {
            var document = XDocument.Parse(xml);
            return document.Descendants().FirstOrDefault(item => item.Name.LocalName == localName)?.Value.Trim();
        }
        catch
        {
            return null;
        }
    }

    public static string FormatTime(TimeSpan time)
    {
        if (time < TimeSpan.Zero)
            time = TimeSpan.Zero;
        // 小时补成两位。山灵把 0:01:05 当成格式不对，进度就拖不动。
        return ((int)time.TotalHours).ToString("00", CultureInfo.InvariantCulture)
            + ":" + time.Minutes.ToString("00", CultureInfo.InvariantCulture)
            + ":" + time.Seconds.ToString("00", CultureInfo.InvariantCulture);
    }

    public static bool TryTime(string? text, out TimeSpan time)
    {
        time = TimeSpan.Zero;
        if (string.IsNullOrWhiteSpace(text))
            return false;
        text = text.Trim();
        if (text.Contains("NOT_IMPLEMENTED", StringComparison.OrdinalIgnoreCase))
            return false;
        var parts = text.Split(':');
        if (parts.Length != 3)
            return false;
        if (!int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out var hours))
            return false;
        if (!int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out var minutes))
            return false;
        if (!double.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds))
            return false;
        time = TimeSpan.FromHours(hours) + TimeSpan.FromMinutes(minutes) + TimeSpan.FromSeconds(seconds);
        return true;
    }

    public static string Xml(string? value)
    {
        if (string.IsNullOrEmpty(value))
            return "";
        var builder = new StringBuilder(value.Length);
        foreach (var ch in value)
        {
            switch (ch)
            {
                case '&':
                    builder.Append("&amp;");
                    break;
                case '<':
                    builder.Append("&lt;");
                    break;
                case '>':
                    builder.Append("&gt;");
                    break;
                case '"':
                    builder.Append("&quot;");
                    break;
                default:
                    if (ch < 32 && ch is not '\t' and not '\n' and not '\r')
                        continue;
                    builder.Append(ch);
                    break;
            }
        }

        return builder.ToString();
    }

    private static string Trim(string value)
    {
        value = value.Trim();
        return value.Length > 120 ? value[..120] : value;
    }
}

public static class SsdpHeaders
{
    public static string Search(string target) =>
        "M-SEARCH * HTTP/1.1\r\n"
        + "HOST: 239.255.255.250:1900\r\n"
        + "MAN: \"ssdp:discover\"\r\n"
        + "MX: 2\r\n"
        + "ST: " + target + "\r\n"
        + "USER-AGENT: Windows/10 UPnP/1.1 PlayerDemo/1.0\r\n"
        + "\r\n";

    public static bool TryHeader(string message, string name, out string value)
    {
        value = "";
        var lines = message.Split("\r\n");
        foreach (var line in lines)
        {
            var colon = line.IndexOf(':');
            if (colon <= 0)
                continue;
            if (!line[..colon].Trim().Equals(name, StringComparison.OrdinalIgnoreCase))
                continue;
            value = line[(colon + 1)..].Trim();
            return value.Length > 0;
        }

        return false;
    }
}

public static class LanAddress
{
    public static IPAddress Toward(IPAddress remote)
    {
        using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        socket.Connect(new IPEndPoint(remote, 9));
        if (socket.LocalEndPoint is IPEndPoint end)
            return end.Address;
        throw new IOException("没有通向这台设备的网络");
    }

    public static List<IPAddress> LocalInterfaces()
    {
        var list = new List<IPAddress>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up)
                    continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                    continue;
                foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
                {
                    var address = unicast.Address;
                    if (address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(address))
                        continue;
                    list.Add(address);
                }
            }
        }
        catch
        {
            // 没有网卡时只留本机声卡。
        }

        return list.Distinct().ToList();
    }
}
