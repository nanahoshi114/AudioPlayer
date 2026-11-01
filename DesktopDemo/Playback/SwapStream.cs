using NAudio.Wave;

namespace PlayerDemo.Playback;

public sealed class SwapStream : WaveStream
{
    private readonly object _gate = new();
    private WaveStream _inner;
    private bool _owns = true;

    public SwapStream(WaveStream inner)
    {
        _inner = inner;
        WaveFormat = inner.WaveFormat;
    }

    public override WaveFormat WaveFormat { get; }

    public override long Length
    {
        get
        {
            lock (_gate)
                return _inner.Length;
        }
    }

    public override long Position
    {
        get
        {
            lock (_gate)
                return _inner.Position;
        }
        set
        {
            lock (_gate)
                _inner.Position = value;
        }
    }

    public void Replace(WaveStream next)
    {
        if (next.WaveFormat.SampleRate != WaveFormat.SampleRate
            || next.WaveFormat.BitsPerSample != WaveFormat.BitsPerSample
            || next.WaveFormat.Channels != WaveFormat.Channels
            || next.WaveFormat.Encoding != WaveFormat.Encoding)
        {
            throw new InvalidOperationException("送出格式和已打开的设备不一致。");
        }

        lock (_gate)
        {
            var previous = _inner;
            _inner = next;
            previous.Dispose();
        }
    }

    public WaveStream Release()
    {
        lock (_gate)
        {
            _owns = false;
            return _inner;
        }
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        lock (_gate)
            return _inner.Read(buffer, offset, count);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && _owns)
        {
            lock (_gate)
                _inner.Dispose();
        }

        base.Dispose(disposing);
    }
}
