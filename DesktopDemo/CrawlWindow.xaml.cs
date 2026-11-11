using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using PlayerDemo.Crawl;
using PlayerDemo.Services;

namespace PlayerDemo;

public partial class CrawlPage : UserControl
{
    private enum FeedKind
    {
        None,
        Search,
        List,
    }

    private readonly ObservableCollection<CrawlHit> _hits = [];
    private CancellationTokenSource? _job;
    private double _progressRatio;
    private string _source = "asmr";
    private ServiceSettings? _settings;
    private FeedKind _feed = FeedKind.None;
    private string _feedKey = "";
    private string? _more;
    private int _generation;
    private bool _loadingMore;
    private bool _resumeTried;
    private readonly List<HitFrame> _stack = [];

    private sealed class HitFrame
    {
        public required List<CrawlHit> Hits { get; init; }
        public required string Title { get; init; }
        public FeedKind Feed { get; init; }
        public string FeedKey { get; init; } = "";
        public string? More { get; init; }
    }

    public event Action<IReadOnlyList<string>>? Finished;

    public CrawlPage()
    {
        InitializeComponent();
        Hits.ItemsSource = _hits;
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

    public void ResumeInterrupted()
    {
        if (_resumeTried || _job != null)
            return;
        _resumeTried = true;
        var pending = DownloadQueue.Load();
        if (pending == null)
            return;
        _ = RunDownloadsAsync(pending.Value.Hits, pending.Value.Extensions, resumed: true);
    }

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
        StopPaging();
        ClearDetail();
        _hits.Clear();
        Formats.Visibility = source == "asmr" ? Visibility.Visible : Visibility.Collapsed;
        BiliFavButton.Visibility = source == "bili" ? Visibility.Visible : Visibility.Collapsed;
        YouTubeLikedButton.Visibility = source == "youtube" ? Visibility.Visible : Visibility.Collapsed;
        YouTubeLaterButton.Visibility = source == "youtube" ? Visibility.Visible : Visibility.Collapsed;
        Status.Text = source == "youtube" ? "喜欢的视频和稍后观看需要 Cookie" : "";
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

    private DateTime _lastHitDownAt;
    private CrawlHit? _lastHitDown;
    private bool _skipHitClick;

    private void Hit_Down(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (_job != null || _loadingMore || sender is not FrameworkElement { DataContext: CrawlHit hit })
            return;
        var now = DateTime.UtcNow;
        var again = ReferenceEquals(hit, _lastHitDown) && (now - _lastHitDownAt).TotalMilliseconds <= 500;
        _lastHitDownAt = now;
        _lastHitDown = hit;
        if (e.ClickCount < 2 && !again)
            return;
        e.Handled = true;
        _skipHitClick = true;
        _ = OpenHitAsync(hit);
    }

    private void Hit_Click(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (_skipHitClick)
        {
            _skipHitClick = false;
            return;
        }

        if (_job != null || _loadingMore || sender is not FrameworkElement { DataContext: CrawlHit hit })
            return;
        hit.IsPicked = !hit.IsPicked;
        RefreshStart();
    }

    private async Task OpenHitAsync(CrawlHit hit)
    {
        if (_job != null || _loadingMore)
            return;
        if (hit.Origin is not ("bili" or "youtube"))
            return;
        if (hit.RemoteCid > 0 || (hit.Origin == "youtube" && hit.PlaylistId.Length == 0))
            return;

        var generation = ++_generation;
        _loadingMore = true;
        var previous = Status.Text;
        Status.Text = "正在读取…";
        try
        {
            var batch = hit.Origin == "bili"
                ? await BilibiliClient.DetailAsync(Settings, hit, CancellationToken.None)
                : await YouTubeClient.DetailAsync(Settings, hit, CancellationToken.None);
            if (generation != _generation)
                return;
            if (batch == null || batch.Value.Hits.Count == 0)
            {
                Status.Text = previous;
                return;
            }

            _stack.Add(new HitFrame
            {
                Hits = _hits.ToList(),
                Title = DetailTitle.Text,
                Feed = _feed,
                FeedKey = _feedKey,
                More = _more,
            });
            Remember(batch.Value, hit.PlaylistId.Length > 0 ? hit.PlaylistId : hit.SourceId);
            DetailTitle.Text = hit.Title;
            DetailBar.Visibility = Visibility.Visible;
            var count = batch.Value.Hits.Count;
            var note = hit.SeasonId > 0 || hit.PlaylistId.Length > 0 ? $"{count} 个视频" : $"{count} P";
            ShowHits(batch.Value.Hits, _more == null ? note : note + "。往下滚动可以继续加载");
            HitScroll.ScrollToVerticalOffset(0);
        }
        catch (Exception ex)
        {
            if (generation == _generation)
                Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "读不了" : ex.Message;
        }
        finally
        {
            if (generation == _generation)
                _loadingMore = false;
        }
    }

    private void DetailBack_Click(object sender, RoutedEventArgs e)
    {
        if (_job != null || _loadingMore || _stack.Count == 0)
            return;
        _generation++;
        var frame = _stack[^1];
        _stack.RemoveAt(_stack.Count - 1);
        _hits.Clear();
        foreach (var hit in frame.Hits)
            _hits.Add(hit);
        _feed = frame.Feed;
        _feedKey = frame.FeedKey;
        _more = frame.More;
        DetailTitle.Text = frame.Title;
        DetailBar.Visibility = _stack.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        RefreshStart();
        Status.Text = _stack.Count == 0
            ? (_hits.Count == 0 ? "" : _more == null ? $"找到 {_hits.Count} 部" : $"找到 {_hits.Count} 部。往下滚动可以继续加载")
            : frame.Title;
        HitScroll.ScrollToVerticalOffset(0);
    }

    private void ClearDetail()
    {
        _stack.Clear();
        DetailTitle.Text = "";
        DetailBar.Visibility = Visibility.Collapsed;
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

    private void Start_Click(object sender, RoutedEventArgs e)
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

        _ = RunDownloadsAsync(picks, extensions, resumed: false);
    }

    private async Task RunDownloadsAsync(List<CrawlHit> picks, List<string> extensions, bool resumed)
    {
        if (_job != null || picks.Count == 0)
            return;
        var job = new CancellationTokenSource();
        _job = job;
        CancelButton.Visibility = Visibility.Visible;
        RefreshStart();
        ShowDownload(new CrawlProgress(resumed ? $"继续下载，还剩 {picks.Count} 部" : "准备下载", 0));
        var saved = new List<string>();
        var missed = 0;
        var failed = 0;
        string? lastError = null;
        var finished = false;
        try
        {
            for (var i = 0; i < picks.Count; i++)
            {
                job.Token.ThrowIfCancellationRequested();
                DownloadQueue.Save(picks.Skip(i).ToList(), extensions);
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

            finished = true;
            DownloadQueue.Clear();
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
            DownloadQueue.Clear();
            Status.Text = saved.Count == 0 ? "已中止" : $"已中止，已保存 {saved.Count} 首";
        }
        catch (Exception ex)
        {
            if (!finished)
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
        if (_job != null || _loadingMore)
            return;
        StopPaging();
        ClearDetail();
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
        if (_job != null || _loadingMore || sender is not Button { Tag: string list })
            return;
        var generation = ++_generation;
        _loadingMore = true;
        StopPaging();
        ClearDetail();
        Status.Text = "正在读取列表…";
        var opened = false;
        try
        {
            var batch = await YouTubeClient.PlaylistPageAsync(Settings, list, null, CancellationToken.None);
            if (generation != _generation)
                return;
            Remember(batch, list);
            ShowHits(batch.Hits, Describe(batch.Hits.Count, _more));
            opened = true;
        }
        catch (Exception ex)
        {
            if (generation == _generation)
                Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "读取列表失败" : ex.Message;
        }
        finally
        {
            if (generation == _generation)
                _loadingMore = false;
        }

        if (opened && generation == _generation)
            MaybeFill();
    }

    private async Task SearchAsync()
    {
        if (_job != null || _loadingMore)
            return;
        var generation = ++_generation;
        var text = Query.Text;
        _loadingMore = true;
        StopPaging();
        ClearDetail();
        Status.Text = "正在查找…";
        var opened = false;
        try
        {
            var batch = _source switch
            {
                "bili" => await BilibiliClient.OpenAsync(Settings, text, CancellationToken.None),
                "youtube" => await YouTubeClient.OpenAsync(Settings, text, CancellationToken.None),
                _ => await AsmrCrawl.OpenAsync(text, CancellationToken.None),
            };
            if (generation != _generation)
                return;
            Remember(batch, text.Trim());
            ShowHits(batch.Hits, Describe(batch.Hits.Count, _more));
            opened = true;
        }
        catch (Exception ex)
        {
            if (generation == _generation)
                Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "查找失败" : ex.Message;
        }
        finally
        {
            if (generation == _generation)
                _loadingMore = false;
        }

        if (opened && generation == _generation)
            MaybeFill();
    }

    private void HitScroll_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        if (_more == null || _loadingMore || _job != null || HitScroll.ScrollableHeight <= 0)
            return;
        if (HitScroll.VerticalOffset + HitScroll.ViewportHeight < HitScroll.ScrollableHeight - 80)
            return;
        _ = LoadMoreAsync();
    }

    private async Task LoadMoreAsync()
    {
        if (_more == null || _loadingMore || _job != null || _feed == FeedKind.None)
            return;
        var generation = _generation;
        var cursor = _more;
        var kind = _feed;
        var key = _feedKey;
        var source = _source;
        _loadingMore = true;
        Status.Text = "正在继续查找…";
        var loaded = false;
        try
        {
            var batch = (source, kind) switch
            {
                ("bili", FeedKind.Search) => await BilibiliClient.SearchPageAsync(Settings, key, int.Parse(cursor, CultureInfo.InvariantCulture), CancellationToken.None),
                ("youtube", FeedKind.Search) => await YouTubeClient.SearchPageAsync(Settings, key, cursor, CancellationToken.None),
                ("youtube", FeedKind.List) => await YouTubeClient.PlaylistPageAsync(Settings, key, cursor, CancellationToken.None),
                (_, FeedKind.Search) => await AsmrCrawl.SearchPageAsync(key, int.Parse(cursor, CultureInfo.InvariantCulture), CancellationToken.None),
                _ => new CrawlBatch([], null),
            };
            if (generation != _generation)
                return;
            var known = _hits.Select(hit => hit.SourceId).ToHashSet(StringComparer.OrdinalIgnoreCase);
            var fresh = batch.Hits.Where(hit => known.Add(hit.SourceId)).ToList();
            if (fresh.Count == 0)
            {
                _more = null;
                Status.Text = $"已经没了，共 {_hits.Count} 部";
                return;
            }

            var shelf = _hits.FirstOrDefault(item => item.ShelfId.Length > 0);
            var order = _hits.Count;
            foreach (var hit in fresh)
            {
                if (shelf != null)
                {
                    hit.ShelfId = shelf.ShelfId;
                    hit.ShelfTitle = shelf.ShelfTitle;
                    hit.ShelfCircle = shelf.ShelfCircle;
                    hit.ShelfOrder = ++order;
                }

                _hits.Add(hit);
            }
            _more = batch.More;
            RefreshStart();
            _ = ShowCoversAsync(fresh);
            Status.Text = _more == null ? $"已经没了，共 {_hits.Count} 部" : $"找到 {_hits.Count} 部。往下滚动可以继续加载";
            loaded = _more != null;
        }
        catch (Exception ex)
        {
            if (generation == _generation)
                Status.Text = string.IsNullOrWhiteSpace(ex.Message) ? "继续查找失败" : ex.Message;
        }
        finally
        {
            if (generation == _generation)
                _loadingMore = false;
        }

        if (loaded && generation == _generation)
            MaybeFill();
    }

    private void StopPaging()
    {
        _feed = FeedKind.None;
        _feedKey = "";
        _more = null;
    }

    private void Remember(CrawlBatch batch, string fallbackKey)
    {
        if (string.IsNullOrEmpty(batch.More))
        {
            StopPaging();
            return;
        }

        if (batch.Feed is { } feed && feed.StartsWith("list:", StringComparison.Ordinal))
        {
            _feed = FeedKind.List;
            _feedKey = feed[5..];
        }
        else
        {
            _feed = FeedKind.Search;
            _feedKey = string.IsNullOrWhiteSpace(batch.Feed) ? fallbackKey : batch.Feed;
        }

        _more = batch.More;
    }

    private static string Describe(int count, string? more)
    {
        if (count == 0)
            return "没有这部作品";
        if (more == null)
            return count == 1 ? "已选中 1 部，点右下角下载" : $"已经没了，共 {count} 部";
        return $"找到 {count} 部。往下滚动可以继续加载";
    }

    private void MaybeFill()
    {
        if (_more == null || _loadingMore || _job != null)
            return;
        Dispatcher.BeginInvoke(() =>
        {
            if (_more == null || _loadingMore || _job != null || HitScroll.ViewportHeight <= 1)
                return;
            if (HitScroll.ScrollableHeight > 48)
                return;
            _ = LoadMoreAsync();
        }, System.Windows.Threading.DispatcherPriority.ContextIdle);
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
        foreach (var hit in hits)
            _hits.Add(hit);
        if (hits.Count == 1)
            hits[0].IsPicked = true;
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
