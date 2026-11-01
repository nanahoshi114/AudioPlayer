using NAudio.CoreAudioApi;
using NAudio.Wave;
using PlayerDemo.Library;

namespace PlayerDemo.Playback;

public readonly record struct PlayResult(bool Ok, string Message)
{
    public static PlayResult Success(string message) => new(true, message);
    public static PlayResult Fail(string message) => new(false, message);
}

/// <summary>
/// 先按文件自己的采样率送进 WASAPI。设备不接受时，再重采样到这块声卡能开的最高采样率。
/// </summary>
public sealed class ExclusivePlayer : IDisposable
{
    private readonly SynchronizationContext? _ui = SynchronizationContext.Current;
    private MMDeviceEnumerator? _devices;
    private MMDevice? _device;
    private string? _deviceId;
    private bool _exclusive;
    private WasapiOut? _output;
    private SwapStream? _swap;
    private WaveFormat? _deviceFormat;
    private OutputAdjust _adjust;
    private bool _stopRequested;
    private string _outputText = "";
    private string _resampleNote = "";
    private string? _formatsKey;
    private List<WaveFormat>? _formats;

    public event EventHandler? Ended;
    public event EventHandler<string>? Failed;

    public bool IsPlaying => _output?.PlaybackState == PlaybackState.Playing;
    public bool IsPaused => _output?.PlaybackState == PlaybackState.Paused;
    public string OutputText => _outputText;

    public TimeSpan Position
    {
        get => _swap?.CurrentTime ?? TimeSpan.Zero;
        set
        {
            if (_swap is not { CanSeek: true } stream)
                return;
            if (value < TimeSpan.Zero)
                value = TimeSpan.Zero;
            var total = stream.TotalTime;
            if (total > TimeSpan.Zero && value > total)
                value = total;
            stream.CurrentTime = value;
        }
    }

    public TimeSpan Duration => _swap?.TotalTime ?? TimeSpan.Zero;

    public static IReadOnlyList<(string Id, string Name)> ListDevices()
    {
        using var devices = new MMDeviceEnumerator();
        string? defaultId = null;
        try
        {
            using var chosen = devices.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            defaultId = chosen.ID;
        }
        catch
        {
            // 没有默认设备时仍列出其余正在用的设备。
        }

        var list = new List<(string Id, string Name)>();
        foreach (var device in devices.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            using (device)
                list.Add((device.ID, device.FriendlyName));
        }

        if (defaultId != null)
        {
            var index = list.FindIndex(item => item.Id == defaultId);
            if (index > 0)
            {
                var item = list[index];
                list.RemoveAt(index);
                list.Insert(0, item);
            }
        }

        return list;
    }

    public static double ReadVolume(string? deviceId)
    {
        using var devices = new MMDeviceEnumerator();
        using var device = OpenListed(devices, deviceId);
        return device.AudioEndpointVolume.MasterVolumeLevelScalar;
    }

    public void UseDevice(string? deviceId, bool exclusive)
    {
        if (_deviceId == deviceId && _exclusive == exclusive)
            return;
        _deviceId = deviceId;
        _exclusive = exclusive;
        _formats = null;
        _formatsKey = null;
        StopOutput();
    }

    public void SetDeviceVolume(double scalar)
    {
        scalar = Math.Clamp(scalar, 0, 1);
        if (_device != null)
        {
            _device.AudioEndpointVolume.MasterVolumeLevelScalar = (float)scalar;
            return;
        }

        using var devices = new MMDeviceEnumerator();
        using var device = OpenListed(devices, _deviceId);
        device.AudioEndpointVolume.MasterVolumeLevelScalar = (float)scalar;
    }

    public PlayResult Play(string path)
    {
        WaveStream raw;
        try
        {
            raw = TrackLoader.OpenPcm(path);
        }
        catch (Exception ex)
        {
            return PlayResult.Fail("打不开这个文件：" + ex.Message);
        }

        if (!TrackLoader.IsPlayable(raw.WaveFormat))
        {
            var format = raw.WaveFormat;
            raw.Dispose();
            return PlayResult.Fail($"这个格式还不能播：{format.Encoding} {format.BitsPerSample} bit。");
        }

        raw = CleanHeader(raw);
        if (_output != null && _swap != null && CanFeed(raw))
        {
            try
            {
                _swap.Replace(WrapForDevice(raw));
                _stopRequested = false;
                if (_output.PlaybackState != PlaybackState.Playing)
                    _output.Play();
                return PlayResult.Success(_outputText);
            }
            catch (Exception ex)
            {
                raw.Dispose();
                return PlayResult.Fail(ex.Message);
            }
        }

        StopOutput();
        return TryStart(path, raw);
    }

    public void Pause()
    {
        if (_output?.PlaybackState == PlaybackState.Playing)
            _output.Pause();
    }

    public void Resume()
    {
        if (_output?.PlaybackState == PlaybackState.Paused)
            _output.Play();
    }

    public void Stop() => StopOutput();

    public void Dispose() => Stop();

    private PlayResult TryStart(string path, WaveStream raw)
    {
        var sourceRate = raw.WaveFormat.SampleRate;
        var sourceBits = raw.WaveFormat.BitsPerSample;
        var isFloat = TrackLoader.IsFloat(raw.WaveFormat);
        try
        {
            EnsureDevice();
        }
        catch (Exception ex)
        {
            raw.Dispose();
            return PlayResult.Fail("没有可用的播放设备：" + ex.Message);
        }

        if (TryDirect(raw, isFloat, sourceBits))
            return PlayResult.Success(_outputText);

        return PlayResampled(path, sourceRate, sourceBits, isFloat);
    }

    private bool TryDirect(WaveStream raw, bool isFloat, int sourceBits)
    {
        try
        {
            StartOutput(raw, OutputAdjust.None);
            return true;
        }
        catch
        {
            // 原样没送出去。同采样率再试一次位深，文件流还留着。
        }

        if (isFloat)
            return TryWrapped(raw, () => new Float32To24Stream(raw), OutputAdjust.FloatTo24);
        if (sourceBits == 16)
            return TryWrapped(raw, () => new Pad16To24Stream(raw), OutputAdjust.Pad16To24);

        raw.Dispose();
        return false;
    }

    private bool TryWrapped(WaveStream raw, Func<WaveStream> wrap, OutputAdjust adjust)
    {
        WaveStream? converted = null;
        try
        {
            raw.Position = 0;
            converted = wrap();
            StartOutput(converted, adjust);
            return true;
        }
        catch
        {
            if (converted != null)
                converted.Dispose();
            else
                raw.Dispose();
            return false;
        }
    }

    private PlayResult PlayResampled(string path, int sourceRate, int sourceBits, bool isFloat)
    {
        var depth = isFloat ? $"{sourceBits} bit float" : $"{sourceBits} bit";
        List<WaveFormat> formats;
        try
        {
            using var opened = CleanHeader(TrackLoader.OpenPcm(path));
            formats = FallbackFormats(opened.WaveFormat);
        }
        catch (Exception ex)
        {
            return PlayResult.Fail("打不开这个文件：" + ex.Message);
        }

        if (formats.Count == 0)
            return PlayResult.Fail($"设备不接受 {sourceRate} Hz · {depth}，这台设备没有可改的采样率。");

        foreach (var format in formats)
        {
            WaveStream? raw = null;
            RateConvertStream? converted = null;
            try
            {
                EnsureDevice();
                raw = CleanHeader(TrackLoader.OpenPcm(path));
                converted = new RateConvertStream(raw, format);
                raw = null;
                StartOutput(converted, OutputAdjust.Resample);
                return PlayResult.Success(_outputText);
            }
            catch
            {
                converted?.Dispose();
                raw?.Dispose();
            }
        }

        _formats = null;
        _formatsKey = null;
        return PlayResult.Fail($"设备不接受 {sourceRate} Hz · {depth}，改到最高采样率也没有打开。");
    }

    private void StartOutput(WaveStream stream, OutputAdjust adjust)
    {
        // 独占不让 WasapiOut 自己落到 44.1/48 kHz，改采样由这里决定最高采样率。
        if (_exclusive && !DeviceAccepts(stream.WaveFormat))
            throw new NotSupportedException("设备不接受这个格式");

        _swap = new SwapStream(stream);
        var mode = _exclusive ? AudioClientShareMode.Exclusive : AudioClientShareMode.Shared;
        _output = new WasapiOut(_device, mode, true, 100);
        _output.PlaybackStopped += OnStopped;
        try
        {
            _stopRequested = false;
            _output.Init(_swap);
            _output.Play();
        }
        catch
        {
            _output.PlaybackStopped -= OnStopped;
            _output.Dispose();
            _output = null;
            _swap.Release();
            _swap.Dispose();
            _swap = null;
            DropDevice();
            throw;
        }

        _adjust = adjust;
        _deviceFormat = stream.WaveFormat;
        _resampleNote = stream is RateConvertStream converted ? converted.SourceNote : "";
        _outputText = Describe(stream.WaveFormat, adjust);
    }

    private bool CanFeed(WaveStream raw)
    {
        if (_deviceFormat == null)
            return false;
        if (SameFormat(_deviceFormat, raw.WaveFormat))
            return true;
        if (raw.WaveFormat.SampleRate != _deviceFormat.SampleRate || raw.WaveFormat.Channels != _deviceFormat.Channels)
            return false;
        if (_deviceFormat.BitsPerSample != 24)
            return false;
        return _adjust switch
        {
            OutputAdjust.Pad16To24 => raw.WaveFormat.BitsPerSample == 16 && TrackLoader.IsIntegerPcm(raw.WaveFormat),
            OutputAdjust.FloatTo24 => TrackLoader.IsFloat(raw.WaveFormat),
            _ => false,
        };
    }

    private WaveStream WrapForDevice(WaveStream raw)
    {
        if (_adjust == OutputAdjust.Pad16To24 && raw.WaveFormat.BitsPerSample == 16 && _deviceFormat?.BitsPerSample == 24)
            return new Pad16To24Stream(raw);
        if (_adjust == OutputAdjust.FloatTo24 && TrackLoader.IsFloat(raw.WaveFormat) && _deviceFormat?.BitsPerSample == 24)
            return new Float32To24Stream(raw);
        return raw;
    }

    private void EnsureDevice()
    {
        if (_device != null)
            return;
        _devices = new MMDeviceEnumerator();
        _device = OpenListed(_devices, _deviceId);
    }

    private void StopOutput()
    {
        _stopRequested = true;
        if (_output != null)
        {
            _output.PlaybackStopped -= OnStopped;
            try
            {
                _output.Stop();
            }
            catch
            {
                // 设备已经断开时，停止本身会失败。
            }

            _output.Dispose();
            _output = null;
        }

        _swap?.Dispose();
        _swap = null;
        _deviceFormat = null;
        _adjust = OutputAdjust.None;
        _outputText = "";
        _resampleNote = "";
        DropDevice();
    }

    private void DropDevice()
    {
        try
        {
            _device?.Dispose();
        }
        catch
        {
            // 初始化失败后设备对象可能已经不能再关。
        }

        _device = null;
        try
        {
            _devices?.Dispose();
        }
        catch
        {
            // 同上。
        }

        _devices = null;
    }

    private static WaveStream CleanHeader(WaveStream raw)
    {
        var format = raw.WaveFormat;
        if (format.ExtraSize == 0)
            return raw;
        try
        {
            var clean = format.Encoding == WaveFormatEncoding.IeeeFloat
                ? WaveFormat.CreateIeeeFloatWaveFormat(format.SampleRate, format.Channels)
                : new WaveFormat(format.SampleRate, format.BitsPerSample, format.Channels);
            if (clean.BlockAlign != format.BlockAlign || clean.Encoding != format.Encoding)
                return raw;
            return new CleanFormatStream(raw, clean);
        }
        catch
        {
            return raw;
        }
    }

    private void OnStopped(object? sender, StoppedEventArgs args)
    {
        if (_stopRequested)
            return;

        void Publish()
        {
            if (args.Exception != null)
                Failed?.Invoke(this, args.Exception.Message);
            else
                Ended?.Invoke(this, EventArgs.Empty);
        }

        if (_ui != null)
            _ui.Post(_ => Publish(), null);
        else
            Publish();
    }

    private static bool SameFormat(WaveFormat left, WaveFormat right) =>
        left.SampleRate == right.SampleRate
        && left.BitsPerSample == right.BitsPerSample
        && left.Channels == right.Channels
        && left.Encoding == right.Encoding;

    private string Describe(WaveFormat format, OutputAdjust adjust)
    {
        var channels = format.Channels switch
        {
            1 => "单声道",
            2 => "立体声",
            _ => $"{format.Channels} 声道",
        };
        var depth = format.Encoding == WaveFormatEncoding.IeeeFloat
            ? $"{format.BitsPerSample} bit float"
            : $"{format.BitsPerSample} bit";
        var route = _exclusive ? "WASAPI Exclusive" : "WASAPI Shared";
        var text = $"{format.SampleRate / 1000.0:0.###} kHz · {depth} · {channels} · {route}";
        if (adjust == OutputAdjust.Pad16To24)
            text += " · 源 16 bit 补零";
        if (adjust == OutputAdjust.FloatTo24)
            text += " · 源 32 bit float";
        if (adjust == OutputAdjust.Resample && _resampleNote.Length > 0)
            text += " · " + _resampleNote;
        return text;
    }

    private static readonly int[] CandidateRates =
    [
        384000, 352800, 192000, 176400, 96000, 88200, 48000, 44100, 32000, 22050, 16000, 11025, 8000,
    ];

    private List<WaveFormat> FallbackFormats(WaveFormat source)
    {
        var supported = DeviceFormats();
        var result = new List<WaveFormat>();
        foreach (var channels in ChannelPreference(source.Channels))
        {
            var rates = new List<int>();
            foreach (var format in supported)
            {
                if (format.Channels == channels && !rates.Contains(format.SampleRate))
                    rates.Add(format.SampleRate);
            }

            rates.Sort((left, right) => right.CompareTo(left));
            var firstRate = true;
            foreach (var rate in rates)
            {
                var take = firstRate ? 2 : 1;
                firstRate = false;
                var picked = 0;
                foreach (var format in OrderAtRate(source, supported, channels, rate))
                {
                    if (SameFormat(format, source) || ContainsFormat(result, format))
                        continue;
                    result.Add(format);
                    if (++picked >= take)
                        break;
                }
            }

            if (result.Count > 0)
                break;
        }

        return result;
    }

    private List<WaveFormat> DeviceFormats()
    {
        var key = (_deviceId ?? "") + "|" + (_exclusive ? "exclusive" : "shared");
        if (_formatsKey == key && _formats != null)
            return _formats;

        var found = new List<WaveFormat>();
        using var devices = new MMDeviceEnumerator();
        using var device = OpenListed(devices, _deviceId);
        var client = device.AudioClient;
        var mode = _exclusive ? AudioClientShareMode.Exclusive : AudioClientShareMode.Shared;
        var rates = new List<int>(CandidateRates);
        try
        {
            var mix = client.MixFormat;
            if (!rates.Contains(mix.SampleRate))
                rates.Add(mix.SampleRate);
            if (Supports(client, mode, mix) && !ContainsFormat(found, mix))
                found.Add(mix);
        }
        catch
        {
            // 读不到系统混音格式时，仍按常见采样率问设备。
        }

        foreach (var rate in rates)
        {
            foreach (var channels in new[] { 1, 2 })
            {
                foreach (var format in Candidates(rate, channels))
                {
                    if (Supports(client, mode, format) && !ContainsFormat(found, format))
                        found.Add(format);
                }
            }
        }

        _formats = found;
        _formatsKey = key;
        return found;
    }

    private bool DeviceAccepts(WaveFormat format)
    {
        try
        {
            EnsureDevice();
            var mode = _exclusive ? AudioClientShareMode.Exclusive : AudioClientShareMode.Shared;
            return Supports(_device!.AudioClient, mode, format);
        }
        catch
        {
            return false;
        }
    }

    private static bool Supports(AudioClient client, AudioClientShareMode mode, WaveFormat format)
    {
        try
        {
            return client.IsFormatSupported(mode, format);
        }
        catch
        {
            return false;
        }
    }

    private static IEnumerable<int> ChannelPreference(int source)
    {
        yield return source;
        if (source != 2)
            yield return 2;
        if (source != 1)
            yield return 1;
    }

    private static IEnumerable<WaveFormat> Candidates(int rate, int channels)
    {
        yield return WaveFormat.CreateIeeeFloatWaveFormat(rate, channels);
        yield return new WaveFormat(rate, 32, channels);
        yield return new WaveFormat(rate, 24, channels);
        yield return new WaveFormat(rate, 16, channels);
        yield return new WaveFormatExtensible(rate, 32, channels);
        yield return new WaveFormatExtensible(rate, 24, channels);
        yield return new WaveFormatExtensible(rate, 16, channels);
    }

    private static List<WaveFormat> OrderAtRate(WaveFormat source, List<WaveFormat> supported, int channels, int rate)
    {
        var list = new List<WaveFormat>();
        foreach (var format in supported)
        {
            if (format.Channels == channels && format.SampleRate == rate)
                list.Add(format);
        }

        list.Sort((left, right) => Rank(source, right).CompareTo(Rank(source, left)));
        return list;
    }

    private static int Rank(WaveFormat source, WaveFormat format)
    {
        var rank = format.BitsPerSample;
        var sourceFloat = TrackLoader.IsFloat(source);
        var formatFloat = TrackLoader.IsFloat(format);
        if (sourceFloat && formatFloat)
            rank += 1000;
        else if (!sourceFloat && !formatFloat && format.BitsPerSample == source.BitsPerSample && format.Encoding == source.Encoding)
            rank += 1000;
        else if (!sourceFloat && !formatFloat && format.BitsPerSample == source.BitsPerSample)
            rank += 800;
        else if (!sourceFloat && !formatFloat)
            rank += 100;
        if (format.Encoding is WaveFormatEncoding.Pcm or WaveFormatEncoding.IeeeFloat)
            rank += 10;
        return rank;
    }

    private static bool ContainsFormat(List<WaveFormat> formats, WaveFormat format)
    {
        foreach (var item in formats)
        {
            if (SameFormat(item, format))
                return true;
        }

        return false;
    }

    private static MMDevice OpenListed(MMDeviceEnumerator devices, string? deviceId) =>
        string.IsNullOrEmpty(deviceId)
            ? devices.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia)
            : devices.GetDevice(deviceId);
}

enum OutputAdjust
{
    None,
    Pad16To24,
    FloatTo24,
    Resample,
}

/// <summary>
/// 去掉 WAV 头里多出来的空格式数据。采样率、位深和样本都不改。
/// </summary>
sealed class CleanFormatStream : WaveStream
{
    private readonly WaveStream _source;

    public CleanFormatStream(WaveStream source, WaveFormat format)
    {
        _source = source;
        WaveFormat = format;
    }

    public override WaveFormat WaveFormat { get; }

    public override long Length => _source.Length;

    public override long Position
    {
        get => _source.Position;
        set => _source.Position = value;
    }

    public override int Read(byte[] buffer, int offset, int count) => _source.Read(buffer, offset, count);

    protected override void Dispose(bool disposing)
    {
        if (disposing)
            _source.Dispose();
        base.Dispose(disposing);
    }
}
