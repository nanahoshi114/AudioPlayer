namespace PlayerDemo.Playback;

/// <summary>本机声卡和 DLNA 设备共用的输出口。界面只跟这一层说话。</summary>
public sealed class OutputSession : IDisposable
{
    public const string DlnaPrefix = "dlna:";

    private enum Kind
    {
        Local,
        Network,
        Missing,
    }

    private readonly ExclusivePlayer _local = new();
    private readonly DlnaOutput _network = new();
    private readonly DlnaDiscovery _discovery = new();
    private Kind _kind = Kind.Local;
    private string? _deviceId;
    private string? _wantedUdn;
    private bool _exclusive;

    public event EventHandler? Ended;
    public event EventHandler<string>? Failed;
    public event EventHandler? RenderersChanged;

    public string? DeviceProblem { get; private set; }

    public string MissingDlnaName => _discovery.HasSearched ? "DLNA 设备（不在网上）" : "DLNA 设备（寻找中）";

    public OutputSession()
    {
        _local.Ended += (_, _) =>
        {
            if (_kind == Kind.Local)
                Ended?.Invoke(this, EventArgs.Empty);
        };
        _local.Failed += (_, message) =>
        {
            if (_kind == Kind.Local)
                Failed?.Invoke(this, message);
        };
        _network.Ended += (_, _) =>
        {
            if (_kind == Kind.Network)
                Ended?.Invoke(this, EventArgs.Empty);
        };
        _network.Failed += (_, message) =>
        {
            if (_kind == Kind.Network)
                Failed?.Invoke(this, message);
        };
        _discovery.Changed += (_, _) => RenderersChanged?.Invoke(this, EventArgs.Empty);
    }

    public bool IsPlaying => _kind switch
    {
        Kind.Network => _network.IsPlaying,
        Kind.Local => _local.IsPlaying,
        _ => false,
    };

    public bool IsPaused => _kind switch
    {
        Kind.Network => _network.IsPaused,
        Kind.Local => _local.IsPaused,
        _ => false,
    };

    public TimeSpan Duration => _kind switch
    {
        Kind.Network => _network.Duration,
        Kind.Local => _local.Duration,
        _ => TimeSpan.Zero,
    };

    public TimeSpan Position
    {
        get => _kind switch
        {
            Kind.Network => _network.Position,
            Kind.Local => _local.Position,
            _ => TimeSpan.Zero,
        };
        set
        {
            if (_kind == Kind.Network)
                _network.Position = value;
            else if (_kind == Kind.Local)
                _local.Position = value;
        }
    }

    public static bool IsDlna(string? deviceId) =>
        deviceId != null && deviceId.StartsWith(DlnaPrefix, StringComparison.Ordinal);

    public IReadOnlyList<(string Id, string Name)> ListDevices()
    {
        var list = new List<(string Id, string Name)>();
        try
        {
            list.AddRange(ExclusivePlayer.ListDevices());
        }
        catch
        {
            // 没有本机声卡时仍然可以把声音推到 DLNA。
        }

        var renderers = _discovery.Renderers.ToList();
        var dlna = new List<(string Id, string Name)>();
        foreach (var group in renderers.GroupBy(item => item.Name, StringComparer.CurrentCultureIgnoreCase))
        {
            var many = group.Count() > 1;
            foreach (var item in group)
            {
                var name = many ? item.Name + " · " + item.Address : item.Name;
                dlna.Add((DlnaPrefix + item.Udn, name + "（DLNA）"));
            }
        }

        dlna.Sort((left, right) => string.Compare(left.Name, right.Name, StringComparison.CurrentCultureIgnoreCase));
        list.AddRange(dlna);
        return list;
    }

    public void InvalidateDevice() => _local.InvalidateDevice();

    public void UseDevice(string? deviceId, bool exclusive)
    {
        if (IsDlna(deviceId))
        {
            var udn = deviceId![DlnaPrefix.Length..];
            var found = _discovery.Find(udn);
            if (found == null)
            {
                var problem = _discovery.HasSearched ? "DLNA 设备不在网上" : "正在寻找 DLNA 设备";
                if (_kind == Kind.Missing && _wantedUdn == udn && DeviceProblem == problem)
                    return;
                if (_kind != Kind.Missing)
                {
                    _network.Stop();
                    _local.Stop();
                }

                _kind = Kind.Missing;
                _wantedUdn = udn;
                _deviceId = deviceId;
                DeviceProblem = problem;
                return;
            }

            _wantedUdn = udn;
            DeviceProblem = null;
            try
            {
                if (_kind != Kind.Network)
                    _local.Stop();
                _network.Use(found);
                _kind = Kind.Network;
                _deviceId = deviceId;
                _exclusive = exclusive;
            }
            catch (Exception ex)
            {
                _kind = Kind.Missing;
                DeviceProblem = ex.Message;
            }

            return;
        }

        if (_kind == Kind.Local && _deviceId == deviceId && _exclusive == exclusive)
            return;
        DeviceProblem = null;
        _network.Stop();
        _kind = Kind.Local;
        _deviceId = deviceId;
        _exclusive = exclusive;
        _wantedUdn = null;
        _local.UseDevice(deviceId, exclusive);
    }

    public double? ReadVolume()
    {
        if (_kind == Kind.Network)
            return _network.TryReadVolume(out var scalar) ? scalar : null;
        if (_kind != Kind.Local)
            return null;
        return ExclusivePlayer.ReadVolume(_deviceId);
    }

    public void SetDeviceVolume(double scalar)
    {
        if (_kind == Kind.Network)
        {
            _network.SetVolume(scalar);
            return;
        }

        if (_kind == Kind.Local)
        {
            _local.SetDeviceVolume(scalar);
            return;
        }

        throw new InvalidOperationException(DeviceProblem ?? "DLNA 设备不在网上");
    }

    public bool PlaysOnNetwork => _kind == Kind.Network;

    public PlayResult Play(string path, string? title = null, string? artist = null, TimeSpan duration = default, TimeSpan start = default, DlnaSidecar? sidecar = null)
    {
        if (_kind == Kind.Network)
            return _network.Play(path, title, artist, duration, start, sidecar);
        if (_kind == Kind.Missing)
            return PlayResult.Fail(DeviceProblem ?? "DLNA 设备不在网上");
        return _local.Play(path, start);
    }

    public PlayResult Seek(TimeSpan position)
    {
        if (_kind == Kind.Network)
        {
            _network.Position = position;
            return PlayResult.Success("");
        }

        if (_kind != Kind.Local)
            return PlayResult.Fail(DeviceProblem ?? "DLNA 设备不在网上");
        return _local.Seek(position);
    }

    public void Pause()
    {
        if (_kind == Kind.Network)
            _network.Pause();
        else if (_kind == Kind.Local)
            _local.Pause();
    }

    public void Resume()
    {
        if (_kind == Kind.Network)
            _network.Resume();
        else if (_kind == Kind.Local)
            _local.Resume();
    }

    public void Stop()
    {
        if (_kind == Kind.Network)
            _network.Stop();
        else if (_kind == Kind.Local)
            _local.Stop();
    }

    public void Dispose()
    {
        _discovery.Dispose();
        _network.Dispose();
        _local.Dispose();
    }
}
