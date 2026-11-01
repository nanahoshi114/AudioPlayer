using System.Buffers;
using NAudio.Wave;

namespace PlayerDemo.Playback;

/// <summary>
/// 16 bit 样本放到 24 bit 的高位，低 8 位补 0。采样率不变。
/// </summary>
public sealed class Pad16To24Stream : WaveStream
{
    private readonly WaveStream _source;

    public Pad16To24Stream(WaveStream source)
    {
        if (source.WaveFormat.BitsPerSample != 16)
            throw new InvalidOperationException("只能把 16 bit PCM 补成 24 bit。");
        _source = source;
        WaveFormat = new WaveFormat(source.WaveFormat.SampleRate, 24, source.WaveFormat.Channels);
    }

    public override WaveFormat WaveFormat { get; }

    public override long Length => FrameCount(_source.Length, _source.WaveFormat.BlockAlign) * WaveFormat.BlockAlign;

    public override long Position
    {
        get => FrameCount(_source.Position, _source.WaveFormat.BlockAlign) * WaveFormat.BlockAlign;
        set => _source.Position = FrameCount(value, WaveFormat.BlockAlign) * _source.WaveFormat.BlockAlign;
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var frames = count / WaveFormat.BlockAlign;
        if (frames <= 0)
            return 0;

        var sourceBytes = frames * _source.WaveFormat.BlockAlign;
        var rented = ArrayPool<byte>.Shared.Rent(sourceBytes);
        try
        {
            var read = _source.Read(rented, 0, sourceBytes);
            var gotFrames = read / _source.WaveFormat.BlockAlign;
            var channels = WaveFormat.Channels;
            var destination = offset;
            for (var i = 0; i < gotFrames * channels; i++)
            {
                var sample = BitConverter.ToInt16(rented, i * 2);
                var value = sample << 8;
                buffer[destination++] = (byte)(value & 0xFF);
                buffer[destination++] = (byte)((value >> 8) & 0xFF);
                buffer[destination++] = (byte)((value >> 16) & 0xFF);
            }

            return gotFrames * WaveFormat.BlockAlign;
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(rented);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
            _source.Dispose();
        base.Dispose(disposing);
    }

    private static long FrameCount(long bytes, int blockAlign) =>
        blockAlign <= 0 ? 0 : bytes / blockAlign;
}
