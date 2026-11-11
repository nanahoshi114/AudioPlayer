using System.Buffers;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using NAudio.Wave;
using PlayerDemo.Library;

namespace PlayerDemo.Playback;

public sealed class DlnaPublication
{
    public required int Generation { get; init; }
    public required string Path { get; init; }
    public required string FilePath { get; init; }
    public required DlnaServeKind Kind { get; init; }
    public required string ContentType { get; init; }
    public required string Features { get; init; }
    public required long Length { get; init; }
    public PcmLayout Layout { get; init; }
    public byte[]? Cover { get; init; }
    public byte[]? Lyrics { get; init; }

    public string CoverRoute => "/audio/" + Generation.ToString(CultureInfo.InvariantCulture) + "/cover.jpg";

    public string LyricsRoute
    {
        get
        {
            var route = Path;
            var slash = route.LastIndexOf('/');
            var dot = route.LastIndexOf('.');
            if (dot > slash)
                return route[..dot] + ".lrc";
            return route + ".lrc";
        }
    }
}

/// <summary>
/// 只提供当前这一首。设备用 Range 自己拉，所以可以拖动进度。
/// 用 TcpListener，避免 HttpListener 在没有管理员权限时打不开局域网地址。
/// </summary>
public sealed class DlnaFileServer : IDisposable
{
    private readonly TcpListener _listener;
    private readonly object _gate = new();
    private readonly Thread _accept;
    private DlnaPublication? _current;
    private int _generation;
    private volatile bool _disposed;

    public int Port { get; }

    public DlnaFileServer()
    {
        _listener = new TcpListener(IPAddress.Any, 0);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        _accept = new Thread(AcceptLoop) { IsBackground = true, Name = "DLNA Http" };
        _accept.Start();
    }

    public bool TryPublish(string path, DlnaServeKind kind, out DlnaPublication publication, out string error, byte[]? cover = null, byte[]? lyrics = null)
    {
        publication = null!;
        error = "";
        if (!File.Exists(path))
        {
            error = "文件不在了";
            return false;
        }

        var layout = default(PcmLayout);
        long length;
        if (kind == DlnaServeKind.Original)
        {
            length = new FileInfo(path).Length;
            if (length <= 0)
            {
                error = "这个文件是空的";
                return false;
            }
        }
        else if (!DlnaPcm.TryMeasure(path, out layout, out error))
        {
            return false;
        }
        else
        {
            length = kind == DlnaServeKind.Wav ? layout.DataBytes + 44 : layout.DataBytes;
        }

        var generation = Interlocked.Increment(ref _generation);
        var mime = DlnaProtocols.MimeOf(path);
        publication = new DlnaPublication
        {
            Generation = generation,
            Path = "/audio/" + generation.ToString(CultureInfo.InvariantCulture) + "/track" + DlnaProtocols.ExtensionFor(path, kind),
            FilePath = path,
            Kind = kind,
            ContentType = DlnaProtocols.ContentType(kind, mime, layout),
            Features = DlnaProtocols.Features(kind != DlnaServeKind.Original, DlnaProtocols.Profile(kind, mime)),
            Length = length,
            Layout = layout,
            Cover = cover is { Length: > 0 } ? cover : null,
            Lyrics = lyrics is { Length: > 0 } ? lyrics : null,
        };
        lock (_gate)
            _current = publication;
        return true;
    }

    public void Unpublish()
    {
        lock (_gate)
            _current = null;
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        try
        {
            _listener.Stop();
        }
        catch
        {
            // 监听已经停了。
        }
    }

    private void AcceptLoop()
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
                    Handle(client);
                }
                catch
                {
                    // 对方中途断开时，这一次传输作废。
                }
                finally
                {
                    client.Dispose();
                }
            })
            {
                IsBackground = true,
                Name = "DLNA Send",
            };
            thread.Start();
        }
    }

    private void Handle(TcpClient client)
    {
        client.NoDelay = true;
        client.ReceiveTimeout = 8000;
        client.SendTimeout = 30000;
        var stream = client.GetStream();
        if (!TryReadHeaders(stream, out var text))
            return;

        var lines = text.Split("\r\n");
        var request = lines[0].Split(' ');
        if (request.Length < 2)
        {
            WriteHead(stream, 400, "Bad Request", ("Content-Length", "0"), ("Connection", "close"));
            return;
        }

        var method = request[0];
        if (!method.Equals("GET", StringComparison.OrdinalIgnoreCase) && !method.Equals("HEAD", StringComparison.OrdinalIgnoreCase))
        {
            WriteHead(stream, 405, "Method Not Allowed", ("Content-Length", "0"), ("Connection", "close"));
            return;
        }

        var headers = ReadHeaderMap(lines);
        var publication = Current();
        if (publication == null || !TryGeneration(request[1], out var generation) || generation != publication.Generation)
        {
            WriteHead(stream, 404, "Not Found", ("Content-Length", "0"), ("Connection", "close"));
            return;
        }

        var name = RequestName(request[1]);
        if (name.Equals("cover.jpg", StringComparison.OrdinalIgnoreCase))
        {
            if (publication.Cover == null)
            {
                WriteHead(stream, 404, "Not Found", ("Content-Length", "0"), ("Connection", "close"));
                return;
            }

            ServeBytes(stream, method, publication.Cover, "image/jpeg", headers);
            return;
        }

        if (name.EndsWith(".lrc", StringComparison.OrdinalIgnoreCase))
        {
            if (publication.Lyrics == null)
            {
                WriteHead(stream, 404, "Not Found", ("Content-Length", "0"), ("Connection", "close"));
                return;
            }

            ServeBytes(stream, method, publication.Lyrics, "text/plain; charset=utf-8", headers);
            return;
        }

        var range = ParseRange(headers.TryGetValue("Range", out var rangeHeader) ? rangeHeader : null, publication.Length, out var start, out var end);
        if (range == RangeResult.Unsatisfiable)
        {
            WriteHead(
                stream,
                416,
                "Range Not Satisfiable",
                ("Content-Range", "bytes */" + publication.Length.ToString(CultureInfo.InvariantCulture)),
                ("Content-Length", "0"),
                ("Connection", "close"));
            return;
        }

        var count = end - start + 1;
        var partial = range == RangeResult.Partial;
        var mode = headers.TryGetValue("transferMode.dlna.org", out var requested) ? requested : "";
        if (!mode.Equals("Interactive", StringComparison.OrdinalIgnoreCase) && !mode.Equals("Background", StringComparison.OrdinalIgnoreCase))
            mode = "Streaming";
        var head = new List<(string, string)>
        {
            ("Content-Type", publication.ContentType),
            ("Content-Length", count.ToString(CultureInfo.InvariantCulture)),
            ("Accept-Ranges", "bytes"),
            ("Cache-Control", "no-cache"),
            ("contentFeatures.dlna.org", publication.Features),
            ("transferMode.dlna.org", mode),
            ("Connection", "close"),
            ("Server", "PlayerDemo UPnP/1.1 DLNADOC/1.50"),
        };
        if (partial)
            head.Add(("Content-Range", "bytes " + start.ToString(CultureInfo.InvariantCulture) + "-" + end.ToString(CultureInfo.InvariantCulture) + "/" + publication.Length.ToString(CultureInfo.InvariantCulture)));
        WriteHead(stream, partial ? 206 : 200, partial ? "Partial Content" : "OK", head.ToArray());
        if (method.Equals("HEAD", StringComparison.OrdinalIgnoreCase))
            return;
        WriteBody(stream, publication, start, end);
    }

    private DlnaPublication? Current()
    {
        lock (_gate)
            return _current;
    }

    private static bool TryReadHeaders(NetworkStream stream, out string text)
    {
        text = "";
        var buffer = new byte[8192];
        var count = 0;
        while (count < buffer.Length)
        {
            int read;
            try
            {
                read = stream.Read(buffer, count, buffer.Length - count);
            }
            catch
            {
                return false;
            }

            if (read <= 0)
                return false;
            count += read;
            for (var index = 0; index + 3 < count; index++)
            {
                if (buffer[index] != '\r' || buffer[index + 1] != '\n' || buffer[index + 2] != '\r' || buffer[index + 3] != '\n')
                    continue;
                text = Encoding.ASCII.GetString(buffer, 0, index + 4);
                return true;
            }
        }

        return false;
    }

    private static Dictionary<string, string> ReadHeaderMap(string[] lines)
    {
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var index = 1; index < lines.Length; index++)
        {
            var line = lines[index];
            if (line.Length == 0)
                break;
            var colon = line.IndexOf(':');
            if (colon <= 0)
                continue;
            headers[line[..colon].Trim()] = line[(colon + 1)..].Trim();
        }

        return headers;
    }

    private static string RequestName(string target)
    {
        var path = StripTarget(target);
        var parts = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length == 0 ? "" : parts[^1];
    }

    private static void ServeBytes(NetworkStream stream, string method, byte[] body, string contentType, Dictionary<string, string> headers)
    {
        var range = ParseRange(headers.TryGetValue("Range", out var rangeHeader) ? rangeHeader : null, body.LongLength, out var start, out var end);
        if (range == RangeResult.Unsatisfiable)
        {
            WriteHead(
                stream,
                416,
                "Range Not Satisfiable",
                ("Content-Range", "bytes */" + body.Length.ToString(CultureInfo.InvariantCulture)),
                ("Content-Length", "0"),
                ("Connection", "close"));
            return;
        }

        var count = end - start + 1;
        var partial = range == RangeResult.Partial;
        var head = new List<(string, string)>
        {
            ("Content-Type", contentType),
            ("Content-Length", count.ToString(CultureInfo.InvariantCulture)),
            ("Accept-Ranges", "bytes"),
            ("Cache-Control", "no-cache"),
            ("Connection", "close"),
            ("Server", "PlayerDemo UPnP/1.1 DLNADOC/1.50"),
        };
        if (partial)
            head.Add(("Content-Range", "bytes " + start.ToString(CultureInfo.InvariantCulture) + "-" + end.ToString(CultureInfo.InvariantCulture) + "/" + body.Length.ToString(CultureInfo.InvariantCulture)));
        WriteHead(stream, partial ? 206 : 200, partial ? "Partial Content" : "OK", head.ToArray());
        if (method.Equals("HEAD", StringComparison.OrdinalIgnoreCase))
            return;
        stream.Write(body, (int)start, (int)count);
    }

    private static bool TryGeneration(string target, out int generation)
    {
        generation = 0;
        var path = StripTarget(target);
        var parts = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2 || !parts[0].Equals("audio", StringComparison.OrdinalIgnoreCase))
            return false;
        return int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out generation);
    }

    private static string StripTarget(string target)
    {
        var path = target;
        if (path.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || path.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            if (Uri.TryCreate(path, UriKind.Absolute, out var uri))
                path = uri.PathAndQuery;
        }

        var query = path.IndexOf('?');
        if (query >= 0)
            path = path[..query];
        return path;
    }

    private static RangeResult ParseRange(string? header, long length, out long start, out long end)
    {
        start = 0;
        end = Math.Max(0, length - 1);
        if (string.IsNullOrWhiteSpace(header))
            return length > 0 ? RangeResult.Full : RangeResult.Unsatisfiable;
        header = header.Trim();
        if (!header.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
            return RangeResult.Unsatisfiable;
        var spec = header[6..].Split(',')[0].Trim();
        var dash = spec.IndexOf('-');
        if (dash < 0)
            return RangeResult.Unsatisfiable;
        var left = spec[..dash];
        var right = spec[(dash + 1)..];
        if (left.Length == 0)
        {
            if (!long.TryParse(right, NumberStyles.Integer, CultureInfo.InvariantCulture, out var suffix) || suffix <= 0)
                return RangeResult.Unsatisfiable;
            if (suffix > length)
                suffix = length;
            start = length - suffix;
            end = length - 1;
            return start == 0 ? RangeResult.Full : RangeResult.Partial;
        }

        if (!long.TryParse(left, NumberStyles.Integer, CultureInfo.InvariantCulture, out start) || start < 0)
            return RangeResult.Unsatisfiable;
        if (right.Length == 0)
            end = length - 1;
        else if (!long.TryParse(right, NumberStyles.Integer, CultureInfo.InvariantCulture, out end))
            return RangeResult.Unsatisfiable;
        if (start >= length)
            return RangeResult.Unsatisfiable;
        if (end >= length)
            end = length - 1;
        if (end < start)
            return RangeResult.Unsatisfiable;
        // 有的音箱对“从头到尾”发 Range，却不接受 206。整段就按普通 200 回。
        if (start == 0 && end == length - 1)
            return RangeResult.Full;
        return RangeResult.Partial;
    }

    private static void WriteHead(NetworkStream stream, int status, string reason, params (string Name, string Value)[] headers)
    {
        var builder = new StringBuilder();
        builder.Append("HTTP/1.1 ").Append(status.ToString(CultureInfo.InvariantCulture)).Append(' ').Append(reason).Append("\r\n");
        foreach (var header in headers)
            builder.Append(header.Name).Append(": ").Append(header.Value).Append("\r\n");
        builder.Append("\r\n");
        var bytes = Encoding.ASCII.GetBytes(builder.ToString());
        stream.Write(bytes, 0, bytes.Length);
    }

    private static void WriteBody(NetworkStream stream, DlnaPublication publication, long start, long end)
    {
        if (publication.Kind == DlnaServeKind.Original)
        {
            using var file = new FileStream(publication.FilePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            file.Position = start;
            Copy(file, stream, end - start + 1);
            return;
        }

        var header = publication.Kind == DlnaServeKind.Wav ? 44 : 0;
        var position = start;
        if (header > 0 && position < header && position <= end)
        {
            var wav = new byte[44];
            DlnaPcm.WriteWavHeader(wav, publication.Layout.SampleRate, publication.Layout.Channels, publication.Layout.DataBytes);
            var from = (int)position;
            var take = (int)Math.Min(header - position, end - position + 1);
            stream.Write(wav, from, take);
            position += take;
        }

        if (position > end)
            return;

        var pcmFrom = position - header;
        var pcmTo = end - header;
        var channels = publication.Layout.Channels;
        var destBlock = channels * 2;
        if (destBlock <= 0)
            return;
        using var audio = TrackLoader.OpenPcm(publication.FilePath);
        var frame = pcmFrom / destBlock;
        if (audio.CanSeek)
            audio.Position = frame * audio.WaveFormat.BlockAlign;
        else
            Discard(audio, frame * audio.WaveFormat.BlockAlign);

        var cursor = frame * destBlock;
        var buffer = ArrayPool<byte>.Shared.Rent(16 * 1024);
        try
        {
            var bigEndian = publication.Kind == DlnaServeKind.L16;
            while (cursor <= pcmTo)
            {
                var want = buffer.Length - buffer.Length % destBlock;
                var got = DlnaPcm.ReadPcm16(audio, buffer, want, bigEndian);
                if (got <= 0)
                {
                    Pad(stream, pcmTo - Math.Max(cursor, pcmFrom) + 1);
                    break;
                }

                var emitFrom = Math.Max(cursor, pcmFrom);
                var emitTo = Math.Min(cursor + got - 1, pcmTo);
                if (emitFrom <= emitTo)
                    stream.Write(buffer, (int)(emitFrom - cursor), (int)(emitTo - emitFrom + 1));
                cursor += got;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static void Copy(Stream source, Stream destination, long count)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(64 * 1024);
        try
        {
            while (count > 0)
            {
                var read = source.Read(buffer, 0, (int)Math.Min(buffer.Length, count));
                if (read <= 0)
                {
                    Pad(destination, count);
                    break;
                }

                destination.Write(buffer, 0, read);
                count -= read;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static void Discard(Stream source, long count)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(16 * 1024);
        try
        {
            while (count > 0)
            {
                var read = source.Read(buffer, 0, (int)Math.Min(buffer.Length, count));
                if (read <= 0)
                    break;
                count -= read;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static void Pad(Stream destination, long count)
    {
        if (count <= 0)
            return;
        var zeros = ArrayPool<byte>.Shared.Rent(4096);
        try
        {
            Array.Clear(zeros, 0, zeros.Length);
            while (count > 0)
            {
                var size = (int)Math.Min(zeros.Length, count);
                destination.Write(zeros, 0, size);
                count -= size;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(zeros);
        }
    }

    private enum RangeResult
    {
        Full,
        Partial,
        Unsatisfiable,
    }
}

public static class DlnaPcm
{
    public static bool TryMeasure(string path, out PcmLayout layout, out string error)
    {
        layout = default;
        error = "";
        try
        {
            using var stream = TrackLoader.OpenPcm(path);
            var format = stream.WaveFormat;
            if (!TrackLoader.IsPlayable(format) || format.SampleRate < 1000 || format.Channels is < 1 or > 8 || format.BlockAlign <= 0)
            {
                error = "这个格式没法转成 WAV";
                return false;
            }

            var frames = stream.Length / format.BlockAlign;
            if (frames <= 0)
            {
                error = "读不出音频长度，没法推到 DLNA";
                return false;
            }

            var data = frames * format.Channels * 2L;
            if (data > uint.MaxValue - 36)
            {
                error = "文件太长，转成 WAV 放不下";
                return false;
            }

            layout = new PcmLayout
            {
                SampleRate = format.SampleRate,
                Channels = format.Channels,
                BitsPerSample = format.BitsPerSample,
                Float = TrackLoader.IsFloat(format),
                BlockAlign = format.BlockAlign,
                DataBytes = data,
            };
            return true;
        }
        catch (Exception ex)
        {
            error = "打不开这个文件：" + ex.Message;
            return false;
        }
    }

    public static void WriteWavHeader(byte[] header, int sampleRate, int channels, long dataBytes)
    {
        header.AsSpan().Clear();
        Encoding.ASCII.GetBytes("RIFF").CopyTo(header, 0);
        BitConverter.TryWriteBytes(header.AsSpan(4), (uint)(36 + dataBytes));
        Encoding.ASCII.GetBytes("WAVE").CopyTo(header, 8);
        Encoding.ASCII.GetBytes("fmt ").CopyTo(header, 12);
        BitConverter.TryWriteBytes(header.AsSpan(16), 16);
        BitConverter.TryWriteBytes(header.AsSpan(20), (short)1);
        BitConverter.TryWriteBytes(header.AsSpan(22), (short)channels);
        BitConverter.TryWriteBytes(header.AsSpan(24), sampleRate);
        var block = channels * 2;
        BitConverter.TryWriteBytes(header.AsSpan(28), sampleRate * block);
        BitConverter.TryWriteBytes(header.AsSpan(32), (short)block);
        BitConverter.TryWriteBytes(header.AsSpan(34), (short)16);
        Encoding.ASCII.GetBytes("data").CopyTo(header, 36);
        BitConverter.TryWriteBytes(header.AsSpan(40), (uint)dataBytes);
    }

    public static int ReadPcm16(WaveStream source, byte[] buffer, int count, bool bigEndian)
    {
        var format = source.WaveFormat;
        var destBlock = format.Channels * 2;
        if (destBlock <= 0)
            return 0;
        count = Math.Min(count, buffer.Length);
        count -= count % destBlock;
        if (count <= 0)
            return 0;
        if (!bigEndian && format.BitsPerSample == 16 && TrackLoader.IsIntegerPcm(format) && format.BlockAlign == destBlock)
            return source.Read(buffer, 0, count);

        var frames = count / destBlock;
        var sourceBytes = frames * format.BlockAlign;
        var rented = ArrayPool<byte>.Shared.Rent(sourceBytes);
        try
        {
            var read = source.Read(rented, 0, sourceBytes);
            var got = read / format.BlockAlign;
            var destination = 0;
            for (var frame = 0; frame < got; frame++)
            {
                for (var channel = 0; channel < format.Channels; channel++)
                {
                    var sample = Sample16(rented, frame, format, channel);
                    if (bigEndian)
                    {
                        buffer[destination++] = (byte)(sample >> 8);
                        buffer[destination++] = (byte)sample;
                    }
                    else
                    {
                        buffer[destination++] = (byte)sample;
                        buffer[destination++] = (byte)(sample >> 8);
                    }
                }
            }

            return got * destBlock;
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(rented);
        }
    }

    private static short Sample16(byte[] source, int frame, WaveFormat format, int channel)
    {
        var width = Math.Max(1, format.BitsPerSample / 8);
        var at = frame * format.BlockAlign + channel * width;
        if (at < 0 || at + width > source.Length)
            return 0;
        if (TrackLoader.IsFloat(format))
        {
            var sample = BitConverter.ToSingle(source, at);
            if (sample >= 1f)
                return short.MaxValue;
            if (sample <= -1f)
                return short.MinValue;
            return (short)Math.Round(sample * 32767.0);
        }

        if (format.BitsPerSample == 16)
            return BitConverter.ToInt16(source, at);
        if (format.BitsPerSample == 24)
        {
            var value = source[at] | (source[at + 1] << 8) | (source[at + 2] << 16);
            if ((value & 0x800000) != 0)
                value |= unchecked((int)0xFF000000);
            return (short)(value >> 8);
        }

        return (short)(BitConverter.ToInt32(source, at) >> 16);
    }
}
