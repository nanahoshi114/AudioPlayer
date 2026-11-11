using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;

namespace PlayerDemo.Playback;

/// <summary>
/// 选中的 DLNA 设备是输出口。本机不开声卡，只把当前文件放到局域网里，让对方来拉。
/// </summary>
public sealed class DlnaOutput : IDisposable
{
    private readonly SynchronizationContext? _ui = SynchronizationContext.Current;
    private readonly HttpClient _http;
    private readonly object _gate = new();
    private readonly Dictionary<string, HashSet<string>> _sinks = new(StringComparer.OrdinalIgnoreCase);
    private DlnaFileServer? _server;
    private DlnaRenderer? _renderer;
    private readonly Thread _poll;
    private bool _disposed;
    private bool _running;
    private bool _playing;
    private bool _paused;
    private bool _seenPlaying;
    private bool _userStop;
    private bool _sent;
    private bool _ended;
    private bool _blind;
    private int _generation;
    private int _startMisses;
    private int _endPolls;
    private int _errors;
    private int _seekSerial;
    private int _volumeSerial;
    private int _stalls;
    private long _length;
    private TimeSpan _position;
    private TimeSpan _duration;
    private TimeSpan _trusted;
    private TimeSpan _reported;
    private DateTime _positionAt;
    private DateTime _holdUntil;

    public event EventHandler? Ended;
    public event EventHandler<string>? Failed;

    public DlnaOutput()
    {
        var handler = new HttpClientHandler { UseProxy = false };
        _http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(8) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("PlayerDemo/1.0 UPnP/1.1");
        _poll = new Thread(PollLoop) { IsBackground = true, Name = "DLNA Poll" };
        _poll.Start();
    }

    public bool IsPlaying
    {
        get { lock (_gate) return _playing; }
    }

    public bool IsPaused
    {
        get { lock (_gate) return _paused; }
    }

    public TimeSpan Duration
    {
        get { lock (_gate) return _duration; }
    }

    public TimeSpan Position
    {
        get
        {
            lock (_gate)
                return PlayingClock();
        }
        set => QueueSeek(value);
    }

    public bool Same(DlnaRenderer renderer)
    {
        lock (_gate)
            return _renderer != null && string.Equals(_renderer.Udn, renderer.Udn, StringComparison.OrdinalIgnoreCase);
    }

    public void Use(DlnaRenderer renderer)
    {
        DlnaRenderer? previous = null;
        lock (_gate)
        {
            if (_renderer != null && !string.Equals(_renderer.Udn, renderer.Udn, StringComparison.OrdinalIgnoreCase))
            {
                previous = _sent ? _renderer : null;
                _blind = false;
                _sent = false;
                _running = false;
                _playing = false;
                _paused = false;
                _generation++;
            }

            _renderer = renderer;
        }

        if (previous == null)
            return;
        try
        {
            _server?.Unpublish();
        }
        catch
        {
            // 旧地址已经不提供文件。
        }

        TryStop(previous, 1200);
    }

    public PlayResult Play(string path, string? title, string? artist, TimeSpan duration, TimeSpan start, DlnaSidecar? sidecar = null)
    {
        DlnaRenderer? renderer;
        bool replace;
        lock (_gate)
        {
            renderer = _renderer;
            replace = _sent;
        }

        if (renderer == null)
            return PlayResult.Fail("没有选择 DLNA 设备");
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
            return PlayResult.Fail("文件不在了");

        var mime = DlnaProtocols.MimeOf(path);
        var kind = DlnaProtocols.Choose(mime, Sinks(renderer));
        DlnaFileServer server;
        try
        {
            server = Server();
        }
        catch (Exception ex)
        {
            return PlayResult.Fail("DLNA 端口打不开：" + ex.Message);
        }

        if (!server.TryPublish(path, kind, out var publication, out var error, sidecar?.CoverJpeg, sidecar?.Lyrics))
            return PlayResult.Fail(error);

        System.Net.IPAddress local;
        try
        {
            local = LanAddress.Toward(renderer.Address);
        }
        catch (Exception ex)
        {
            server.Unpublish();
            return PlayResult.Fail("到不了这台 DLNA 设备：" + ex.Message);
        }

        var root = "http://" + local + ":" + server.Port.ToString(CultureInfo.InvariantCulture);
        var url = root + publication.Path;
        var artUrl = publication.Cover == null ? null : root + publication.CoverRoute;
        var lyricsUrl = publication.Lyrics == null ? null : root + publication.LyricsRoute;
        var converted = kind != DlnaServeKind.Original;
        var protocol = DlnaProtocols.ProtocolInfo(kind, mime, publication.Layout, converted);
        var metadata = DlnaSoap.Metadata(url, title, artist, protocol, publication.Length, duration, sidecar?.Album, artUrl, lyricsUrl);
        var withoutLyrics = DlnaSoap.Metadata(url, title, artist, protocol, publication.Length, duration, sidecar?.Album, artUrl, null);
        var titleOnly = DlnaSoap.Metadata(url, title, artist, protocol, publication.Length, duration);
        // 先让进度查询停下来，再 Stop。否则设备进入 STOPPED 会被当成这一首播完了。
        var generation = Begin(duration, publication.Length);
        if (replace)
            TryStop(renderer, 1200);

        try
        {
            var sentMeta = false;
            DlnaException? last = null;
            string? previousMeta = null;
            foreach (var item in new[] { metadata, withoutLyrics, titleOnly, "" })
            {
                if (item == previousMeta)
                    continue;
                previousMeta = item;
                try
                {
                    Post(renderer.AvTransport, renderer.AvType, "SetAVTransportURI", 3000,
                        ("InstanceID", "0"),
                        ("CurrentURI", url),
                        ("CurrentURIMetaData", item));
                    sentMeta = true;
                    break;
                }
                catch (DlnaException ex)
                {
                    last = ex;
                }
            }

            if (!sentMeta)
                throw last ?? new DlnaException("设备拒绝了曲目信息");

            Post(renderer.AvTransport, renderer.AvType, "Play", 3000,
                ("InstanceID", "0"),
                ("Speed", "1"));
            if (start > TimeSpan.Zero)
            {
                TrySeek(renderer, start, wait: true);
                lock (_gate)
                {
                    _position = start;
                    _positionAt = DateTime.UtcNow;
                    _holdUntil = DateTime.UtcNow.AddMilliseconds(3500);
                }
            }

            Commit(generation);
            return PlayResult.Success(Describe(kind, path, publication.Layout));
        }
        catch (Exception ex)
        {
            Cancel(generation);
            server.Unpublish();
            return PlayResult.Fail(Friendly(ex));
        }
    }

    public void Pause()
    {
        DlnaRenderer? renderer;
        TimeSpan position;
        lock (_gate)
        {
            if (!_playing)
                return;
            position = PlayingClock();
            _position = position;
            _positionAt = DateTime.UtcNow;
            _playing = false;
            _paused = true;
            renderer = _renderer;
            Interlocked.Increment(ref _seekSerial);
        }

        if (renderer == null)
            return;
        try
        {
            TrySeek(renderer, position, wait: false);
            Post(renderer.AvTransport, renderer.AvType, "Pause", 2000, ("InstanceID", "0"));
        }
        catch (Exception ex)
        {
            lock (_gate)
            {
                _playing = true;
                _paused = false;
                _positionAt = DateTime.UtcNow;
            }

            RaiseFailed(Friendly(ex));
        }
    }

    public void Resume()
    {
        DlnaRenderer? renderer;
        lock (_gate)
        {
            if (_playing || _renderer == null)
                return;
            renderer = _renderer;
            _playing = true;
            _paused = false;
            _running = true;
            _userStop = false;
            _positionAt = DateTime.UtcNow;
        }

        try
        {
            Post(renderer.AvTransport, renderer.AvType, "Play", 2000,
                ("InstanceID", "0"),
                ("Speed", "1"));
        }
        catch (Exception ex)
        {
            lock (_gate)
            {
                _playing = false;
                _paused = true;
            }

            RaiseFailed(Friendly(ex));
        }
    }

    public void Stop()
    {
        DlnaRenderer? renderer;
        lock (_gate)
        {
            renderer = _sent ? _renderer : null;
            _userStop = true;
            _running = false;
            _playing = false;
            _paused = false;
            _sent = false;
            _generation++;
        }

        try
        {
            _server?.Unpublish();
        }
        catch
        {
            // 监听已经停了。
        }

        if (renderer != null)
            TryStop(renderer, 1200);
    }

    public bool TryReadVolume(out double scalar)
    {
        scalar = 0;
        DlnaRenderer? renderer;
        lock (_gate)
            renderer = _renderer;
        if (renderer?.RenderingControl == null)
            return false;
        try
        {
            var xml = Post(renderer.RenderingControl, renderer.RenderingType ?? DlnaSoap.RenderingControl1, "GetVolume", 1500,
                ("InstanceID", "0"),
                ("Channel", "Master"));
            var text = DlnaSoap.Value(xml, "CurrentVolume");
            if (!int.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var volume))
                return false;
            scalar = Math.Clamp(volume, 0, 100) / 100.0;
            return true;
        }
        catch
        {
            return false;
        }
    }

    public void SetVolume(double scalar)
    {
        DlnaRenderer? renderer;
        lock (_gate)
            renderer = _renderer;
        if (renderer?.RenderingControl == null)
            throw new InvalidOperationException("这台设备不能调音量");
        var volume = (int)Math.Round(Math.Clamp(scalar, 0, 1) * 100);
        var serial = Interlocked.Increment(ref _volumeSerial);
        var control = renderer.RenderingControl;
        var type = renderer.RenderingType ?? DlnaSoap.RenderingControl1;
        Task.Run(() =>
        {
            Thread.Sleep(80);
            if (serial != Volatile.Read(ref _volumeSerial) || _disposed)
                return;
            try
            {
                Post(control, type, "SetVolume", 2000,
                    ("InstanceID", "0"),
                    ("Channel", "Master"),
                    ("DesiredVolume", volume.ToString(CultureInfo.InvariantCulture)));
            }
            catch (Exception ex)
            {
                if (serial == Volatile.Read(ref _volumeSerial))
                    RaiseFailed(Friendly(ex));
            }
        });
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        try
        {
            Stop();
        }
        catch
        {
            // 退出时设备可能已经不在。
        }

        try
        {
            _server?.Dispose();
        }
        catch
        {
            // 端口已经放开。
        }

        _http.Dispose();
    }

    private void QueueSeek(TimeSpan value)
    {
        if (value < TimeSpan.Zero)
            value = TimeSpan.Zero;
        DlnaRenderer? renderer;
        int generation;
        int serial;
        lock (_gate)
        {
            if (_duration > TimeSpan.Zero && value > _duration)
                value = _duration;
            _position = value;
            _positionAt = DateTime.UtcNow;
            _holdUntil = DateTime.UtcNow.AddMilliseconds(3500);
            renderer = _renderer;
            generation = _generation;
            serial = Interlocked.Increment(ref _seekSerial);
        }

        if (renderer == null || !_sent)
            return;
        Task.Run(() =>
        {
            Thread.Sleep(120);
            lock (_gate)
            {
                if (serial != _seekSerial || generation != _generation || _disposed)
                    return;
            }

            try
            {
                TrySeek(renderer, value, wait: true);
            }
            catch
            {
                // 下一次进度查询会把界面拉回设备上的位置。
            }
        });
    }

    private int Begin(TimeSpan duration, long length)
    {
        lock (_gate)
        {
            _generation++;
            _running = false;
            _playing = false;
            _paused = false;
            _seenPlaying = false;
            _userStop = false;
            _ended = false;
            _startMisses = 0;
            _endPolls = 0;
            _errors = 0;
            _stalls = 0;
            _length = length;
            _trusted = duration > TimeSpan.Zero ? duration : TimeSpan.Zero;
            _duration = _trusted;
            _reported = TimeSpan.MinValue;
            _position = TimeSpan.Zero;
            _positionAt = DateTime.UtcNow;
            _holdUntil = DateTime.UtcNow;
            return _generation;
        }
    }

    private void Commit(int generation)
    {
        lock (_gate)
        {
            if (generation != _generation)
                return;
            _sent = true;
            _running = true;
            _playing = true;
            _paused = false;
            _positionAt = DateTime.UtcNow;
        }
    }

    private void Cancel(int generation)
    {
        lock (_gate)
        {
            if (generation != _generation)
                return;
            _sent = false;
            _running = false;
            _playing = false;
            _paused = false;
        }
    }

    private HashSet<string> Sinks(DlnaRenderer renderer)
    {
        lock (_gate)
        {
            if (_sinks.TryGetValue(renderer.Udn, out var cached))
                return cached;
        }

        if (renderer.ConnectionManager == null)
            return new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            var xml = Post(renderer.ConnectionManager, renderer.ConnectionType ?? DlnaSoap.ConnectionManager1, "GetProtocolInfo", 2500);
            var sink = DlnaSoap.Value(xml, "Sink") ?? "";
            var set = DlnaProtocols.SinkMimes(sink);
            lock (_gate)
                _sinks[renderer.Udn] = set;
            return set;
        }
        catch
        {
            return new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        }
    }

    private DlnaFileServer Server() => _server ??= new DlnaFileServer();

    private bool TrySeek(DlnaRenderer renderer, TimeSpan value, bool wait)
    {
        if (PostSeek(renderer, "REL_TIME", DlnaSoap.FormatTime(value)))
            return true;
        if (wait)
        {
            Thread.Sleep(450);
            if (PostSeek(renderer, "REL_TIME", DlnaSoap.FormatTime(value)))
                return true;
        }

        long length;
        TimeSpan duration;
        lock (_gate)
        {
            length = _length;
            duration = _duration > TimeSpan.Zero ? _duration : _trusted;
        }

        var bytes = ByteTarget(value, duration, length);
        if (bytes < 0)
            return false;
        var text = bytes.ToString(CultureInfo.InvariantCulture);
        return PostSeek(renderer, "X_DLNA_REL_BYTE", text) || PostSeek(renderer, "REL_BYTE", text);
    }

    private bool PostSeek(DlnaRenderer renderer, string unit, string target)
    {
        try
        {
            Post(renderer.AvTransport, renderer.AvType, "Seek", 2000,
                ("InstanceID", "0"),
                ("Unit", unit),
                ("Target", target));
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static long ByteTarget(TimeSpan position, TimeSpan duration, long length)
    {
        if (length <= 0 || duration <= TimeSpan.Zero)
            return -1;
        if (position <= TimeSpan.Zero)
            return 0;
        var ratio = position.TotalSeconds / duration.TotalSeconds;
        if (ratio < 0)
            ratio = 0;
        if (ratio > 1)
            ratio = 1;
        var bytes = (long)(length * ratio);
        if (bytes >= length)
            bytes = length - 1;
        return bytes;
    }

    private void TryStop(DlnaRenderer renderer, int timeoutMs)
    {
        try
        {
            Post(renderer.AvTransport, renderer.AvType, "Stop", timeoutMs, ("InstanceID", "0"));
        }
        catch
        {
            // 本来就没在播。
        }
    }

    private string Post(Uri control, string service, string action, int timeoutMs, params (string Name, string Value)[] fields)
    {
        var xml = DlnaSoap.Envelope(service, action, fields);
        using var request = new HttpRequestMessage(HttpMethod.Post, control);
        var content = new ByteArrayContent(Encoding.UTF8.GetBytes(xml));
        content.Headers.TryAddWithoutValidation("Content-Type", "text/xml; charset=\"utf-8\"");
        request.Content = content;
        request.Headers.ExpectContinue = false;
        request.Headers.TryAddWithoutValidation("SOAPACTION", "\"" + service + "#" + action + "\"");
        using var cts = new CancellationTokenSource(timeoutMs);
        using var response = _http.Send(request, cts.Token);
        using var stream = response.Content.ReadAsStream();
        using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true, bufferSize: 1024, leaveOpen: true);
        var body = reader.ReadToEnd();
        if (!response.IsSuccessStatusCode || DlnaSoap.IsFault(body))
            throw new DlnaException(DlnaSoap.FaultText(body) ?? "DLNA 设备返回 " + (int)response.StatusCode, DlnaSoap.FaultCode(body));
        return body;
    }

    private void PollLoop()
    {
        while (!_disposed)
        {
            Thread.Sleep(800);
            if (_disposed)
                break;
            DlnaRenderer? renderer;
            int generation;
            bool running;
            bool blind;
            lock (_gate)
            {
                running = _running;
                blind = _blind;
                renderer = _renderer;
                generation = _generation;
            }

            if (!running || renderer == null)
                continue;
            if (blind)
            {
                ApplyBlind(generation);
                continue;
            }

            try
            {
                var stateXml = Post(renderer.AvTransport, renderer.AvType, "GetTransportInfo", 2000, ("InstanceID", "0"));
                var state = DlnaSoap.Value(stateXml, "CurrentTransportState") ?? "";
                var positionXml = Post(renderer.AvTransport, renderer.AvType, "GetPositionInfo", 2000, ("InstanceID", "0"));
                ApplyPoll(generation, state, DlnaSoap.Value(positionXml, "RelTime"), DlnaSoap.Value(positionXml, "TrackDuration"));
            }
            catch (DlnaException ex) when (ex.Code is 401 or 501)
            {
                lock (_gate)
                {
                    if (generation == _generation)
                        _blind = true;
                }
            }
            catch
            {
                NoteError(generation);
            }
        }
    }

    private void ApplyPoll(int generation, string state, string? rel, string? durationText)
    {
        var end = false;
        var fail = false;
        var stallEnd = false;
        var failMessage = "";
        lock (_gate)
        {
            if (generation != _generation || !_running || _userStop)
                return;
            _errors = 0;
            if (DlnaSoap.TryTime(durationText, out var reportedDuration) && reportedDuration > TimeSpan.Zero)
            {
                var believable = _trusted <= TimeSpan.Zero
                    || reportedDuration <= _trusted + TimeSpan.FromSeconds(3)
                    || reportedDuration.TotalSeconds < _trusted.TotalSeconds * 1.25 + 5;
                if (believable)
                    _duration = reportedDuration;
            }

            if (DateTime.UtcNow >= _holdUntil && DlnaSoap.TryTime(rel, out var reported))
            {
                if (reported < TimeSpan.Zero)
                    reported = TimeSpan.Zero;
                var clock = PlayingClock();
                var stuckAtZero = reported < TimeSpan.FromMilliseconds(800) && clock > TimeSpan.FromSeconds(5);
                if (!stuckAtZero)
                {
                    _position = reported;
                    _positionAt = DateTime.UtcNow;
                }

                if (reported == _reported)
                    _stalls++;
                else
                {
                    _stalls = 0;
                    _reported = reported;
                }

                stallEnd = _stalls >= 6 && _duration > TimeSpan.FromSeconds(1) && reported >= _duration - TimeSpan.FromSeconds(6);
            }

            var token = state.Trim().ToUpperInvariant();
            if (token is "PLAYING")
            {
                _seenPlaying = true;
                _playing = true;
                _paused = false;
                _startMisses = 0;
                if (stallEnd)
                {
                    _playing = false;
                    _running = false;
                    end = !_ended;
                    _ended = true;
                }
            }
            else if (token is "PAUSED_PLAYBACK" or "PAUSED")
            {
                _seenPlaying = true;
                _playing = false;
                _paused = true;
                _startMisses = 0;
            }
            else if (token is "STOPPED" or "NO_MEDIA_PRESENT" or "COMPLETED" or "END_OF_MEDIA" or "EOS" or "STOPPED_PLAYBACK")
            {
                if (_seenPlaying)
                {
                    _playing = false;
                    _paused = false;
                    _running = false;
                    end = !_ended;
                    _ended = true;
                }
                else
                {
                    _startMisses++;
                    if (_startMisses >= 8)
                    {
                        _playing = false;
                        _running = false;
                        fail = !_ended;
                        _ended = true;
                        failMessage = "设备没有开始播放。请确认它支持这个格式，并允许播放器通过防火墙。";
                    }
                }
            }

            if (_playing && _duration > TimeSpan.Zero)
            {
                var cursor = PlayingClock();
                if (cursor >= _duration - TimeSpan.FromMilliseconds(1200))
                {
                    _endPolls++;
                    if (_endPolls >= 2)
                    {
                        _playing = false;
                        _running = false;
                        end = !_ended;
                        _ended = true;
                    }
                }
                else
                {
                    _endPolls = 0;
                }
            }
            else
            {
                _endPolls = 0;
            }
        }

        if (fail)
            RaiseFailed(failMessage);
        else if (end)
            RaiseEnded();
    }

    private void ApplyBlind(int generation)
    {
        var end = false;
        lock (_gate)
        {
            if (generation != _generation || !_running || !_playing || _duration <= TimeSpan.Zero)
                return;
            if (PlayingClock() < _duration)
                return;
            _playing = false;
            _running = false;
            end = !_ended;
            _ended = true;
        }

        if (end)
            RaiseEnded();
    }

    private void NoteError(int generation)
    {
        var fail = false;
        lock (_gate)
        {
            if (generation != _generation || !_running)
                return;
            _errors++;
            if (_errors < 4)
                return;
            _running = false;
            _playing = false;
            _paused = false;
            fail = !_ended;
            _ended = true;
        }

        if (fail)
            RaiseFailed("和 DLNA 设备断开了");
    }

    private TimeSpan PlayingClock()
    {
        if (!_playing)
            return _position;
        var value = _position + (DateTime.UtcNow - _positionAt);
        if (value < TimeSpan.Zero)
            return TimeSpan.Zero;
        if (_duration > TimeSpan.Zero && value > _duration)
            return _duration;
        return value;
    }

    private void RaiseEnded()
    {
        void Publish() => Ended?.Invoke(this, EventArgs.Empty);
        if (_ui != null)
            _ui.Post(_ => Publish(), null);
        else
            Publish();
    }

    private void RaiseFailed(string message)
    {
        void Publish() => Failed?.Invoke(this, message);
        if (_ui != null)
            _ui.Post(_ => Publish(), null);
        else
            Publish();
    }

    private static string Describe(DlnaServeKind kind, string path, PcmLayout layout)
    {
        if (kind == DlnaServeKind.Original)
            return "原样传输 " + DlnaProtocols.OriginalLabel(path);
        var how = kind == DlnaServeKind.L16 ? "LPCM" : "WAV";
        return "转成 " + (layout.SampleRate / 1000.0).ToString("0.###", CultureInfo.InvariantCulture) + " kHz 16 bit " + how;
    }

    private static string Friendly(Exception ex)
    {
        if (ex is DlnaException)
            return ex.Message;
        if (ex is TaskCanceledException or OperationCanceledException or HttpRequestException or SocketException or IOException or TimeoutException)
            return "连不上这台 DLNA 设备";
        var message = ex.Message.Trim();
        return message.Length == 0 ? "DLNA 设备拒绝了播放" : message;
    }
}
