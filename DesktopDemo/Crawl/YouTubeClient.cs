using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using PlayerDemo.Library;
using PlayerDemo.Services;
using PlayerDemo.Subtitles;

namespace PlayerDemo.Crawl;

public static partial class YouTubeClient
{
    private const string VisionVersion = "1.02";
    private const string VisionAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private const string AndroidVersion = "21.26.364";
    private const string AndroidAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip";
    private const string WebVersion = "2.20261029.11.00";
    private const string WebAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private const string NoAudio = "这个视频没有可用的音频";

    private static readonly ClientSpec[] Players =
    [
        new("VISIONOS", VisionVersion, "101", VisionAgent, "Apple", "RealityDevice17,1", "visionOS", "26.5.23O471", null),
        new("ANDROID", AndroidVersion, "3", AndroidAgent, null, null, "Android", "11", 30),
        new("WEB", "", "1", WebAgent, null, null, null, null, null),
    ];

    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static VisitorGate? _visitor;
    private static string? _playerJs;
    private static string? _playerJsUrl;

    private static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private static readonly HttpClient Http = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = true,
        AutomaticDecompression = DecompressionMethods.All,
        UseCookies = false,
    })
    {
        Timeout = TimeSpan.FromSeconds(20),
        DefaultRequestVersion = HttpVersion.Version11,
        DefaultVersionPolicy = HttpVersionPolicy.RequestVersionOrLower,
    };

    private static readonly HttpClient Media = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = true,
        AutomaticDecompression = DecompressionMethods.All,
        UseCookies = false,
    })
    {
        Timeout = TimeSpan.FromMinutes(10),
        DefaultRequestVersion = HttpVersion.Version11,
        DefaultVersionPolicy = HttpVersionPolicy.RequestVersionOrLower,
    };

    public static string NormalizeCookie(string raw) =>
        raw.Replace("\r", "", StringComparison.Ordinal).Replace("\n", "", StringComparison.Ordinal).Trim().Trim(';').Trim();

    public static async Task<string> AccountStatusAsync(ServiceSettings settings, CancellationToken cancellationToken)
    {
        var cookie = NormalizeCookie(settings.YouTubeCookie);
        if (cookie.Length == 0 || !cookie.Contains('=', StringComparison.Ordinal))
            throw new InvalidOperationException("请粘贴浏览器里 youtube.com 的整段 Cookie");
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://www.youtube.com/");
        request.Headers.TryAddWithoutValidation("User-Agent", WebAgent);
        request.Headers.TryAddWithoutValidation("Cookie", cookie);
        request.Headers.TryAddWithoutValidation("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        using var response = await Http.SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if (body.Contains("\"LOGGED_IN\":true", StringComparison.Ordinal) || body.Contains("loggedIn\":true", StringComparison.Ordinal))
            return "已登录";
        if (body.Contains("\"LOGGED_IN\":false", StringComparison.Ordinal))
            return "保存了，但 YouTube 没有确认登录";
        return response.IsSuccessStatusCode ? "已保存" : "保存了，但连不上 YouTube";
    }

    public static async Task<IReadOnlyList<CrawlHit>> LookupAsync(ServiceSettings settings, string input, CancellationToken cancellationToken) =>
        (await OpenAsync(settings, input, cancellationToken)).Hits;

    public static async Task<CrawlBatch> OpenAsync(ServiceSettings settings, string input, CancellationToken cancellationToken)
    {
        var text = input.Trim();
        if (text.Length == 0)
            throw new InvalidOperationException("请输入关键词");
        var typed = SearchQueryOf(text);
        if (typed != null)
            return await SearchPageAsync(settings, typed, null, cancellationToken);
        var video = VideoOf(text);
        var playlist = PlaylistOf(text);
        if (video != null && (playlist == null || text.Contains("watch", StringComparison.OrdinalIgnoreCase) || text.Contains("youtu.be", StringComparison.OrdinalIgnoreCase) || text.Contains("shorts", StringComparison.OrdinalIgnoreCase)))
            return new CrawlBatch([await VideoAsync(settings, video, cancellationToken)], null);
        if (playlist != null)
            return await PlaylistPageAsync(settings, playlist, null, cancellationToken);
        if (video != null)
            return new CrawlBatch([await VideoAsync(settings, video, cancellationToken)], null);
        return await SearchPageAsync(settings, text, null, cancellationToken);
    }

    public static async Task<IReadOnlyList<CrawlHit>> PlaylistAsync(ServiceSettings settings, string playlistId, CancellationToken cancellationToken) =>
        (await PlaylistPageAsync(settings, playlistId, null, cancellationToken)).Hits;

    public static Task<CrawlBatch> PlaylistPageAsync(ServiceSettings settings, string playlistId, string? continuation, CancellationToken cancellationToken) =>
        BrowsePageAsync(settings, playlistId, continuation, cancellationToken);

    public static async Task<IReadOnlyList<string>> DownloadAsync(
        ServiceSettings settings,
        CrawlHit hit,
        IProgress<CrawlProgress> progress,
        CancellationToken cancellationToken)
    {
        var videoId = hit.SourceId.StartsWith("YT", StringComparison.Ordinal) ? hit.SourceId[2..] : hit.SourceId;
        var resolved = string.IsNullOrWhiteSpace(hit.MediaUrl)
            ? await VideoAsync(settings, videoId, cancellationToken)
            : hit;
        if (string.IsNullOrWhiteSpace(resolved.MediaUrl))
            throw new InvalidOperationException(NoAudio);
        var folderId = videoId.StartsWith("YT", StringComparison.Ordinal) ? videoId : "YT" + videoId;
        var root = Path.Combine(TrackLoader.LibraryRoot(), Safe(folderId));
        Directory.CreateDirectory(root);
        WriteWork(root, resolved.AlbumTitle.Length == 0 ? resolved.Title : resolved.AlbumTitle, resolved.Circle);
        var extension = string.Equals(resolved.MediaExtension, ".webm", StringComparison.OrdinalIgnoreCase) ? ".webm" : ".m4a";
        var path = Path.Combine(root, Safe(resolved.Title) + extension);
        var replaced = extension == ".webm" ? Path.Combine(root, Safe(resolved.Title) + ".m4a") : "";
        if (File.Exists(path) && new FileInfo(path).Length > 0)
        {
            if (replaced.Length > 0)
                TryDelete(replaced);
            Report(progress, resolved.Title, -1, -1, true);
        }
        else
        {
            Report(progress, resolved.Title, 0, -1, false);
            var partial = path + ".part";
            try
            {
                await SaveAsync(resolved.MediaUrl, resolved.MediaAgent ?? VisionAgent, "https://www.youtube.com/watch?v=" + videoId, settings.YouTubeCookie, partial, (received, total) =>
                    Report(progress, resolved.Title, received, total, false), cancellationToken);
                if (new FileInfo(partial).Length < 1024)
                    throw new InvalidOperationException("音频下不来");
                if (extension == ".webm" && !StartsWithEbml(partial))
                    throw new InvalidOperationException("YouTube 音轨不是完整的 WebM");
                File.Move(partial, path, true);
                if (replaced.Length > 0)
                    TryDelete(replaced);
            }
            catch
            {
                TryDelete(partial);
                throw;
            }
        }

        await TryCoverAsync(resolved.CoverUrl, root, cancellationToken);
        await TrySubtitleAsync(videoId, path, progress, resolved.Title, cancellationToken);
        return [path];
    }

    private static async Task<CrawlHit> VideoAsync(ServiceSettings settings, string videoId, CancellationToken cancellationToken)
    {
        var player = await PlayerAsync(settings, videoId, false, cancellationToken);
        if (player.Url == null && settings.YouTubeCookie.Length > 0 && player.Status is "LOGIN_REQUIRED" or "UNPLAYABLE" or "AGE_CHECK_REQUIRED")
            player = await PlayerAsync(settings, videoId, true, cancellationToken);
        if (player.Url == null)
        {
            var reason = player.Reason.Length > 0 ? player.Reason : NoAudio;
            throw new InvalidOperationException(reason);
        }

        var title = player.Title.Length == 0 ? videoId : player.Title;
        return new CrawlHit(0, "YT" + videoId, title, player.Author, player.Thumbnail)
        {
            Origin = "youtube",
            AlbumTitle = title,
            CoverReferer = "https://www.youtube.com/",
            MediaUrl = player.Url,
            MediaAgent = player.Agent,
            MediaExtension = player.Extension == ".webm" ? ".webm" : ".m4a",
        };
    }

    private readonly record struct PlayerResult(string? Url, string Agent, string Title, string Author, string? Thumbnail, string Status, string Reason, string Extension);

    private readonly record struct ClientSpec(string Name, string Version, string Number, string Agent, string? DeviceMake, string? DeviceModel, string? OsName, string? OsVersion, int? Sdk);

    private sealed class VisitorGate
    {
        public string Visitor { get; init; } = "";
        public int Timestamp { get; init; }
        public string JsUrl { get; init; } = "";
        public string WebVersion { get; init; } = YouTubeClient.WebVersion;
    }

    private readonly record struct StreamRef(string Mime, int Bitrate, string Url, string Cipher);

    private readonly record struct AudioLink(string Url, string Extension);

    private sealed class Solved
    {
        public static readonly Solved Empty = new();
        public Dictionary<string, string> Sig { get; } = new(StringComparer.Ordinal);
        public Dictionary<string, string> N { get; } = new(StringComparer.Ordinal);
    }

    private static async Task<PlayerResult> PlayerAsync(ServiceSettings settings, string videoId, bool withCookie, CancellationToken cancellationToken)
    {
        PlayerResult? fallback = null;
        PlayerResult? last = null;
        foreach (var client in Players)
        {
            cancellationToken.ThrowIfCancellationRequested();
            PlayerResult result;
            try
            {
                result = await PlayerOnceAsync(settings, videoId, client, withCookie, cancellationToken);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                result = new PlayerResult(null, client.Agent, videoId, "", null, "", "连不上 YouTube", ".m4a");
            }
            catch (HttpRequestException)
            {
                result = new PlayerResult(null, client.Agent, videoId, "", null, "", "连不上 YouTube", ".m4a");
            }

            last = result;
            if (result.Url == null)
                continue;
            if (result.Extension == ".webm")
                return result;
            fallback ??= result;
        }

        if (fallback != null)
            return fallback.Value;
        return last ?? new PlayerResult(null, VisionAgent, videoId, "", null, "", NoAudio, ".m4a");
    }

    private static async Task<PlayerResult> PlayerOnceAsync(
        ServiceSettings settings,
        string videoId,
        ClientSpec client,
        bool withCookie,
        CancellationToken cancellationToken)
    {
        var gate = await EnsureVisitorAsync(cancellationToken);
        var version = client.Version.Length == 0 ? gate.WebVersion : client.Version;
        var body = JsonSerializer.Serialize(new
        {
            videoId,
            context = new
            {
                client = new
                {
                    clientName = client.Name,
                    clientVersion = version,
                    deviceMake = client.DeviceMake,
                    deviceModel = client.DeviceModel,
                    androidSdkVersion = client.Sdk,
                    osName = client.OsName,
                    osVersion = client.OsVersion,
                    visitorData = gate.Visitor.Length == 0 ? null : gate.Visitor,
                    hl = "zh-CN",
                    gl = "US",
                },
            },
            playbackContext = gate.Timestamp > 0
                ? new { contentPlaybackContext = new { signatureTimestamp = gate.Timestamp } }
                : null,
            contentCheckOk = true,
            racyCheckOk = true,
        }, Json);
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("User-Agent", client.Agent);
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Name", client.Number);
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Version", version);
        request.Headers.TryAddWithoutValidation("Origin", "https://www.youtube.com");
        request.Headers.TryAddWithoutValidation("Referer", "https://www.youtube.com/");
        StampCookie(request, withCookie ? settings.YouTubeCookie : "");
        using var response = await Http.SendAsync(request, cancellationToken);
        var payload = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
            return new PlayerResult(null, client.Agent, videoId, "", null, "", "连不上 YouTube", ".m4a");
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(payload);
        }
        catch (JsonException)
        {
            return new PlayerResult(null, client.Agent, videoId, "", null, "", "连不上 YouTube", ".m4a");
        }

        using (document)
        {
            var root = document.RootElement;
            var playability = Child(root, "playabilityStatus");
            var status = Text(playability, "status");
            var reason = Text(playability, "reason");
            var details = Child(root, "videoDetails");
            var title = Text(details, "title");
            var author = Text(details, "author");
            var thumbnail = ThumbnailOf(details) ?? ("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
            AudioLink? audio = null;
            var message = reason;
            if (status.Length == 0 || status == "OK")
            {
                try
                {
                    audio = await MaterializeAsync(ListStreams(Child(root, "streamingData")), cancellationToken);
                }
                catch (InvalidOperationException ex)
                {
                    message = ex.Message;
                }
            }

            if (audio == null && message.Length == 0)
                message = status == "LOGIN_REQUIRED"
                    ? "这个视频需要登录，请在设置里保存 YouTube Cookie"
                    : NoAudio;
            return new PlayerResult(audio?.Url, client.Agent, title.Length == 0 ? videoId : title, author, thumbnail, status, message, audio?.Extension ?? ".m4a");
        }
    }

    private static async Task<CrawlBatch> BrowsePageAsync(ServiceSettings settings, string playlistId, string? continuation, CancellationToken cancellationToken)
    {
        var gate = await EnsureVisitorAsync(cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        var payload = continuation == null
            ? JsonSerializer.Serialize(new
            {
                browseId = "VL" + playlistId,
                context = WebContext(gate),
            }, Json)
            : JsonSerializer.Serialize(new
            {
                continuation,
                context = WebContext(gate),
            }, Json);
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
        {
            Content = new StringContent(payload, Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("User-Agent", WebAgent);
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Name", "1");
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Version", gate.WebVersion);
        request.Headers.TryAddWithoutValidation("Origin", "https://www.youtube.com");
        request.Headers.TryAddWithoutValidation("Referer", "https://www.youtube.com/playlist?list=" + playlistId);
        StampCookie(request, settings.YouTubeCookie);
        using var response = await Http.SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(response.StatusCode == HttpStatusCode.Unauthorized ? "请先在设置里保存 YouTube Cookie" : "连不上 YouTube");
        using var document = JsonDocument.Parse(body);
        var hits = new List<CrawlHit>();
        Collect(document.RootElement, hits, new HashSet<string>(StringComparer.Ordinal));
        if (hits.Count == 0)
        {
            if (continuation != null)
                return new CrawlBatch([], null);
            throw new InvalidOperationException(settings.YouTubeCookie.Length == 0 && playlistId is "LL" or "WL"
                ? "请先在设置里保存 YouTube Cookie"
                : "没有读到这个播放列表");
        }

        return new CrawlBatch(hits, Continuation(document.RootElement), "list:" + playlistId);
    }

    public static async Task<CrawlBatch> SearchPageAsync(ServiceSettings settings, string query, string? continuation, CancellationToken cancellationToken)
    {
        var gate = await EnsureVisitorAsync(cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        var payload = continuation == null
            ? JsonSerializer.Serialize(new { query, context = WebContext(gate) }, Json)
            : JsonSerializer.Serialize(new { continuation, context = WebContext(gate) }, Json);
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
        {
            Content = new StringContent(payload, Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("User-Agent", WebAgent);
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Name", "1");
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Version", gate.WebVersion);
        request.Headers.TryAddWithoutValidation("Origin", "https://www.youtube.com");
        request.Headers.TryAddWithoutValidation("Referer", "https://www.youtube.com/results?search_query=" + Uri.EscapeDataString(query));
        StampCookie(request, settings.YouTubeCookie);
        using var response = await Http.SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException("连不上 YouTube");
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(body);
        }
        catch (JsonException)
        {
            throw new InvalidOperationException("连不上 YouTube");
        }

        using (document)
        {
            var hits = new List<CrawlHit>();
            CollectSearch(document.RootElement, hits, new HashSet<string>(StringComparer.Ordinal));
            if (hits.Count == 0)
            {
                if (continuation != null)
                    return new CrawlBatch([], null);
                throw new InvalidOperationException("没有找到相关视频");
            }

            return new CrawlBatch(hits, Continuation(document.RootElement));
        }
    }

    private static object WebContext(VisitorGate gate) => new
    {
        client = new
        {
            clientName = "WEB",
            clientVersion = gate.WebVersion,
            hl = "zh-CN",
            gl = "US",
            visitorData = gate.Visitor.Length == 0 ? null : gate.Visitor,
        },
    };

    private static void CollectSearch(JsonElement element, List<CrawlHit> hits, HashSet<string> seen)
    {
        if (hits.Count >= 80)
            return;
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                if (element.TryGetProperty("videoRenderer", out var video))
                    Add(video, hits, seen);
                foreach (var property in element.EnumerateObject())
                {
                    if (property.NameEquals("videoRenderer"))
                        continue;
                    CollectSearch(property.Value, hits, seen);
                }

                break;
            case JsonValueKind.Array:
                foreach (var item in element.EnumerateArray())
                    CollectSearch(item, hits, seen);
                break;
        }
    }

    private static void Collect(JsonElement element, List<CrawlHit> hits, HashSet<string> seen)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                if (element.TryGetProperty("playlistVideoRenderer", out var video))
                    Add(video, hits, seen);
                foreach (var property in element.EnumerateObject())
                {
                    if (property.NameEquals("playlistVideoRenderer"))
                        continue;
                    Collect(property.Value, hits, seen);
                }

                break;
            case JsonValueKind.Array:
                foreach (var item in element.EnumerateArray())
                    Collect(item, hits, seen);
                break;
        }
    }

    private static void Add(JsonElement video, List<CrawlHit> hits, HashSet<string> seen)
    {
        if (hits.Count >= 200)
            return;
        var id = Text(video, "videoId");
        if (id.Length != 11 || !seen.Add(id))
            return;
        var title = Runs(video, "title");
        var author = Runs(video, "shortBylineText");
        if (author.Length == 0)
            author = Runs(video, "longBylineText");
        if (author.Length == 0)
            author = Runs(video, "ownerText");
        var length = Runs(video, "lengthText");
        if (length.Length > 0)
            author = author.Length == 0 ? length : author + " · " + length;
        hits.Add(new CrawlHit(0, "YT" + id, title.Length == 0 ? id : title, author, "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg")
        {
            Origin = "youtube",
            AlbumTitle = title.Length == 0 ? id : title,
            CoverReferer = "https://www.youtube.com/",
        });
    }

    private static string? Continuation(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            if (element.TryGetProperty("continuationItemRenderer", out var renderer))
            {
                var token = DigToken(renderer);
                if (token != null)
                    return token;
            }

            foreach (var property in element.EnumerateObject())
            {
                var token = Continuation(property.Value);
                if (token != null)
                    return token;
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                var token = Continuation(item);
                if (token != null)
                    return token;
            }
        }

        return null;
    }

    private static string? DigToken(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            if (element.TryGetProperty("token", out var token) && token.ValueKind == JsonValueKind.String)
            {
                var text = token.GetString();
                if (!string.IsNullOrWhiteSpace(text))
                    return text;
            }

            foreach (var property in element.EnumerateObject())
            {
                var found = DigToken(property.Value);
                if (found != null)
                    return found;
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                var found = DigToken(item);
                if (found != null)
                    return found;
            }
        }

        return null;
    }

    private static List<StreamRef> ListStreams(JsonElement streaming)
    {
        var list = new List<StreamRef>();
        if (streaming.ValueKind != JsonValueKind.Object)
            return list;
        AddStreams(streaming, "adaptiveFormats", list);
        AddStreams(streaming, "formats", list);
        return list;
    }

    private static void AddStreams(JsonElement streaming, string name, List<StreamRef> list)
    {
        if (!streaming.TryGetProperty(name, out var formats) || formats.ValueKind != JsonValueKind.Array)
            return;
        foreach (var format in formats.EnumerateArray())
        {
            var cipher = Text(format, "signatureCipher");
            if (cipher.Length == 0)
                cipher = Text(format, "cipher");
            var bitrate = (int)Long(format, "bitrate");
            if (bitrate <= 0)
                bitrate = (int)Long(format, "averageBitrate");
            list.Add(new StreamRef(Text(format, "mimeType"), bitrate, Text(format, "url"), cipher));
        }
    }

    private static long Rank(StreamRef stream)
    {
        var mime = stream.Mime;
        // 96 kbps 以上的 Opus 听感好于同档 AAC。更低的 Opus 仍让给 AAC。
        if (mime.StartsWith("audio/webm", StringComparison.OrdinalIgnoreCase) && mime.Contains("opus", StringComparison.OrdinalIgnoreCase))
        {
            var rate = stream.Bitrate > 0 ? stream.Bitrate : 160_000;
            var tier = rate >= 96_000 ? 5_000_000_000L : 2_500_000_000L;
            return tier + rate;
        }

        if (mime.StartsWith("audio/mp4", StringComparison.OrdinalIgnoreCase))
            return (mime.Contains("mp4a", StringComparison.OrdinalIgnoreCase) ? 4_000_000_000L : 3_000_000_000L) + Math.Max(stream.Bitrate, 0);
        if (mime.StartsWith("video/mp4", StringComparison.OrdinalIgnoreCase) && mime.Contains("mp4a", StringComparison.OrdinalIgnoreCase))
            return 1_000_000_000L + Math.Max(stream.Bitrate, 0);
        return -1;
    }

    private static string ExtensionOf(string mime) =>
        mime.StartsWith("audio/webm", StringComparison.OrdinalIgnoreCase) ? ".webm" : ".m4a";

    private static async Task<AudioLink?> MaterializeAsync(List<StreamRef> streams, CancellationToken cancellationToken)
    {
        var ranked = streams
            .Where(stream => Rank(stream) >= 0 && (stream.Url.Length > 0 || stream.Cipher.Length > 0))
            .OrderByDescending(Rank)
            .ToList();
        if (ranked.Count == 0)
            return null;
        var sigs = new List<string>();
        var ns = new List<string>();
        foreach (var stream in ranked)
        {
            if (stream.Cipher.Length > 0)
            {
                var form = Form(stream.Cipher);
                if (form.TryGetValue("s", out var challenge) && challenge.Length > 0)
                    sigs.Add(challenge);
                if (form.TryGetValue("url", out var raw))
                {
                    var n = Param(raw, "n");
                    if (n != null)
                        ns.Add(n);
                }
            }
            else
            {
                var n = Param(stream.Url, "n");
                if (n != null)
                    ns.Add(n);
            }
        }

        var solved = await SolveAsync(sigs, ns, cancellationToken);
        foreach (var stream in ranked)
        {
            var url = Finish(stream, solved);
            if (url != null)
                return new AudioLink(url, ExtensionOf(stream.Mime));
        }

        if (sigs.Count > 0 || ns.Count > 0)
            throw new InvalidOperationException("音频签名解不开");
        return null;
    }

    private static string? Finish(StreamRef stream, Solved solved)
    {
        if (stream.Cipher.Length > 0)
        {
            var form = Form(stream.Cipher);
            if (!form.TryGetValue("url", out var url) || url.Length == 0 || !form.TryGetValue("s", out var challenge))
                return null;
            if (!solved.Sig.TryGetValue(challenge, out var sig) || sig.Length == 0)
                return null;
            var sp = form.GetValueOrDefault("sp");
            if (string.IsNullOrEmpty(sp))
                sp = "sig";
            return ApplyN(url, solved) + "&" + sp + "=" + Uri.EscapeDataString(sig);
        }

        return stream.Url.Length == 0 ? null : ApplyN(stream.Url, solved);
    }

    private static string ApplyN(string url, Solved solved)
    {
        var n = Param(url, "n");
        if (n == null || !solved.N.TryGetValue(n, out var next) || next.Length == 0 || next == n)
            return url;
        return ReplaceParam(url, "n", next);
    }

    private static async Task<Solved> SolveAsync(List<string> sigs, List<string> ns, CancellationToken cancellationToken)
    {
        sigs = sigs.Distinct(StringComparer.Ordinal).ToList();
        ns = ns.Distinct(StringComparer.Ordinal).ToList();
        if (sigs.Count == 0 && ns.Count == 0)
            return Solved.Empty;
        var player = await PlayerScriptAsync(cancellationToken);
        var node = FindNode() ?? throw new InvalidOperationException("解开 YouTube 签名需要本机安装 Node.js");
        var script = SolverFile();
        var requests = new List<object>();
        if (sigs.Count > 0)
            requests.Add(new { type = "sig", challenges = sigs });
        if (ns.Count > 0)
            requests.Add(new { type = "n", challenges = ns });
        var start = new ProcessStartInfo(node)
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardInputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
            StandardOutputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
        };
        start.ArgumentList.Add(script);
        using var process = new Process { StartInfo = start };
        if (!process.Start())
            throw new InvalidOperationException("解开 YouTube 签名失败");
        using var kill = cancellationToken.Register(() =>
        {
            try
            {
                if (!process.HasExited)
                    process.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException)
            {
            }
        });
        var stdoutTask = process.StandardOutput.ReadToEndAsync(cancellationToken);
        var stderrTask = process.StandardError.ReadToEndAsync(cancellationToken);
        await process.StandardInput.WriteAsync(JsonSerializer.Serialize(new { player, requests }));
        process.StandardInput.Close();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        try
        {
            await process.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            try
            {
                if (!process.HasExited)
                    process.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException)
            {
            }

            if (cancellationToken.IsCancellationRequested)
                throw;
            throw new InvalidOperationException("解开 YouTube 签名超时");
        }

        var stdout = await stdoutTask;
        await stderrTask;
        if (process.ExitCode != 0 || stdout.Length == 0)
            throw new InvalidOperationException("解开 YouTube 签名失败");
        Solved solved;
        try
        {
            using var document = JsonDocument.Parse(stdout);
            if (!document.RootElement.TryGetProperty("responses", out var responses) || responses.ValueKind != JsonValueKind.Array)
                throw new InvalidOperationException("解开 YouTube 签名失败");
            var expected = (sigs.Count > 0 ? 1 : 0) + (ns.Count > 0 ? 1 : 0);
            if (responses.GetArrayLength() < expected)
                throw new InvalidOperationException("解开 YouTube 签名失败");
            solved = new Solved();
            var index = 0;
            if (sigs.Count > 0)
                ReadSolved(responses[index++], solved.Sig);
            if (ns.Count > 0)
                ReadSolved(responses[index], solved.N);
        }
        catch (JsonException)
        {
            throw new InvalidOperationException("解开 YouTube 签名失败");
        }
        catch (InvalidOperationException)
        {
            throw;
        }
        catch (KeyNotFoundException)
        {
            throw new InvalidOperationException("解开 YouTube 签名失败");
        }

        return solved;
    }

    private static void ReadSolved(JsonElement response, Dictionary<string, string> target)
    {
        var type = Text(response, "type");
        if (type != "result" || !response.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.Object)
            throw new InvalidOperationException("解开 YouTube 签名失败");
        foreach (var item in data.EnumerateObject())
        {
            if (item.Value.ValueKind != JsonValueKind.String)
                continue;
            var value = item.Value.GetString();
            if (!string.IsNullOrEmpty(value))
                target[item.Name] = value;
        }
    }

    private static async Task<VisitorGate> EnsureVisitorAsync(CancellationToken cancellationToken)
    {
        if (_visitor != null)
            return _visitor;
        await Gate.WaitAsync(cancellationToken);
        try
        {
            if (_visitor != null)
                return _visitor;
            using var request = new HttpRequestMessage(HttpMethod.Get, "https://www.youtube.com/");
            request.Headers.TryAddWithoutValidation("User-Agent", WebAgent);
            request.Headers.TryAddWithoutValidation("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            using var response = await Http.SendAsync(request, cancellationToken);
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
                return new VisitorGate();
            var visitor = Group(VisitorData(), body);
            var js = Group(PlayerJs(), body).Replace("\\/", "/", StringComparison.Ordinal);
            var version = Group(InnertubeVersion(), body);
            var timestamp = int.TryParse(Group(SignatureTime(), body), NumberStyles.None, CultureInfo.InvariantCulture, out var parsed) ? parsed : 0;
            if (visitor.Length == 0)
                return new VisitorGate { Timestamp = timestamp, JsUrl = js, WebVersion = version.Length == 0 ? WebVersion : version };
            _visitor = new VisitorGate
            {
                Visitor = visitor,
                Timestamp = timestamp,
                JsUrl = js,
                WebVersion = version.Length == 0 ? WebVersion : version,
            };
            return _visitor;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return new VisitorGate();
        }
        catch (HttpRequestException)
        {
            return new VisitorGate();
        }
        finally
        {
            Gate.Release();
        }
    }

    private static async Task<string> PlayerScriptAsync(CancellationToken cancellationToken)
    {
        var gate = await EnsureVisitorAsync(cancellationToken);
        if (_playerJs != null && _playerJsUrl == gate.JsUrl && gate.JsUrl.Length > 0)
            return _playerJs;
        if (gate.JsUrl.Length == 0)
            throw new InvalidOperationException("没有找到 YouTube 播放器脚本");
        await Gate.WaitAsync(cancellationToken);
        try
        {
            if (_playerJs != null && _playerJsUrl == gate.JsUrl)
                return _playerJs;
            var url = gate.JsUrl.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? gate.JsUrl : "https://www.youtube.com" + gate.JsUrl;
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.TryAddWithoutValidation("User-Agent", WebAgent);
            using var response = await Http.SendAsync(request, cancellationToken);
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode || body.Length < 100_000)
                throw new InvalidOperationException("播放器脚本下不来");
            _playerJs = body;
            _playerJsUrl = gate.JsUrl;
            return body;
        }
        finally
        {
            Gate.Release();
        }
    }

    private static string SolverFile()
    {
        foreach (var dir in new[] { AppContext.BaseDirectory, Path.GetDirectoryName(typeof(YouTubeClient).Assembly.Location) ?? "" })
        {
            if (dir.Length == 0)
                continue;
            foreach (var relative in new[] { "yt-solver.cjs", Path.Combine("Crawl", "yt-solver.cjs") })
            {
                var path = Path.Combine(dir, relative);
                if (File.Exists(path))
                    return path;
            }
        }

        var extracted = Path.Combine(Path.GetTempPath(), "PlayerDemo", "yt-solver.cjs");
        Directory.CreateDirectory(Path.GetDirectoryName(extracted)!);
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("PlayerDemo.yt-solver.cjs")
            ?? throw new InvalidOperationException("缺少签名组件");
        using var file = File.Create(extracted);
        stream.CopyTo(file);
        return extracted;
    }

    private static string? FindNode()
    {
        foreach (var root in new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs"),
        })
        {
            if (root.Length == 0)
                continue;
            var file = Path.Combine(root, "nodejs", "node.exe");
            if (File.Exists(file))
                return file;
        }

        var path = Environment.GetEnvironmentVariable("PATH");
        if (path == null)
            return null;
        foreach (var dir in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            var file = Path.Combine(dir.Trim(), "node.exe");
            if (File.Exists(file))
                return file;
        }

        return null;
    }

    private static Dictionary<string, string> Form(string query)
    {
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var part in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var cut = part.IndexOf('=');
            var name = Decode(cut < 0 ? part : part[..cut]);
            var value = Decode(cut < 0 ? "" : part[(cut + 1)..]);
            map[name] = value;
        }

        return map;
    }

    private static string Decode(string text) => Uri.UnescapeDataString(text.Replace("+", " ", StringComparison.Ordinal));

    private static string? Param(string url, string key)
    {
        var query = url.IndexOf('?');
        if (query < 0)
            return null;
        foreach (var part in url[(query + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var cut = part.IndexOf('=');
            var name = Decode(cut < 0 ? part : part[..cut]);
            if (name == key)
                return Decode(cut < 0 ? "" : part[(cut + 1)..]);
        }

        return null;
    }

    private static string ReplaceParam(string url, string key, string value)
    {
        var encoded = Uri.EscapeDataString(value);
        var query = url.IndexOf('?');
        if (query < 0)
            return url + "?" + key + "=" + encoded;
        var parts = url[(query + 1)..].Split('&');
        for (var i = 0; i < parts.Length; i++)
        {
            var cut = parts[i].IndexOf('=');
            var name = Decode(cut < 0 ? parts[i] : parts[i][..cut]);
            if (name == key)
                parts[i] = key + "=" + encoded;
        }

        return url[..(query + 1)] + string.Join("&", parts);
    }

    private static string Group(Regex regex, string text)
    {
        var match = regex.Match(text);
        return match.Success ? match.Groups[1].Value : "";
    }

    private const int SegmentBytes = 1024 * 1024;
    private const int SegmentParallel = 4;

    private static async Task SaveAsync(string url, string agent, string referer, string cookie, string path, Action<long, long> onBytes, CancellationToken cancellationToken)
    {
        var length = await ProbeLengthAsync(url, agent, referer, cookie, cancellationToken);
        if (length <= 0)
            throw new InvalidOperationException("音频下不来");
        await using var output = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read, 64 * 1024, FileOptions.Asynchronous);
        output.SetLength(length);
        var slot = new SemaphoreSlim(SegmentParallel);
        using var failed = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var received = 0L;
        var workers = new List<Task>();
        try
        {
            for (long start = 0; start < length; start += SegmentBytes)
            {
                await slot.WaitAsync(failed.Token);
                var from = start;
                var count = (int)Math.Min(SegmentBytes, length - from);
                workers.Add(WriteSegmentAsync(output, url, agent, referer, cookie, from, count, () =>
                {
                    var done = Interlocked.Add(ref received, count);
                    onBytes(done, length);
                }, slot, failed));
            }

            await Task.WhenAll(workers);
        }
        catch
        {
            try
            {
                await failed.CancelAsync();
            }
            catch (ObjectDisposedException)
            {
            }

            try
            {
                await Task.WhenAll(workers);
            }
            catch
            {
                // 失败的那段已经抛出，其余段取消即可。
            }

            throw;
        }

        await output.FlushAsync(cancellationToken);
    }

    private static async Task WriteSegmentAsync(
        FileStream output,
        string url,
        string agent,
        string referer,
        string cookie,
        long from,
        int count,
        Action report,
        SemaphoreSlim slot,
        CancellationTokenSource failed)
    {
        try
        {
            var data = await ReadSegmentAsync(url, agent, referer, cookie, from, count, failed.Token);
            lock (output)
            {
                output.Position = from;
                output.Write(data, 0, data.Length);
            }

            report();
        }
        catch
        {
            try
            {
                await failed.CancelAsync();
            }
            catch (ObjectDisposedException)
            {
            }

            throw;
        }
        finally
        {
            slot.Release();
        }
    }

    private static async Task<long> ProbeLengthAsync(string url, string agent, string referer, string cookie, CancellationToken cancellationToken)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                using var request = MediaRequest(url, agent, referer, cookie);
                request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 0);
                using var response = await Media.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                if (response.StatusCode == HttpStatusCode.Forbidden)
                    throw new InvalidOperationException("这个音频地址被 YouTube 拒绝了。当前没有稳定的公开音频接口，校验没通过时下不了。");
                if (response.StatusCode != HttpStatusCode.PartialContent)
                    throw new InvalidOperationException("音频下不来");
                return response.Content.Headers.ContentRange?.Length ?? -1;
            }
            catch (Exception) when (attempt < 2 && !cancellationToken.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(200 * (attempt + 1)), cancellationToken);
            }
        }
    }

    private static async Task<byte[]> ReadSegmentAsync(string url, string agent, string referer, string cookie, long from, int count, CancellationToken cancellationToken)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                using var request = MediaRequest(url, agent, referer, cookie);
                request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(from, from + count - 1);
                using var response = await Media.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                if (response.StatusCode == HttpStatusCode.Forbidden)
                    throw new InvalidOperationException("这个音频地址被 YouTube 拒绝了。当前没有稳定的公开音频接口，校验没通过时下不了。");
                if (response.StatusCode != HttpStatusCode.PartialContent)
                    throw new InvalidOperationException("音频下不来");
                var data = new byte[count];
                await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
                var offset = 0;
                while (offset < count)
                {
                    var read = await stream.ReadAsync(data.AsMemory(offset, count - offset), cancellationToken);
                    if (read == 0)
                        throw new EndOfStreamException();
                    offset += read;
                }

                return data;
            }
            catch (Exception) when (attempt < 2 && !cancellationToken.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(200 * (attempt + 1)), cancellationToken);
            }
        }
    }

    private static HttpRequestMessage MediaRequest(string url, string agent, string referer, string cookie)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", agent);
        request.Headers.TryAddWithoutValidation("Referer", referer);
        request.Headers.TryAddWithoutValidation("Origin", "https://www.youtube.com");
        StampCookie(request, cookie);
        return request;
    }

    private static async Task TrySubtitleAsync(
        string videoId,
        string audioPath,
        IProgress<CrawlProgress> progress,
        string title,
        CancellationToken cancellationToken)
    {
        var srt = Path.ChangeExtension(audioPath, ".srt");
        if (File.Exists(srt) && new FileInfo(srt).Length > 0)
        {
            Report(progress, title + "  字幕", -1, -1, true);
            return;
        }

        try
        {
            var address = await CaptionUrlAsync(videoId, cancellationToken);
            if (address == null)
                return;
            using var request = new HttpRequestMessage(HttpMethod.Get, address);
            request.Headers.TryAddWithoutValidation("User-Agent", AndroidAgent);
            using var response = await Http.SendAsync(request, cancellationToken);
            if (!response.IsSuccessStatusCode)
                return;
            var body = await response.Content.ReadAsStringAsync(cancellationToken);
            var cues = ReadTimedText(body);
            if (cues.Count == 0)
                return;
            CueDocument.WriteSrt(srt, cues);
            Report(progress, title + "  字幕", 1, 1, false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
        }
    }

    private static async Task<string?> CaptionUrlAsync(string videoId, CancellationToken cancellationToken)
    {
        var client = Players.First(item => item.Name == "ANDROID");
        var gate = await EnsureVisitorAsync(cancellationToken);
        var version = client.Version;
        var body = JsonSerializer.Serialize(new
        {
            videoId,
            context = new
            {
                client = new
                {
                    clientName = client.Name,
                    clientVersion = version,
                    hl = "ja",
                    gl = "JP",
                    osName = client.OsName,
                    osVersion = client.OsVersion,
                    androidSdkVersion = client.Sdk,
                    visitorData = gate.Visitor.Length == 0 ? null : gate.Visitor,
                },
            },
            playbackContext = gate.Timestamp > 0
                ? new { contentPlaybackContext = new { signatureTimestamp = gate.Timestamp } }
                : null,
            contentCheckOk = true,
            racyCheckOk = true,
        }, Json);
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("User-Agent", client.Agent);
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Name", client.Number);
        request.Headers.TryAddWithoutValidation("X-YouTube-Client-Version", version);
        using var response = await Http.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
            return null;
        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        var tracks = Child(Child(Child(document.RootElement, "captions"), "playerCaptionsTracklistRenderer"), "captionTracks");
        if (tracks.ValueKind != JsonValueKind.Array)
            return null;
        string? best = null;
        var bestScore = int.MinValue;
        foreach (var track in tracks.EnumerateArray())
        {
            var address = Text(track, "baseUrl");
            if (address.Length == 0)
                continue;
            var score = string.Equals(Text(track, "kind"), "asr", StringComparison.OrdinalIgnoreCase) ? 0 : 100;
            score += Text(track, "languageCode") switch
            {
                "ja" => 40,
                "zh-Hans" or "zh-CN" => 30,
                "zh-Hant" or "zh-TW" => 28,
                "zh" => 26,
                "en" => 10,
                _ => 0,
            };
            if (score > bestScore)
            {
                bestScore = score;
                best = address;
            }
        }

        return best;
    }

    private static List<SubtitleCue> ReadTimedText(string xml)
    {
        var cues = new List<SubtitleCue>();
        if (string.IsNullOrWhiteSpace(xml))
            return cues;
        var document = XDocument.Parse(xml);
        foreach (var paragraph in document.Descendants().Where(item => item.Name.LocalName == "p"))
        {
            var text = CaptionText(paragraph);
            if (text.Length == 0)
                continue;
            var start = CaptionTime(paragraph, "t");
            var duration = CaptionTime(paragraph, "d");
            cues.Add(new SubtitleCue(start, start + Math.Max(duration, 200), text));
        }

        if (cues.Count > 0)
            return cues;
        foreach (var line in document.Descendants().Where(item => item.Name.LocalName == "text"))
        {
            var text = CaptionText(line);
            if (text.Length == 0)
                continue;
            var start = (long)Math.Round(CaptionSeconds(line, "start") * 1000);
            var duration = (long)Math.Round(CaptionSeconds(line, "dur") * 1000);
            cues.Add(new SubtitleCue(start, start + Math.Max(duration, 200), text));
        }

        return cues;
    }

    private static string CaptionText(XElement element)
    {
        var builder = new StringBuilder();
        foreach (var node in element.Nodes())
        {
            if (node is XText text)
                builder.Append(text.Value);
            else if (node is XElement child && child.Name.LocalName == "br")
                builder.Append('\n');
            else if (node is XElement nested)
                builder.Append(CaptionText(nested));
        }

        return string.Join("\n", builder.ToString().Split('\n').Select(line => line.Trim()).Where(line => line.Length > 0));
    }

    private static long CaptionTime(XElement element, string name)
    {
        var value = element.Attribute(name)?.Value;
        return long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var time) ? time : 0;
    }

    private static double CaptionSeconds(XElement element, string name)
    {
        var value = element.Attribute(name)?.Value;
        return double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var time) ? time : 0;
    }

    private static async Task TryCoverAsync(string? url, string root, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(url))
            return;
        var cover = Path.Combine(root, "cover.jpg");
        if (File.Exists(cover) && new FileInfo(cover).Length > 0)
            return;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.TryAddWithoutValidation("User-Agent", WebAgent);
            request.Headers.TryAddWithoutValidation("Referer", "https://www.youtube.com/");
            using var response = await Media.SendAsync(request, cancellationToken);
            if (!response.IsSuccessStatusCode)
                return;
            var partial = cover + ".part";
            await using (var input = await response.Content.ReadAsStreamAsync(cancellationToken))
            await using (var output = File.Create(partial))
                await input.CopyToAsync(output, cancellationToken);
            File.Move(partial, cover, true);
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

    private static void StampCookie(HttpRequestMessage request, string cookie)
    {
        var text = NormalizeCookie(cookie);
        if (text.Length == 0)
            return;
        request.Headers.TryAddWithoutValidation("Cookie", text);
        var authorization = Sapisid(text);
        if (authorization != null)
            request.Headers.TryAddWithoutValidation("Authorization", authorization);
        request.Headers.TryAddWithoutValidation("X-Goog-AuthUser", "0");
    }

    private static string? Sapisid(string cookie)
    {
        var value = Field(cookie, "SAPISID") ?? Field(cookie, "__Secure-3PAPISID");
        if (value == null)
            return null;
        var stamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture);
        var hash = Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(stamp + " " + value + " https://www.youtube.com"))).ToLowerInvariant();
        return "SAPISIDHASH " + stamp + "_" + hash;
    }

    private static string? Field(string header, string name)
    {
        foreach (var part in header.Split(';'))
        {
            var trimmed = part.Trim();
            var cut = trimmed.IndexOf('=');
            if (cut <= 0 || trimmed[..cut].Trim() != name)
                continue;
            var value = trimmed[(cut + 1)..];
            return value.Length == 0 ? null : value;
        }

        return null;
    }

    private static string? SearchQueryOf(string text)
    {
        var match = SearchQuery().Match(text);
        if (!match.Success)
            return null;
        var value = Uri.UnescapeDataString(match.Groups[1].Value.Replace("+", " ", StringComparison.Ordinal)).Trim();
        return value.Length == 0 ? null : value;
    }

    private static string? VideoOf(string text)
    {
        var match = VideoId().Match(text);
        if (match.Success)
            return match.Groups[1].Value;
        var bare = text.Trim();
        return bare.Length == 11 && BareId().IsMatch(bare) ? bare : null;
    }

    private static string? PlaylistOf(string text)
    {
        var match = PlaylistId().Match(text);
        if (match.Success)
            return match.Groups[1].Value;
        var bare = text.Trim();
        if (bare is "LL" or "WL")
            return bare;
        return bare.StartsWith("PL", StringComparison.Ordinal) && bare.Length is >= 13 and <= 80 && BareList().IsMatch(bare) ? bare : null;
    }

    private static string? ThumbnailOf(JsonElement details)
    {
        var thumbs = Child(Child(details, "thumbnail"), "thumbnails");
        if (thumbs.ValueKind != JsonValueKind.Array)
            return null;
        string? url = null;
        foreach (var thumb in thumbs.EnumerateArray())
        {
            var candidate = Text(thumb, "url");
            if (candidate.Length > 0)
                url = candidate;
        }

        return url;
    }

    private static string Runs(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value))
            return "";
        if (value.ValueKind == JsonValueKind.String)
            return value.GetString()?.Trim() ?? "";
        var simple = Text(value, "simpleText");
        if (simple.Length > 0)
            return simple;
        if (!value.TryGetProperty("runs", out var runs) || runs.ValueKind != JsonValueKind.Array)
            return "";
        var builder = new StringBuilder();
        foreach (var run in runs.EnumerateArray())
            builder.Append(Text(run, "text"));
        return builder.ToString().Trim();
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

    private static void Report(IProgress<CrawlProgress> progress, string name, long received, long total, bool existing)
    {
        var inner = existing ? 1d : total > 0 ? Math.Clamp((double)received / total, 0, 1) : 0;
        var detail = existing ? "已有" : total > 0 ? $"{(int)Math.Round(inner * 100)}%  {Size(received)} / {Size(total)}" : received > 0 ? Size(received) : "开始";
        progress.Report(new CrawlProgress($"{name}  {detail}", inner));
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

    private static bool StartsWithEbml(string path)
    {
        try
        {
            using var stream = File.OpenRead(path);
            Span<byte> magic = stackalloc byte[4];
            return stream.Read(magic) == 4 && magic[0] == 0x1A && magic[1] == 0x45 && magic[2] == 0xDF && magic[3] == 0xA3;
        }
        catch (IOException)
        {
            return false;
        }
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

    private static JsonElement Child(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) ? value : default;

    private static string Text(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
            return "";
        return value.GetString()?.Trim() ?? "";
    }

    private static long Long(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value))
            return 0;
        if (value.ValueKind == JsonValueKind.Number)
            return value.TryGetInt64(out var number) ? number : (long)value.GetDouble();
        return 0;
    }

    [GeneratedRegex(@"[?&]search_query=([^&]+)", RegexOptions.IgnoreCase)]
    private static partial Regex SearchQuery();

    [GeneratedRegex(@"(?:v=|/shorts/|/embed/|/live/|/v/|youtu\.be/)([A-Za-z0-9_-]{11})")]
    private static partial Regex VideoId();

    [GeneratedRegex(@"[?&]list=([A-Za-z0-9_-]{2,80})")]
    private static partial Regex PlaylistId();

    [GeneratedRegex(@"^[A-Za-z0-9_-]{11}$")]
    private static partial Regex BareId();

    [GeneratedRegex(@"^[A-Za-z0-9_-]+$")]
    private static partial Regex BareList();

    [GeneratedRegex("\"VISITOR_DATA\":\"([^\"]+)\"")]
    private static partial Regex VisitorData();

    [GeneratedRegex("\"jsUrl\":\"([^\"]+)\"")]
    private static partial Regex PlayerJs();

    [GeneratedRegex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")]
    private static partial Regex InnertubeVersion();

    [GeneratedRegex("\"STS\":(\\d+)")]
    private static partial Regex SignatureTime();
}
