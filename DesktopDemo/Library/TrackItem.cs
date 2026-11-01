using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Media.Imaging;
using PlayerDemo.Subtitles;

namespace PlayerDemo.Library;

public sealed class TrackItem : INotifyPropertyChanged
{
    private bool _isCurrent;
    private int _order;

    public required string FilePath { get; init; }
    public required string Title { get; init; }
    public string Artist { get; init; } = "";
    public string Album { get; init; } = "";
    public int TrackNumber { get; init; }
    public TimeSpan Duration { get; init; }
    public int SampleRate { get; init; }
    public int BitsPerSample { get; init; }
    public bool IsFloat { get; init; }
    public int Channels { get; init; }
    public string Codec { get; init; } = "";
    public BitmapSource? Art { get; set; }
    public byte[]? ArtBytes { get; init; }
    public string? CoverPath { get; init; }
    public List<SubtitleFile> Subtitles { get; init; } = [];
    public string Fingerprint { get; init; } = "";

    private bool _isDuplicate;

    public bool IsDuplicate
    {
        get => _isDuplicate;
        set
        {
            if (_isDuplicate == value)
                return;
            _isDuplicate = value;
            OnPropertyChanged();
        }
    }

    private int _subtitleIndex = -1;

    public int SubtitleIndex
    {
        get => _subtitleIndex;
        set
        {
            if (_subtitleIndex == value)
                return;
            _subtitleIndex = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(SubtitleMark));
        }
    }

    public string SubtitleMark
    {
        get
        {
            if (Subtitles.Count == 0)
                return "";
            if (SubtitleIndex < 0 || SubtitleIndex >= Subtitles.Count)
                return "关";
            return Subtitles[SubtitleIndex].Kind;
        }
    }

    public SubtitleFile? SelectedSubtitle =>
        SubtitleIndex >= 0 && SubtitleIndex < Subtitles.Count ? Subtitles[SubtitleIndex] : null;

    public void SelectDefault() => SubtitleIndex = Subtitles.Count > 0 ? 0 : -1;

    public void AddSubtitle(SubtitleFile file)
    {
        var existing = Subtitles.FindIndex(item => item.FullPath.Equals(file.FullPath, StringComparison.OrdinalIgnoreCase));
        if (existing >= 0)
        {
            SubtitleIndex = existing;
            return;
        }

        Subtitles.Add(file);
        SubtitleIndex = Subtitles.Count - 1;
    }

    public string DurationText => FormatTime(Duration);

    public string FileFormatText
    {
        get
        {
            var rate = SampleRate / 1000.0;
            var channels = Channels switch
            {
                1 => "单声道",
                2 => "立体声",
                _ => $"{Channels} 声道",
            };
            var codec = string.IsNullOrEmpty(Codec) ? "" : $"{Codec} · ";
            var depth = IsFloat ? $"{BitsPerSample} bit float" : $"{BitsPerSample} bit";
            return $"文件 {codec}{rate:0.###} kHz · {depth} · {channels}";
        }
    }

    public int Order
    {
        get => _order;
        set
        {
            if (_order == value)
                return;
            _order = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(OrderText));
        }
    }

    public string OrderText => _order > 0 ? _order.ToString() : "";

    public bool IsCurrent
    {
        get => _isCurrent;
        set
        {
            if (_isCurrent == value)
                return;
            _isCurrent = value;
            OnPropertyChanged();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public static string FormatTime(TimeSpan time)
    {
        if (time < TimeSpan.Zero)
            time = TimeSpan.Zero;
        return time.TotalHours >= 1
            ? $"{(int)time.TotalHours}:{time.Minutes:00}:{time.Seconds:00}"
            : $"{(int)time.TotalMinutes}:{time.Seconds:00}";
    }

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
