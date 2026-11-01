using System.Buffers;
using NAudio.Wave;
using PlayerDemo.Library;

namespace PlayerDemo.Playback;

/// <summary>
/// 32 bit float 收成同采样率的 24 bit 整数。只在声卡不接受浮点时使用。
/// </summary>
public sealed class Float32To24Stream : WaveStream
{
    private readonly WaveStream _source;

    public Float32To24Stream(WaveStream source)
    {
        if (!TrackLoader.IsFloat(source.WaveFormat))
            throw new InvalidOperationException("只能把 32 bit float 收成 24 bit。");
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
            var channels = WaveFormat.Channels;
            var gotFrames = read / _source.WaveFormat.BlockAlign;
            var destination = offset;
            for (var i = 0; i < gotFrames * channels; i++)
            {
                var sample = BitConverter.ToSingle(rented, i * 4);
                var value = ToInt24(sample);
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

    private static int ToInt24(float sample)
    {
        if (sample >= 1f)
            return 8_388_607;
        if (sample <= -1f)
            return -8_388_608;
        return (int)Math.Round(sample * 8_388_607.0);
    }

    private static long FrameCount(long bytes, int blockAlign) =>
        blockAlign <= 0 ? 0 : bytes / blockAlign;
}
