using NAudio.Wave;

namespace PlayerDemo.Playback;

public sealed class SwapStream : WaveStream
{
    private readonly object _gate = new();
    private WaveStream _inner;
    private Func<WaveStream>? _reopen;
    private TimeSpan _reopenAt;
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

    public void ArmSeek(Func<WaveStream> reopen, TimeSpan start)
    {
        lock (_gate)
        {
            _reopen = reopen;
            _reopenAt = start < TimeSpan.Zero ? TimeSpan.Zero : start;
        }
    }

    public bool TryPending(out TimeSpan start)
    {
        lock (_gate)
        {
            if (_reopen == null)
            {
                start = TimeSpan.Zero;
                return false;
            }

            start = _reopenAt;
            return true;
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
            _reopen = null;
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
        {
            if (_reopen != null)
                TakeReopen();
            return _inner.Read(buffer, offset, count);
        }
    }

    private void TakeReopen()
    {
        var reopen = _reopen;
        var start = _reopenAt;
        _reopen = null;
        if (reopen == null)
            return;
        WaveStream? next = null;
        try
        {
            next = reopen();
            if (next.WaveFormat.SampleRate != WaveFormat.SampleRate
                || next.WaveFormat.BitsPerSample != WaveFormat.BitsPerSample
                || next.WaveFormat.Channels != WaveFormat.Channels
                || next.WaveFormat.Encoding != WaveFormat.Encoding)
            {
                next.Dispose();
                return;
            }

            if (start > TimeSpan.Zero && next.CanSeek)
            {
                var total = next.TotalTime;
                if (total > TimeSpan.Zero && start > total)
                    start = total;
                next.CurrentTime = start;
            }

            var previous = _inner;
            _inner = next;
            next = null;
            try
            {
                previous.Dispose();
            }
            catch (Exception)
            {
                // 旧的读取器关掉失败时，新的已经在播。
            }
        }
        catch (Exception)
        {
            next?.Dispose();
        }
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
