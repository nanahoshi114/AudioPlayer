using System.Buffers.Binary;
using System.IO;
using System.Text;
using Concentus;
using Concentus.Structs;
using NAudio.Wave;

namespace PlayerDemo.Library;

/// <summary>
/// Media Foundation 解不了 Opus。这里拆 WebM / Ogg，并用 Concentus 解成 48 kHz float。
/// </summary>
public sealed class OpusWaveStream : WaveStream
{
    private const int Rate = 48000;
    private const int MaxFrame = 5760;

    private const uint IdEbml = 0x1A45DFA3;
    private const uint IdDocType = 0x4282;
    private const uint IdSegment = 0x18538067;
    private const uint IdSeekHead = 0x114D9B74;
    private const uint IdInfo = 0x1549A966;
    private const uint IdTimecodeScale = 0x2AD7B1;
    private const uint IdDuration = 0x4489;
    private const uint IdTracks = 0x1654AE6B;
    private const uint IdTrackEntry = 0xAE;
    private const uint IdTrackNumber = 0xD7;
    private const uint IdTrackType = 0x83;
    private const uint IdCodecId = 0x86;
    private const uint IdCodecPrivate = 0x63A2;
    private const uint IdCodecDelay = 0x56AA;
    private const uint IdSeekPreRoll = 0x56BB;
    private const uint IdCues = 0x1C53BB6B;
    private const uint IdCluster = 0x1F43B675;
    private const uint IdSimpleBlock = 0xA3;
    private const uint IdBlockGroup = 0xA0;
    private const uint IdBlock = 0xA1;
    private const uint IdDiscardPadding = 0x75A2;
    private const uint IdTags = 0x1254C367;
    private const uint IdChapters = 0x1043A770;
    private const uint IdAttachments = 0x1941A469;

    private readonly object _gate = new();
    private readonly FileStream _file;
    private readonly Io _io;
    private readonly IOpusDecoder _decoder;
    private readonly int _channels;
    private readonly int _preSkip;
    private readonly int _preRoll;
    private readonly float _gain;
    private readonly long _headerSamples;
    private readonly bool _ogg;
    private readonly int _track;
    private readonly int _serial;
    private readonly float[] _pcm;
    private readonly byte[] _pending;
    private readonly List<Packet> _packets = [];
    private readonly List<byte[]> _blobs = [];

    private int _preSkipLeft;
    private long _indexedAudible;
    private bool _done;
    private bool _inCluster;
    private bool _clusterUnknown;
    private long _clusterEnd;
    private int _decodeIndex;
    private long _discard;
    private int _pendingOffset;
    private int _pendingCount;
    private long _position;

    private int _segIndex;
    private int _segCount;
    private long _segPos;
    private byte[] _segs = [];
    private long _spanOffset = -1;
    private int _spanLen;
    private List<byte>? _openBlob;

    private OpusWaveStream(FileStream file, Container container)
    {
        _file = file;
        _io = new Io(file, container.Limit);
        _io.Position = container.Start;
        _channels = container.Channels;
        _preSkip = container.PreSkip;
        _preSkipLeft = container.PreSkip;
        _preRoll = container.PreRoll > 0 ? container.PreRoll : 3840;
        _gain = container.Gain;
        _headerSamples = container.HeaderSamples;
        _ogg = container.Ogg;
        _track = container.Track;
        _serial = container.Serial;
        _decoder = OpusCodecFactory.CreateDecoder(Rate, _channels, null);
        _pcm = new float[MaxFrame * _channels];
        _pending = new byte[MaxFrame * _channels * 4];
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(Rate, _channels);
    }

    public override WaveFormat WaveFormat { get; }

    public override long Length
    {
        get
        {
            lock (_gate)
            {
                var samples = _headerSamples > 0 ? _headerSamples : _indexedAudible;
                return samples * WaveFormat.BlockAlign;
            }
        }
    }

    public override long Position
    {
        get
        {
            lock (_gate)
                return _position;
        }
        set
        {
            lock (_gate)
                SeekTo(value);
        }
    }

    public static bool TryOpen(string path, out WaveStream? audio)
    {
        audio = null;
        FileStream? file = null;
        try
        {
            file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 65536, FileOptions.RandomAccess);
            var container = Probe(file);
            if (container is not Container found)
                return false;
            audio = new OpusWaveStream(file, found);
            file = null;
            return true;
        }
        finally
        {
            file?.Dispose();
        }
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        lock (_gate)
        {
            var block = WaveFormat.BlockAlign;
            if (count < block)
                return 0;
            count -= count % block;
            var filled = 0;
            while (filled < count)
            {
                if (_pendingCount == 0 && !FillPending())
                    break;
                var copy = Math.Min(_pendingCount, count - filled);
                copy -= copy % block;
                if (copy <= 0)
                    break;
                Buffer.BlockCopy(_pending, _pendingOffset, buffer, offset + filled, copy);
                _pendingOffset += copy;
                _pendingCount -= copy;
                _position += copy;
                filled += copy;
            }

            return filled;
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            lock (_gate)
                _file.Dispose();
        }

        base.Dispose(disposing);
    }

    private void SeekTo(long value)
    {
        var block = WaveFormat.BlockAlign;
        if (value < 0)
            value = 0;
        value -= value % block;
        var sample = value / block;
        if (_headerSamples > 0 && sample > _headerSamples)
            sample = _headerSamples;
        EnsureIndexed(sample);
        if (sample > _indexedAudible)
            sample = _indexedAudible;
        _position = sample * block;
        _pendingOffset = 0;
        _pendingCount = 0;
        _decoder.ResetState();
        if (sample <= 0 || _packets.Count == 0)
        {
            _decodeIndex = 0;
            _discard = 0;
            return;
        }

        if (_done && sample >= _indexedAudible)
        {
            _decodeIndex = _packets.Count;
            _discard = 0;
            return;
        }

        var index = FindPacket(sample);
        var start = index;
        var back = sample - _preRoll;
        if (back < 0)
            back = 0;
        while (start > 0 && _packets[start - 1].Origin >= back)
            start--;
        _decodeIndex = start;
        _discard = sample - _packets[start].Origin;
        if (_discard < 0)
            _discard = 0;
    }

    private int FindPacket(long sample)
    {
        var lo = 0;
        var hi = _packets.Count - 1;
        var answer = 0;
        while (lo <= hi)
        {
            var mid = (lo + hi) >> 1;
            if (_packets[mid].Origin <= sample)
            {
                answer = mid;
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }

        return answer;
    }

    private void EnsureIndexed(long sample)
    {
        while (!_done && _indexedAudible <= sample)
        {
            if (!PullProgress())
                _done = true;
        }
    }

    private bool PullProgress()
    {
        var mark = _io.Position;
        var count = _packets.Count;
        var indexed = _indexedAudible;
        if (!Pull())
            return false;
        return _io.Position != mark || _packets.Count != count || _indexedAudible != indexed;
    }

    private bool FillPending()
    {
        while (_pendingCount == 0)
        {
            if (_decodeIndex < _packets.Count)
            {
                Queue(_packets[_decodeIndex++]);
                continue;
            }

            if (_done || !PullProgress())
            {
                _done = true;
                return false;
            }
        }

        return true;
    }

    private void Queue(Packet packet)
    {
        var data = PacketBytes(packet);
        var decoded = _decoder.Decode(data, _pcm.AsSpan(), MaxFrame, false);
        if (decoded < 0)
            decoded = 0;
        var head = Math.Min(packet.Head, decoded);
        var expect = packet.Audible;
        var available = Math.Max(0, decoded - head - Math.Max(0, packet.Tail));
        var take = Math.Min(expect, available);
        var drop = (int)Math.Min(_discard, expect);
        _discard -= drop;
        var keep = expect - drop;
        if (keep <= 0)
            return;

        var bytes = keep * _channels * 4;
        var dest = _pending.AsSpan(0, bytes);
        var realEnd = head + take;
        for (var i = 0; i < keep; i++)
        {
            var src = head + drop + i;
            for (var channel = 0; channel < _channels; channel++)
            {
                var sample = 0f;
                if (src < realEnd)
                {
                    sample = _pcm[src * _channels + channel];
                    if (_gain != 1f)
                        sample *= _gain;
                    if (!float.IsFinite(sample))
                        sample = 0;
                }

                BinaryPrimitives.WriteSingleLittleEndian(dest, sample);
                dest = dest[4..];
            }
        }

        _pendingOffset = 0;
        _pendingCount = bytes;
    }

    private byte[] PacketBytes(Packet packet)
    {
        if (packet.Blob >= 0)
            return _blobs[packet.Blob];
        return _io.ReadAt(packet.Offset, packet.Length);
    }

    private bool Pull()
    {
        if (_ogg)
            return PullOgg();
        while (true)
        {
            var parent = _inCluster && !_clusterUnknown ? _clusterEnd : _io.Limit;
            if (_io.Position >= parent && _inCluster && !_clusterUnknown)
                _inCluster = false;
            if (_io.Position >= _io.Limit)
                return false;
            var start = _io.Position;
            parent = _inCluster && !_clusterUnknown ? _clusterEnd : _io.Limit;
            if (!_io.TryElement(parent, out var element))
                return false;
            if (_inCluster && _clusterUnknown && IsSegmentLevel(element.Id))
            {
                _io.Position = start;
                _inCluster = false;
                continue;
            }

            if (!_inCluster)
            {
                if (element.Id == IdCluster)
                {
                    _inCluster = true;
                    _clusterUnknown = element.Unknown;
                    _clusterEnd = element.End;
                    continue;
                }

                _io.Position = element.End;
                if (_io.Position <= start)
                    return false;
                continue;
            }

            if (element.Id == IdSimpleBlock)
            {
                var payload = _io.ReadBytes(element.Size);
                AddFrames(payload, element.Body, 0);
                return true;
            }

            if (element.Id == IdBlockGroup)
            {
                TakeGroup(element);
                return true;
            }

            _io.Position = element.End;
            if (_io.Position <= start)
                return false;
        }
    }

    private void TakeGroup(Element element)
    {
        byte[]? block = null;
        var blockPos = 0L;
        var tail = 0;
        while (_io.Position + 2 <= element.End)
        {
            if (!_io.TryElement(element.End, out var child))
                break;
            if (child.Id == IdBlock)
            {
                blockPos = child.Body;
                block = _io.ReadBytes(child.Size);
            }
            else if (child.Id == IdDiscardPadding)
            {
                var ns = _io.ReadInt(child.Size);
                if (ns > 0)
                    tail += (int)(ns * Rate / 1_000_000_000L);
            }
            else
            {
                _io.Position = child.End;
            }
        }

        _io.Position = element.End;
        if (block != null)
            AddFrames(block, blockPos, tail);
    }

    private void AddFrames(byte[] payload, long payloadPos, int tailSamples)
    {
        if (!SplitFrames(payload, _track, out var frames))
            return;
        var raw = new int[frames.Count];
        for (var i = 0; i < frames.Count; i++)
        {
            var frame = frames[i];
            if (frame.Length <= 0)
                continue;
            raw[i] = OpusPacketInfo.GetNumSamples(payload.AsSpan(frame.Offset, frame.Length), Rate);
            if (raw[i] < 0)
                raw[i] = 0;
        }

        var tails = new int[frames.Count];
        var left = tailSamples;
        for (var i = frames.Count - 1; i >= 0 && left > 0; i--)
        {
            var take = Math.Min(left, raw[i]);
            tails[i] = take;
            left -= take;
        }

        for (var i = 0; i < frames.Count; i++)
        {
            if (raw[i] <= 0 || frames[i].Length <= 0)
                continue;
            var head = Math.Min(_preSkipLeft, raw[i]);
            _preSkipLeft -= head;
            var rest = raw[i] - head;
            var tail = Math.Min(tails[i], rest);
            var audible = rest - tail;
            _packets.Add(new Packet(
                payloadPos + frames[i].Offset,
                frames[i].Length,
                _indexedAudible,
                audible,
                head,
                tail,
                -1));
            _indexedAudible += audible;
        }
    }

    private bool PullOgg()
    {
        while (true)
        {
            if (!NextOggPacket(out _, out var header))
                return false;
            if (!header)
                return true;
        }
    }

    private bool NextOggPacket(out Packet packet, out bool header)
    {
        packet = default;
        header = false;
        while (true)
        {
            if (_segIndex >= _segCount)
            {
                if (!ReadOggPage())
                    return false;
            }

            var length = _segs[_segIndex++];
            var position = _segPos;
            _segPos += length;
            if (_spanOffset < 0)
            {
                _spanOffset = position;
                _spanLen = 0;
            }

            _spanLen += length;
            if (length == 255)
                continue;

            long offset;
            var size = 0;
            var blob = -1;
            if (_openBlob != null)
            {
                if (_spanLen > 0)
                    _openBlob.AddRange(_io.ReadAt(_spanOffset, _spanLen));
                var bytes = _openBlob.ToArray();
                _openBlob = null;
                blob = _blobs.Count;
                _blobs.Add(bytes);
                offset = 0;
                size = bytes.Length;
            }
            else
            {
                offset = _spanOffset;
                size = _spanLen;
            }

            _spanOffset = -1;
            _spanLen = 0;
            if (size <= 0)
                continue;
            var peek = blob >= 0 ? _blobs[blob] : _io.ReadAt(offset, Math.Min(size, 8));
            if (IsHeader(peek))
            {
                header = true;
                packet = default;
                return true;
            }

            var raw = OpusPacketInfo.GetNumSamples(blob >= 0 ? _blobs[blob] : _io.ReadAt(offset, size), Rate);
            if (raw <= 0)
                continue;
            var skip = Math.Min(_preSkipLeft, raw);
            _preSkipLeft -= skip;
            var audible = raw - skip;
            packet = new Packet(offset, blob >= 0 ? 0 : size, _indexedAudible, audible, skip, 0, blob);
            _packets.Add(packet);
            _indexedAudible += audible;
            return true;
        }
    }

    private bool ReadOggPage()
    {
        if (_spanOffset >= 0)
        {
            _openBlob ??= new List<byte>(_spanLen + 255);
            if (_spanLen > 0)
                _openBlob.AddRange(_io.ReadAt(_spanOffset, _spanLen));
            _spanOffset = -1;
            _spanLen = 0;
        }

        if (_io.Position + 27 > _io.Limit)
            return false;
        var capture = _io.ReadBytes(4);
        if (capture[0] != (byte)'O' || capture[1] != (byte)'g' || capture[2] != (byte)'g' || capture[3] != (byte)'S')
            return false;
        _ = _io.ReadByte();
        _ = _io.ReadByte();
        _ = _io.ReadUInt(8);
        var serial = (int)_io.ReadUInt(4);
        _ = _io.ReadUInt(4);
        _ = _io.ReadUInt(4);
        var count = _io.ReadByte();
        var table = _io.ReadBytes(count);
        var data = _io.Position;
        var total = 0;
        foreach (var part in table)
            total += part;
        if (serial != _serial)
        {
            _io.Skip(total);
            _segCount = 0;
            _segIndex = 0;
            return _io.Position < _io.Limit;
        }

        _segs = table;
        _segCount = count;
        _segIndex = 0;
        _segPos = data;
        _io.Skip(total);
        return true;
    }

    private static bool IsHeader(byte[] data)
    {
        if (data.Length < 8)
            return false;
        var text = Encoding.ASCII.GetString(data, 0, 8);
        return text is "OpusHead" or "OpusTags";
    }

    private static Container? Probe(FileStream file)
    {
        if (file.Length < 4)
            return null;
        var io = new Io(file, file.Length);
        var magic = io.ReadBytes(4);
        io.Position = 0;
        if (magic[0] == 0x1A && magic[1] == 0x45 && magic[2] == 0xDF && magic[3] == 0xA3)
            return ProbeWebm(io);
        if (magic[0] == (byte)'O' && magic[1] == (byte)'g' && magic[2] == (byte)'g' && magic[3] == (byte)'S')
            return ProbeOgg(io);
        return null;
    }

    private static Container? ProbeWebm(Io io)
    {
        long scale = 1_000_000;
        double duration = 0;
        OpusSetup? opus = null;
        var firstCluster = -1L;
        var limit = io.Limit;
        while (io.Position + 2 <= io.Limit)
        {
            if (!io.TryElement(io.Limit, out var element))
                break;
            if (element.Id == IdEbml)
            {
                var end = element.End;
                while (io.Position + 2 <= end)
                {
                    if (!io.TryElement(end, out var child))
                        break;
                    if (child.Id == IdDocType)
                    {
                        var doc = io.ReadAscii(child.Size);
                        if (doc.Length > 0 && doc != "webm" && doc != "matroska")
                            return null;
                    }
                    else
                    {
                        io.Position = child.End;
                    }
                }

                io.Position = end;
                continue;
            }

            if (element.Id != IdSegment)
            {
                io.Position = element.End;
                continue;
            }

            limit = element.Unknown ? io.Limit : element.End;
            io.Limit = limit;
            while (io.Position + 2 <= limit)
            {
                var mark = io.Position;
                if (!io.TryElement(limit, out var child))
                    break;
                if (child.Id == IdInfo)
                {
                    var end = child.End;
                    while (io.Position + 2 <= end)
                    {
                        if (!io.TryElement(end, out var info))
                            break;
                        if (info.Id == IdTimecodeScale)
                            scale = io.ReadUInt(info.Size);
                        else if (info.Id == IdDuration)
                            duration = io.ReadFloat(info.Size);
                        else
                            io.Position = info.End;
                    }

                    io.Position = end;
                }
                else if (child.Id == IdTracks)
                {
                    var end = child.End;
                    while (io.Position + 2 <= end && opus == null)
                    {
                        if (!io.TryElement(end, out var entry))
                            break;
                        if (entry.Id == IdTrackEntry)
                            opus ??= ReadTrack(io, entry.End);
                        else
                            io.Position = entry.End;
                    }

                    io.Position = end;
                }
                else if (child.Id == IdCluster)
                {
                    if (firstCluster < 0)
                        firstCluster = mark;
                    if (opus != null)
                        break;
                    io.Position = child.Unknown ? limit : child.End;
                }
                else
                {
                    io.Position = child.End;
                }

                if (opus != null && firstCluster >= 0)
                    break;
            }

            break;
        }

        if (opus is not OpusSetup setup || firstCluster < 0)
            return null;
        var samples = duration <= 0 || scale <= 0
            ? 0
            : (long)Math.Round(duration * scale * Rate / 1_000_000_000d, MidpointRounding.AwayFromZero);
        if (samples < 0)
            samples = 0;
        return new Container(false, setup.Channels, setup.PreSkip, setup.PreRoll, setup.Gain, samples, setup.Track, firstCluster, limit, 0);
    }

    private static OpusSetup? ReadTrack(Io io, long end)
    {
        var number = 0;
        var type = 0;
        var codec = "";
        byte[]? priv = null;
        long delay = 0;
        long preroll = 0;
        while (io.Position + 2 <= end)
        {
            if (!io.TryElement(end, out var child))
                break;
            if (child.Id == IdTrackNumber)
                number = (int)io.ReadUInt(child.Size);
            else if (child.Id == IdTrackType)
                type = (int)io.ReadUInt(child.Size);
            else if (child.Id == IdCodecId)
                codec = io.ReadAscii(child.Size);
            else if (child.Id == IdCodecPrivate)
                priv = io.ReadBytes(child.Size);
            else if (child.Id == IdCodecDelay)
                delay = io.ReadUInt(child.Size);
            else if (child.Id == IdSeekPreRoll)
                preroll = io.ReadUInt(child.Size);
            else
                io.Position = child.End;
        }

        io.Position = end;
        if (type != 2 || codec != "A_OPUS" || priv == null)
            return null;
        return OpusSetup.From(number <= 0 ? 1 : number, priv, delay, preroll);
    }

    private static Container? ProbeOgg(Io io)
    {
        if (!TryOggPage(io, out var serial, out var packet))
            return null;
        if (packet.Length < 19 || Encoding.ASCII.GetString(packet, 0, 8) != "OpusHead")
            return null;
        var setup = OpusSetup.From(1, packet, 0, 0);
        var samples = LastGranule(io, serial) - setup.PreSkip;
        if (samples < 0)
            samples = 0;
        return new Container(true, setup.Channels, setup.PreSkip, setup.PreRoll, setup.Gain, samples, 1, 0, io.Stream.Length, serial);
    }

    private static bool TryOggPage(Io io, out int serial, out byte[] packet)
    {
        serial = 0;
        packet = [];
        if (io.Position + 27 > io.Limit)
            return false;
        var capture = io.ReadBytes(4);
        if (capture[0] != (byte)'O' || capture[1] != (byte)'g' || capture[2] != (byte)'g' || capture[3] != (byte)'S')
            return false;
        _ = io.ReadByte();
        _ = io.ReadByte();
        _ = io.ReadUInt(8);
        serial = (int)io.ReadUInt(4);
        _ = io.ReadUInt(4);
        _ = io.ReadUInt(4);
        var count = io.ReadByte();
        var table = io.ReadBytes(count);
        var data = new List<byte>();
        foreach (var part in table)
        {
            var piece = io.ReadBytes(part);
            data.AddRange(piece);
            if (part < 255)
                break;
        }

        var rest = 0;
        var seenEnd = false;
        foreach (var part in table)
        {
            if (!seenEnd)
            {
                if (part < 255)
                    seenEnd = true;
                continue;
            }

            rest += part;
        }

        if (rest > 0)
            io.Skip(rest);
        packet = data.ToArray();
        return packet.Length > 0;
    }

    private static long LastGranule(Io io, int serial)
    {
        var tail = (int)Math.Min(io.Stream.Length, 128 * 1024);
        if (tail < 27)
            return 0;
        var start = io.Stream.Length - tail;
        io.Stream.Position = start;
        var buf = new byte[tail];
        io.Stream.ReadExactly(buf, 0, tail);
        for (var i = buf.Length - 27; i >= 0; i--)
        {
            if (buf[i] != (byte)'O' || buf[i + 1] != (byte)'g' || buf[i + 2] != (byte)'g' || buf[i + 3] != (byte)'S')
                continue;
            if (buf[i + 4] != 0)
                continue;
            var pageSerial = BinaryPrimitives.ReadInt32LittleEndian(buf.AsSpan(i + 14));
            if (pageSerial != serial)
                continue;
            var granule = BinaryPrimitives.ReadInt64LittleEndian(buf.AsSpan(i + 6));
            if (granule >= 0)
                return granule;
        }

        return 0;
    }

    private static bool SplitFrames(byte[] payload, int track, out List<Frame> frames)
    {
        frames = [];
        var cursor = 0;
        if (!TryVint(payload, ref cursor, out var number))
            return false;
        if (cursor + 3 > payload.Length)
            return false;
        cursor += 2;
        var flags = payload[cursor++];
        if (number != track)
            return true;
        var lacing = (flags >> 1) & 3;
        if (lacing == 0)
        {
            frames.Add(new Frame(cursor, payload.Length - cursor));
            return true;
        }

        if (cursor >= payload.Length)
            return false;
        var count = payload[cursor++] + 1;
        if (count <= 0)
            return false;
        var sizes = new int[count];
        if (lacing == 2)
        {
            var size = (payload.Length - cursor) / count;
            for (var i = 0; i < count; i++)
                sizes[i] = size;
        }
        else if (lacing == 1)
        {
            var used = 0;
            for (var i = 0; i < count - 1; i++)
            {
                var size = 0;
                byte part;
                do
                {
                    if (cursor >= payload.Length)
                        return false;
                    part = payload[cursor++];
                    size += part;
                }
                while (part == 255);
                sizes[i] = size;
                used += size;
            }

            sizes[count - 1] = payload.Length - cursor - used;
        }
        else
        {
            if (!TryLaceSize(payload, ref cursor, false, out var first))
                return false;
            sizes[0] = (int)first;
            for (var i = 1; i < count; i++)
            {
                if (!TryLaceSize(payload, ref cursor, true, out var delta))
                    return false;
                sizes[i] = sizes[i - 1] + (int)delta;
            }
        }

        foreach (var size in sizes)
        {
            if (size < 0 || cursor > payload.Length - size)
                return false;
            frames.Add(new Frame(cursor, size));
            cursor += size;
        }

        return true;
    }

    private static bool TryLaceSize(byte[] data, ref int cursor, bool delta, out long value)
    {
        value = 0;
        if (cursor >= data.Length)
            return false;
        var first = data[cursor++];
        var mask = 0x80;
        var width = 1;
        while (width < 8 && (first & mask) == 0)
        {
            mask >>= 1;
            width++;
        }

        if ((first & mask) == 0)
            return false;
        long raw = first & (mask - 1);
        for (var i = 1; i < width; i++)
        {
            if (cursor >= data.Length)
                return false;
            raw = (raw << 8) | data[cursor++];
        }

        if (!delta)
        {
            value = raw;
            return true;
        }

        var bias = (1L << (width * 7 - 1)) - 1;
        value = raw - bias;
        return true;
    }

    private static bool TryVint(byte[] data, ref int cursor, out int value)
    {
        value = 0;
        if (cursor >= data.Length)
            return false;
        var first = data[cursor++];
        var mask = 0x80;
        var width = 1;
        while (width <= 8 && (first & mask) == 0)
        {
            mask >>= 1;
            width++;
        }

        if (width > 8 || (first & mask) == 0)
            return false;
        var raw = first & (mask - 1);
        for (var i = 1; i < width; i++)
        {
            if (cursor >= data.Length)
                return false;
            raw = (raw << 8) | data[cursor++];
        }

        value = raw;
        return true;
    }

    private static bool IsSegmentLevel(uint id) => id is IdSeekHead or IdInfo or IdTracks or IdCues or IdCluster or IdTags or IdChapters or IdAttachments;

    private readonly record struct Frame(int Offset, int Length);

    private readonly record struct Packet(
        long Offset,
        int Length,
        long Origin,
        int Audible,
        int Head,
        int Tail,
        int Blob);

    private readonly record struct OpusSetup(int Track, int Channels, int PreSkip, int PreRoll, float Gain)
    {
        public static OpusSetup From(int track, byte[] head, long delayNs, long prerollNs)
        {
            if (head.Length < 19 || Encoding.ASCII.GetString(head, 0, 8) != "OpusHead" || head[8] != 1)
                throw new InvalidDataException("Opus 头损坏");
            var channels = head[9];
            var family = head[18];
            if (channels is < 1 or > 2 || family != 0)
                throw new InvalidDataException("这个 Opus 声道布局还不支持");
            var preskip = (int)BinaryPrimitives.ReadUInt16LittleEndian(head.AsSpan(10));
            var delay = delayNs <= 0 ? 0 : (int)(delayNs * Rate / 1_000_000_000L);
            if (delay > preskip)
                preskip = delay;
            var gainQ = BinaryPrimitives.ReadInt16LittleEndian(head.AsSpan(16));
            var gain = gainQ == 0 ? 1f : MathF.Pow(10f, gainQ / 5120f);
            var preroll = prerollNs > 0 ? (int)(prerollNs * Rate / 1_000_000_000L) : 3840;
            if (preroll <= 0)
                preroll = 3840;
            return new OpusSetup(track, channels, preskip, preroll, gain);
        }
    }

    private readonly record struct Container(
        bool Ogg,
        int Channels,
        int PreSkip,
        int PreRoll,
        float Gain,
        long HeaderSamples,
        int Track,
        long Start,
        long Limit,
        int Serial);

    private readonly record struct Element(uint Id, long Size, long Start, long Body, long End, bool Unknown);

    private sealed class Io
    {
        public Io(FileStream stream, long limit)
        {
            Stream = stream;
            Limit = limit;
        }

        public FileStream Stream { get; }
        public long Position { get; set; }
        public long Limit { get; set; }

        public bool TryElement(long parentEnd, out Element element)
        {
            element = default;
            try
            {
                return TryElementCore(parentEnd, out element);
            }
            catch (EndOfStreamException)
            {
                return false;
            }
        }

        private bool TryElementCore(long parentEnd, out Element element)
        {
            element = default;
            if (Position >= parentEnd || Position >= Limit || parentEnd - Position < 2)
                return false;
            var start = Position;
            if (!TryId(out var id) || !TrySize(out var size, out var unknown))
                return false;
            var body = Position;
            var end = unknown ? parentEnd : body + size;
            if (!unknown && (end < body || end > Limit))
                throw new InvalidDataException("Opus 文件不完整");
            element = new Element(id, unknown ? end - body : size, start, body, end, unknown);
            return true;
        }

        public byte[] ReadAt(long offset, int count)
        {
            var saved = Position;
            Position = offset;
            var data = ReadBytes(count);
            Position = saved;
            return data;
        }

        public byte[] ReadBytes(long count)
        {
            if (count < 0 || count > 4_000_000)
                throw new InvalidDataException("Opus 文件损坏");
            var data = new byte[count];
            ReadExact(data);
            return data;
        }

        public void ReadExact(Span<byte> dest)
        {
            Place();
            Stream.ReadExactly(dest);
            Position += dest.Length;
        }

        public int ReadByte()
        {
            Place();
            var value = Stream.ReadByte();
            if (value < 0)
                throw new EndOfStreamException();
            Position++;
            return value;
        }

        public long ReadUInt(long size)
        {
            if (size <= 0)
                return 0;
            if (size > 8)
            {
                Skip(size);
                return 0;
            }

            long value = 0;
            for (var i = 0; i < size; i++)
                value = (value << 8) | (byte)ReadByte();
            return value;
        }

        public long ReadInt(long size)
        {
            var value = ReadUInt(size);
            if (size is <= 0 or >= 8)
                return value;
            var bits = (int)size * 8;
            if ((value & (1L << (bits - 1))) != 0)
                value -= 1L << bits;
            return value;
        }

        public double ReadFloat(long size)
        {
            if (size is not (4 or 8))
            {
                Skip(size);
                return 0;
            }

            var data = ReadBytes(size);
            if (BitConverter.IsLittleEndian)
                Array.Reverse(data);
            return size == 4 ? BitConverter.ToSingle(data, 0) : BitConverter.ToDouble(data, 0);
        }

        public string ReadAscii(long size)
        {
            if (size <= 0 || size > 4096)
            {
                Skip(Math.Max(0, size));
                return "";
            }

            return Encoding.ASCII.GetString(ReadBytes(size)).TrimEnd('\0').Trim();
        }

        public void Skip(long count)
        {
            if (count < 0)
                throw new InvalidDataException("Opus 文件损坏");
            Position += count;
        }

        private bool TryId(out uint id)
        {
            id = 0;
            var first = ReadByte();
            var width = first switch
            {
                >= 0x80 => 1,
                >= 0x40 => 2,
                >= 0x20 => 3,
                >= 0x10 => 4,
                _ => 0,
            };
            if (width == 0)
                throw new InvalidDataException("Opus 文件损坏");
            uint value = (uint)first;
            for (var i = 1; i < width; i++)
                value = (value << 8) | (uint)ReadByte();
            id = value;
            return true;
        }

        private bool TrySize(out long size, out bool unknown)
        {
            size = 0;
            unknown = false;
            var first = ReadByte();
            var mask = 0x80;
            var width = 1;
            while (width <= 8 && (first & mask) == 0)
            {
                mask >>= 1;
                width++;
            }

            if (width > 8 || (first & mask) == 0)
                throw new InvalidDataException("Opus 文件损坏");
            long value = first & (mask - 1);
            for (var i = 1; i < width; i++)
                value = (value << 8) | (byte)ReadByte();
            var allOnes = (1L << (7 * width)) - 1;
            unknown = value == allOnes;
            size = unknown ? 0 : value;
            return true;
        }

        private void Place()
        {
            if (Stream.Position != Position)
                Stream.Position = Position;
        }
    }
}
