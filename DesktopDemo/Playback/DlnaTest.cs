using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Xml.Linq;
using NAudio.Wave;

namespace PlayerDemo.Playback;

public static class DlnaTest
{
    public static string? Run()
    {
        try
        {
            return TestDescriptions()
                ?? TestProtocols()
                ?? TestSoap()
                ?? TestServer()
                ?? TestPush();
        }
        catch (Exception ex)
        {
            return ex.ToString();
        }
    }

    private static string? TestDescriptions()
    {
        var xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://192.168.1.8:1400/</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>库</friendlyName>
                <UDN>uuid:server</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <controlURL>/cd</controlURL>
                  </service>
                </serviceList>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>客厅音箱</friendlyName>
                    <UDN>uuid:renderer</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/av</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                        <controlURL>/rc</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                        <controlURL>http://192.168.1.8:1400/cm</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
            """;
        var list = DlnaDescriptions.Read(XDocument.Parse(xml), "http://192.168.1.8:1400/xml/device.xml");
        if (list.Count != 1)
            return "description count " + list.Count;
        var renderer = list[0];
        if (renderer.Name != "客厅音箱" || renderer.Udn != "uuid:renderer")
            return "description name";
        if (renderer.AvTransport.ToString() != "http://192.168.1.8:1400/av")
            return "description av " + renderer.AvTransport;
        if (renderer.RenderingControl?.ToString() != "http://192.168.1.8:1400/rc")
            return "description rc";
        if (renderer.ConnectionManager?.ToString() != "http://192.168.1.8:1400/cm")
            return "description cm";
        if (!renderer.Address.Equals(IPAddress.Parse("192.168.1.8")))
            return "description address";
        return null;
    }

    private static string? TestProtocols()
    {
        var empty = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (DlnaProtocols.Choose("audio/flac", empty) != DlnaServeKind.Original)
            return "flac original";
        if (DlnaProtocols.Choose("audio/opus", empty) != DlnaServeKind.Wav)
            return "opus wav";
        var mpeg = DlnaProtocols.SinkMimes("http-get:*:audio/mpeg:*,http-get:*:audio/L16;rate=44100;channels=2:*");
        if (DlnaProtocols.Choose("audio/flac", mpeg) != DlnaServeKind.L16)
            return "flac to l16";
        if (DlnaProtocols.Choose("audio/mpeg", mpeg) != DlnaServeKind.Original)
            return "mp3 original";
        var wav = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "audio/wav" };
        if (DlnaProtocols.Choose("audio/mp4", wav) != DlnaServeKind.Wav)
            return "m4a to wav";
        if (!DlnaSoap.TryTime("0:01:02.500", out var time) || Math.Abs(time.TotalSeconds - 62.5) > 0.01)
            return "time parse";
        if (DlnaSoap.TryTime("NOT_IMPLEMENTED", out _))
            return "time skip";
        if (DlnaSoap.FormatTime(TimeSpan.FromSeconds(65)) != "0:01:05")
            return "time format";
        return null;
    }

    private static string? TestSoap()
    {
        var metadata = DlnaSoap.Metadata("http://a/b", "A & B", "甲", "http-get:*:audio/mpeg:*", 12, TimeSpan.FromSeconds(65));
        if (!metadata.Contains("A &amp; B", StringComparison.Ordinal) || !metadata.Contains("duration=\"0:01:05\"", StringComparison.Ordinal))
            return "metadata";
        var envelope = DlnaSoap.Envelope(DlnaSoap.AvTransport1, "Ping", [("CurrentURIMetaData", "<item>")]);
        if (!envelope.Contains("&lt;item&gt;", StringComparison.Ordinal) || envelope.Contains("<item>", StringComparison.Ordinal))
            return "soap escape";
        var fault = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode><errorDescription>Illegal mime-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>
            """;
        if (DlnaSoap.FaultText(fault) != "这台设备不接受这个音频格式" || DlnaSoap.FaultCode(fault) != 714)
            return "fault";
        var message = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.9:1400/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
        if (!SsdpHeaders.TryHeader(message, "Location", out var location) || location != "http://192.168.1.9:1400/desc.xml")
            return "ssdp";
        return null;
    }

    private static string? TestServer()
    {
        var bin = Path.Combine(Path.GetTempPath(), "player-dlna-bytes.bin");
        var wav = Path.Combine(Path.GetTempPath(), "player-dlna-float.wav");
        DlnaFileServer? server = null;
        try
        {
            var raw = new byte[100];
            for (var index = 0; index < raw.Length; index++)
                raw[index] = (byte)index;
            File.WriteAllBytes(bin, raw);
            var format = WaveFormat.CreateIeeeFloatWaveFormat(8000, 2);
            using (var writer = new WaveFileWriter(wav, format))
                writer.WriteSamples([0.5f, -0.5f], 0, 2);

            server = new DlnaFileServer();
            if (!server.TryPublish(bin, DlnaServeKind.Original, out var original, out var error))
                return error;
            var full = Get("http://127.0.0.1:" + server.Port.ToString(CultureInfo.InvariantCulture) + original.Path, null);
            if (!full.AsSpan().SequenceEqual(raw))
                return "direct bytes";
            var range = Get("http://127.0.0.1:" + server.Port.ToString(CultureInfo.InvariantCulture) + original.Path, "bytes=10-19");
            if (!range.AsSpan().SequenceEqual(raw.AsSpan(10, 10)))
                return "direct range";
            var missing = Status("http://127.0.0.1:" + server.Port.ToString(CultureInfo.InvariantCulture) + "/audio/999/track.bin");
            if (missing != 404)
                return "missing " + missing;

            if (!server.TryPublish(wav, DlnaServeKind.Wav, out var transcoded, out error))
                return error;
            var body = Get("http://127.0.0.1:" + server.Port.ToString(CultureInfo.InvariantCulture) + transcoded.Path, null);
            if (body.Length < 48 || body[0] != (byte)'R' || body[8] != (byte)'W')
                return "wav header";
            if (body[44] != 0x00 || body[45] != 0x40 || body[46] != 0x00 || body[47] != 0xC0)
                return "wav samples " + Convert.ToHexString(body, 44, Math.Min(8, body.Length - 44));
            var head = Get("http://127.0.0.1:" + server.Port.ToString(CultureInfo.InvariantCulture) + transcoded.Path, "bytes=44-47");
            if (head.Length != 4 || head[1] != 0x40 || head[3] != 0xC0)
                return "wav range";
            return null;
        }
        finally
        {
            server?.Dispose();
            TryDelete(bin);
            TryDelete(wav);
        }
    }

    private static string? TestPush()
    {
        var path = Path.Combine(Path.GetTempPath(), "player-dlna-push.wav");
        FakeRenderer? fake = null;
        DlnaOutput? output = null;
        try
        {
            var format = new WaveFormat(44100, 16, 2);
            using (var writer = new WaveFileWriter(path, format))
                writer.Write(new byte[format.BlockAlign * 8], 0, format.BlockAlign * 8);
            fake = new FakeRenderer();
            var renderer = new DlnaRenderer
            {
                Udn = "uuid:fake",
                Name = "假音箱",
                Location = fake.Url,
                AvTransport = new Uri(fake.Url),
                AvType = DlnaSoap.AvTransport1,
                Address = IPAddress.Loopback,
            };
            output = new DlnaOutput();
            output.Use(renderer);
            var result = output.Play(path, "标题", "作者", TimeSpan.FromMilliseconds(8), TimeSpan.Zero);
            if (!result.Ok)
                return result.Message;
            if (!result.Message.Contains("WAV", StringComparison.Ordinal))
                return "push message " + result.Message;
            if (!output.IsPlaying)
                return "push state";
            var audio = fake.Audio;
            if (audio == null || !audio.AsSpan().SequenceEqual(File.ReadAllBytes(path)))
                return "push bytes " + (audio?.Length.ToString(CultureInfo.InvariantCulture) ?? "null");
            output.Pause();
            if (!output.IsPaused)
                return "push pause";
            output.Stop();
            if (output.IsPlaying || output.IsPaused)
                return "push stop";
            return null;
        }
        finally
        {
            output?.Dispose();
            fake?.Dispose();
            TryDelete(path);
        }
    }

    private static byte[] Get(string url, string? range)
    {
        using var client = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(5) };
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        if (range != null)
            request.Headers.TryAddWithoutValidation("Range", range);
        using var response = client.Send(request);
        if (!response.IsSuccessStatusCode)
            throw new IOException("HTTP " + (int)response.StatusCode);
        using var stream = response.Content.ReadAsStream();
        using var memory = new MemoryStream();
        stream.CopyTo(memory);
        return memory.ToArray();
    }

    private static int Status(string url)
    {
        using var client = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(5) };
        using var response = client.Send(new HttpRequestMessage(HttpMethod.Get, url));
        return (int)response.StatusCode;
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch
        {
            // 临时文件留着也没关系。
        }
    }

    private sealed class FakeRenderer : IDisposable
    {
        private readonly TcpListener _listener;
        private readonly Thread _thread;
        private readonly HttpClient _http = new(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(5) };
        private readonly object _gate = new();
        private byte[]? _audio;
        private volatile bool _disposed;

        public string Url { get; }

        public byte[]? Audio
        {
            get
            {
                lock (_gate)
                    return _audio;
            }
        }

        public FakeRenderer()
        {
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            Url = "http://127.0.0.1:" + ((IPEndPoint)_listener.LocalEndpoint).Port + "/";
            _thread = new Thread(Loop) { IsBackground = true, Name = "DLNA Fake" };
            _thread.Start();
        }

        public void Dispose()
        {
            _disposed = true;
            try
            {
                _listener.Stop();
            }
            catch
            {
                // 测试结束。
            }

            _http.Dispose();
        }

        private void Loop()
        {
            while (!_disposed)
            {
                TcpClient client;
                try
                {
                    client = _listener.AcceptTcpClient();
                }
                catch
                {
                    break;
                }

                var thread = new Thread(() =>
                {
                    try
                    {
                        Serve(client);
                    }
                    catch
                    {
                        // 测试连接中断。
                    }
                    finally
                    {
                        client.Dispose();
                    }
                })
                { IsBackground = true };
                thread.Start();
            }
        }

        private void Serve(TcpClient client)
        {
            client.ReceiveTimeout = 5000;
            var stream = client.GetStream();
            var buffer = new byte[8192];
            var count = 0;
            while (count < buffer.Length)
            {
                var read = stream.Read(buffer, count, buffer.Length - count);
                if (read <= 0)
                    return;
                count += read;
                var end = Encoding.ASCII.GetString(buffer, 0, count).IndexOf("\r\n\r\n", StringComparison.Ordinal);
                if (end < 0)
                    continue;
                var header = Encoding.ASCII.GetString(buffer, 0, end);
                var length = ContentLength(header);
                var have = count - (end + 4);
                var body = new byte[length];
                if (have > 0)
                    Buffer.BlockCopy(buffer, end + 4, body, 0, Math.Min(have, length));
                var filled = Math.Min(have, length);
                while (filled < length)
                {
                    var readBody = stream.Read(body, filled, length - filled);
                    if (readBody <= 0)
                        break;
                    filled += readBody;
                }

                var text = Encoding.UTF8.GetString(body, 0, filled);
                var uri = UriOf(text);
                if (uri != null)
                {
                    using var response = _http.Send(new HttpRequestMessage(HttpMethod.Get, uri));
                    using var remote = response.Content.ReadAsStream();
                    using var memory = new MemoryStream();
                    remote.CopyTo(memory);
                    lock (_gate)
                        _audio = memory.ToArray();
                }

                const string ok = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:OK xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:OK></s:Body></s:Envelope>";
                var payload = Encoding.UTF8.GetBytes(ok);
                var answer = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: " + payload.Length.ToString(CultureInfo.InvariantCulture) + "\r\nConnection: close\r\n\r\n");
                stream.Write(answer);
                stream.Write(payload);
                return;
            }
        }

        private static int ContentLength(string header)
        {
            foreach (var line in header.Split("\r\n"))
            {
                if (!line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
                    continue;
                return int.TryParse(line["Content-Length:".Length..].Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var length)
                    ? length
                    : 0;
            }

            return 0;
        }

        private static string? UriOf(string body)
        {
            const string open = "<CurrentURI>";
            const string close = "</CurrentURI>";
            var start = body.IndexOf(open, StringComparison.Ordinal);
            if (start < 0)
                return null;
            start += open.Length;
            var finish = body.IndexOf(close, start, StringComparison.Ordinal);
            if (finish < 0)
                return null;
            return body[start..finish].Replace("&amp;", "&", StringComparison.Ordinal);
        }
    }
}
