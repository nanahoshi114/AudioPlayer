using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using PlayerDemo.Library;
using PlayerDemo.Services;
using PlayerDemo.Subtitles;

namespace PlayerDemo.Crawl;

public static partial class BilibiliClient
{
    public readonly record struct QrLogin(string Url, string Key);

    public enum QrState
    {
        Waiting,
        Scanned,
        Expired,
        Confirmed,
    }

    private const string UserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private const string TicketKey = "XgwSnGZ1p";
    private const long KeyTtlMs = 30 * 60 * 1000;
    private const long TicketTtlSec = 259200;
    private const long RefreshIntervalMs = 20 * 60 * 60 * 1000;
    private const int PageSize = 20;
    private const int QrWaiting = 86101;
    private const int QrScanned = 86090;
    private const int QrExpired = 86038;

    private static readonly int[] MixinTab =
    [
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    ];

    private static readonly string[] SessionNames = ["SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"];
    private static readonly HashSet<string> ManagedCookies = new(StringComparer.Ordinal) { "buvid3", "buvid4", "bili_ticket", "bili_ticket_expires" };

    private static readonly HttpClient Api = CreateClient(redirect: false, TimeSpan.FromSeconds(30));
    private static readonly HttpClient Media = CreateClient(redirect: true, TimeSpan.FromMinutes(10));
    private static readonly SemaphoreSlim IdentityLock = new(1, 1);
    private static string? _mixinKey;
    private static long _mixinAt;

    public static string NormalizeCookie(string raw)
    {
        var text = raw.Replace("\r", "", StringComparison.Ordinal).Replace("\n", "", StringComparison.Ordinal).Trim().Trim(';').Trim();
        if (text.Length == 0)
            return "";
        if (!text.Contains('=', StringComparison.Ordinal))
            return "SESSDATA=" + text.Replace(",", "%2C", StringComparison.Ordinal);
        return text;
    }

    public static async Task<string> AccountNameAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        RequireSession(settings);
        using var document = await GetJsonAsync(settings, "https://api.bilibili.com/x/web-interface/nav", "https://www.bilibili.com", false, cancellationToken);
        var root = document.RootElement;
        var data = Child(root, "data");
        var mid = Long(data, "mid");
        if (Code(root) != 0 || mid <= 0)
            throw new InvalidOperationException("登录信息无效或已过期，请重新粘贴 SESSDATA");
        var name = Text(data, "uname");
        return name.Length == 0 ? "已登录" : "已登录 · " + name;
    }

    public static async Task<QrLogin> RequestQrAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        using var document = await GetJsonAsync(
            settings,
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate",
            "https://passport.bilibili.com/login",
            true,
            cancellationToken);
        var root = document.RootElement;
        var data = Child(root, "data");
        var url = Text(data, "url");
        var key = Text(data, "qrcode_key");
        if (Code(root) != 0 || url.Length == 0 || key.Length == 0)
            throw new InvalidOperationException("没有拿到登录二维码");
        return new QrLogin(url, key);
    }

    public static async Task<QrState> PollQrAsync(ServiceSettings settings, string key, CancellationToken cancellationToken)
    {
        var url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + Uri.EscapeDataString(key);
        var exchange = await ExchangeAsync(settings, HttpMethod.Get, url, "https://passport.bilibili.com/login", true, null, false, cancellationToken);
        using var document = JsonDocument.Parse(exchange.Body);
        var root = document.RootElement;
        var data = Child(root, "data");
        if (data.ValueKind != JsonValueKind.Object)
            throw new InvalidOperationException("登录状态没有返回");
        var state = (int)Long(data, "code");
        switch (state)
        {
            case QrWaiting:
                return QrState.Waiting;
            case QrScanned:
                return QrState.Scanned;
            case QrExpired:
                return QrState.Expired;
            case 0:
                var cookie = CombineCookies(CookiesFromSetCookie(exchange.SetCookies), CookiesFromUrl(Text(data, "url")));
                if (Field(cookie, "SESSDATA") == null)
                    throw new InvalidOperationException("登录没有返回有效信息");
                settings.BiliCookie = cookie;
                settings.BiliRefreshToken = Text(data, "refresh_token");
                settings.BiliRefreshChecked = DateTimeOffset.Now.ToUnixTimeMilliseconds();
                settings.Save();
                return QrState.Confirmed;
            default:
                throw new InvalidOperationException("扫码登录失败");
        }
    }

    public static async Task<IReadOnlyList<CrawlHit>> LookupAsync(ServiceSettings settings, string input, CancellationToken cancellationToken)
    {
        var text = input.Trim();
        if (text.Length == 0)
            throw new InvalidOperationException("请输入关键词");
        var match = Bvid().Match(text);
        if (!match.Success)
            return (await SearchPageAsync(settings, text, 1, cancellationToken)).Hits;
        var bvid = match.Value;
        using var document = await GetJsonAsync(settings, "https://api.bilibili.com/x/web-interface/view?bvid=" + Uri.EscapeDataString(bvid), "https://www.bilibili.com", false, cancellationToken);
        var root = document.RootElement;
        var data = Child(root, "data");
        if (Code(root) != 0 || data.ValueKind != JsonValueKind.Object)
            throw new InvalidOperationException(Code(root) == -404 ? "找不到这个视频" : Detail(root, "找不到这个视频"));
        return Parts(data, bvid);
    }

    public static async Task<IReadOnlyList<CrawlHit>> FavoritesAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        RequireSession(settings);
        var mid = await LoginMidAsync(settings, cancellationToken);
        using var foldersDoc = await GetJsonAsync(
            settings,
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=" + mid.ToString(CultureInfo.InvariantCulture),
            "https://space.bilibili.com/" + mid.ToString(CultureInfo.InvariantCulture) + "/favlist",
            false,
            cancellationToken);
        var folderRoot = foldersDoc.RootElement;
        var folderData = Child(folderRoot, "data");
        if (Code(folderRoot) != 0 || folderData.ValueKind != JsonValueKind.Object)
            throw new InvalidOperationException(Code(folderRoot) == 0 ? "登录有效，但没有读到收藏夹" : Detail(folderRoot, "无法读取收藏夹（" + Code(folderRoot) + "）"));
        var hits = new List<CrawlHit>();
        if (!folderData.TryGetProperty("list", out var list) || list.ValueKind != JsonValueKind.Array)
            return hits;
        foreach (var folder in list.EnumerateArray())
        {
            var folderId = Long(folder, "id");
            if (folderId <= 0 || hits.Count >= 200)
                continue;
            for (var page = 1; page <= 10 && hits.Count < 200; page++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var address = "https://api.bilibili.com/x/v3/fav/resource/list?media_id=" + folderId.ToString(CultureInfo.InvariantCulture)
                    + "&pn=" + page.ToString(CultureInfo.InvariantCulture) + "&ps=" + PageSize.ToString(CultureInfo.InvariantCulture) + "&platform=web";
                using var pageDoc = await GetJsonAsync(settings, address, "https://space.bilibili.com", false, cancellationToken);
                var pageRoot = pageDoc.RootElement;
                if (Code(pageRoot) != 0)
                    throw new InvalidOperationException(Detail(pageRoot, "无法读取收藏夹（" + Code(pageRoot) + "）"));
                var medias = Child(Child(pageRoot, "data"), "medias");
                var count = 0;
                if (medias.ValueKind == JsonValueKind.Array)
                {
                    foreach (var media in medias.EnumerateArray())
                    {
                        count++;
                        var id = Bvid().Match(Text(media, "bvid"));
                        if (!id.Success || hits.Count >= 200)
                            continue;
                        var title = Text(media, "title");
                        hits.Add(new CrawlHit(0, id.Value, title.Length == 0 ? id.Value : title, Text(Child(media, "upper"), "name"), ToHttps(Text(media, "cover")))
                        {
                            Origin = "bili",
                            AlbumTitle = title,
                            CoverReferer = "https://www.bilibili.com/",
                        });
                    }
                }

                var hasMore = Bool(Child(pageRoot, "data"), "has_more") || count >= PageSize;
                if (!hasMore)
                    break;
            }
        }

        return hits;
    }

    public static async Task<CrawlBatch> OpenAsync(ServiceSettings settings, string input, CancellationToken cancellationToken)
    {
        var text = input.Trim();
        if (text.Length == 0)
            throw new InvalidOperationException("请输入关键词");
        if (Bvid().IsMatch(text))
            return new CrawlBatch(await LookupAsync(settings, text, cancellationToken), null);
        return await SearchPageAsync(settings, text, 1, cancellationToken);
    }

    public static async Task<CrawlBatch> SearchPageAsync(ServiceSettings settings, string keyword, int page, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var mixin = await MixinAsync(settings, false, cancellationToken);
        var signed = Sign(new Dictionary<string, string>
        {
            ["search_type"] = "video",
            ["keyword"] = keyword,
            ["page"] = page.ToString(CultureInfo.InvariantCulture),
            ["page_size"] = "20",
        }, mixin);
        var address = WithQuery("https://api.bilibili.com/x/web-interface/wbi/search/type", signed);
        using var document = await GetJsonAsync(settings, address, "https://search.bilibili.com/all?keyword=" + Uri.EscapeDataString(keyword), false, cancellationToken);
        var root = document.RootElement;
        if (Code(root) != 0)
            throw new InvalidOperationException(Detail(root, "没有找到相关视频"));
        var result = Child(Child(root, "data"), "result");
        var hits = new List<CrawlHit>();
        if (result.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in result.EnumerateArray())
            {
                var id = Bvid().Match(Text(item, "bvid"));
                if (!id.Success)
                    continue;
                var title = Plain(Text(item, "title"));
                var author = Plain(Text(item, "author"));
                var length = Text(item, "duration");
                if (length.Length > 0)
                    author = author.Length == 0 ? length : author + " · " + length;
                hits.Add(new CrawlHit(0, id.Value, title.Length == 0 ? id.Value : title, author, ToHttps(Text(item, "pic")))
                {
                    Origin = "bili",
                    AlbumTitle = title.Length == 0 ? id.Value : title,
                    CoverReferer = "https://www.bilibili.com/",
                });
            }
        }

        if (hits.Count == 0)
        {
            if (page <= 1)
                throw new InvalidOperationException("没有找到相关视频");
            return new CrawlBatch([], null);
        }

        return new CrawlBatch(hits, (page + 1).ToString(CultureInfo.InvariantCulture));
    }

    private static string Plain(string text)
    {
        var stripped = Markup().Replace(text, "");
        return stripped
            .Replace("&amp;", "&", StringComparison.Ordinal)
            .Replace("&lt;", "<", StringComparison.Ordinal)
            .Replace("&gt;", ">", StringComparison.Ordinal)
            .Replace("&quot;", "\"", StringComparison.Ordinal)
            .Replace("&#39;", "'", StringComparison.Ordinal)
            .Trim();
    }

    public static async Task<IReadOnlyList<string>> DownloadAsync(
        ServiceSettings settings,
        CrawlHit hit,
        IProgress<CrawlProgress> progress,
        CancellationToken cancellationToken)
    {
        var parts = hit.RemoteCid > 0
            ? new List<CrawlHit> { hit }
            : (await LookupAsync(settings, hit.SourceId, cancellationToken)).ToList();
        if (parts.Count == 0)
            throw new InvalidOperationException("找不到这个视频");
        var saved = new List<string>();
        for (var i = 0; i < parts.Count; i++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var part = parts[i];
            var prefix = parts.Count == 1 ? "" : $"{i + 1}/{parts.Count}  ";
            var paths = await DownloadPartAsync(settings, part, progress, prefix, cancellationToken);
            saved.AddRange(paths);
        }

        return saved;
    }

    private static List<CrawlHit> Parts(JsonElement data, string fallbackBvid)
    {
        var bvid = Text(data, "bvid");
        if (bvid.Length == 0)
            bvid = fallbackBvid;
        var album = Text(data, "title");
        var owner = Text(Child(data, "owner"), "name");
        var cover = ToHttps(Text(data, "pic"));
        var pages = new List<JsonElement>();
        if (data.TryGetProperty("pages", out var pageList) && pageList.ValueKind == JsonValueKind.Array)
        {
            foreach (var page in pageList.EnumerateArray())
                pages.Add(page);
        }

        if (pages.Count == 0)
        {
            return
            [
                new CrawlHit(Long(data, "cid"), bvid, album.Length == 0 ? bvid : album, owner, cover)
                {
                    RemoteCid = Long(data, "cid"),
                    Page = 1,
                    Origin = "bili",
                    AlbumTitle = album,
                    CoverReferer = "https://www.bilibili.com/",
                },
            ];
        }

        var hits = new List<CrawlHit>(pages.Count);
        foreach (var page in pages)
        {
            var cid = Long(page, "cid");
            var number = (int)Long(page, "page");
            if (number <= 0)
                number = hits.Count + 1;
            var part = Text(page, "part");
            var title = pages.Count > 1 && part.Length > 0 ? album + " - " + part : album;
            if (title.Length == 0)
                title = bvid;
            hits.Add(new CrawlHit(cid, bvid, title, owner, cover)
            {
                RemoteCid = cid,
                Page = number,
                Origin = "bili",
                AlbumTitle = album,
                CoverReferer = "https://www.bilibili.com/",
            });
        }

        return hits;
    }

    private static async Task<IReadOnlyList<string>> DownloadPartAsync(
        ServiceSettings settings,
        CrawlHit hit,
        IProgress<CrawlProgress> progress,
        string prefix,
        CancellationToken cancellationToken)
    {
        var audio = await ResolveAudioAsync(settings, hit.SourceId, hit.RemoteCid, cancellationToken);
        var root = Path.Combine(TrackLoader.LibraryRoot(), Safe(hit.SourceId));
        Directory.CreateDirectory(root);
        WriteWork(root, hit.AlbumTitle.Length == 0 ? hit.Title : hit.AlbumTitle, hit.Circle);
        var stem = hit.Page > 1 ? $"{hit.Page:00} {Safe(hit.Title)}" : Safe(hit.Title);
        var extension = audio.Mime.Contains("webm", StringComparison.OrdinalIgnoreCase) ? ".webm" : ".m4a";
        var path = Path.Combine(root, stem + extension);
        var steps = 3;
        if (File.Exists(path) && new FileInfo(path).Length > 0)
        {
            Report(progress, prefix, 1, steps, hit.Title, -1, -1, true);
        }
        else
        {
            Report(progress, prefix, 1, steps, hit.Title, 0, -1, false);
            await DownloadFileAsync(settings, audio, hit.SourceId, path, (received, total) =>
                Report(progress, prefix, 1, steps, hit.Title, received, total, false), cancellationToken);
        }

        await TryCoverAsync(audio.CoverUrl.Length == 0 ? hit.CoverUrl : audio.CoverUrl, root, progress, prefix, cancellationToken);
        await TrySubtitleAsync(settings, hit, path, progress, prefix, cancellationToken);
        return [path];
    }

    private readonly record struct AudioChoice(string Url, string Mime, string InitRange, string CoverUrl);

    private static async Task<AudioChoice> ResolveAudioAsync(ServiceSettings settings, string bvid, long cid, CancellationToken cancellationToken)
    {
        try
        {
            return await RequestAudioAsync(settings, bvid, cid, false, cancellationToken);
        }
        catch (BiliException ex) when (ex.Code is -403 or -400 or -412)
        {
            settings.BiliTicket = "";
            settings.BiliTicketExpires = 0;
            settings.Save();
            return await RequestAudioAsync(settings, bvid, cid, true, cancellationToken);
        }
    }

    private static async Task<AudioChoice> RequestAudioAsync(ServiceSettings settings, string bvid, long cid, bool refreshKey, CancellationToken cancellationToken)
    {
        var signed = Sign(new Dictionary<string, string>
        {
            ["bvid"] = bvid,
            ["cid"] = cid.ToString(CultureInfo.InvariantCulture),
            ["qn"] = "127",
            ["fnver"] = "0",
            ["fnval"] = "4048",
            ["fourk"] = "1",
            ["platform"] = "pc",
        }, await MixinAsync(settings, refreshKey, cancellationToken));
        using var document = await GetJsonAsync(settings, WithQuery("https://api.bilibili.com/x/player/wbi/playurl", signed), "https://www.bilibili.com/video/" + bvid, false, cancellationToken);
        var root = document.RootElement;
        var data = Child(root, "data");
        if (Code(root) != 0 || data.ValueKind != JsonValueKind.Object)
            throw new BiliException(Code(root), Code(root) == -404 ? "找不到这个视频" : Detail(root, "B站返回错误 " + Code(root)));
        var dash = Child(data, "dash");
        JsonElement? best = null;
        var bestBandwidth = -1;
        JsonElement? fallback = null;
        var fallbackBandwidth = -1;
        foreach (var stream in AudioStreams(dash))
        {
            if (PlayUrl(stream).Length == 0)
                continue;
            var bandwidth = (int)Long(stream, "bandwidth");
            if (bandwidth > fallbackBandwidth)
            {
                fallbackBandwidth = bandwidth;
                fallback = stream;
            }

            var codec = Text(stream, "codecs").ToLowerInvariant();
            var desktop = codec.Length == 0 || codec.Contains("mp4a", StringComparison.Ordinal) || codec.Contains("flac", StringComparison.Ordinal) || codec.Contains("alac", StringComparison.Ordinal);
            if (desktop && bandwidth > bestBandwidth)
            {
                bestBandwidth = bandwidth;
                best = stream;
            }
        }

        best ??= fallback;

        if (best == null)
            throw new InvalidOperationException("没有可播放的音轨，可能需要登录或大会员");
        var chosen = best.Value;
        var segment = Child(chosen, "SegmentBase");
        if (segment.ValueKind != JsonValueKind.Object)
            segment = Child(chosen, "segment_base");
        var init = Text(segment, "Initialization");
        if (init.Length == 0)
            init = Text(segment, "initialization");
        var mime = Text(chosen, "mimeType");
        if (mime.Length == 0)
            mime = Text(chosen, "mime_type");
        return new AudioChoice(PlayUrl(chosen), mime.Length == 0 ? "audio/mp4" : mime, init, "");
    }

    private static IEnumerable<JsonElement> AudioStreams(JsonElement dash)
    {
        if (dash.ValueKind != JsonValueKind.Object)
            yield break;
        if (dash.TryGetProperty("audio", out var audio) && audio.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in audio.EnumerateArray())
                yield return item;
        }

        if (dash.TryGetProperty("dolby", out var dolby))
        {
            var dolbyAudio = Child(dolby, "audio");
            if (dolbyAudio.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in dolbyAudio.EnumerateArray())
                    yield return item;
            }
            else if (dolbyAudio.ValueKind == JsonValueKind.Object)
            {
                yield return dolbyAudio;
            }
        }

        var flac = Child(Child(dash, "flac"), "audio");
        if (flac.ValueKind == JsonValueKind.Object)
            yield return flac;
    }

    private static string PlayUrl(JsonElement stream)
    {
        var url = Text(stream, "baseUrl", "base_url");
        if (url.Length > 0)
            return ToHttps(url);
        foreach (var name in new[] { "backupUrl", "backup_url" })
        {
            if (!stream.TryGetProperty(name, out var backup) || backup.ValueKind != JsonValueKind.Array)
                continue;
            foreach (var item in backup.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.String && item.GetString() is { Length: > 0 } text)
                    return ToHttps(text);
            }
        }

        return "";
    }

    private static async Task DownloadFileAsync(
        ServiceSettings settings,
        AudioChoice audio,
        string bvid,
        string path,
        Action<long, long> onBytes,
        CancellationToken cancellationToken)
    {
        var partial = path + ".part";
        try
        {
            await SaveMediaAsync(settings, audio.Url, bvid, partial, onBytes, false, cancellationToken);
            if (!IsFtyp(partial) && TryRange(audio.InitRange, out var start, out var end))
            {
                var initPath = path + ".init";
                try
                {
                    await SaveMediaAsync(settings, audio.Url, bvid, initPath, (_, _) => { }, true, cancellationToken, start, end);
                    if (IsFtyp(initPath))
                    {
                        var merged = path + ".merged";
                        await using (var output = File.Create(merged))
                        {
                            await using (var init = File.OpenRead(initPath))
                                await init.CopyToAsync(output, cancellationToken);
                            await using (var body = File.OpenRead(partial))
                                await body.CopyToAsync(output, cancellationToken);
                        }

                        File.Move(merged, partial, true);
                    }
                }
                finally
                {
                    TryDelete(initPath);
                }
            }

            if (!IsFtyp(partial) && new FileInfo(partial).Length < 32 * 1024)
                throw new InvalidOperationException("音轨下不来");
            File.Move(partial, path, true);
        }
        catch
        {
            TryDelete(partial);
            throw;
        }
    }

    private static async Task SaveMediaAsync(
        ServiceSettings settings,
        string url,
        string bvid,
        string path,
        Action<long, long> onBytes,
        bool ranged,
        CancellationToken cancellationToken,
        long start = 0,
        long end = 0)
    {
        using var response = await MediaResponseAsync(settings, url, bvid, ranged, start, end, false, cancellationToken)
            ?? await MediaResponseAsync(settings, url, bvid, ranged, start, end, true, cancellationToken)
            ?? throw new InvalidOperationException("音轨下不来");
        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        var total = response.Content.Headers.ContentLength ?? -1;
        await using var output = File.Create(path);
        var buffer = new byte[64 * 1024];
        long received = 0;
        while (true)
        {
            var read = await input.ReadAsync(buffer, cancellationToken);
            if (read == 0)
                break;
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            received += read;
            onBytes(received, total);
        }
    }

    private static async Task<HttpResponseMessage?> MediaResponseAsync(
        ServiceSettings settings,
        string url,
        string bvid,
        bool ranged,
        long start,
        long end,
        bool withCookie,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        request.Headers.TryAddWithoutValidation("Referer", "https://www.bilibili.com/video/" + bvid);
        request.Headers.TryAddWithoutValidation("Origin", "https://www.bilibili.com");
        if (withCookie)
        {
            var cookie = CookieHeader(settings, false);
            if (cookie.Length > 0)
                request.Headers.TryAddWithoutValidation("Cookie", cookie);
        }

        if (ranged)
            request.Headers.TryAddWithoutValidation("Range", "bytes=" + start.ToString(CultureInfo.InvariantCulture) + "-" + end.ToString(CultureInfo.InvariantCulture));
        var response = await Media.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (response.IsSuccessStatusCode)
            return response;
        response.Dispose();
        return null;
    }

    private static async Task TryCoverAsync(string? url, string root, IProgress<CrawlProgress> progress, string prefix, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(url))
            return;
        var cover = Path.Combine(root, "cover.jpg");
        if (File.Exists(cover) && new FileInfo(cover).Length > 0)
        {
            Report(progress, prefix, 2, 3, "封面", -1, -1, true);
            return;
        }

        try
        {
            Report(progress, prefix, 2, 3, "封面", 0, -1, false);
            using var request = new HttpRequestMessage(HttpMethod.Get, ToHttps(url));
            request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
            request.Headers.TryAddWithoutValidation("Referer", "https://www.bilibili.com/");
            using var response = await Media.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (!response.IsSuccessStatusCode)
                return;
            var partial = cover + ".part";
            await using (var input = await response.Content.ReadAsStreamAsync(cancellationToken))
            await using (var output = File.Create(partial))
                await input.CopyToAsync(output, cancellationToken);
            File.Move(partial, cover, true);
            Report(progress, prefix, 2, 3, "封面", 1, 1, false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            TryDelete(Path.Combine(root, "cover.jpg.part"));
        }
    }

    private static async Task TrySubtitleAsync(
        ServiceSettings settings,
        CrawlHit hit,
        string audioPath,
        IProgress<CrawlProgress> progress,
        string prefix,
        CancellationToken cancellationToken)
    {
        var srt = Path.ChangeExtension(audioPath, ".srt");
        if (File.Exists(srt) && new FileInfo(srt).Length > 0)
        {
            Report(progress, prefix, 3, 3, "字幕", -1, -1, true);
            return;
        }

        try
        {
            Report(progress, prefix, 3, 3, "字幕", 0, -1, false);
            var subtitleUrl = await SubtitleUrlAsync(settings, hit.SourceId, hit.RemoteCid, cancellationToken);
            if (subtitleUrl == null)
            {
                Report(progress, prefix, 3, 3, "字幕", -1, -1, true);
                return;
            }

            using var document = await GetJsonAsync(settings, subtitleUrl, "https://www.bilibili.com/video/" + hit.SourceId, false, cancellationToken);
            var body = Child(document.RootElement, "body");
            var cues = new List<SubtitleCue>();
            if (body.ValueKind == JsonValueKind.Array)
            {
                foreach (var line in body.EnumerateArray())
                {
                    var text = Text(line, "content").Trim();
                    if (text.Length == 0)
                        continue;
                    var start = (long)(Double(line, "from") * 1000);
                    var end = (long)(Double(line, "to") * 1000);
                    if (end <= start)
                        end = start + 500;
                    cues.Add(new SubtitleCue(start, end, text));
                }
            }

            if (cues.Count > 0)
                CueDocument.WriteSrt(srt, cues);
            Report(progress, prefix, 3, 3, "字幕", 1, 1, false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
        }
    }

    private static async Task<string?> SubtitleUrlAsync(ServiceSettings settings, string bvid, long cid, CancellationToken cancellationToken)
    {
        JsonDocument? document = null;
        try
        {
            document = await PlayerInfoAsync(settings, bvid, cid, false, cancellationToken);
            var code = Code(document.RootElement);
            if (code is -403 or -400 or -412)
            {
                document.Dispose();
                settings.BiliTicket = "";
                settings.BiliTicketExpires = 0;
                settings.Save();
                document = await PlayerInfoAsync(settings, bvid, cid, true, cancellationToken);
            }

            var root = document.RootElement;
            if (Code(root) != 0)
                return null;
            var data = Child(root, "data");
            var returnedBvid = Text(data, "bvid");
            var returnedCid = Long(data, "cid");
            if ((returnedBvid.Length > 0 && returnedBvid != bvid) || (returnedCid != 0 && returnedCid != cid))
                return null;
            var list = Child(Child(data, "subtitle"), "subtitles");
            if (list.ValueKind != JsonValueKind.Array)
                return null;
            string? chosen = null;
            var rank = int.MaxValue;
            foreach (var item in list.EnumerateArray())
            {
                var url = ToHttps(Text(item, "subtitle_url"));
                if (url.Length == 0)
                    continue;
                var language = Text(item, "lan").ToLowerInvariant();
                var itemRank = language.StartsWith("zh", StringComparison.Ordinal) ? 0 : language.Contains("zh", StringComparison.Ordinal) ? 1 : 2;
                if (itemRank < rank)
                {
                    rank = itemRank;
                    chosen = url;
                }
            }

            return chosen;
        }
        finally
        {
            document?.Dispose();
        }
    }

    private static async Task<JsonDocument> PlayerInfoAsync(ServiceSettings settings, string bvid, long cid, bool refreshKey, CancellationToken cancellationToken)
    {
        var signed = Sign(new Dictionary<string, string>
        {
            ["bvid"] = bvid,
            ["cid"] = cid.ToString(CultureInfo.InvariantCulture),
        }, await MixinAsync(settings, refreshKey, cancellationToken));
        return await GetJsonAsync(settings, WithQuery("https://api.bilibili.com/x/player/wbi/v2", signed), "https://www.bilibili.com/video/" + bvid, false, cancellationToken);
    }

    private static async Task<long> LoginMidAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        using var document = await GetJsonAsync(settings, "https://api.bilibili.com/x/web-interface/nav", "https://www.bilibili.com", false, cancellationToken);
        var mid = Long(Child(document.RootElement, "data"), "mid");
        if (Code(document.RootElement) != 0 || mid <= 0)
            throw new InvalidOperationException("登录信息无效或已过期，请重新粘贴 SESSDATA");
        return mid;
    }

    private static void RequireSession(ServiceSettings settings)
    {
        var raw = settings.BiliCookie.Trim();
        if (raw.Length == 0)
            throw new InvalidOperationException("请先在设置里保存登录信息");
        if (raw.Contains('=', StringComparison.Ordinal) && !raw.Contains("SESSDATA=", StringComparison.Ordinal))
            throw new InvalidOperationException("保存的内容里没有 SESSDATA，请重新粘贴");
    }

    private static async Task<JsonDocument> GetJsonAsync(ServiceSettings settings, string url, string referer, bool guestOnly, CancellationToken cancellationToken)
    {
        var exchange = await ExchangeAsync(settings, HttpMethod.Get, url, referer, guestOnly, null, false, cancellationToken);
        try
        {
            return JsonDocument.Parse(exchange.Body);
        }
        catch (JsonException)
        {
            throw new InvalidOperationException("B站没有返回播放数据，请检查登录信息或稍后再试");
        }
    }

    private static async Task<Exchange> ExchangeAsync(
        ServiceSettings settings,
        HttpMethod method,
        string url,
        string referer,
        bool guestOnly,
        HttpContent? content,
        bool allowHtml,
        CancellationToken cancellationToken)
    {
        await PrepareIdentityAsync(settings, cancellationToken);
        using var request = new HttpRequestMessage(method, url) { Content = content };
        var cookie = CookieHeader(settings, guestOnly);
        if (cookie.Length > 0)
            request.Headers.TryAddWithoutValidation("Cookie", cookie);
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        request.Headers.TryAddWithoutValidation("Referer", referer);
        request.Headers.TryAddWithoutValidation("Origin", OriginOf(referer));
        request.Headers.TryAddWithoutValidation("Accept", "application/json, text/plain, */*");
        request.Headers.TryAddWithoutValidation("Accept-Language", "zh-CN,zh;q=0.9");
        HttpResponseMessage response;
        try
        {
            response = await Api.SendAsync(request, cancellationToken);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new InvalidOperationException("网络请求超时");
        }

        using (response)
        {
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!allowHtml && body.TrimStart().StartsWith('<'))
                throw new BiliException(-412, "B站没有返回播放数据，请检查登录信息或稍后再试");
            var status = (int)response.StatusCode;
            if (status is >= 300 and <= 399 || (!response.IsSuccessStatusCode && body.Length == 0))
                throw new InvalidOperationException("网络请求失败");
            var cookies = new List<string>();
            if (response.Headers.TryGetValues("Set-Cookie", out var values))
                cookies.AddRange(values);
            return new Exchange(body, cookies);
        }
    }

    private static async Task PrepareIdentityAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        await IdentityLock.WaitAsync(cancellationToken);
        try
        {
            if (settings.BiliBuvid3.Length == 0 || !settings.BiliBuvid3.Contains('-', StringComparison.Ordinal))
            {
                try
                {
                    var exchange = await SendRawAsync(settings, HttpMethod.Get, "https://api.bilibili.com/x/frontend/finger/spi", "https://www.bilibili.com", true, null, false, cancellationToken);
                    using var document = JsonDocument.Parse(exchange.Body);
                    var data = Child(document.RootElement, "data");
                    var buvid3 = Text(data, "b_3");
                    var buvid4 = Text(data, "b_4");
                    if (Code(document.RootElement) == 0 && buvid3.Length > 0)
                    {
                        settings.BiliBuvid3 = buvid3;
                        settings.BiliBuvid4 = buvid4;
                        settings.Save();
                    }
                }
                catch (OperationCanceledException)
                {
                    throw;
                }
                catch
                {
                }
            }

            if (settings.BiliTicketExpires <= DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 60)
            {
                try
                {
                    await FetchTicketAsync(settings, cancellationToken);
                }
                catch (OperationCanceledException)
                {
                    throw;
                }
                catch
                {
                }
            }

            await MaybeRefreshAsync(settings, cancellationToken);
        }
        finally
        {
            IdentityLock.Release();
        }
    }

    private static async Task FetchTicketAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        var ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var sign = HmacSha256(TicketKey, "ts" + ts.ToString(CultureInfo.InvariantCulture));
        var url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket?key_id=ec02&hexsign="
            + sign + "&context%5Bts%5D=" + ts.ToString(CultureInfo.InvariantCulture) + "&csrf=";
        var exchange = await SendRawAsync(settings, HttpMethod.Post, url, "https://www.bilibili.com", true, new ByteArrayContent([]), false, cancellationToken);
        using var document = JsonDocument.Parse(exchange.Body);
        var data = Child(document.RootElement, "data");
        var ticket = Text(data, "ticket");
        if (Code(document.RootElement) != 0 || ticket.Length == 0)
            return;
        var created = Long(data, "created_at");
        if (created <= 0)
            created = ts;
        var ttl = Long(data, "ttl");
        if (ttl <= 0)
            ttl = TicketTtlSec;
        settings.BiliTicket = ticket;
        settings.BiliTicketExpires = created + ttl;
        settings.Save();
    }

    private static async Task MaybeRefreshAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        if (settings.BiliRefreshToken.Length == 0)
            return;
        if (DateTimeOffset.Now.ToUnixTimeMilliseconds() - settings.BiliRefreshChecked < RefreshIntervalMs)
            return;
        var csrf = Field(settings.BiliCookie, "bili_jct") ?? "";
        var infoUrl = "https://passport.bilibili.com/x/passport-login/web/cookie/info";
        if (csrf.Length > 0)
            infoUrl += "?csrf=" + Uri.EscapeDataString(csrf);
        Exchange info;
        try
        {
            info = await SendRawAsync(settings, HttpMethod.Get, infoUrl, "https://www.bilibili.com", false, null, false, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return;
        }

        using var document = JsonDocument.Parse(info.Body);
        var root = document.RootElement;
        var data = Child(root, "data");
        var needs = Code(root) == -101 || Bool(data, "refresh");
        try
        {
            if (needs)
                await RefreshSessionAsync(settings, settings.BiliRefreshToken, Long(data, "timestamp"), cancellationToken);
            settings.BiliRefreshChecked = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            settings.Save();
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
        }
    }

    private static async Task RefreshSessionAsync(ServiceSettings settings, string oldToken, long timestamp, CancellationToken cancellationToken)
    {
        var csrf = Field(settings.BiliCookie, "bili_jct");
        if (csrf == null)
            return;
        var stamp = timestamp > 0 ? timestamp : DateTimeOffset.Now.ToUnixTimeMilliseconds();
        var page = await SendRawAsync(settings, HttpMethod.Get, "https://www.bilibili.com/correspond/1/" + CorrespondPath(stamp), "https://www.bilibili.com", false, null, true, cancellationToken);
        var refreshCsrf = RefreshCsrf().Match(page.Body);
        if (!refreshCsrf.Success)
            return;
        var form = new Dictionary<string, string>
        {
            ["csrf"] = csrf,
            ["refresh_csrf"] = refreshCsrf.Groups[1].Value.Trim(),
            ["source"] = "main_web",
            ["refresh_token"] = oldToken,
        };
        var refreshed = await SendRawAsync(
            settings,
            HttpMethod.Post,
            "https://passport.bilibili.com/x/passport-login/web/cookie/refresh",
            "https://www.bilibili.com",
            false,
            new FormUrlEncodedContent(form),
            false,
            cancellationToken);
        using var document = JsonDocument.Parse(refreshed.Body);
        var newToken = Text(Child(document.RootElement, "data"), "refresh_token");
        var cookie = CombineCookies(CookiesFromSetCookie(refreshed.SetCookies), settings.BiliCookie);
        if (Code(document.RootElement) != 0 || newToken.Length == 0 || Field(cookie, "SESSDATA") == null)
            return;
        settings.BiliCookie = cookie;
        settings.BiliRefreshToken = newToken;
        settings.Save();
        var newCsrf = Field(cookie, "bili_jct");
        if (newCsrf == null)
            return;
        await SendRawAsync(
            settings,
            HttpMethod.Post,
            "https://passport.bilibili.com/x/passport-login/web/confirm/refresh",
            "https://www.bilibili.com",
            false,
            new FormUrlEncodedContent(new Dictionary<string, string> { ["csrf"] = newCsrf, ["refresh_token"] = oldToken }),
            false,
            cancellationToken);
    }

    private static async Task<Exchange> SendRawAsync(
        ServiceSettings settings,
        HttpMethod method,
        string url,
        string referer,
        bool guestOnly,
        HttpContent? content,
        bool allowHtml,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(method, url) { Content = content };
        var cookie = CookieHeader(settings, guestOnly);
        if (cookie.Length > 0)
            request.Headers.TryAddWithoutValidation("Cookie", cookie);
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        request.Headers.TryAddWithoutValidation("Referer", referer);
        request.Headers.TryAddWithoutValidation("Origin", OriginOf(referer));
        request.Headers.TryAddWithoutValidation("Accept", allowHtml ? "text/html,application/json,*/*" : "application/json, text/plain, */*");
        HttpResponseMessage response;
        try
        {
            response = await Api.SendAsync(request, cancellationToken);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new InvalidOperationException("网络请求超时");
        }

        using (response)
        {
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!allowHtml && body.TrimStart().StartsWith('<'))
                throw new BiliException(-412, "B站没有返回播放数据，请检查登录信息或稍后再试");
            var cookies = new List<string>();
            if (response.Headers.TryGetValues("Set-Cookie", out var values))
                cookies.AddRange(values);
            return new Exchange(body, cookies);
        }
    }

    private static async Task<string> MixinAsync(ServiceSettings settings, bool force, CancellationToken cancellationToken)
    {
        var cached = _mixinKey;
        if (!force && cached != null && DateTimeOffset.Now.ToUnixTimeMilliseconds() - _mixinAt < KeyTtlMs)
            return cached;
        using var document = await GetJsonAsync(settings, "https://api.bilibili.com/x/web-interface/nav", "https://www.bilibili.com", false, cancellationToken);
        var wbi = Child(Child(document.RootElement, "data"), "wbi_img");
        var img = Text(wbi, "img_url");
        var sub = Text(wbi, "sub_url");
        if (img.Length == 0 || sub.Length == 0)
            throw new InvalidOperationException("暂时无法连接 B 站");
        var key = Mixin(KeyFromUrl(img), KeyFromUrl(sub));
        _mixinKey = key;
        _mixinAt = DateTimeOffset.Now.ToUnixTimeMilliseconds();
        return key;
    }

    private static string Mixin(string imgKey, string subKey)
    {
        var raw = imgKey + subKey;
        var mixed = new StringBuilder();
        foreach (var index in MixinTab)
        {
            if (index < raw.Length)
                mixed.Append(raw[index]);
        }

        var text = mixed.ToString();
        return text.Length <= 32 ? text : text[..32];
    }

    private static Dictionary<string, string> Sign(Dictionary<string, string> parameters, string mixinKey)
    {
        var filtered = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in parameters)
            filtered[pair.Key] = new string(pair.Value.Where(ch => ch is not ('!' or '\'' or '(' or ')' or '*')).ToArray());
        filtered["wts"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture);
        var query = string.Join("&", filtered.Select(pair => Uri.EscapeDataString(pair.Key) + "=" + Uri.EscapeDataString(pair.Value)));
        var signed = new Dictionary<string, string>(filtered) { ["w_rid"] = Md5(query + mixinKey) };
        return signed;
    }

    private static string WithQuery(string url, Dictionary<string, string> query)
    {
        var builder = new StringBuilder(url);
        builder.Append(url.Contains('?', StringComparison.Ordinal) ? '&' : '?');
        var first = true;
        foreach (var pair in query)
        {
            if (!first)
                builder.Append('&');
            first = false;
            builder.Append(Uri.EscapeDataString(pair.Key)).Append('=').Append(Uri.EscapeDataString(pair.Value));
        }

        return builder.ToString();
    }

    private static string CookieHeader(ServiceSettings settings, bool guestOnly)
    {
        var parts = new List<string>();
        if (settings.BiliBuvid3.Contains('-', StringComparison.Ordinal))
            parts.Add("buvid3=" + settings.BiliBuvid3);
        if (settings.BiliBuvid4.Length > 0)
            parts.Add("buvid4=" + settings.BiliBuvid4);
        if (!guestOnly && settings.BiliTicket.Length > 0)
            parts.Add("bili_ticket=" + settings.BiliTicket);
        if (!guestOnly && settings.BiliTicketExpires > 0)
            parts.Add("bili_ticket_expires=" + settings.BiliTicketExpires.ToString(CultureInfo.InvariantCulture));
        if (!guestOnly)
        {
            var user = settings.BiliCookie.Replace("\r", "", StringComparison.Ordinal).Replace("\n", "", StringComparison.Ordinal).Trim();
            if (user.Length > 0)
                parts.Add(user.Contains('=', StringComparison.Ordinal) ? StripManaged(user) : "SESSDATA=" + user);
        }

        return string.Join("; ", parts.Where(part => part.Length > 0));
    }

    private static string StripManaged(string header) =>
        string.Join("; ", header.Split(';').Select(part => part.Trim()).Where(part =>
        {
            var key = part.Split('=')[0].Trim();
            return key.Length > 0 && !ManagedCookies.Contains(key);
        }));

    private static string CookiesFromUrl(string url)
    {
        var query = url.Contains('?', StringComparison.Ordinal) ? url.Split('?', 2)[1].Split('#')[0] : "";
        var pairs = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var part in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var cut = part.IndexOf('=');
            if (cut <= 0)
                continue;
            var name = Uri.UnescapeDataString(part[..cut]);
            if (!SessionNames.Contains(name, StringComparer.Ordinal))
                continue;
            var value = Uri.UnescapeDataString(part[(cut + 1)..]);
            if (value.Length > 0)
                pairs[name] = value;
        }

        return SessionCookies(pairs);
    }

    private static string CookiesFromSetCookie(IEnumerable<string> headers)
    {
        var pairs = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var header in headers)
        {
            var first = header.Split(';')[0].Trim();
            var cut = first.IndexOf('=');
            if (cut <= 0)
                continue;
            var name = first[..cut].Trim();
            if (!SessionNames.Contains(name, StringComparer.Ordinal))
                continue;
            var value = first[(cut + 1)..].Trim().Trim('"');
            if (value.Length > 0)
                pairs[name] = value;
        }

        return SessionCookies(pairs);
    }

    private static string CombineCookies(string primary, string fallback)
    {
        var pairs = new Dictionary<string, string>(StringComparer.Ordinal);
        void Absorb(string header, bool overwrite)
        {
            foreach (var part in header.Split(';'))
            {
                var trimmed = part.Trim();
                var cut = trimmed.IndexOf('=');
                if (cut <= 0)
                    continue;
                var name = trimmed[..cut].Trim();
                if (!SessionNames.Contains(name, StringComparer.Ordinal))
                    continue;
                var value = trimmed[(cut + 1)..];
                if (value.Length == 0)
                    continue;
                if (overwrite || !pairs.ContainsKey(name))
                    pairs[name] = value;
            }
        }

        Absorb(fallback, false);
        Absorb(primary, true);
        return SessionCookies(pairs);
    }

    private static string SessionCookies(Dictionary<string, string> pairs)
    {
        var parts = new List<string>();
        foreach (var name in SessionNames)
        {
            if (!pairs.TryGetValue(name, out var value) || value.Trim().Length == 0)
                continue;
            var stored = name == "SESSDATA" ? value.Trim().Replace(",", "%2C", StringComparison.Ordinal) : value.Trim();
            parts.Add(name + "=" + stored);
        }

        return parts.Any(part => part.StartsWith("SESSDATA=", StringComparison.Ordinal)) ? string.Join("; ", parts) : "";
    }

    private static string? Field(string header, string name)
    {
        foreach (var part in header.Split(';'))
        {
            var trimmed = part.Trim();
            var cut = trimmed.IndexOf('=');
            if (cut <= 0)
                continue;
            if (trimmed[..cut].Trim() == name)
            {
                var value = trimmed[(cut + 1)..];
                return value.Length == 0 ? null : value;
            }
        }

        return null;
    }

    private static string CorrespondPath(long timestampMs)
    {
        using var rsa = RSA.Create();
        rsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(CorrespondKey), out _);
        var encrypted = rsa.Encrypt(Encoding.UTF8.GetBytes("refresh_" + timestampMs.ToString(CultureInfo.InvariantCulture)), RSAEncryptionPadding.OaepSHA256);
        return Convert.ToHexString(encrypted).ToLowerInvariant();
    }

    private const string CorrespondKey =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB";

    private static string HmacSha256(string key, string message)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(key));
        return Convert.ToHexString(hmac.ComputeHash(Encoding.UTF8.GetBytes(message))).ToLowerInvariant();
    }

    private static string Md5(string value)
    {
        var hash = MD5.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static string KeyFromUrl(string url)
    {
        var name = url.Split('?')[0];
        var slash = name.LastIndexOf('/');
        if (slash >= 0)
            name = name[(slash + 1)..];
        var dot = name.IndexOf('.');
        return dot > 0 ? name[..dot] : name;
    }

    private static string OriginOf(string referer)
    {
        if (!referer.Contains("://", StringComparison.Ordinal))
            return "https://www.bilibili.com";
        var scheme = referer.Split("://", 2)[0];
        var host = referer.Split("://", 2)[1].Split('/')[0];
        return host.Length == 0 ? "https://www.bilibili.com" : scheme + "://" + host;
    }

    private static string ToHttps(string url)
    {
        if (url.StartsWith("//", StringComparison.Ordinal))
            return "https:" + url;
        if (url.StartsWith("http://", StringComparison.Ordinal))
            return "https://" + url[7..];
        return url;
    }

    private static bool IsFtyp(string path)
    {
        try
        {
            using var stream = File.OpenRead(path);
            var buffer = new byte[12];
            return stream.Read(buffer, 0, buffer.Length) >= 8
                && buffer[4] == (byte)'f' && buffer[5] == (byte)'t' && buffer[6] == (byte)'y' && buffer[7] == (byte)'p';
        }
        catch
        {
            return false;
        }
    }

    private static bool TryRange(string text, out long start, out long end)
    {
        start = 0;
        end = 0;
        var cut = text.IndexOf('-');
        return cut > 0
            && long.TryParse(text[..cut], NumberStyles.Integer, CultureInfo.InvariantCulture, out start)
            && long.TryParse(text[(cut + 1)..], NumberStyles.Integer, CultureInfo.InvariantCulture, out end)
            && end >= start;
    }

    private static void WriteWork(string root, string title, string circle)
    {
        try
        {
            var path = Path.Combine(root, "work.json");
            if (File.Exists(path))
                return;
            File.WriteAllText(path, JsonSerializer.Serialize(new Dictionary<string, string>
            {
                ["title"] = title,
                ["circle"] = circle,
            }));
        }
        catch (IOException)
        {
        }
    }

    private static void Report(IProgress<CrawlProgress> progress, string prefix, int index, int steps, string name, long received, long total, bool existing)
    {
        var inner = existing ? 1d : total > 0 ? Math.Clamp((double)received / total, 0, 1) : received > 0 ? 0.5 : 0;
        var ratio = steps <= 0 ? 0 : (index - 1 + inner) / steps;
        var detail = existing ? "已有" : total > 0 ? $"{(int)Math.Round(inner * 100)}%  {Size(received)} / {Size(total)}" : received > 0 ? Size(received) : "开始";
        progress.Report(new CrawlProgress(prefix + $"{index}/{steps}  {name}  {detail}", ratio));
    }

    private static string Size(long bytes)
    {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return (bytes / 1024d).ToString("0.0", CultureInfo.InvariantCulture) + " KB";
        return (bytes / 1024d / 1024d).ToString("0.0", CultureInfo.InvariantCulture) + " MB";
    }

    private static string Safe(string name)
    {
        foreach (var c in Path.GetInvalidFileNameChars())
            name = name.Replace(c, '_');
        var cleaned = name.Trim();
        if (cleaned.Length > 80)
            cleaned = cleaned[..80].Trim();
        return cleaned.Length == 0 ? "audio" : cleaned;
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
                File.Delete(path);
        }
        catch (IOException)
        {
        }
    }

    private static HttpClient CreateClient(bool redirect, TimeSpan timeout)
    {
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = redirect,
            AutomaticDecompression = DecompressionMethods.All,
            UseCookies = false,
        };
        var client = new HttpClient(handler) { Timeout = timeout };
        client.DefaultRequestVersion = HttpVersion.Version11;
        client.DefaultVersionPolicy = HttpVersionPolicy.RequestVersionOrLower;
        return client;
    }

    private static int Code(JsonElement element) => (int)Long(element, "code");

    private static JsonElement Child(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) ? value : default;

    private static string Text(JsonElement element, params string[] names)
    {
        foreach (var name in names)
        {
            if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String)
                return value.GetString()?.Trim() ?? "";
        }

        return "";
    }

    private static long Long(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value))
            return 0;
        if (value.ValueKind == JsonValueKind.Number)
            return value.TryGetInt64(out var number) ? number : (long)value.GetDouble();
        if (value.ValueKind == JsonValueKind.String && long.TryParse(value.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed))
            return parsed;
        return 0;
    }

    private static double Double(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value))
            return 0;
        if (value.ValueKind == JsonValueKind.Number)
            return value.GetDouble();
        if (value.ValueKind == JsonValueKind.String && double.TryParse(value.GetString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed))
            return parsed;
        return 0;
    }

    private static bool Bool(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object
        && element.TryGetProperty(name, out var value)
        && ((value.ValueKind == JsonValueKind.True) || (value.ValueKind == JsonValueKind.String && value.GetString() == "true"));

    private static string Detail(JsonElement root, string fallback)
    {
        var text = Text(root, "message");
        if (text.Length == 0 || text == "0")
            text = Text(root, "msg");
        return text.Length == 0 || text == "0" ? fallback : text;
    }

    [GeneratedRegex("BV[0-9A-Za-z]{10}")]
    private static partial Regex Bvid();

    [GeneratedRegex("<[^>]+>")]
    private static partial Regex Markup();

    [GeneratedRegex("""id\s*=\s*["']1-name["']\s*>([^<]+)<""")]
    private static partial Regex RefreshCsrf();

    private sealed record Exchange(string Body, List<string> SetCookies);

    private sealed class BiliException : Exception
    {
        public BiliException(int code, string message) : base(message) => Code = code;
        public int Code { get; }
    }
}
