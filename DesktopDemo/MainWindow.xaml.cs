        using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using NAudio.Wave;
using PlayerDemo.Library;
using PlayerDemo.Playback;
using PlayerDemo.Services;
using PlayerDemo.Subtitles;
using PlayerDemo.Theme;

namespace PlayerDemo;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<TrackItem> _tracks = new();
    private List<AlbumItem> _albums = [];
    private AlbumItem? _openAlbum;
    private DateTime _openedAlbumAt;
    private readonly ExclusivePlayer _player = new();
    private readonly DispatcherTimer _clock;
    private TrackItem? _current;
    private TrackItem? _shownTrack;
    private List<SubtitleCue> _cues = [];
    private bool _suppressSubtitleEvent;
    private bool _draggingSeek;
    private bool _draggingVolume;
    private bool _volumeReady;
    private double _volumeScalar = 0.8;
    private TimeSpan _paintedPosition;
    private TimeSpan _paintedDuration;
    private TimeSpan? _pendingSeek;
    private int _skipped;
    private readonly ServiceSettings _settings = ServiceSettings.Load();
    private SubtitleFloatWindow _floatSubtitles;
    private CancellationTokenSource? _job;
    private bool _showOriginal = true;
    private bool _showDuplicates;
    private bool _listReady;
    private bool _outputReady;
    private Color _accent = Color.FromRgb(0x8A, 0x5A, 0x32);
    private string? _appliedDeviceId;
    private bool _appliedExclusive;
    private List<string>? _shuffleKeys;
    private OutputDeviceWatcher? _deviceWatcher;
    private readonly DispatcherTimer _deviceRefresh;

    public MainWindow()
    {
        InitializeComponent();
        _showOriginal = _settings.ShowOriginal;
        LanguageButton.Content = _showOriginal ? "隐藏原文" : "显示原文";
        _floatSubtitles = new SubtitleFloatWindow(_settings);
        BindFloat(_floatSubtitles);
        _floatSubtitles.SetOriginalShown(_showOriginal);
        PaintPlayMode();
        SettingsPage.Bind(_settings);
        CrawlPage.Bind(_settings);
        CrawlPage.Finished += paths => Dispatcher.Invoke(() => AddPaths(paths));
        ShowPage("player");
        ShowAlbumList();
        _listReady = true;
        _tracks.CollectionChanged += (_, _) => UpdateEmpty();

        _player.Ended += (_, _) => PlayRelative(1, fromEnd: true);
        _player.Failed += (_, message) => OutputText.Text = message;

        _clock = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        _clock.Tick += (_, _) => RefreshClock();
        _clock.Start();
        _deviceRefresh = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        _deviceRefresh.Tick += (_, _) =>
        {
            _deviceRefresh.Stop();
            RefreshDevices();
        };

        Loaded += (_, _) =>
        {
            LoadDevice();
            if (_deviceWatcher == null)
            {
                _deviceWatcher = new OutputDeviceWatcher();
                _deviceWatcher.Changed += (_, _) => Dispatcher.BeginInvoke(ScheduleDeviceRefresh);
            }

            LoadStoredLibrary();
        };
        Activated += (_, _) => ScheduleDeviceRefresh();
        SourceInitialized += (_, _) =>
        {
            AttachWindowShadow();
            if (PresentationSource.FromVisual(this) is HwndSource source)
                source.AddHook(MaxHook);
        };
    }

    public void LoadFilledSample()
    {
        var directory = Path.Combine(Path.GetTempPath(), "player-demo-sample");
        Directory.CreateDirectory(directory);
        var wav = Path.Combine(directory, "sample.wav");
        WriteSampleWav(wav);
        File.WriteAllText(Path.Combine(directory, "sample.lrc"), "[00:00.00]雨声靠近窗口\n[01:12.00]壁炉还在响\n[03:00.00]夜里安静下来\n");
        File.WriteAllText(Path.Combine(directory, "sample.ja.srt"), "1\n00:01:12,000 --> 00:01:20,000\nfireplace\n");
        SubtitleFinder.ClearCache();
        var subtitles = SubtitleFinder.ForAudio(wav);
        var art = MakeArt();
        (string Title, string Artist, string Album)[] rows =
        [
            ("雨声", "室内", "夜晚"),
            ("翻书", "桌面", "夜晚"),
            ("壁炉", "房间", "夜晚"),
            ("夜间电台", "短波", "远处"),
            ("城市远处", "窗边", "远处"),
            ("木地板", "走廊", "白天"),
            ("布料", "织物", "白天"),
            ("白噪音", "空气", "持续"),
        ];
        foreach (var row in rows)
        {
            _tracks.Add(new TrackItem
            {
                FilePath = wav,
                Title = row.Title,
                Artist = row.Artist,
                Album = row.Album,
                Duration = TimeSpan.FromSeconds(8),
                SampleRate = 44100,
                BitsPerSample = 16,
                Channels = 2,
                Codec = "WAV",
                Art = art,
                Subtitles = subtitles.Select(item => item).ToList(),
            });
            _tracks[^1].SelectDefault();
        }

        _tracks[2].IsCurrent = true;
        ShowTrack(_tracks[2]);
        RebuildLibrary();
        var album = _albums.FirstOrDefault(item => item.Tracks.Contains(_tracks[2]));
        if (album != null)
        {
            Playlist.SelectedItem = album;
            Playlist.ScrollIntoView(album);
        }
        PaintSeek(TimeSpan.FromSeconds(72), TimeSpan.FromSeconds(8 * 60));
        OutputText.Text = "44.1 kHz · 16 bit · 立体声 · WASAPI Shared";
        PlayGlyph.Visibility = Visibility.Collapsed;
        PauseGlyph.Visibility = Visibility.Visible;
        LoadCues(_tracks[2], TimeSpan.FromSeconds(72));
    }

    public void CaptureAndExit(string path)
    {
        ContentRendered += (_, _) =>
        {
            Dispatcher.BeginInvoke(() =>
            {
                UpdateLayout();
                var dpi = VisualTreeHelper.GetDpi(this);
                var width = Math.Max(1, (int)(ActualWidth * dpi.DpiScaleX));
                var height = Math.Max(1, (int)(ActualHeight * dpi.DpiScaleY));
                var bitmap = new RenderTargetBitmap(width, height, dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
                bitmap.Render(this);
                var encoder = new PngBitmapEncoder();
                encoder.Frames.Add(BitmapFrame.Create(bitmap));
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                using var stream = File.Create(path);
                encoder.Save(stream);
                Application.Current.Shutdown();
            }, DispatcherPriority.ApplicationIdle);
        };
    }

    private void LoadDevice()
    {
        if (_settings.Exclusive)
            ExclusiveBox.IsChecked = true;
        else
            SharedBox.IsChecked = true;
        RefreshDevices();
    }

    private void ScheduleDeviceRefresh()
    {
        if (!IsLoaded || _deviceWatcher == null)
            return;
        _deviceRefresh.Stop();
        _deviceRefresh.Start();
    }

    private void RefreshDevices()
    {
        var selectedId = (DeviceBox.SelectedItem as OutputDevice)?.Id ?? _settings.DeviceId;
        IReadOnlyList<(string Id, string Name)> devices;
        try
        {
            devices = ExclusivePlayer.ListDevices();
        }
        catch
        {
            SetDeviceNote("没有播放设备");
            return;
        }

        if (SameDevices(devices))
            return;

        _player.InvalidateDevice();
        _outputReady = false;
        DeviceBox.Items.Clear();
        var selected = 0;
        for (var index = 0; index < devices.Count; index++)
        {
            DeviceBox.Items.Add(new OutputDevice(devices[index].Id, devices[index].Name));
            if (!string.IsNullOrEmpty(selectedId) && devices[index].Id == selectedId)
                selected = index;
        }

        if (DeviceBox.Items.Count == 0)
        {
            SetDeviceNote("没有播放设备");
            _outputReady = true;
            if (_appliedDeviceId != null)
            {
                _appliedDeviceId = null;
                _player.Stop();
                OutputText.Text = "没有播放设备";
            }

            return;
        }

        DeviceBox.SelectedIndex = selected;
        _outputReady = true;
        var now = DeviceBox.SelectedItem as OutputDevice;
        var exclusive = ExclusiveBox.IsChecked == true;
        if (now != null && now.Id == _appliedDeviceId && exclusive == _appliedExclusive)
        {
            RefreshDeviceStatus(now.Id, exclusive);
            return;
        }

        ApplyOutput(restart: _player.IsPlaying || _player.IsPaused);
    }

    private bool SameDevices(IReadOnlyList<(string Id, string Name)> devices)
    {
        if (devices.Count != DeviceBox.Items.Count)
            return false;
        for (var index = 0; index < devices.Count; index++)
        {
            if (DeviceBox.Items[index] is not OutputDevice item)
                return false;
            if (item.Id != devices[index].Id || item.Name != devices[index].Name)
                return false;
        }

        return true;
    }

    private void RefreshDeviceStatus(string deviceId, bool exclusive)
    {
        if (_draggingVolume)
            return;

        try
        {
            _volumeReady = false;
            _volumeScalar = ExclusivePlayer.ReadVolume(deviceId);
            PaintVolume();
            _volumeReady = true;
            SetDeviceNote(OutputModeText(exclusive));
        }
        catch
        {
            _volumeReady = true;
            SetDeviceNote("读不了这台设备");
        }
    }

    private static string OutputModeText(bool exclusive) =>
        exclusive ? "这台声卡只给播放器" : "和其他程序一起出声";

    private void SetDeviceNote(string text)
    {
        var error = text is "没有播放设备" or "读不了这台设备";
        DeviceText.Text = error ? text : "";
        DeviceText.Visibility = error ? Visibility.Visible : Visibility.Collapsed;
    }

    private void DeviceBox_SelectionChanged(object sender, SelectionChangedEventArgs e) => ApplyOutput(restart: true);

    private void OutputMode_Checked(object sender, RoutedEventArgs e)
    {
        if (!_outputReady || sender is not RadioButton { IsChecked: true })
            return;
        ApplyOutput(restart: true);
    }

    private void ApplyOutput(bool restart)
    {
        if (!_outputReady)
            return;
        var device = DeviceBox.SelectedItem as OutputDevice;
        if (device == null)
        {
            if (DeviceBox.Items.Count == 0)
                SetDeviceNote("没有播放设备");
            return;
        }

        var exclusive = ExclusiveBox.IsChecked == true;
        var playing = restart && _player.IsPlaying;
        var paused = restart && _player.IsPaused;
        var position = playing || paused ? _player.Position : TimeSpan.Zero;
        var track = _current;
        _settings.DeviceId = device.Id;
        _settings.Exclusive = exclusive;
        _settings.Save();
        _appliedDeviceId = device.Id;
        _appliedExclusive = exclusive;
        _player.UseDevice(device.Id, exclusive);
        try
        {
            _volumeReady = false;
            _volumeScalar = ExclusivePlayer.ReadVolume(device.Id);
            PaintVolume();
            _volumeReady = true;
            SetDeviceNote(OutputModeText(exclusive));
        }
        catch
        {
            _volumeReady = true;
            SetDeviceNote("读不了这台设备");
        }

        if ((playing || paused) && track != null)
        {
            PlayTrack(track);
            if (_player.IsPlaying || _player.IsPaused)
                _player.Position = position;
            if (paused)
                _player.Pause();
            return;
        }

        OutputText.Text = StatusLine(OutputModeText(exclusive));
    }

    private string StatusLine(string message)
    {
        var name = (DeviceBox.SelectedItem as OutputDevice)?.Name;
        if (string.IsNullOrWhiteSpace(name) || message.Contains(name, StringComparison.Ordinal))
            return message;
        return message + " · " + name;
    }

    private void RefreshDuplicates()
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var track in _tracks)
            track.IsDuplicate = track.Fingerprint.Length > 0 && !seen.Add(track.Fingerprint);
        RebuildLibrary();
    }

    private void RebuildLibrary()
    {
        var openKey = _openAlbum?.Key;
        _albums = AlbumShelf.Group(_tracks, _showDuplicates);
        if (openKey != null)
        {
            var still = _albums.FirstOrDefault(album => album.Key == openKey);
            if (still != null)
            {
                ShowTracks(still);
                return;
            }
        }

        _openAlbum = null;
        ShowAlbumList();
    }

    private void ShowAlbumList()
    {
        _openAlbum = null;
        BackButton.Visibility = Visibility.Collapsed;
        ListTitle.Text = "专辑";
        Playlist.ItemsSource = _albums;
        if (_current != null)
        {
            var album = AlbumOf(_current);
            if (album != null)
            {
                Playlist.SelectedItem = album;
                Playlist.ScrollIntoView(album);
            }
        }

        UpdateEmpty();
    }

    private void ShowTracks(AlbumItem album)
    {
        _openAlbum = album;
        BackButton.Visibility = Visibility.Visible;
        ListTitle.Text = album.Title;
        AlbumShelf.Number(album.Tracks);
        Playlist.ItemsSource = album.Tracks;
        var current = album.Tracks.FirstOrDefault(track => track.IsCurrent);
        if (current != null)
        {
            Playlist.SelectedItem = current;
            Playlist.ScrollIntoView(current);
        }

        UpdateEmpty();
    }

    private void OpenAlbum(AlbumItem album)
    {
        _openedAlbumAt = DateTime.UtcNow;
        ShowTracks(album);
    }

    private void Back_Click(object sender, RoutedEventArgs e) => ShowAlbumList();

    private AlbumItem? AlbumOf(TrackItem track) =>
        _albums.FirstOrDefault(album => album.Tracks.Contains(track));

    private void UpdateEmpty()
    {
        var visible = _openAlbum != null ? _openAlbum.Tracks.Count > 0 : _albums.Count > 0;
        if (_tracks.Count == 0)
            EmptyHint.Text = "把音频拖到窗口里";
        else if (!visible)
            EmptyHint.Text = "重复的已藏起";
        EmptyHint.Visibility = visible ? Visibility.Collapsed : Visibility.Visible;
    }

    private void ShowDuplicates_Changed(object sender, RoutedEventArgs e)
    {
        if (!_listReady)
            return;
        _showDuplicates = ShowDuplicates.IsChecked == true;
        RebuildLibrary();
    }

    private void PlayNav_Click(object sender, RoutedEventArgs e) => ShowPage("player");

    private void ShowPage(string page)
    {
        PlayerPage.Visibility = page == "player" ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == "settings" ? Visibility.Visible : Visibility.Collapsed;
        CrawlPage.Visibility = page == "crawl" ? Visibility.Visible : Visibility.Collapsed;
        MarkNav(PlayNav, page == "player");
        MarkNav(SettingsNav, page == "settings");
        MarkNav(CrawlNav, page == "crawl");
    }

    private static void MarkNav(Button button, bool active)
    {
        button.FontWeight = active ? FontWeights.SemiBold : FontWeights.Normal;
        button.Foreground = active
            ? (Brush)button.FindResource("InkBrush")
            : (Brush)button.FindResource("MutedBrush");
    }

    private void Crawl_Click(object sender, RoutedEventArgs e) => ShowPage("crawl");

    private void OpenFiles_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "打开文件",
            Filter = "音频|*.wav;*.flac;*.mp3;*.aif;*.aiff;*.m4a;*.aac;*.ogg|所有文件|*.*",
            Multiselect = true,
        };
        if (dialog.ShowDialog(this) == true)
            AddPaths(dialog.FileNames);
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "打开文件夹" };
        if (dialog.ShowDialog(this) == true)
            AddPaths([dialog.FolderName]);
    }

    private void Window_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    private void Window_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetData(DataFormats.FileDrop) is string[] paths)
            AddPaths(paths);
    }

    private void LoadStoredLibrary()
    {
        if (_tracks.Count > 0)
            return;
        var root = TrackLoader.LibraryRoot();
        if (!Directory.Exists(root))
            return;
        AddPaths([root], openAlbum: false, limit: 20000);
    }

    private async void AddPaths(IEnumerable<string> paths, bool openAlbum = true, int limit = 2000)
    {
        OutputText.Text = "正在读取…";
        SubtitleFinder.ClearCache();
        var files = await Task.Run(() => TrackLoader.Expand(paths, limit));
        var loaded = await Task.Run(() => files.Select(TrackLoader.TryRead).ToList());
        var added = 0;
        var withSubtitles = 0;
        var duplicates = 0;
        _skipped = 0;
        var covers = new Dictionary<string, BitmapSource?>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in loaded)
        {
            if (item == null)
            {
                _skipped++;
                continue;
            }

            if (_tracks.Any(track => track.FilePath.Equals(item.FilePath, StringComparison.OrdinalIgnoreCase)))
            {
                duplicates++;
                continue;
            }

            if (item.CoverPath != null)
            {
                if (!covers.TryGetValue(item.CoverPath, out var art))
                {
                    art = TrackLoader.DecodeFile(item.CoverPath);
                    covers[item.CoverPath] = art;
                }

                item.Art = art;
            }
            else
            {
                item.Art = TrackLoader.DecodeArt(item.ArtBytes);
            }

            _tracks.Add(item);
            added++;
            if (item.Subtitles.Count > 0)
                withSubtitles++;
            if (item.Fingerprint.Length > 0 && _tracks.Any(track => !ReferenceEquals(track, item) && track.Fingerprint == item.Fingerprint))
                duplicates++;
        }

        RefreshDuplicates();

        if (openAlbum && added > 0 && _current == null)
        {
            var pick = _tracks.LastOrDefault(track => !track.IsDuplicate) ?? _tracks[^1];
            ShowTrack(pick);
            var album = AlbumOf(pick);
            if (album != null)
                ShowTracks(album);
        }

        var summary = added > 0
            ? (openAlbum ? $"已加入 {added} 首" : $"已读取 {added} 首")
            : (openAlbum ? "没有读到音频" : "原样独占");
        if (duplicates > 0)
            summary += $"，{duplicates} 首是重复";
        if (withSubtitles > 0)
            summary += $"，{withSubtitles} 首有同级字幕";
        if (_skipped > 0)
            summary += $"，{_skipped} 首读不了";
        OutputText.Text = summary;
    }

    private void Playlist_PreviewMouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (ItemAt(e.OriginalSource) is AlbumItem album)
        {
            if (album.Tracks.Count == 1)
                PlayTrack(album.Tracks[0]);
            else
                OpenAlbum(album);
            e.Handled = true;
            return;
        }

        if (ItemAt(e.OriginalSource) is TrackItem track && (DateTime.UtcNow - _openedAlbumAt).TotalMilliseconds >= 400)
            PlayTrack(track);
    }

    private static object? ItemAt(object? source)
    {
        var node = source as DependencyObject;
        while (node != null && node is not ListBoxItem)
            node = VisualTreeHelper.GetParent(node);
        return (node as ListBoxItem)?.DataContext;
    }

    private void Playlist_PreviewMouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        var target = TracksOf(ItemAt(e.OriginalSource));
        if (target.Count == 0)
            return;

        _menuTracks = target;
        _menuPath = target[0].FilePath;
        DeleteButton.Content = target.Count > 1 ? "删除这张专辑" : "删除";
        var pages = target.Select(SourcePage).Where(page => page != null).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        _menuPage = pages.Count == 1 ? pages[0] : null;
        OpenSourceButton.Visibility = _menuPage == null ? Visibility.Collapsed : Visibility.Visible;
        LibraryPopup.IsOpen = true;
        e.Handled = true;
    }

    private List<TrackItem>? _menuTracks;
    private string? _menuPath;
    private string? _menuPage;

    private void RevealFile_Click(object sender, RoutedEventArgs e)
    {
        LibraryPopup.IsOpen = false;
        if (_menuPath != null)
            Reveal(_menuPath);
    }

    private void OpenSource_Click(object sender, RoutedEventArgs e)
    {
        LibraryPopup.IsOpen = false;
        if (_menuPage != null)
            OpenPage(_menuPage);
    }

    private void DeleteMenu_Click(object sender, RoutedEventArgs e)
    {
        LibraryPopup.IsOpen = false;
        var tracks = _menuTracks;
        if (tracks == null || tracks.Count == 0)
            return;
        var prompt = tracks.Count == 1
            ? $"删除「{tracks[0].Title}」？文件会从电脑上移除。"
            : $"删除这 {tracks.Count} 首？文件会从电脑上移除。";
        if (MessageBox.Show(this, prompt, "删除", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK)
            return;

        var playing = _current;
        if (playing != null && tracks.Any(track => track.FilePath.Equals(playing.FilePath, StringComparison.OrdinalIgnoreCase)))
        {
            _player.Stop();
            playing.IsCurrent = false;
            _current = null;
            RefreshTransport();
        }

        var directories = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var failed = false;
        foreach (var track in tracks)
        {
            var directory = Path.GetDirectoryName(track.FilePath);
            if (!string.IsNullOrEmpty(directory))
                directories.Add(directory);
            if (!DeleteTrackFiles(track))
            {
                failed = true;
                continue;
            }

            _tracks.Remove(track);
        }

        var shownPath = _shownTrack?.FilePath;
        if (shownPath != null && tracks.Any(track => track.FilePath.Equals(shownPath, StringComparison.OrdinalIgnoreCase)))
        {
            _shownTrack = null;
            TitleText.Text = "没有正在播放";
            ArtistText.Text = "";
            AlbumText.Text = "";
            ArtImage.Source = null;
            PaintSeek(TimeSpan.Zero, TimeSpan.Zero);
            PresentCue("", "");
        }

        foreach (var directory in directories)
            RemoveEmptyWorkFolder(directory);
        SubtitleFinder.ClearCache();
        RebuildLibrary();
        if (failed)
            OutputText.Text = "有的文件正在使用，删不掉";
    }

    private static bool DeleteTrackFiles(TrackItem track)
    {
        var path = track.FilePath;
        var directory = Path.GetDirectoryName(path);
        if (!TryDeleteFile(path))
            return false;
        if (string.IsNullOrEmpty(directory) || !Directory.Exists(directory))
            return true;
        var name = Path.GetFileName(path);
        var stem = Path.GetFileNameWithoutExtension(path);
        foreach (var file in Directory.EnumerateFiles(directory))
        {
            var fileName = Path.GetFileName(file);
            if (fileName.StartsWith(name + ".", StringComparison.OrdinalIgnoreCase) && !TryDeleteFile(file))
                return false;
        }

        foreach (var extension in new[] { ".srt", ".lrc", ".vtt" })
        {
            if (!TryDeleteFile(Path.Combine(directory, stem + extension)))
                return false;
        }

        return true;
    }

    private static void RemoveEmptyWorkFolder(string directory)
    {
        var root = Path.TrimEndingDirectorySeparator(TrackLoader.LibraryRoot());
        var parent = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(directory));
        if (parent == null || !parent.Equals(root, StringComparison.OrdinalIgnoreCase) || !Directory.Exists(directory))
            return;
        if (Directory.EnumerateFiles(directory).Any(TrackLoader.IsAudioFile))
            return;
        TryDeleteFile(Path.Combine(directory, "cover.jpg"));
        TryDeleteFile(Path.Combine(directory, "work.json"));
        try
        {
            if (!Directory.EnumerateFileSystemEntries(directory).Any())
                Directory.Delete(directory);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static bool TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path))
                File.Delete(path);
            return true;
        }
        catch (IOException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static List<TrackItem> TracksOf(object? item) => item switch
    {
        TrackItem track => [track],
        AlbumItem album => album.Tracks.ToList(),
        _ => [],
    };

    private static string? SourcePage(TrackItem track)
    {
        var library = TrackLoader.LibraryRoot();
        var prefix = library + Path.DirectorySeparatorChar;
        if (!track.FilePath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            return null;
        var rest = track.FilePath[prefix.Length..];
        var split = rest.IndexOf(Path.DirectorySeparatorChar);
        if (split <= 2)
            return null;
        var folder = rest[..split];
        if (folder.StartsWith("YT", StringComparison.Ordinal))
            return "https://www.youtube.com/watch?v=" + Uri.EscapeDataString(folder[2..]);
        if (folder.StartsWith("BV", StringComparison.OrdinalIgnoreCase))
            return "https://www.bilibili.com/video/" + folder;
        return null;
    }

    private static void Reveal(string path)
    {
        if (!File.Exists(path))
            return;
        Process.Start(new ProcessStartInfo
        {
            FileName = "explorer.exe",
            Arguments = "/select,\"" + path + "\"",
            UseShellExecute = true,
        });
    }

    private static void OpenPage(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }

    private void Playlist_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_current != null || Playlist.SelectedItem is not TrackItem track)
            return;
        ShowTrack(track);
        OutputText.Text = track.FileFormatText;
    }

    private void Playlist_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if ((DateTime.UtcNow - _openedAlbumAt).TotalMilliseconds < 400)
            return;
        if (Playlist.SelectedItem is TrackItem track)
            PlayTrack(track);
    }

    private void Play_Click(object sender, RoutedEventArgs e) => TogglePlay();

    private void Prev_Click(object sender, RoutedEventArgs e) => PlayRelative(-1, fromEnd: false);

    private void Next_Click(object sender, RoutedEventArgs e) => PlayRelative(1, fromEnd: false);

    private void PlayModeButton_Click(object sender, RoutedEventArgs e)
    {
        var mode = _settings.PlayMode switch
        {
            "order" => "shuffle",
            "shuffle" => "repeat",
            _ => "order",
        };
        _settings.PlayMode = mode;
        _settings.Save();
        _shuffleKeys = mode == "shuffle" ? BuildShuffleKeys() : null;
        PaintPlayMode();
    }

    private void PaintPlayMode()
    {
        var mode = _settings.PlayMode;
        OrderGlyph.Visibility = mode == "shuffle" || mode == "repeat" ? Visibility.Collapsed : Visibility.Visible;
        ShuffleGlyph.Visibility = mode == "shuffle" ? Visibility.Visible : Visibility.Collapsed;
        RepeatGlyph.Visibility = mode == "repeat" ? Visibility.Visible : Visibility.Collapsed;
        PlayModeButton.ToolTip = mode switch
        {
            "shuffle" => "乱序播放",
            "repeat" => "单曲循环",
            _ => "顺序播放",
        };
    }

    private void Stop_Click(object sender, RoutedEventArgs e)
    {
        _player.Stop();
        RefreshTransport();
        if (_current != null)
            OutputText.Text = _current.FileFormatText;
        _pendingSeek = null;
        PaintSeek(TimeSpan.Zero, _current?.Duration ?? TimeSpan.Zero);
    }

    private void TogglePlay()
    {
        if (_player.IsPlaying)
        {
            _player.Pause();
            RefreshTransport();
            return;
        }

        if (_player.IsPaused)
        {
            _player.Resume();
            RefreshTransport();
            return;
        }

        if (Playlist.SelectedItem is TrackItem selected)
            PlayTrack(selected);
        else if (Playlist.SelectedItem is AlbumItem album && album.Tracks.Count > 0)
            PlayTrack(album.Tracks[0]);
        else if (_tracks.FirstOrDefault(track => _showDuplicates || !track.IsDuplicate) is TrackItem first)
            PlayTrack(first);
    }

    private void PlayRelative(int delta, bool fromEnd)
    {
        if (fromEnd && _settings.PlayMode == "repeat" && _current != null)
        {
            PlayTrack(_current);
            return;
        }

        var next = AdjacentTrack(delta);
        if (next == null)
        {
            if (delta > 0 && fromEnd)
                Stop_Click(this, new RoutedEventArgs());
            return;
        }

        PlayTrack(next);
    }

    private TrackItem? AdjacentTrack(int delta)
    {
        if (delta == 0 || _albums.Count == 0)
            return null;
        var album = _current != null ? AlbumOf(_current) : _openAlbum;
        if (album == null && Playlist.SelectedItem is AlbumItem selectedAlbum)
            album = selectedAlbum;
        if (album == null)
            return delta > 0 ? _albums[0].Tracks.FirstOrDefault() : _albums[^1].Tracks.LastOrDefault();

        var step = delta > 0 ? 1 : -1;
        if (album.Tracks.Count > 1 && _current != null)
        {
            var index = IndexOf(album.Tracks, _current);
            var inner = index + step;
            if (index >= 0 && inner >= 0 && inner < album.Tracks.Count)
                return album.Tracks[inner];
        }
        else if (_current == null && album.Tracks.Count > 0)
        {
            return step > 0 ? album.Tracks[0] : album.Tracks[^1];
        }

        var albums = AlbumOrder();
        var albumIndex = -1;
        for (var i = 0; i < albums.Count; i++)
        {
            if (albums[i].Key == album.Key)
            {
                albumIndex = i;
                break;
            }
        }

        var nextIndex = albumIndex + step;
        if (albumIndex < 0 || nextIndex < 0 || nextIndex >= albums.Count)
            return null;
        var nextAlbum = albums[nextIndex];
        if (nextAlbum.Tracks.Count == 0)
            return null;
        return step > 0 ? nextAlbum.Tracks[0] : nextAlbum.Tracks[^1];
    }

    private IReadOnlyList<AlbumItem> AlbumOrder()
    {
        if (_settings.PlayMode != "shuffle")
            return _albums;
        if (_shuffleKeys == null)
            _shuffleKeys = BuildShuffleKeys();
        var live = _albums.ToDictionary(album => album.Key, StringComparer.Ordinal);
        var ordered = new List<AlbumItem>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var key in _shuffleKeys)
        {
            if (!live.TryGetValue(key, out var album) || !seen.Add(key))
                continue;
            ordered.Add(album);
        }

        var fresh = _albums.Where(album => seen.Add(album.Key)).ToList();
        if (fresh.Count > 0)
        {
            Shuffle(fresh);
            ordered.AddRange(fresh);
            _shuffleKeys = ordered.Select(album => album.Key).ToList();
        }

        return ordered;
    }

    private List<string> BuildShuffleKeys()
    {
        var keys = _albums.Select(album => album.Key).ToList();
        var currentKey = _current != null ? AlbumOf(_current)?.Key : null;
        if (currentKey != null)
            keys.Remove(currentKey);
        Shuffle(keys);
        if (currentKey != null)
            keys.Insert(0, currentKey);
        return keys;
    }

    private static void Shuffle<T>(List<T> items)
    {
        for (var i = items.Count - 1; i > 0; i--)
        {
            var swap = Random.Shared.Next(i + 1);
            (items[i], items[swap]) = (items[swap], items[i]);
        }
    }

    private static int IndexOf(IReadOnlyList<TrackItem> tracks, TrackItem track)
    {
        for (var i = 0; i < tracks.Count; i++)
        {
            if (ReferenceEquals(tracks[i], track))
                return i;
        }

        return -1;
    }

    private void PlayTrack(TrackItem track)
    {
        var pending = ReferenceEquals(_shownTrack, track) ? _pendingSeek : null;
        SetCurrent(track);
        var result = _player.Play(track.FilePath);
        if (!result.Ok)
        {
            OutputText.Text = StatusLine(result.Message);
            RefreshTransport();
            return;
        }

        OutputText.Text = StatusLine(result.Message);
        if (pending is TimeSpan jump)
        {
            _player.Position = jump;
            _pendingSeek = null;
            var duration = _player.Duration > TimeSpan.Zero ? _player.Duration : track.Duration;
            PaintSeek(jump, duration);
            ShowCue(jump);
        }

        RefreshTransport();
    }

    private void SetCurrent(TrackItem track)
    {
        if (_current != null)
            _current.IsCurrent = false;
        _current = track;
        _current.IsCurrent = true;
        var album = AlbumOf(track);
        if (_openAlbum != null && album != null && album.Key == _openAlbum.Key)
        {
            Playlist.SelectedItem = track;
            Playlist.ScrollIntoView(track);
        }
        else if (_openAlbum == null && album != null)
        {
            Playlist.SelectedItem = album;
            Playlist.ScrollIntoView(album);
        }

        ShowTrack(track);
    }

    private void Cover_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount < 2)
            return;
        e.Handled = true;
        var track = _shownTrack;
        var path = track == null ? null : track.CoverPath ?? TrackLoader.CoverFile(track.FilePath);
        if (path == null && track?.ArtBytes is { Length: > 0 } bytes)
        {
            path = Path.Combine(Path.GetTempPath(), "PlayerDemo-cover" + (bytes.Length >= 4 && bytes[0] == 0x89 && bytes[1] == 0x50 ? ".png" : ".jpg"));
            try
            {
                File.WriteAllBytes(path, bytes);
            }
            catch (IOException)
            {
                OutputText.Text = "封面打不开";
                return;
            }
        }

        if (string.IsNullOrEmpty(path) || !File.Exists(path))
        {
            OutputText.Text = "没有封面";
            return;
        }

        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true });
        }
        catch (System.ComponentModel.Win32Exception)
        {
            OutputText.Text = "封面打不开";
        }
    }

    private void ShowTrack(TrackItem track)
    {
        if (!ReferenceEquals(_shownTrack, track))
            _pendingSeek = null;

        TitleText.Text = track.Title;
        ArtistText.Text = track.Artist;
        AlbumText.Text = track.Album;
        ArtImage.Source = track.Art;
        ApplyAccent(track.Art);
        var position = TimeSpan.Zero;
        if (ReferenceEquals(track, _current) && (_player.IsPlaying || _player.IsPaused))
            position = _player.Position;
        else if (_pendingSeek is TimeSpan pending)
            position = pending;
        _shownTrack = track;
        PaintSeek(position, track.Duration);
        LoadCues(track, position);
    }

    private void ApplyAccent(ImageSource? art)
    {
        var color = art is BitmapSource bitmap ? ArtColor.FromArt(bitmap) : Color.FromRgb(0x8A, 0x5A, 0x32);
        _accent = color;
        Resources["AccentBrush"] = new SolidColorBrush(color);
        Resources["PaperBrush"] = new SolidColorBrush(ArtColor.Paper(color));
        Resources["PlayFillBrush"] = new SolidColorBrush(ArtColor.PlayFill(color));
        if (!_floatSubtitles.IsClosed)
            _floatSubtitles.UseAccent(color);
    }

    private void RefreshClock()
    {
        if (_draggingSeek || _current == null)
            return;
        if (!_player.IsPlaying && !_player.IsPaused)
            return;

        var duration = _player.Duration;
        if (duration <= TimeSpan.Zero)
            duration = _current.Duration;
        PaintSeek(_player.Position, duration);
        ShowCue(_player.Position);
        RefreshTransport();
    }

    private void RefreshTransport()
    {
        var playing = _player.IsPlaying;
        PlayGlyph.Visibility = playing ? Visibility.Collapsed : Visibility.Visible;
        PauseGlyph.Visibility = playing ? Visibility.Visible : Visibility.Collapsed;
        if (!_floatSubtitles.IsClosed)
            _floatSubtitles.SetPlaying(playing);
    }

    private void SeekBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _draggingSeek = true;
        SeekBar.CaptureMouse();
        SeekTo(e.GetPosition(SeekBar).X);
        e.Handled = true;
    }

    private void SeekBar_MouseMove(object sender, MouseEventArgs e)
    {
        if (!_draggingSeek || e.LeftButton != MouseButtonState.Pressed)
            return;
        SeekTo(e.GetPosition(SeekBar).X);
    }

    private void SeekBar_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (!_draggingSeek)
            return;
        _draggingSeek = false;
        if (SeekBar.IsMouseCaptured)
            SeekBar.ReleaseMouseCapture();
        SeekTo(e.GetPosition(SeekBar).X);
    }

    private void SeekBar_SizeChanged(object sender, SizeChangedEventArgs e) =>
        PaintSeek(_paintedPosition, _paintedDuration);

    private void SeekTo(double x)
    {
        var duration = _player.Duration;
        if (duration <= TimeSpan.Zero)
            duration = _shownTrack?.Duration ?? _paintedDuration;
        if (duration <= TimeSpan.Zero || SeekBar.ActualWidth <= 0)
            return;

        var ratio = Math.Clamp(x / SeekBar.ActualWidth, 0, 1);
        var position = TimeSpan.FromMilliseconds(duration.TotalMilliseconds * ratio);
        PaintSeek(position, duration);
        if (_player.IsPlaying || _player.IsPaused)
        {
            _pendingSeek = null;
            _player.Position = position;
            ShowCue(position);
            return;
        }

        _pendingSeek = position;
        ShowCue(position);
    }

    private void PaintSeek(TimeSpan position, TimeSpan duration)
    {
        _paintedPosition = position;
        _paintedDuration = duration;
        PositionText.Text = TrackItem.FormatTime(position);
        if (duration > TimeSpan.Zero)
            DurationText.Text = TrackItem.FormatTime(duration);
        var width = SeekBar.ActualWidth;
        var ratio = duration.TotalMilliseconds <= 0
            ? 0
            : Math.Clamp(position.TotalMilliseconds / duration.TotalMilliseconds, 0, 1);
        var pixels = ratio * width;
        SeekFill.Width = pixels;
        SeekThumb.Margin = new Thickness(Math.Max(0, pixels - 6), 0, 0, 0);
    }

    private void SubtitleButton_Click(object sender, RoutedEventArgs e)
    {
        var track = _shownTrack;
        if (track == null)
            return;

        _suppressSubtitleEvent = true;
        SubtitleMenu.Items.Clear();
        SubtitleMenu.Items.Add(new SubtitleMenuItem("不显示", -1));
        for (var i = 0; i < track.Subtitles.Count; i++)
            SubtitleMenu.Items.Add(new SubtitleMenuItem(track.Subtitles[i].Name, i));
        SubtitleMenu.Items.Add(new SubtitleMenuItem("选择文件…", -2));
        SubtitleMenu.SelectedIndex = track.SubtitleIndex + 1;
        _suppressSubtitleEvent = false;
        SubtitlePopup.IsOpen = true;
    }

    private void SubtitleMenu_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSubtitleEvent || SubtitleMenu.SelectedItem is not SubtitleMenuItem item || _shownTrack == null)
            return;

        SubtitlePopup.IsOpen = false;
        if (item.Index == -2)
        {
            PickSubtitleFile(_shownTrack);
            return;
        }

        _shownTrack.SubtitleIndex = item.Index;
        var position = ReferenceEquals(_shownTrack, _current) && (_player.IsPlaying || _player.IsPaused)
            ? _player.Position
            : TimeSpan.Zero;
        LoadCues(_shownTrack, position);
    }

    private void PickSubtitleFile(TrackItem track)
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择字幕",
            Filter = "字幕|*.lrc;*.srt;*.vtt|所有文件|*.*",
        };
        if (dialog.ShowDialog(this) != true)
            return;

        track.AddSubtitle(new SubtitleFile(dialog.FileName, Path.GetFileName(dialog.FileName)));
        var position = ReferenceEquals(track, _current) && (_player.IsPlaying || _player.IsPaused)
            ? _player.Position
            : TimeSpan.Zero;
        LoadCues(track, position);
    }

    private void LoadCues(TrackItem track, TimeSpan position)
    {
        var selected = track.SelectedSubtitle;
        if (selected == null)
        {
            _cues = [];
            SubtitleButton.Content = track.Subtitles.Count == 0 ? "没有字幕" : "不显示字幕";
            PresentCue("", "");
            if (_floatSubtitles.IsVisible)
                _floatSubtitles.Hide();
            return;
        }

        try
        {
            _cues = CueDocument.Apply(track.FilePath, SubtitleParser.Read(selected.FullPath));
            SubtitleButton.Content = selected.Name;
        }
        catch
        {
            _cues = [];
            SubtitleButton.Content = selected.Name;
            PresentCue("字幕读不了");
            return;
        }

        ShowCue(position);
    }

    private void ShowCue(TimeSpan position)
    {
        if (_cues.Count == 0)
        {
            PresentCue("", "");
            if (_floatSubtitles.IsVisible)
                _floatSubtitles.Hide();
            return;
        }

        var positionMs = (long)position.TotalMilliseconds;
        var index = -1;
        for (var i = _cues.Count - 1; i >= 0; i--)
        {
            if (positionMs >= _cues[i].StartMs)
            {
                index = i;
                break;
            }
        }

        if (index < 0)
        {
            PresentCue("", "");
            if (!_floatSubtitles.IsClosed && !_floatSubtitles.IsVisible)
                _floatSubtitles.Show();
            return;
        }

        var cue = _cues[index];
        var chinese = cue.Translation.Trim();
        var japanese = cue.Text.Trim();
        if (chinese.Length == 0 || string.Equals(chinese, japanese, StringComparison.Ordinal))
        {
            PresentCue(japanese, "");
            return;
        }

        if (!_showOriginal)
        {
            PresentCue(chinese, "");
            return;
        }

        PresentCue(japanese, chinese);
    }

    private void PresentCue(string? current, string? next = null)
    {
        if (_floatSubtitles.IsClosed)
        {
            _floatSubtitles = new SubtitleFloatWindow(_settings);
            BindFloat(_floatSubtitles);
        }

        _floatSubtitles.SetOriginalShown(_showOriginal);
        _floatSubtitles.ShowPair(current, next);
    }

    private void BindFloat(SubtitleFloatWindow window)
    {
        window.UseAccent(_accent);
        window.SetPlaying(_player.IsPlaying);
        window.OriginalRequested += (_, _) => Language_Click(this, new RoutedEventArgs());
        window.CloseRequested += (_, _) => CloseSubtitles();
        window.PreviousRequested += (_, _) => PlayRelative(-1, fromEnd: false);
        window.NextRequested += (_, _) => PlayRelative(1, fromEnd: false);
        window.PlayRequested += (_, _) => TogglePlay();
    }

    private void CloseSubtitles()
    {
        var track = _shownTrack ?? _current;
        if (track == null)
        {
            if (_floatSubtitles.IsVisible)
                _floatSubtitles.Hide();
            return;
        }

        track.SubtitleIndex = -1;
        var position = ReferenceEquals(track, _current) && (_player.IsPlaying || _player.IsPaused)
            ? _player.Position
            : TimeSpan.Zero;
        LoadCues(track, position);
    }

    private void Settings_Click(object sender, RoutedEventArgs e) => ShowPage("settings");

    private void Language_Click(object sender, RoutedEventArgs e)
    {
        _showOriginal = !_showOriginal;
        _settings.ShowOriginal = _showOriginal;
        _settings.Save();
        LanguageButton.Content = _showOriginal ? "隐藏原文" : "显示原文";
        if (!_floatSubtitles.IsClosed)
            _floatSubtitles.SetOriginalShown(_showOriginal);
        var position = ReferenceEquals(_shownTrack, _current) && (_player.IsPlaying || _player.IsPaused)
            ? _player.Position
            : _paintedPosition;
        ShowCue(position);
    }

    private void CancelJob_Click(object sender, RoutedEventArgs e) => _job?.Cancel();

    private async void GenerateCue_Click(object sender, RoutedEventArgs e) => await GenerateAsync(withTranslation: false);

    private async void GenerateBoth_Click(object sender, RoutedEventArgs e) => await GenerateAsync(withTranslation: true);

    private async void TranslateOnly_Click(object sender, RoutedEventArgs e) => await TranslateCurrentAsync();

    private async Task GenerateAsync(bool withTranslation)
    {
        var track = _shownTrack;
        if (track == null || !File.Exists(track.FilePath))
        {
            OutputText.Text = "先打开一首音频";
            return;
        }

        if (!_settings.HasWhisper())
        {
            OutputText.Text = "先在设置里保存听写";
            return;
        }

        if (withTranslation && !_settings.HasTranslate())
        {
            OutputText.Text = "先在设置里保存翻译";
            return;
        }

        if (WhisperService.NeedsSizeConfirm(_settings, new FileInfo(track.FilePath).Length))
        {
            var send = MessageBox.Show(this, "这个文件超过 25MB，仍要发送吗？", "听写", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (send != MessageBoxResult.Yes)
            {
                OutputText.Text = "已中止";
                return;
            }
        }

        var durationMs = (long)track.Duration.TotalMilliseconds;
        await RunJobAsync(async (cancellationToken, progress) =>
        {
            var cues = (await WhisperService.TranscribeAsync(_settings, track.FilePath, durationMs, progress, cancellationToken)).ToList();
            if (cues.Count == 0)
            {
                progress.Report("没有听出人声");
                return;
            }

            if (withTranslation)
            {
                progress.Report("翻译中");
                try
                {
                    var translated = await TranslateService.TranslateAsync(_settings, cues.Select(cue => cue.Text).ToList(), cancellationToken);
                    cues = cues.Select((cue, index) => cue with { Translation = translated[index] }).ToList();
                }
                catch (OperationCanceledException)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    CueDocument.Save(track.FilePath, cues);
                    AttachGenerated(track, cues);
                    progress.Report("听写已完成，翻译失败");
                    if (!string.IsNullOrWhiteSpace(ex.Message) && ex.Message != "翻译失败")
                        progress.Report("听写已完成，翻译失败");
                    return;
                }
            }

            CueDocument.Save(track.FilePath, cues);
            AttachGenerated(track, cues);
            progress.Report(withTranslation ? "字幕和翻译已写好" : "字幕已写好");
        });
    }

    private async Task TranslateCurrentAsync()
    {
        var track = _shownTrack;
        if (track == null)
        {
            OutputText.Text = "先打开一首音频";
            return;
        }

        if (_cues.Count == 0)
        {
            OutputText.Text = "没有日文字幕";
            return;
        }

        if (!_settings.HasTranslate())
        {
            OutputText.Text = "先在设置里保存翻译";
            return;
        }

        await RunJobAsync(async (cancellationToken, progress) =>
        {
            progress.Report("翻译中");
            var translated = await TranslateService.TranslateAsync(_settings, _cues.Select(cue => cue.Text).ToList(), cancellationToken);
            var cues = _cues.Select((cue, index) => cue with { Translation = translated[index] }).ToList();
            CueDocument.Save(track.FilePath, cues);
            _cues = cues;
            var position = ReferenceEquals(track, _current) && (_player.IsPlaying || _player.IsPaused)
                ? _player.Position
                : _paintedPosition;
            ShowCue(position);
            progress.Report("翻译已写好");
        });
    }

    private async Task RunJobAsync(Func<CancellationToken, IProgress<string>, Task> work)
    {
        if (_job != null)
        {
            OutputText.Text = "正在生成";
            return;
        }

        var job = new CancellationTokenSource();
        _job = job;
        CancelJobButton.Visibility = Visibility.Visible;
        var progress = new Progress<string>(text => OutputText.Text = text);
        try
        {
            await work(job.Token, progress);
        }
        catch (OperationCanceledException)
        {
            OutputText.Text = "已中止";
        }
        catch (Exception ex)
        {
            OutputText.Text = string.IsNullOrWhiteSpace(ex.Message) ? "生成失败" : ex.Message;
        }
        finally
        {
            if (ReferenceEquals(_job, job))
                _job = null;
            job.Dispose();
            CancelJobButton.Visibility = Visibility.Collapsed;
        }
    }

    private void AttachGenerated(TrackItem track, List<SubtitleCue> cues)
    {
        SubtitleFinder.ClearCache();
        var path = CueDocument.SrtPath(track.FilePath);
        track.AddSubtitle(new SubtitleFile(path, Path.GetFileName(path)));
        _cues = cues;
        var position = ReferenceEquals(track, _current) && (_player.IsPlaying || _player.IsPaused)
            ? _player.Position
            : _paintedPosition;
        SubtitleButton.Content = Path.GetFileName(path);
        ShowCue(position);
    }

    private void VolumeBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _draggingVolume = true;
        VolumeBar.CaptureMouse();
        ApplyVolume(e.GetPosition(VolumeBar).X);
        e.Handled = true;
    }

    private void VolumeBar_MouseMove(object sender, MouseEventArgs e)
    {
        if (!_draggingVolume || e.LeftButton != MouseButtonState.Pressed)
            return;
        ApplyVolume(e.GetPosition(VolumeBar).X);
    }

    private void VolumeBar_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (!_draggingVolume)
            return;
        _draggingVolume = false;
        if (VolumeBar.IsMouseCaptured)
            VolumeBar.ReleaseMouseCapture();
        ApplyVolume(e.GetPosition(VolumeBar).X);
    }

    private void VolumeBar_SizeChanged(object sender, SizeChangedEventArgs e) => PaintVolume();

    private void ApplyVolume(double x)
    {
        if (VolumeBar.ActualWidth <= 0)
            return;
        _volumeScalar = Math.Clamp(x / VolumeBar.ActualWidth, 0, 1);
        PaintVolume();
        if (!_volumeReady)
            return;
        try
        {
            _player.SetDeviceVolume(_volumeScalar);
        }
        catch
        {
            OutputText.Text = "改不了设备音量";
        }
    }

    private void PaintVolume()
    {
        VolumeFill.Width = Math.Clamp(_volumeScalar, 0, 1) * VolumeBar.ActualWidth;
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Space)
        {
            TogglePlay();
            e.Handled = true;
        }
        else if (e.Key == Key.O && Keyboard.Modifiers == ModifierKeys.Control)
        {
            OpenFiles_Click(this, new RoutedEventArgs());
            e.Handled = true;
        }
    }

    private void Min_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void Max_Click(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Window_Closed(object? sender, EventArgs e)
    {
        _clock.Stop();
        _deviceRefresh.Stop();
        _deviceWatcher?.Dispose();
        _deviceWatcher = null;
        _job?.Cancel();
        _floatSubtitles.Shutdown();
        _player.Dispose();
    }

    private void AttachWindowShadow()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        var preference = 1;
        DwmSetWindowAttribute(hwnd, 33, ref preference, sizeof(int));
        var margins = new Margins { Left = 1, Right = 1, Top = 1, Bottom = 1 };
        DwmExtendFrameIntoClientArea(hwnd, ref margins);
    }

    private static void WriteSampleWav(string path)
    {
        if (File.Exists(path))
            return;
        var format = new WaveFormat(44100, 16, 2);
        using var writer = new WaveFileWriter(path, format);
        writer.Write(new byte[format.AverageBytesPerSecond * 8], 0, format.AverageBytesPerSecond * 8);
    }

    private static BitmapSource MakeArt()
    {
        var visual = new DrawingVisual();
        using (var context = visual.RenderOpen())
        {
            context.DrawRectangle(new SolidColorBrush(Color.FromRgb(0x1A, 0x2C, 0x28)), null, new Rect(0, 0, 600, 600));
            context.DrawRectangle(new SolidColorBrush(Color.FromRgb(0xC4, 0x7A, 0x3A)), null, new Rect(390, 0, 210, 600));
            context.DrawRectangle(new SolidColorBrush(Color.FromRgb(0xE4, 0xD2, 0xB0)), null, new Rect(36, 470, 250, 10));
        }

        var bitmap = new RenderTargetBitmap(600, 600, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        bitmap.Freeze();
        return bitmap;
    }

    private IntPtr MaxHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        const int WM_GETMINMAXINFO = 0x0024;
        if (msg != WM_GETMINMAXINFO)
            return IntPtr.Zero;

        var info = Marshal.PtrToStructure<MinMaxInfo>(lParam);
        var monitor = MonitorFromWindow(hwnd, 2);
        var monitorInfo = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (GetMonitorInfo(monitor, ref monitorInfo))
        {
            var work = monitorInfo.Work;
            var area = monitorInfo.Monitor;
            info.MaxPosition.X = work.Left - area.Left;
            info.MaxPosition.Y = work.Top - area.Top;
            info.MaxSize.X = work.Right - work.Left;
            info.MaxSize.Y = work.Bottom - work.Top;
            Marshal.StructureToPtr(info, lParam, true);
            handled = true;
        }

        return IntPtr.Zero;
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    [DllImport("dwmapi.dll")]
    private static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref Margins margins);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);

    [StructLayout(LayoutKind.Sequential)]
    private struct Margins
    {
        public int Left;
        public int Right;
        public int Top;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MinMaxInfo
    {
        public Point Reserved;
        public Point MaxSize;
        public Point MaxPosition;
        public Point MinTrack;
        public Point MaxTrack;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MonitorInfo
    {
        public int Size;
        public NativeRect Monitor;
        public NativeRect Work;
        public uint Flags;
    }
}

sealed class OutputDevice
{
    public OutputDevice(string id, string name)
    {
        Id = id;
        Name = name;
    }

    public string Id { get; }
    public string Name { get; }
    public override string ToString() => Name;
}

public sealed class LibraryTemplateSelector : DataTemplateSelector
{
    public DataTemplate? AlbumTemplate { get; set; }
    public DataTemplate? TrackTemplate { get; set; }

    public override DataTemplate? SelectTemplate(object item, DependencyObject container) =>
        item is AlbumItem ? AlbumTemplate : TrackTemplate;
}

sealed class SubtitleMenuItem
{
    public SubtitleMenuItem(string label, int index)
    {
        Label = label;
        Index = index;
    }

    public string Label { get; }
    public int Index { get; }
    public override string ToString() => Label;
}
