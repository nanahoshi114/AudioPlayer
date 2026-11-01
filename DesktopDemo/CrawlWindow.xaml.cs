using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using PlayerDemo.Crawl;
using PlayerDemo.Services;

namespace PlayerDemo;

public partial class CrawlPage : UserControl
{
    private readonly List<CrawlHit> _hits = [];
    private CancellationTokenSource? _job;
    private double _progressRatio;
    private string _source = "asmr";
    private ServiceSettings? _settings;

    public event Action<IReadOnlyList<string>>? Finished;

    public CrawlPage()
    {
        InitializeComponent();
        foreach (var (name, source, on) in new (string Name, string Source, bool On)[]
        {
            ("ASMR", "asmr", true),
            ("B站", "bili", false),
            ("YouTube", "youtube", false),
        })
        {
            var chip = new ToggleButton
            {
                Content = name,
                Tag = source,
                IsChecked = on,
                Margin = new Thickness(0, 0, 8, 0),
                Style = (Style)FindResource("PaperChip"),
            };
            chip.Click += Source_Click;
            Sources.Children.Add(chip);
        }

        foreach (var (name, extension, on) in new (string Name, string Extension, bool On)[]
        {
            ("WAV", "wav", true),
            ("FLAC", "flac", false),
            ("AIFF", "aiff", false),
            ("MP3", "mp3", false),
            ("M4A", "m4a", false),
            ("OGG", "ogg", false),
        })
        {
            var chip = new ToggleButton
            {
                Content = name,
                Tag = extension,
                IsChecked = on,
                Margin = new Thickness(0, 0, 8, 0),
                Style = (Style)FindResource("PaperChip"),
            };
            chip.Checked += (_, _) => RefreshStart();
            chip.Unchecked += (_, _) => RefreshStart();
            Formats.Children.Add(chip);
        }
    }

    public void Bind(ServiceSettings settings) => _settings = settings;

    private ServiceSettings Settings => _settings ?? ServiceSettings.Load();

    private void Source_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleButton chip || chip.Tag is not string source)
            return;
        if (_job != null)
        {
            chip.IsChecked = source == _source;
            return;
        }

        _source = source;
        foreach (ToggleButton other in Sources.Children)
            other.IsChecked = ReferenceEquals(other, chip);
        _hits.Clear();
        Hits.ItemsSource = null;
        Hits.ItemsSource = _hits;
        Formats.Visibility = source == "asmr" ? Visibility.Visible : Visibility.Collapsed;
        BiliFavButton.Visibility = source == "bili" ? Visibility.Visible : Visibility.Collapsed;
        YouTubeLikedButton.Visibility = source == "youtube" ? Visibility.Visible : Visibility.Collapsed;
        YouTubeLaterButton.Visibility = source == "youtube" ? Visibility.Visible : Visibility.Collapsed;
        Status.Text = source switch
        {
            "bili" => "输入 BV 号或视频链接，或点「我的收藏」",
            "youtube" => "输入关键词、视频或播放列表链接。喜欢的视频和稍后观看需要 Cookie",
            _ => "左键点选作品，可以多选，然后点右下角下载",
        };
        RefreshStart();
    }

    private async void Search_Click(object sender, RoutedEventArgs e) => await SearchAsync();

    private async void Query_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != System.Windows.Input.Key.Enter || _job != null)
            return;
        e.Handled = true;
        await SearchAsync();
    }

    private void Hit_Click(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (_job != null || sender is not FrameworkElement { DataContext: CrawlHit hit })
            return;
        hit.IsPicked = !hit.IsPicked;
        RefreshStart();
    }

    private void SelectAll_Click(object sender, RoutedEventArgs e)
    {
        if (_job != null || _hits.Count == 0)
            return;
        var pick = _hits.Any(hit => !hit.IsPicked);
        foreach (var hit in _hits)
            hit.IsPicked = pick;
        RefreshStart();
    }

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        if (_job != null)
            return;
        var picks = _hits.Where(hit => hit.IsPicked).ToList();
        var extensions = ChosenExtensions();
        if (picks.Count == 0)
        {
            Status.Text = "先点选作品";
            return;
        }

        if (picks.All(hit => hit.Origin == "asmr") && extensions.Count == 0)
        {
            Status.Text = "先选格式";
            return;
        }

        var job = new CancellationTokenSource();
        _job = job;
        CancelButton.Visibility = Visibility.Visible;
        RefreshStart();
        ShowDownload(new CrawlProgress("准备下载", 0));
        var saved = new List<string>();
        var missed = 0;
        var failed = 0;
        string? lastError = null;
        try
        {
            for (var i = 0; i < picks.Count; i++)
            {
                job.Token.ThrowIfCancellationRequested();
                var hit = picks[i];
                var label = hit.SourceId.Length > 0 ? hit.SourceId : hit.Title;
                var index = i;
                var progress = new Progress<CrawlProgress>(step =>
                    ShowDownload(new CrawlProgress($"{index + 1}/{picks.Count}  {label}  {step.Text}", (index + step.Ratio) / picks.Count)));
                ShowDownload(new CrawlProgress($"{i + 1}/{picks.Count}  {label}  正在读取", i / (double)picks.Count));
                try
                {
                    IReadOnlyList<string> paths;
                    if (hit.Origin == "bili")
                    {
                        paths = await BilibiliClient.DownloadAsync(Settings, hit, progress, job.Token);
                    }
                    else if (hit.Origin == "youtube")
                    {
                        paths = await YouTubeClient.DownloadAsync(Settings, hit, progress, job.Token);
                    }
                    else
                    {
                        var key = hit.SourceId.Length > 0 ? hit.SourceId : hit.Id.ToString();
                        var work = await AsmrCrawl.LoadAsync(key, job.Token);
                        if (!work.Files.Any(file => extensions.Contains(file.Extension)))
                        {
                            missed++;
                            continue;
                        }

                        paths = await AsmrCrawl.DownloadAsync(work, extensions, progress, job.Token, summarize: false);
                    }

                    saved.AddRange(paths);
                    if (paths.Count > 0)
                        Finished?.Invoke(paths);
                }
                catch (OperationCanceledException)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    failed++;
                    lastError = string.IsNullOrWhiteSpace(ex.Message) ? lastError : ex.Message;
                }
            }

            var summary = saved.Count == 0 && lastError != null
                ? lastError
                : $"已保存 {saved.Count} 首";
            if (saved.Count > 0 && missed > 0)
                summary += $"，{missed} 部没有所选格式";
            if (saved.Count > 0 && failed > 0)
                summary += $"，{failed} 部没读到";
            ShowDownload(new CrawlProgress(summary, 1));
        }
        catch (OperationCanceledException)
        {
            Status.Text = saved.Count == 0 ? "已中止" : $"已中止，已保存 {saved.Count} 首";
        }
        catch (Exception ex)
        {
            Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "爬取失败" : ex.Message;
        }
        finally
        {
            if (ReferenceEquals(_job, job))
                _job = null;
            job.Dispose();
            CancelButton.Visibility = Visibility.Collapsed;
            RefreshStart();
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => _job?.Cancel();

    private void ShowDownload(CrawlProgress step)
    {
        _progressRatio = step.Ratio;
        ProgressTrack.Visibility = Visibility.Visible;
        Status.Text = step.Text;
        UpdateProgressFill();
    }

    private void ProgressTrack_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateProgressFill();

    private void UpdateProgressFill()
    {
        var width = ProgressTrack.ActualWidth;
        if (width <= 0)
            return;
        ProgressFill.Width = Math.Max(0, width * Math.Clamp(_progressRatio, 0, 1));
    }

    private async void BiliFav_Click(object sender, RoutedEventArgs e)
    {
        if (_job != null)
            return;
        Status.Text = "正在读取收藏…";
        try
        {
            var hits = await BilibiliClient.FavoritesAsync(Settings, CancellationToken.None);
            ShowHits(hits, hits.Count == 0 ? "收藏夹是空的" : $"收藏里有 {hits.Count} 个视频。左键点选后，点右下角下载");
        }
        catch (Exception ex)
        {
            Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "读取收藏失败" : ex.Message;
        }
    }

    private async void YouTubeList_Click(object sender, RoutedEventArgs e)
    {
        if (_job != null || sender is not Button { Tag: string list })
            return;
        Status.Text = "正在读取列表…";
        try
        {
            var hits = await YouTubeClient.PlaylistAsync(Settings, list, CancellationToken.None);
            ShowHits(hits, $"找到 {hits.Count} 个视频。左键点选后，点右下角下载");
        }
        catch (Exception ex)
        {
            Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "读取列表失败" : ex.Message;
        }
    }

    private async Task SearchAsync()
    {
        Status.Text = "正在查找…";
        try
        {
            var hits = _source switch
            {
                "bili" => await BilibiliClient.LookupAsync(Settings, Query.Text, CancellationToken.None),
                "youtube" => await YouTubeClient.LookupAsync(Settings, Query.Text, CancellationToken.None),
                _ => await AsmrCrawl.SearchAsync(Query.Text, CancellationToken.None),
            };
            ShowHits(hits, hits.Count switch
            {
                0 => "没有这部作品",
                1 => "已选中 1 部，点右下角下载",
                _ => $"找到 {hits.Count} 部。左键点选后，点右下角下载",
            });
        }
        catch (Exception ex)
        {
            Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "查找失败" : ex.Message;
        }
    }

    private void RefreshStart()
    {
        var count = _hits.Count(hit => hit.IsPicked);
        var busy = _job != null;
        var formatsReady = _hits.Any(hit => hit.Origin != "asmr") || ChosenExtensions().Count > 0;
        StartButton.IsEnabled = !busy && count > 0 && formatsReady;
        StartButton.Content = count == 0 ? "下载所选" : $"下载 {count} 部";
        SelectAllButton.Content = _hits.Count > 0 && count == _hits.Count ? "取消全选" : "全选";
        SelectAllButton.IsEnabled = !busy && _hits.Count > 0;
    }

    private List<string> ChosenExtensions()
    {
        var selected = Formats.Children.OfType<ToggleButton>()
            .Where(box => box.IsChecked == true && box.Tag is string)
            .Select(box => (string)box.Tag)
            .ToList();
        if (selected.Contains("aiff"))
            selected.Add("aif");
        if (selected.Contains("m4a"))
            selected.Add("aac");
        return selected;
    }

    private void ShowHits(IReadOnlyList<CrawlHit> hits, string status)
    {
        _hits.Clear();
        _hits.AddRange(hits);
        if (hits.Count == 1)
            hits[0].IsPicked = true;
        Hits.ItemsSource = null;
        Hits.ItemsSource = _hits;
        RefreshStart();
        _ = ShowCoversAsync(hits);
        Status.Text = status;
    }

    private static async Task ShowCoversAsync(IReadOnlyList<CrawlHit> hits)
    {
        try
        {
            await Task.WhenAll(hits.Select(hit => hit.EnsureCoverAsync()));
        }
        catch
        {
            // 某一张封面失败时，其余仍继续显示。
        }
    }

}
