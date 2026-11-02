using System.IO;
using System.Text;
using NAudio.Wave;

namespace PlayerDemo.Library;

/// <summary>
/// YouTube 下载的 m4a 是 dash 分段文件。Media Foundation 能读到时长，但读不出采样。
/// 这里按原来的 AAC 帧重写成一个普通的 m4a。
/// </summary>
static class FragmentedMp4
{
    public static void EnsureProgressive(string path)
    {
        try
        {
            if (IsOurRemux(path))
                RepairSampleTimes(path);
        }
        catch
        {
            // 时间表修不好就按原来的文件继续。
        }

        var temp = path + ".progressive";
        try
        {
            if (!IsFragmented(path))
                return;
            Remux(path, temp);
            if (!HasSamples(temp))
            {
                TryDelete(temp);
                return;
            }

            File.Move(temp, path, true);
        }
        catch
        {
            TryDelete(temp);
        }
    }

    private static bool IsOurRemux(string path)
    {
        using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var header = new byte[Ftyp.Length];
        return stream.Read(header, 0, header.Length) == header.Length && header.AsSpan().SequenceEqual(Ftyp);
    }

    /// <summary>
    /// 早期重写把 stts 的 sample_count 和 sample_delta 写反了。
    /// 两条数字对调后帧数能对上时，就地改回来。
    /// </summary>
    private static void RepairSampleTimes(string path)
    {
        using var stream = File.Open(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite);
        foreach (var moov in Boxes(stream, 0, stream.Length, "moov"))
        {
            foreach (var trak in Boxes(stream, moov.Payload, moov.End, "trak"))
            {
                foreach (var mdia in Boxes(stream, trak.Payload, trak.End, "mdia"))
                {
                    foreach (var minf in Boxes(stream, mdia.Payload, mdia.End, "minf"))
                    {
                        foreach (var stbl in Boxes(stream, minf.Payload, minf.End, "stbl"))
                        {
                            BoxAt? times = null;
                            BoxAt? sizes = null;
                            foreach (var child in Boxes(stream, stbl.Payload, stbl.End, null))
                            {
                                if (child.Type == "stts")
                                    times = child;
                                else if (child.Type == "stsz")
                                    sizes = child;
                            }

                            if (times is BoxAt stts && sizes is BoxAt stsz)
                                SwapReversedTimes(stream, stts, stsz);
                        }
                    }
                }
            }
        }
    }

    private static void SwapReversedTimes(Stream stream, BoxAt stts, BoxAt stsz)
    {
        stream.Position = stsz.Payload + 4;
        _ = ReadBe32(stream);
        var samples = ReadBe32(stream);
        if (samples == 0)
            return;

        stream.Position = stts.Payload + 4;
        var entries = ReadBe32(stream);
        if (entries == 0 || entries > 1_000_000)
            return;

        var entryAt = stream.Position;
        long declared = 0;
        long swapped = 0;
        for (var i = 0u; i < entries; i++)
        {
            declared += ReadBe32(stream);
            swapped += ReadBe32(stream);
        }

        if (declared == samples || swapped != samples)
            return;

        stream.Position = entryAt;
        for (var i = 0u; i < entries; i++)
        {
            var at = stream.Position;
            var count = ReadBe32(stream);
            var delta = ReadBe32(stream);
            stream.Position = at;
            WriteU32(stream, delta);
            WriteU32(stream, count);
        }
    }

    private readonly record struct BoxAt(long Start, long End, int Header, string Type)
    {
        public long Payload => Start + Header;
    }

    private static IEnumerable<BoxAt> Boxes(Stream stream, long start, long end, string? type)
    {
        var header = new byte[8];
        var cursor = start;
        while (cursor + 8 <= end)
        {
            stream.Position = cursor;
            if (stream.Read(header, 0, 8) < 8)
                yield break;

            var size = ((long)header[0] << 24) | ((long)header[1] << 16) | ((long)header[2] << 8) | header[3];
            var name = Encoding.ASCII.GetString(header, 4, 4);
            var headerSize = 8;
            if (size == 1)
            {
                if (stream.Read(header, 0, 8) < 8)
                    yield break;
                size = 0;
                foreach (var part in header)
                    size = (size << 8) | part;
                headerSize = 16;
            }
            else if (size == 0)
            {
                size = end - cursor;
            }

            if (size < headerSize || cursor + size > end)
                yield break;
            if (type == null || name == type)
                yield return new BoxAt(cursor, cursor + size, headerSize, name);
            cursor += size;
        }
    }

    private static uint ReadBe32(Stream stream)
    {
        var bytes = new byte[4];
        if (stream.Read(bytes, 0, 4) < 4)
            throw new EndOfStreamException();
        return ((uint)bytes[0] << 24) | ((uint)bytes[1] << 16) | ((uint)bytes[2] << 8) | bytes[3];
    }

    private static bool IsFragmented(string path)
    {
        using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var header = new byte[8];
        while (stream.Position + 8 <= stream.Length)
        {
            var start = stream.Position;
            if (stream.Read(header, 0, 8) < 8)
                return false;
            var size = ((long)header[0] << 24) | ((long)header[1] << 16) | ((long)header[2] << 8) | header[3];
            var type = Encoding.ASCII.GetString(header, 4, 4);
            if (size == 1)
            {
                if (stream.Read(header, 0, 8) < 8)
                    return false;
                size = 0;
                foreach (var part in header)
                    size = (size << 8) | part;
            }

            if (type == "moof")
                return true;
            if (size < 8 || start + size > stream.Length)
                return false;
            stream.Position = start + size;
        }

        return false;
    }

    private static void Remux(string sourcePath, string destPath)
    {
        using var source = File.Open(sourcePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        byte[]? moov = null;
        var fragments = new List<(long Start, byte[] Bytes)>();
        while (source.Position + 8 <= source.Length)
        {
            var start = source.Position;
            var box = ReadBox(source);
            var type = BoxType(box);
            if (type == "moov")
                moov = box;
            else if (type == "moof")
                fragments.Add((start, box));
        }

        if (moov == null || fragments.Count == 0)
            throw new InvalidDataException("不是分段的 m4a");

        var trak = AudioTrak(moov) ?? throw new InvalidDataException("没有音频轨");
        var samples = new List<Sample>(4096);
        foreach (var fragment in fragments)
            Collect(fragment.Bytes, fragment.Start, trak.TrackId, trak.Defaults, samples);
        if (samples.Count == 0)
            throw new InvalidDataException("没有音频帧");

        ulong duration = 0;
        long bytes = 0;
        foreach (var sample in samples)
        {
            duration += sample.Duration;
            bytes += sample.Size;
        }

        if (bytes <= 0 || bytes > int.MaxValue - 64)
            throw new InvalidDataException("音频长度不对");

        using var output = File.Create(destPath);
        output.Write(Ftyp);
        var dataStart = output.Position + 8;
        WriteU32(output, (uint)(8 + bytes));
        output.Write(Encoding.ASCII.GetBytes("mdat"));
        foreach (var sample in samples)
            CopyRange(source, output, sample.Offset, sample.Size);

        var movie = Movie(trak, samples, duration, dataStart);
        output.Write(movie);
    }

    private static void Collect(byte[] moof, long moofStart, uint trackId, Trex defaults, List<Sample> samples)
    {
        foreach (var child in Children(moof))
        {
            if (BoxType(child) != "traf")
                continue;
            var duration = defaults.Duration;
            var size = defaults.Size;
            long? baseOffset = null;
            var baseIsMoof = false;
            var matched = false;
            foreach (var box in Children(child))
            {
                var type = BoxType(box);
                if (type == "tfhd")
                    matched = ReadTfhd(box, trackId, ref duration, ref size, ref baseOffset, ref baseIsMoof);
                else if (matched && type == "trun")
                    ReadTrun(box, moofStart, baseOffset, baseIsMoof, duration, size, samples);
            }
        }
    }

    private static bool ReadTfhd(byte[] box, uint trackId, ref uint duration, ref uint size, ref long? baseOffset, ref bool baseIsMoof)
    {
        var flags = Flags(box);
        var cursor = 12;
        var id = Be32(box, cursor);
        cursor += 4;
        if (id != trackId)
            return false;
        baseIsMoof = (flags & 0x020000) != 0;
        if ((flags & 0x000001) != 0)
        {
            baseOffset = (long)Be64(box, cursor);
            cursor += 8;
        }

        if ((flags & 0x000002) != 0)
            cursor += 4;
        if ((flags & 0x000008) != 0)
        {
            duration = Be32(box, cursor);
            cursor += 4;
        }

        if ((flags & 0x000010) != 0)
            size = Be32(box, cursor);
        return true;
    }

    private static void ReadTrun(byte[] box, long moofStart, long? baseOffset, bool baseIsMoof, uint defaultDuration, uint defaultSize, List<Sample> samples)
    {
        var flags = Flags(box);
        var count = Be32(box, 12);
        var cursor = 16;
        var dataOffset = 0;
        if ((flags & 0x000001) != 0)
        {
            dataOffset = Be32Signed(box, cursor);
            cursor += 4;
        }

        if ((flags & 0x000004) != 0)
            cursor += 4;
        long origin = baseOffset ?? (baseIsMoof ? moofStart : 0);
        long position = origin + dataOffset;
        var hasDuration = (flags & 0x000100) != 0;
        var hasSize = (flags & 0x000200) != 0;
        var hasFlags = (flags & 0x000400) != 0;
        var hasOffset = (flags & 0x000800) != 0;
        for (var i = 0; i < count; i++)
        {
            var sampleDuration = defaultDuration;
            var sampleSize = defaultSize;
            if (hasDuration)
            {
                sampleDuration = Be32(box, cursor);
                cursor += 4;
            }

            if (hasSize)
            {
                sampleSize = Be32(box, cursor);
                cursor += 4;
            }

            if (hasFlags)
                cursor += 4;
            if (hasOffset)
                cursor += 4;
            if (sampleSize > int.MaxValue)
                throw new InvalidDataException("音频帧太大");
            samples.Add(new Sample(position, (int)sampleSize, sampleDuration));
            position += sampleSize;
        }
    }

    private static byte[] Movie(Audio trak, List<Sample> samples, ulong duration, long dataStart)
    {
        PatchDuration(trak.Mvhd, duration, false);
        PatchDuration(trak.Mdhd, duration, false);
        PatchDuration(trak.Tkhd, duration, true);
        var table = SampleTable(trak.Stsd, samples, dataStart);
        var minf = Box("minf", Concat(trak.Smhd, trak.Dinf, table));
        var mdia = Box("mdia", Concat(trak.Mdhd, trak.Hdlr, minf));
        var track = Box("trak", Concat(trak.Tkhd, mdia));
        return Box("moov", Concat(trak.Mvhd, track));
    }

    private static byte[] SampleTable(byte[] stsd, List<Sample> samples, long dataStart)
    {
        var runs = new List<(uint Duration, uint Count)>();
        foreach (var sample in samples)
        {
            if (runs.Count > 0 && runs[^1].Duration == sample.Duration)
            {
                var last = runs[^1];
                runs[^1] = (last.Duration, last.Count + 1);
            }
            else
            {
                runs.Add((sample.Duration, 1));
            }
        }

        var stts = new byte[8 + runs.Count * 8];
        WriteBe32(stts, 4, (uint)runs.Count);
        var cursor = 8;
        foreach (var run in runs)
        {
            // 每条是 sample_count，然后才是 sample_delta。
            WriteBe32(stts, cursor, run.Count);
            WriteBe32(stts, cursor + 4, run.Duration);
            cursor += 8;
        }

        var stsc = new byte[20];
        WriteBe32(stsc, 4, 1);
        WriteBe32(stsc, 8, 1);
        WriteBe32(stsc, 12, (uint)samples.Count);
        WriteBe32(stsc, 16, 1);
        var stsz = new byte[12 + samples.Count * 4];
        WriteBe32(stsz, 8, (uint)samples.Count);
        cursor = 12;
        foreach (var sample in samples)
        {
            WriteBe32(stsz, cursor, (uint)sample.Size);
            cursor += 4;
        }

        byte[] chunk;
        if (dataStart <= uint.MaxValue)
        {
            chunk = new byte[12];
            WriteBe32(chunk, 4, 1);
            WriteBe32(chunk, 8, (uint)dataStart);
            chunk = Box("stco", chunk);
        }
        else
        {
            chunk = new byte[16];
            WriteBe32(chunk, 4, 1);
            WriteBe64(chunk, 8, (ulong)dataStart);
            chunk = Box("co64", chunk);
        }

        return Box("stbl", Concat(stsd, Box("stts", stts), Box("stsc", stsc), Box("stsz", stsz), chunk));
    }

    private static Audio AudioTrak(byte[] moov)
    {
        foreach (var trak in Children(moov))
        {
            if (BoxType(trak) != "trak")
                continue;
            var hdlr = Find(trak, "hdlr");
            if (hdlr == null || hdlr.Length < 20 || Encoding.ASCII.GetString(hdlr, 16, 4) != "soun")
                continue;
            var tkhd = Find(trak, "tkhd") ?? throw new InvalidDataException("音频轨不完整");
            var mdhd = Find(trak, "mdhd") ?? throw new InvalidDataException("音频轨不完整");
            var stsd = Find(trak, "stsd") ?? throw new InvalidDataException("音频轨不完整");
            var trackId = Be32(tkhd, 20);
            return new Audio(
                Find(moov, "mvhd") ?? throw new InvalidDataException("音频轨不完整"),
                (byte[])tkhd.Clone(),
                (byte[])mdhd.Clone(),
                hdlr,
                Find(trak, "smhd") ?? MinimalSmhd(),
                Find(trak, "dinf") ?? MinimalDinf(),
                stsd,
                trackId,
                Defaults(moov, trackId));
        }

        throw new InvalidDataException("没有音频轨");
    }

    private static Trex Defaults(byte[] moov, uint trackId)
    {
        foreach (var box in Children(moov))
        {
            if (BoxType(box) != "mvex")
                continue;
            foreach (var child in Children(box))
            {
                if (BoxType(child) != "trex" || child.Length < 32 || Be32(child, 12) != trackId)
                    continue;
                return new Trex(Be32(child, 20), Be32(child, 24));
            }
        }

        return new Trex(0, 0);
    }

    private static byte[]? Find(byte[] parent, string type)
    {
        foreach (var child in Children(parent))
        {
            if (BoxType(child) == type)
                return child;
            if (IsContainer(child))
            {
                var nested = Find(child, type);
                if (nested != null)
                    return nested;
            }
        }

        return null;
    }

    private static bool IsContainer(byte[] box)
    {
        var type = BoxType(box);
        return type is "moov" or "trak" or "mdia" or "minf" or "stbl" or "edts" or "mvex" or "moof" or "traf" or "udta";
    }

    private static IEnumerable<byte[]> Children(byte[] box)
    {
        var cursor = HeaderSize(box);
        var end = box.Length;
        while (cursor + 8 <= end)
        {
            var size = (long)Be32(box, cursor);
            var header = 8;
            if (size == 1)
            {
                if (cursor + 16 > end)
                    yield break;
                size = (long)Be64(box, cursor + 8);
                header = 16;
            }
            else if (size == 0)
            {
                size = end - cursor;
            }

            if (size < header || cursor + size > end)
                yield break;
            var child = new byte[size];
            Buffer.BlockCopy(box, cursor, child, 0, (int)size);
            yield return child;
            cursor += (int)size;
        }
    }

    private static byte[] ReadBox(Stream stream)
    {
        var start = stream.Position;
        if (!TryBoxSize(stream, out var size))
            throw new EndOfStreamException();
        if (size < 8 || size > int.MaxValue || start + size > stream.Length)
            throw new InvalidDataException("m4a 结构损坏");
        var box = new byte[size];
        stream.Position = start;
        stream.ReadExactly(box);
        return box;
    }

    private static bool TryBoxSize(Stream stream, out long size)
    {
        Span<byte> header = stackalloc byte[8];
        if (stream.Read(header) < 8)
        {
            size = 0;
            return false;
        }

        size = ((long)header[0] << 24) | ((long)header[1] << 16) | ((long)header[2] << 8) | header[3];
        if (size == 1)
        {
            Span<byte> large = stackalloc byte[8];
            if (stream.Read(large) < 8)
                return false;
            size = 0;
            foreach (var part in large)
                size = (size << 8) | part;
        }

        return true;
    }

    private static void CopyRange(Stream source, Stream dest, long offset, int size)
    {
        source.Position = offset;
        var left = size;
        var buffer = new byte[64 * 1024];
        while (left > 0)
        {
            var read = source.Read(buffer, 0, Math.Min(buffer.Length, left));
            if (read <= 0)
                throw new EndOfStreamException();
            dest.Write(buffer, 0, read);
            left -= read;
        }
    }

    private static bool HasSamples(string path)
    {
        try
        {
            using var reader = new MediaFoundationReader(path);
            if (!reader.CanSeek)
                return reader.Length > 0;
            var count = Math.Max(reader.WaveFormat.BlockAlign, 1);
            var buffer = new byte[count];
            var read = reader.Read(buffer, 0, count);
            return read > 0;
        }
        catch
        {
            return false;
        }
    }

    private static void PatchDuration(byte[] box, ulong duration, bool track)
    {
        var version = box[8];
        var offset = version == 0 ? (track ? 28 : 24) : (track ? 36 : 32);
        if (version == 0)
        {
            if (duration > uint.MaxValue || box.Length < offset + 4)
                return;
            WriteBe32(box, offset, (uint)duration);
            return;
        }

        if (box.Length < offset + 8)
            return;
        WriteBe64(box, offset, duration);
    }

    private static byte[] MinimalSmhd() => Box("smhd", new byte[8]);

    private static byte[] MinimalDinf()
    {
        var url = Box("url ", [0, 0, 0, 1]);
        var dref = new byte[8 + url.Length];
        WriteBe32(dref, 4, 1);
        Buffer.BlockCopy(url, 0, dref, 8, url.Length);
        return Box("dinf", Box("dref", dref));
    }

    private static byte[] Box(string type, byte[] payload)
    {
        var box = new byte[8 + payload.Length];
        WriteBe32(box, 0, (uint)box.Length);
        Encoding.ASCII.GetBytes(type).CopyTo(box, 4);
        Buffer.BlockCopy(payload, 0, box, 8, payload.Length);
        return box;
    }

    private static byte[] Concat(params byte[][] parts)
    {
        var length = 0;
        foreach (var part in parts)
            length += part.Length;
        var result = new byte[length];
        var cursor = 0;
        foreach (var part in parts)
        {
            Buffer.BlockCopy(part, 0, result, cursor, part.Length);
            cursor += part.Length;
        }

        return result;
    }

    private static int HeaderSize(byte[] box) => Be32(box, 0) == 1 ? 16 : 8;

    private static string BoxType(byte[] box) => Encoding.ASCII.GetString(box, 4, 4);

    private static uint Flags(byte[] box) => Be32(box, 8) & 0x00FFFFFF;

    private static uint Be32(byte[] box, int offset) =>
        ((uint)box[offset] << 24) | ((uint)box[offset + 1] << 16) | ((uint)box[offset + 2] << 8) | box[offset + 3];

    private static int Be32Signed(byte[] box, int offset) => (int)Be32(box, offset);

    private static ulong Be64(byte[] box, int offset)
    {
        ulong value = 0;
        for (var i = 0; i < 8; i++)
            value = (value << 8) | box[offset + i];
        return value;
    }

    private static void WriteU32(Stream stream, uint value)
    {
        Span<byte> bytes = stackalloc byte[4];
        bytes[0] = (byte)(value >> 24);
        bytes[1] = (byte)(value >> 16);
        bytes[2] = (byte)(value >> 8);
        bytes[3] = (byte)value;
        stream.Write(bytes);
    }

    private static void WriteBe32(byte[] box, int offset, uint value)
    {
        box[offset] = (byte)(value >> 24);
        box[offset + 1] = (byte)(value >> 16);
        box[offset + 2] = (byte)(value >> 8);
        box[offset + 3] = (byte)value;
    }

    private static void WriteBe64(byte[] box, int offset, ulong value)
    {
        for (var i = 7; i >= 0; i--)
        {
            box[offset + i] = (byte)value;
            value >>= 8;
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
                File.Delete(path);
        }
        catch
        {
            // 临时文件留着不影响原来的音频。
        }
    }

    private static readonly byte[] Ftyp =
    [
        0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x00, 0x00,
        0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32, 0x6D, 0x70, 0x34, 0x31,
    ];

    private readonly record struct Sample(long Offset, int Size, uint Duration);

    private readonly record struct Trex(uint Duration, uint Size);

    private sealed class Audio
    {
        public Audio(byte[] mvhd, byte[] tkhd, byte[] mdhd, byte[] hdlr, byte[] smhd, byte[] dinf, byte[] stsd, uint trackId, Trex defaults)
        {
            Mvhd = mvhd;
            Tkhd = tkhd;
            Mdhd = mdhd;
            Hdlr = hdlr;
            Smhd = smhd;
            Dinf = dinf;
            Stsd = stsd;
            TrackId = trackId;
            Defaults = defaults;
        }

        public byte[] Mvhd { get; }
        public byte[] Tkhd { get; }
        public byte[] Mdhd { get; }
        public byte[] Hdlr { get; }
        public byte[] Smhd { get; }
        public byte[] Dinf { get; }
        public byte[] Stsd { get; }
        public uint TrackId { get; }
        public Trex Defaults { get; }
    }
}
