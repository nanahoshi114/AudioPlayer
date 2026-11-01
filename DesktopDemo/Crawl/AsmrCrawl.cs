using System.ComponentModel;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace PlayerDemo.Crawl;

public sealed class CrawlHit : INotifyPropertyChanged
{
    private ImageSource? _cover;
    private bool _picked;

    public CrawlHit(long id, string sourceId, string title, string circle, string? coverUrl)
    {
        Id = id;
        SourceId = sourceId;
        Title = title;
        Circle = circle;
        CoverUrl = string.IsNullOrWhiteSpace(coverUrl) ? null : coverUrl;
    }

    public long Id { get; }
    public string SourceId { get; }
    public string Title { get; }
    public string Circle { get; }
    public string? CoverUrl { get; }
    public long RemoteCid { get; init; }
    public int Page { get; init; } = 1;
    public string Origin { get; init; } = "asmr";
    public string AlbumTitle { get; init; } = "";
    public string? MediaUrl { get; init; }
    public string? MediaAgent { get; init; }
    public string CoverReferer { get; init; } = "https://asmr.one/";
    public bool IsPicked
    {
        get => _picked;
        set
        {
            if (_picked == value)
                return;
            _picked = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsPicked)));
        }
    }

    public ImageSource? Cover
    {
        get => _cover;
        private set
        {
            if (ReferenceEquals(_cover, value))
                return;
            _cover = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Cover)));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public async Task EnsureCoverAsync()
    {
        if (Cover != null || CoverUrl == null)
            return;
        var image = await AsmrCrawl.CoverAsync(CoverUrl, CoverReferer);
        if (image != null)
            Cover = image;
    }
}

public sealed record CrawlFile(string Title, string Extension, string Url, string Folder, long Size = 0);

public sealed record CrawlWork(
    string SourceId,
    string Title,
    string Circle,
    string? CoverUrl,
    IReadOnlyList<CrawlFile> Files,
    IReadOnlyList<CrawlFile> Images);

public readonly record struct CrawlProgress(string Text, double Ratio);

public static partial class AsmrCrawl
{
    private static readonly string[] Bases =
    [
        "https://api.asmr-200.com",
        "https://api.asmr.one",
        "https://api.asmr-100.com",
        "https://api.asmr-300.com",
    ];

    private static readonly string[] AudioExtensions = ["wav", "flac", "aiff", "aif", "mp3", "m4a", "aac", "ogg"];
    private static readonly string[] ImageExtensions = ["jpg", "jpeg", "png", "webp"];
    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, Task<ImageSource?>> Covers = new();

    public static async Task<IReadOnlyList<CrawlHit>> SearchAsync(string input, CancellationToken cancellationToken)
    {
        var keyword = input.Trim();
        if (keyword.Length == 0)
            throw new InvalidOperationException("先填写 RJ 号或关键词");
        if (Code().IsMatch(keyword) || keyword.All(char.IsDigit))
        {
            var work = await LoadAsync(keyword, cancellationToken);
            return [new CrawlHit(0, work.SourceId, work.Title, work.Circle, work.CoverUrl)];
        }

        var encoded = Uri.EscapeDataString(keyword);
        using var document = await GetAsync($"/api/search/{encoded}?order=dl_count&sort=desc&page=1&pageSize=20&subtitle=0&includeTranslationWorks=true", cancellationToken);
        if (!document.RootElement.TryGetProperty("works", out var works) || works.ValueKind != JsonValueKind.Array)
            return [];
        var hits = new List<CrawlHit>();
        foreach (var item in works.EnumerateArray())
        {
            var id = item.TryGetProperty("id", out var idValue) && idValue.TryGetInt64(out var number) ? number : 0;
            if (id <= 0)
                continue;
            var source = Text(item, "source_id").ToUpperInvariant();
            var circle = item.TryGetProperty("circle", out var circleValue) ? Text(circleValue, "name") : "";
            if (circle.Length == 0)
                circle = Text(item, "name");
            var cover = Text(item, "thumbnailCoverUrl");
            if (cover.Length == 0)
                cover = Text(item, "mainCoverUrl");
            hits.Add(new CrawlHit(id, source, Text(item, "title"), circle, cover));
        }

        return hits;
    }

    public static async Task<CrawlWork> LoadAsync(string input, CancellationToken cancellationToken)
    {
        var code = Code().Match(input);
        var key = code.Success ? code.Value.ToUpperInvariant() : "RJ" + new string(input.Where(char.IsDigit).ToArray());
        if (key.Length <= 2 && long.TryParse(input.Trim(), out var numeric) && numeric > 0)
            key = numeric.ToString();
        using var info = await GetAsync("/api/workInfo/" + Uri.EscapeDataString(key), cancellationToken);
        var id = info.RootElement.TryGetProperty("id", out var idValue) && idValue.TryGetInt64(out var workId) ? workId : 0;
        if (id <= 0)
            throw new InvalidOperationException("没有读到作品编号");
        var source = Text(info.RootElement, "source_id");
        if (source.Length == 0)
            source = key.ToUpperInvariant();
        var circle = info.RootElement.TryGetProperty("circle", out var circleValue) ? Text(circleValue, "name") : "";
        if (circle.Length == 0)
            circle = Text(info.RootElement, "name");
        var cover = Text(info.RootElement, "mainCoverUrl");
        if (cover.Length == 0)
            cover = Text(info.RootElement, "thumbnailCoverUrl");
        using var tracks = await GetAsync("/api/tracks/" + id + "?v=2", cancellationToken);
        var files = new List<CrawlFile>();
        var images = new List<CrawlFile>();
        if (tracks.RootElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var node in tracks.RootElement.EnumerateArray())
                Walk(node, "", files, images);
        }

        return new CrawlWork(source, Text(info.RootElement, "title"), circle, cover.Length == 0 ? null : cover, files, images);
    }

    public static async Task<IReadOnlyList<string>> DownloadAsync(
        CrawlWork work,
        IReadOnlyCollection<string> extensions,
        IProgress<CrawlProgress> progress,
        CancellationToken cancellationToken,
        bool summarize = true)
    {
        var chosen = work.Files.Where(file => extensions.Contains(file.Extension)).ToList();
        if (chosen.Count == 0)
            throw new InvalidOperationException("没有选中的格式");
        var root = Path.Combine(Library.TrackLoader.LibraryRoot(), Safe(work.SourceId));
        Directory.CreateDirectory(root);
        TryWriteWorkNote(root, work);
        var saved = new List<string>();
        var coverFailed = false;
        var original = OriginalCover(work.Images);
        var coverUrl = original?.Url ?? work.CoverUrl;
        var steps = chosen.Count + (string.IsNullOrWhiteSpace(coverUrl) ? 0 : 1);
        var index = 0;
        foreach (var file in chosen)
        {
            cancellationToken.ThrowIfCancellationRequested();
            index++;
            var directory = Path.Combine(root, SafePath(file.Folder));
            Directory.CreateDirectory(directory);
            var path = Path.Combine(directory, Safe(file.Title));
            if (!Path.GetExtension(path).Equals("." + file.Extension, StringComparison.OrdinalIgnoreCase))
                path += "." + file.Extension;
            var current = index;
            if (File.Exists(path) && new FileInfo(path).Length > 0)
            {
                progress.Report(Step(current, steps, file.Title, -1, -1, true));
            }
            else
            {
                progress.Report(Step(current, steps, file.Title, 0, -1, false));
                await SaveAsync(file.Url, path, (received, total) =>
                    progress.Report(Step(current, steps, file.Title, received, total, false)), cancellationToken);
            }

            saved.Add(path);
        }

        if (!string.IsNullOrWhiteSpace(coverUrl))
        {
            index++;
            var coverIndex = index;
            var label = original == null ? "封面" : "原图";
            var coverExt = original == null
                ? (work.CoverUrl!.Contains(".png", StringComparison.OrdinalIgnoreCase) ? ".png" : ".jpg")
                : CoverExtension(original);
            var temp = Path.Combine(root, "cover" + coverExt);
            var existing = new FileInfo(temp);
            var keep = existing.Exists && existing.Length > 0
                && (original == null || original.Size <= 0 || existing.Length >= original.Size * 9 / 10);
            try
            {
                if (keep)
                {
                    progress.Report(Step(coverIndex, steps, label, -1, -1, true));
                }
                else
                {
                    progress.Report(Step(coverIndex, steps, label, 0, -1, false));
                    await SaveAsync(coverUrl, temp, (received, total) =>
                        progress.Report(Step(coverIndex, steps, label, received, total, false)), cancellationToken, image: true);
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception) when (original != null && !string.IsNullOrWhiteSpace(work.CoverUrl))
            {
                try
                {
                    var fallback = Path.Combine(root, "cover.jpg");
                    await SaveAsync(work.CoverUrl, fallback, (received, total) =>
                        progress.Report(Step(coverIndex, steps, "封面", received, total, false)), cancellationToken, image: true);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    coverFailed = true;
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                coverFailed = true;
            }
        }

        if (summarize)
        {
            var summary = coverFailed ? $"已保存 {saved.Count} 首，封面没下下来" : $"已保存 {saved.Count} 首";
            progress.Report(new CrawlProgress(summary, 1));
        }

        return saved;
    }

    private static void TryWriteWorkNote(string root, CrawlWork work)
    {
        try
        {
            var path = Path.Combine(root, "work.json");
            if (File.Exists(path))
                return;
            File.WriteAllText(path, JsonSerializer.Serialize(new Dictionary<string, string>
            {
                ["title"] = work.Title,
                ["circle"] = work.Circle,
            }));
        }
        catch (IOException)
        {
        }
    }

    private static CrawlProgress Step(int index, int steps, string name, long received, long total, bool existing)
    {
        var inner = existing ? 1d : total > 0 ? Math.Clamp((double)received / total, 0, 1) : 0d;
        var ratio = steps <= 0 ? 0 : (index - 1 + inner) / steps;
        string detail;
        if (existing)
            detail = "已有";
        else if (total > 0)
            detail = $"{(int)Math.Round(inner * 100)}%  {SizeText(received)} / {SizeText(total)}";
        else if (received > 0)
            detail = SizeText(received);
        else
            detail = "开始";
        return new CrawlProgress($"{index}/{steps}  {name}  {detail}", ratio);
    }

    private static string SizeText(long bytes)
    {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return (bytes / 1024d).ToString("0.0") + " KB";
        if (bytes < 1024d * 1024 * 1024)
            return (bytes / 1024d / 1024).ToString("0.0") + " MB";
        return (bytes / 1024d / 1024 / 1024).ToString("0.00") + " GB";
    }

    private static CrawlFile? OriginalCover(IReadOnlyList<CrawlFile> images)
    {
        var roots = images.Where(file => file.Folder.Length == 0).ToList();
        if (roots.Count > 0)
            return roots.OrderByDescending(file => file.Size).First();
        return images.Where(file => IsCoverName(file.Title)).OrderByDescending(file => file.Size).FirstOrDefault();
    }

    private static bool IsCoverName(string title)
    {
        var name = title.ToLowerInvariant();
        return title.Contains("封面")
            || title.Contains("原图")
            || title.Contains("主视")
            || title.Contains("パッケージ")
            || name.Contains("cover")
            || name.Contains("jacket");
    }

    private static string CoverExtension(CrawlFile file) => file.Extension switch
    {
        "png" => ".png",
        "webp" => ".webp",
        _ => ".jpg",
    };

    private static void Walk(JsonElement node, string folder, List<CrawlFile> files, List<CrawlFile> images)
    {
        var title = Text(node, "title");
        var type = Text(node, "type");
        if (node.TryGetProperty("children", out var children) && children.ValueKind == JsonValueKind.Array)
        {
            var next = type.Equals("folder", StringComparison.OrdinalIgnoreCase) && title.Length > 0
                ? (folder.Length == 0 ? title : folder + "/" + title)
                : folder;
            foreach (var child in children.EnumerateArray())
                Walk(child, next, files, images);
            return;
        }

        var extension = Path.GetExtension(title).TrimStart('.').ToLowerInvariant();
        var url = Text(node, "mediaStreamUrl");
        if (url.Length == 0)
            url = Text(node, "mediaDownloadUrl");
        if (url.Length == 0)
            return;
        var size = Long(node, "size");
        if (type.Equals("image", StringComparison.OrdinalIgnoreCase) || ImageExtensions.Contains(extension))
        {
            if (extension.Length == 0)
                extension = "jpg";
            images.Add(new CrawlFile(title.Length == 0 ? "cover." + extension : title, extension, url, folder, size));
            return;
        }

        if (!type.Equals("audio", StringComparison.OrdinalIgnoreCase) && !AudioExtensions.Contains(extension))
            return;
        if (extension.Length == 0)
            extension = "mp3";
        files.Add(new CrawlFile(title.Length == 0 ? "audio." + extension : title, extension, url, folder, size));
    }

    private static async Task<JsonDocument> GetAsync(string path, CancellationToken cancellationToken)
    {
        Exception? last = null;
        foreach (var baseUrl in Bases)
        {
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, baseUrl + path);
                Stamp(request);
                using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                limit.CancelAfter(TimeSpan.FromSeconds(30));
                using var response = await Http.SendAsync(request, limit.Token);
                var body = await response.Content.ReadAsStringAsync(limit.Token);
                if ((int)response.StatusCode == 404)
                    throw new InvalidOperationException("未找到该作品");
                if ((int)response.StatusCode == 429)
                {
                    await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
                    continue;
                }

                if (!response.IsSuccessStatusCode)
                    throw new InvalidOperationException("作品读不了");
                await Task.Delay(TimeSpan.FromMilliseconds(300), cancellationToken);
                return JsonDocument.Parse(body);
            }
            catch (InvalidOperationException)
            {
                throw;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                last = ex;
            }
        }

        throw new InvalidOperationException(last?.Message ?? "作品读不了");
    }

    public static async Task<ImageSource?> CoverAsync(string url, string? referer = null)
    {
        if (Covers.TryGetValue(url, out var ready))
            return await ready;

        var pending = LoadCoverAsync(url, referer);
        if (!Covers.TryAdd(url, pending))
            return await Covers[url];
        try
        {
            var image = await pending;
            if (image == null)
                Covers.TryRemove(url, out _);
            return image;
        }
        catch
        {
            Covers.TryRemove(url, out _);
            return null;
        }
    }

    private static async Task<ImageSource?> LoadCoverAsync(string url, string? referer)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", "Mozilla/5.0");
        request.Headers.TryAddWithoutValidation("Referer", string.IsNullOrWhiteSpace(referer) ? "https://asmr.one/" : referer);
        request.Headers.TryAddWithoutValidation("Accept", "image/avif,image/webp,image/apng,image/*,*/*");
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        using var response = await Http.SendAsync(request, limit.Token);
        if (!response.IsSuccessStatusCode)
            return null;
        var bytes = await response.Content.ReadAsByteArrayAsync(limit.Token);
        if (bytes.Length == 0)
            return null;
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher == null)
            return null;
        return await dispatcher.InvokeAsync(() => Decode(bytes));
    }

    private static ImageSource Decode(byte[] bytes)
    {
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
        image.DecodePixelWidth = 480;
        image.StreamSource = new MemoryStream(bytes);
        image.EndInit();
        image.Freeze();
        return image;
    }

    private static async Task SaveAsync(string url, string path, Action<long, long> onBytes, CancellationToken cancellationToken, bool image = false)
    {
        var partial = path + ".part";
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            Stamp(request, image);
            using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (!response.IsSuccessStatusCode)
                throw new InvalidOperationException("文件下不来");
            var total = response.Content.Headers.ContentLength ?? -1;
            await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
            var directory = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(directory))
                Directory.CreateDirectory(directory);
            await using (var output = File.Create(partial))
            {
                var buffer = new byte[128 * 1024];
                long received = 0;
                var last = 0L;
                while (true)
                {
                    var read = await input.ReadAsync(buffer, cancellationToken);
                    if (read == 0)
                        break;
                    await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                    received += read;
                    var now = Environment.TickCount64;
                    if (now - last >= 200 || (total > 0 && received >= total))
                    {
                        last = now;
                        onBytes(received, total);
                    }
                }
            }

            if (File.Exists(path))
                File.Delete(path);
            File.Move(partial, path);
        }
        catch
        {
            try
            {
                if (File.Exists(partial))
                    File.Delete(partial);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
            }

            throw;
        }
    }

    private static void Stamp(HttpRequestMessage request, bool image = false)
    {
        request.Headers.TryAddWithoutValidation("User-Agent", "Mozilla/5.0");
        request.Headers.TryAddWithoutValidation("Referer", "https://asmr.one/");
        request.Headers.TryAddWithoutValidation("Origin", "https://asmr.one");
        request.Headers.TryAddWithoutValidation("Accept", image ? "image/*,*/*" : "application/json");
    }

    private static long Long(JsonElement item, string name)
    {
        if (!item.TryGetProperty(name, out var value))
            return 0;
        if (value.TryGetInt64(out var number))
            return number;
        return value.TryGetDouble(out var fractional) ? (long)fractional : 0;
    }

    private static string Text(JsonElement item, string name)
    {
        if (!item.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
            return "";
        return value.GetString()?.Trim() ?? "";
    }

    private static string Safe(string name)
    {
        foreach (var c in Path.GetInvalidFileNameChars())
            name = name.Replace(c, '_');
        var cleaned = name.Trim();
        return cleaned.Length == 0 ? "audio" : cleaned;
    }

    private static string SafePath(string folder)
    {
        if (folder.Length == 0)
            return "";
        var parts = folder.Split('/', '\\').Select(Safe).Where(part => part.Length > 0);
        return Path.Combine(parts.ToArray());
    }

    [GeneratedRegex("(?i)(?:RJ|VJ)\\d+")]
    private static partial Regex Code();
}
