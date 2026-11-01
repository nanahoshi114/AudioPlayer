using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PlayerDemo.Subtitles;

namespace PlayerDemo.Services;

public static class WhisperService
{
    public const long OnlineBytes = 25L * 1024 * 1024;
    private const long QwenTempBytes = 1024L * 1024 * 1024;
    private const string QwenFileModel = "qwen-audio-3.1-asr-flash-filetrans";
    private const string Qwen3Fallback = "Qwen/Qwen3-ASR-1.7B";

    private static readonly HttpClient Jobs = new() { Timeout = TimeSpan.FromMinutes(2) };
    private static readonly HttpClient Online = new() { Timeout = TimeSpan.FromMinutes(30) };
    private static readonly HttpClient Qwen = new() { Timeout = TimeSpan.FromMinutes(30) };

    public static bool NeedsSizeConfirm(ServiceSettings settings, long bytes)
    {
        if (settings.WhisperModel != ServiceSettings.Whisper1 || bytes <= OnlineBytes)
            return false;
        return Host(settings.EffectiveWhisperBase()) == "api.openai.com";
    }

    public static async Task<IReadOnlyList<SubtitleCue>> TranscribeAsync(
        ServiceSettings settings,
        string audioPath,
        long durationMs,
        IProgress<string> progress,
        CancellationToken cancellationToken)
    {
        var token = settings.WhisperToken.Trim();
        var baseUrl = settings.EffectiveWhisperBase();
        if (token.Length == 0)
            throw new InvalidOperationException("没有听写口令");
        if (baseUrl == null)
            throw new InvalidOperationException("没有听写地址");

        progress.Report("听写中");
        return settings.WhisperModel switch
        {
            ServiceSettings.WhisperQwen => await TranscribeQwenAsync(audioPath, baseUrl, token, progress, cancellationToken),
            ServiceSettings.WhisperQwen3 => await TranscribeQwen3Async(audioPath, ServiceSettings.TranscriptionBase(baseUrl), token, durationMs, cancellationToken),
            ServiceSettings.WhisperLocal => await TranscribeOnlineAsync(audioPath, ServiceSettings.TranscriptionBase(baseUrl), token, cancellationToken),
            ServiceSettings.Whisper1 => await TranscribeCloudAsync(audioPath, ServiceSettings.TranscriptionBase(baseUrl), token, cancellationToken),
            _ => await TranscribeJobsAsync(audioPath, baseUrl, token, progress, cancellationToken),
        };
    }

    private static async Task<IReadOnlyList<SubtitleCue>> TranscribeCloudAsync(string path, string baseUrl, string token, CancellationToken cancellationToken)
    {
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        limit.CancelAfter(TimeSpan.FromMinutes(10));
        try
        {
            return await TranscribeOnlineAsync(path, baseUrl, token, limit.Token);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new InvalidOperationException("生成失败");
        }
    }

    private static async Task<IReadOnlyList<SubtitleCue>> TranscribeOnlineAsync(string path, string baseUrl, string token, CancellationToken cancellationToken)
    {
        using var form = AudioForm(path);
        form.Add(new StringContent("whisper-1"), "model");
        form.Add(new StringContent("ja"), "language");
        form.Add(new StringContent("verbose_json"), "response_format");
        form.Add(new StringContent("segment"), "timestamp_granularities[]");
        using var json = await SendAsync(Online, HttpMethod.Post, baseUrl.TrimEnd('/') + "/audio/transcriptions", token, form, cancellationToken)
            ?? throw new InvalidOperationException("生成失败");
        return Segments(json);
    }

    private static async Task<IReadOnlyList<SubtitleCue>> TranscribeQwen3Async(
        string path,
        string baseUrl,
        string token,
        long durationMs,
        CancellationToken cancellationToken)
    {
        var model = await Qwen3ModelAsync(baseUrl, token, cancellationToken);
        using var form = AudioForm(path);
        form.Add(new StringContent(model), "model");
        form.Add(new StringContent("ja"), "language");
        form.Add(new StringContent("json"), "response_format");
        using var json = await SendAsync(Online, HttpMethod.Post, baseUrl.TrimEnd('/') + "/audio/transcriptions", token, form, cancellationToken)
            ?? throw new InvalidOperationException("生成失败");
        var segments = Segments(json);
        if (segments.Count > 0)
            return segments;
        var words = Words(json);
        if (words.Count > 0)
            return GroupSentences(words);
        return WholeCue(TextOf(json), durationMs);
    }

    private static async Task<string> Qwen3ModelAsync(string baseUrl, string token, CancellationToken cancellationToken)
    {
        try
        {
            var json = await SendAsync(Online, HttpMethod.Get, baseUrl.TrimEnd('/') + "/models", token, null, cancellationToken, missingIsError: false);
            if (json == null)
                return Qwen3Fallback;
            using (json)
            {
                if (!json.RootElement.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.Array)
                    return Qwen3Fallback;
                string? first = null;
                foreach (var item in data.EnumerateArray())
                {
                    var id = item.TryGetProperty("id", out var value) ? value.GetString() : null;
                    if (string.IsNullOrWhiteSpace(id))
                        continue;
                    first ??= id;
                    if (id.Contains("qwen3-asr", StringComparison.OrdinalIgnoreCase))
                        return id;
                }

                return first ?? Qwen3Fallback;
            }
        }
        catch (InvalidOperationException ex) when (ex.Message is "无法连接听写服务" or "没有听写口令" or "听写地址不正确" or "没有听写地址")
        {
            throw;
        }
        catch (InvalidOperationException)
        {
            return Qwen3Fallback;
        }
    }

    private static async Task<IReadOnlyList<SubtitleCue>> TranscribeJobsAsync(
        string path,
        string baseUrl,
        string token,
        IProgress<string> progress,
        CancellationToken cancellationToken)
    {
        using var form = AudioForm(path);
        form.Add(new StringContent(await FingerprintAsync(path, cancellationToken)), "fingerprint");
        form.Add(new StringContent("ja"), "language");
        using var created = await SendAsync(Jobs, HttpMethod.Post, baseUrl.TrimEnd('/') + "/jobs", token, form, cancellationToken)
            ?? throw new InvalidOperationException("生成失败");
        var id = created.RootElement.TryGetProperty("id", out var idValue) ? idValue.GetString() : null;
        if (string.IsNullOrWhiteSpace(id))
            throw new InvalidOperationException("生成失败");
        var status = created.RootElement.TryGetProperty("status", out var statusValue) ? statusValue.GetString() : "";
        if (status == "failed")
            throw new InvalidOperationException("生成失败");
        if (status != "done")
        {
            while (true)
            {
                await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
                using var job = await SendAsync(Jobs, HttpMethod.Get, baseUrl.TrimEnd('/') + "/jobs/" + id, token, null, cancellationToken)
                    ?? throw new InvalidOperationException("生成失败");
                status = job.RootElement.TryGetProperty("status", out var state) ? state.GetString() : "";
                var ratio = job.RootElement.TryGetProperty("progress", out var ratioValue) && ratioValue.TryGetDouble(out var number) ? number : 0;
                progress.Report("听写中 " + Math.Clamp((int)Math.Round(ratio * 100), 0, 100) + "%");
                if (status == "done")
                    break;
                if (status == "failed")
                {
                    var error = job.RootElement.TryGetProperty("error", out var errorValue) ? errorValue.GetString() : null;
                    throw new InvalidOperationException(string.IsNullOrWhiteSpace(error) ? "生成失败" : error);
                }
            }
        }

        using var request = Authorized(HttpMethod.Get, baseUrl.TrimEnd('/') + "/jobs/" + id + "/cues", token);
        using var response = await Jobs.SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if ((int)response.StatusCode == 409)
            throw new InvalidOperationException("生成失败");
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(ReadError(body));
        return JobCues(body);
    }

    private static async Task<IReadOnlyList<SubtitleCue>> TranscribeQwenAsync(
        string path,
        string baseUrl,
        string token,
        IProgress<string> progress,
        CancellationToken cancellationToken)
    {
        var info = new FileInfo(path);
        if (info.Length > QwenTempBytes)
            throw new InvalidOperationException("这首超过 1GB，千问接不了");
        var origin = Origin(baseUrl) ?? throw new InvalidOperationException("听写地址不正确");
        using var policyDocument = await SendAsync(
            Qwen,
            HttpMethod.Get,
            origin + "/api/v1/uploads?action=getPolicy&model=" + Uri.EscapeDataString(QwenFileModel),
            token,
            null,
            cancellationToken) ?? throw new InvalidOperationException("生成失败");
        if (!policyDocument.RootElement.TryGetProperty("data", out var policy))
            throw new InvalidOperationException("生成失败");
        progress.Report("正在上传");
        var key = await UploadQwenAsync(path, policy, cancellationToken);
        progress.Report("听写中");
        var task = await SubmitQwenAsync(origin, token, "oss://" + key, cancellationToken);
        var resultUrl = await PollQwenAsync(origin, token, task, cancellationToken);
        using var resultRequest = new HttpRequestMessage(HttpMethod.Get, resultUrl);
        using var result = await Qwen.SendAsync(resultRequest, cancellationToken);
        var payload = await result.Content.ReadAsStringAsync(cancellationToken);
        if (!result.IsSuccessStatusCode)
            throw new InvalidOperationException("生成失败");
        return QwenCues(payload);
    }

    private static async Task<string> UploadQwenAsync(string path, JsonElement policy, CancellationToken cancellationToken)
    {
        var host = policy.TryGetProperty("upload_host", out var hostValue) ? hostValue.GetString() : null;
        var dir = policy.TryGetProperty("upload_dir", out var dirValue) ? dirValue.GetString()?.Trim().Trim('/') : null;
        if (string.IsNullOrWhiteSpace(host) || string.IsNullOrWhiteSpace(dir))
            throw new InvalidOperationException("生成失败");
        var name = UploadName(path).Replace("/", "_").Replace("\\", "_").Replace("\"", "_").Replace("\r", "_").Replace("\n", "_");
        var key = dir + "/" + name;
        var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        var content = new OssMultipartContent(stream, name, new (string, string)[]
        {
            ("OSSAccessKeyId", Field(policy, "oss_access_key_id")),
            ("Signature", Field(policy, "signature")),
            ("policy", Field(policy, "policy")),
            ("x-oss-object-acl", Field(policy, "x_oss_object_acl")),
            ("x-oss-forbid-overwrite", Field(policy, "x_oss_forbid_overwrite")),
            ("key", key),
            ("success_action_status", "200"),
        });
        using var request = new HttpRequestMessage(HttpMethod.Post, host) { Content = content };
        request.Headers.ExpectContinue = false;
        using var response = await Qwen.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            var text = await response.Content.ReadAsStringAsync(cancellationToken);
            throw new InvalidOperationException(OssFault(text));
        }

        return key;
    }

    private static async Task<string> SubmitQwenAsync(string origin, string token, string ossUrl, CancellationToken cancellationToken)
    {
        var body = JsonSerializer.Serialize(new
        {
            model = QwenFileModel,
            input = new { file_urls = new[] { ossUrl } },
            parameters = new { language_hints = new[] { "ja" } },
        });
        using var request = Authorized(HttpMethod.Post, origin + "/api/v1/services/audio/asr/transcription", token);
        request.Headers.TryAddWithoutValidation("X-DashScope-Async", "enable");
        request.Headers.TryAddWithoutValidation("X-DashScope-OssResourceResolve", "enable");
        request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await Qwen.SendAsync(request, cancellationToken);
        var text = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(ReadError(text));
        using var document = JsonDocument.Parse(text);
        var id = document.RootElement.TryGetProperty("output", out var output) && output.TryGetProperty("task_id", out var task)
            ? task.GetString()
            : null;
        if (string.IsNullOrWhiteSpace(id))
            throw new InvalidOperationException("生成失败");
        return id;
    }

    private static async Task<string> PollQwenAsync(string origin, string token, string taskId, CancellationToken cancellationToken)
    {
        var url = origin + "/api/v1/tasks/" + taskId;
        while (true)
        {
            using var document = await SendAsync(Qwen, HttpMethod.Get, url, token, null, cancellationToken)
                ?? throw new InvalidOperationException("生成失败");
            if (!document.RootElement.TryGetProperty("output", out var output))
                throw new InvalidOperationException("生成失败");
            var status = output.TryGetProperty("task_status", out var state) ? state.GetString() : "";
            if (status is "PENDING" or "RUNNING" or "UNKNOWN")
            {
                await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken);
                continue;
            }

            JsonElement result = default;
            var hasResult = output.TryGetProperty("results", out var results) && results.ValueKind == JsonValueKind.Array && results.GetArrayLength() > 0;
            if (hasResult)
                result = results[0];
            if (status == "SUCCEEDED")
            {
                if (!hasResult)
                    throw new InvalidOperationException("生成失败");
                if (result.TryGetProperty("subtask_status", out var sub) && sub.GetString() == "FAILED")
                    throw new InvalidOperationException(QwenFault(output, result));
                var transcription = result.TryGetProperty("transcription_url", out var link) ? link.GetString() : null;
                if (string.IsNullOrWhiteSpace(transcription))
                    throw new InvalidOperationException("生成失败");
                return transcription;
            }

            throw new InvalidOperationException(QwenFault(output, result));
        }
    }

    private static async Task<JsonDocument?> SendAsync(
        HttpClient http,
        HttpMethod method,
        string url,
        string token,
        HttpContent? content,
        CancellationToken cancellationToken,
        bool missingIsError = true)
    {
        using var request = Authorized(method, url, token);
        request.Content = content;
        try
        {
            using var response = await http.SendAsync(request, cancellationToken);
            request.Content = null;
            var text = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                if (!missingIsError)
                    return null;
                throw new InvalidOperationException(ReadError(text));
            }

            return JsonDocument.Parse(text);
        }
        catch (InvalidOperationException)
        {
            request.Content = null;
            throw;
        }
        catch (OperationCanceledException)
        {
            request.Content = null;
            throw;
        }
        catch (JsonException)
        {
            request.Content = null;
            throw new InvalidOperationException("生成失败");
        }
        catch
        {
            request.Content = null;
            throw new InvalidOperationException("无法连接听写服务");
        }
    }

    private static HttpRequestMessage Authorized(HttpMethod method, string url, string token)
    {
        var request = new HttpRequestMessage(method, url);
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
        return request;
    }

    private static MultipartFormDataContent AudioForm(string path)
    {
        var form = new MultipartFormDataContent();
        var stream = File.OpenRead(path);
        var file = new StreamContent(stream);
        file.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        form.Add(file, "file", UploadName(path));
        return form;
    }

    private static async Task<string> FingerprintAsync(string path, CancellationToken cancellationToken)
    {
        await using var stream = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(stream, cancellationToken);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static List<SubtitleCue> Segments(JsonDocument json)
    {
        if (!json.RootElement.TryGetProperty("segments", out var array) || array.ValueKind != JsonValueKind.Array)
            return [];
        var cues = new List<SubtitleCue>();
        foreach (var item in array.EnumerateArray())
        {
            var line = item.TryGetProperty("text", out var text) ? text.GetString()?.Trim() : null;
            if (string.IsNullOrEmpty(line))
                continue;
            if (!item.TryGetProperty("start", out var startValue) || !item.TryGetProperty("end", out var endValue))
                continue;
            var start = (long)(startValue.GetDouble() * 1000);
            var end = (long)(endValue.GetDouble() * 1000);
            if (start < 0 || end < 0)
                continue;
            if (end <= start)
                end = start + 1;
            cues.Add(new SubtitleCue(start, end, line));
        }

        cues.Sort((left, right) => left.StartMs.CompareTo(right.StartMs));
        return cues;
    }

    private static List<SubtitleCue> Words(JsonDocument json)
    {
        if (!json.RootElement.TryGetProperty("words", out var array) || array.ValueKind != JsonValueKind.Array)
            return [];
        var cues = new List<SubtitleCue>();
        foreach (var item in array.EnumerateArray())
        {
            var line = item.TryGetProperty("word", out var word) ? word.GetString() : null;
            if (string.IsNullOrEmpty(line) && item.TryGetProperty("text", out var text))
                line = text.GetString();
            if (string.IsNullOrEmpty(line))
                continue;
            if (!item.TryGetProperty("start", out var startValue) || !item.TryGetProperty("end", out var endValue))
                continue;
            var start = (long)(startValue.GetDouble() * 1000);
            var end = (long)(endValue.GetDouble() * 1000);
            if (start < 0 || end < 0)
                continue;
            if (end <= start)
                end = start + 1;
            cues.Add(new SubtitleCue(start, end, line));
        }

        return cues;
    }

    private static List<SubtitleCue> GroupSentences(List<SubtitleCue> pieces)
    {
        var cues = new List<SubtitleCue>();
        var buffer = new StringBuilder();
        var start = 0L;
        var end = 0L;
        void Flush()
        {
            var line = buffer.ToString().Trim();
            buffer.Clear();
            if (line.Length == 0)
                return;
            cues.Add(new SubtitleCue(start, end > start ? end : start + 1, line));
        }

        foreach (var piece in pieces)
        {
            if (buffer.Length == 0)
                start = piece.StartMs;
            buffer.Append(piece.Text);
            end = piece.EndMs;
            if (buffer.Length > 0 && "。！？!?".Contains(buffer[^1]))
                Flush();
        }

        Flush();
        return cues;
    }

    private static List<SubtitleCue> WholeCue(string text, long durationMs)
    {
        var marker = "<asr_text>";
        var at = text.IndexOf(marker, StringComparison.Ordinal);
        var line = (at >= 0 ? text[(at + marker.Length)..] : text).Trim();
        if (line.Length == 0)
            return [];
        if (durationMs <= 1)
            throw new InvalidOperationException("没有分段时间");
        return [new SubtitleCue(0, durationMs, line)];
    }

    private static string TextOf(JsonDocument json) =>
        json.RootElement.TryGetProperty("text", out var text) ? text.GetString() ?? "" : "";

    private static List<SubtitleCue> JobCues(string body)
    {
        using var document = JsonDocument.Parse(body);
        if (!document.RootElement.TryGetProperty("cues", out var array) || array.ValueKind != JsonValueKind.Array)
            throw new InvalidOperationException("生成失败");
        var cues = new List<SubtitleCue>();
        foreach (var item in array.EnumerateArray())
        {
            var line = item.TryGetProperty("text", out var text) ? text.GetString()?.Trim() : null;
            if (string.IsNullOrEmpty(line))
                continue;
            var start = item.TryGetProperty("startMs", out var startValue) ? startValue.GetInt64() : -1;
            var end = item.TryGetProperty("endMs", out var endValue) ? endValue.GetInt64() : -1;
            if (start < 0 || end <= start)
                continue;
            cues.Add(new SubtitleCue(start, end, line));
        }

        cues.Sort((left, right) => left.StartMs.CompareTo(right.StartMs));
        return cues;
    }

    private static List<SubtitleCue> QwenCues(string body)
    {
        using var document = JsonDocument.Parse(body);
        var found = new List<SubtitleCue>();
        if (document.RootElement.TryGetProperty("output", out var output))
            AddSentences(found, output, "sentences");
        if (found.Count == 0 && document.RootElement.TryGetProperty("transcripts", out var transcripts) && transcripts.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in transcripts.EnumerateArray())
                AddSentences(found, item, "sentences");
        }

        if (found.Count == 0 && output.ValueKind == JsonValueKind.Object && output.TryGetProperty("sentence", out var sentence))
            AddSentence(found, sentence);
        if (found.Count == 0 && output.ValueKind == JsonValueKind.Object && output.TryGetProperty("text", out var text) && !string.IsNullOrWhiteSpace(text.GetString()))
            throw new InvalidOperationException("没有分段时间");
        found.Sort((left, right) => left.StartMs.CompareTo(right.StartMs));
        return found;
    }

    private static void AddSentences(List<SubtitleCue> cues, JsonElement parent, string name)
    {
        if (parent.ValueKind != JsonValueKind.Object || !parent.TryGetProperty(name, out var array) || array.ValueKind != JsonValueKind.Array)
            return;
        foreach (var item in array.EnumerateArray())
            AddSentence(cues, item);
    }

    private static void AddSentence(List<SubtitleCue> cues, JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object)
            return;
        if (item.TryGetProperty("sentence_end", out var ended) && ended.ValueKind == JsonValueKind.False)
            return;
        var line = item.TryGetProperty("text", out var text) ? text.GetString()?.Trim() : null;
        if (string.IsNullOrEmpty(line))
            return;
        var start = item.TryGetProperty("begin_time", out var startValue) ? startValue.GetInt64() : -1;
        var end = item.TryGetProperty("end_time", out var endValue) ? endValue.GetInt64() : -1;
        if (start < 0 || end < 0)
            return;
        if (end <= start)
            end = start + 1;
        cues.Add(new SubtitleCue(start, end, line));
    }

    private static string ReadError(string text)
    {
        try
        {
            using var document = JsonDocument.Parse(text);
            if (!document.RootElement.TryGetProperty("error", out var error))
                return Short(document.RootElement, "message") ?? "生成失败";
            if (error.ValueKind == JsonValueKind.String)
                return string.IsNullOrWhiteSpace(error.GetString()) ? "生成失败" : error.GetString()!;
            if (error.ValueKind == JsonValueKind.Object && error.TryGetProperty("message", out var message) && !string.IsNullOrWhiteSpace(message.GetString()))
                return message.GetString()!;
        }
        catch
        {
            // 不是 JSON 时用统一说法。
        }

        return "生成失败";
    }

    private static string? Short(JsonElement item, string name)
    {
        if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty(name, out var value))
            return null;
        var text = value.GetString();
        if (string.IsNullOrWhiteSpace(text))
            return null;
        return text.Length <= 80 ? text : text[..80];
    }

    private static string Field(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) ? value.GetString() ?? "" : "";

    private static string? Origin(string baseUrl)
    {
        if (!Uri.TryCreate(baseUrl.Contains("://") ? baseUrl : "https://" + baseUrl, UriKind.Absolute, out var uri))
            return null;
        if (uri.Host.Length == 0)
            return null;
        var port = uri.IsDefaultPort ? "" : ":" + uri.Port;
        return uri.Scheme + "://" + uri.Host + port;
    }

    private static string? Host(string? baseUrl)
    {
        if (string.IsNullOrWhiteSpace(baseUrl))
            return null;
        return Uri.TryCreate(baseUrl, UriKind.Absolute, out var uri) ? uri.Host.ToLowerInvariant() : null;
    }

    private static string UploadName(string path)
    {
        var fileName = Path.GetFileName(path);
        var header = new byte[16];
        var count = 0;
        using (var stream = File.OpenRead(path))
            count = stream.Read(header, 0, header.Length);
        if (count < header.Length)
            header = header[..count];
        var cleaned = string.IsNullOrWhiteSpace(fileName) ? "audio" : fileName.Replace("\r", "_").Replace("\n", "_").Replace("\"", "_");
        var current = Path.GetExtension(cleaned).TrimStart('.').ToLowerInvariant();
        if (current is "mp3" or "m4a" or "aac" or "wav" or "ogg" or "flac")
            return cleaned;
        var sniffed = Sniff(header);
        if (sniffed == null)
            return cleaned;
        var stem = Path.GetFileNameWithoutExtension(cleaned);
        if (string.IsNullOrEmpty(stem))
            stem = cleaned;
        return stem + "." + sniffed;
    }

    private static string? Sniff(byte[] header)
    {
        if (header.Length >= 3 && header[0] == (byte)'I' && header[1] == (byte)'D' && header[2] == (byte)'3')
            return "mp3";
        if (header.Length >= 2 && header[0] == 0xFF)
        {
            var second = header[1];
            if ((second & 0xE0) == 0xE0 && (second & 0x06) == 0x02)
                return "mp3";
            if ((second & 0xF6) == 0xF0)
                return "aac";
        }

        if (Starts(header, "fLaC"))
            return "flac";
        if (Starts(header, "OggS"))
            return "ogg";
        if (Starts(header, "RIFF") && header.Length >= 12 && Encoding.ASCII.GetString(header, 8, 4) == "WAVE")
            return "wav";
        if (header.Length >= 8 && Encoding.ASCII.GetString(header, 4, 4) == "ftyp")
            return "m4a";
        return null;
    }

    private static bool Starts(byte[] header, string text) =>
        header.Length >= text.Length && Encoding.ASCII.GetString(header, 0, text.Length) == text;

    private static string QwenFault(JsonElement output, JsonElement result)
    {
        var message = Short(result, "message") ?? Short(output, "message") ?? Short(output, "code");
        if (message is "ASR_RESPONSE_HAVE_NO_WORDS")
            return "没有听出人声";
        return message ?? "生成失败";
    }

    private static string OssFault(string text)
    {
        var message = Between(text, "<Message>", "</Message>");
        if (string.IsNullOrWhiteSpace(message))
            return "生成失败";
        message = message.Trim();
        return message.Length <= 80 ? message : message[..80];
    }

    private static string? Between(string text, string start, string end)
    {
        var from = text.IndexOf(start, StringComparison.Ordinal);
        if (from < 0)
            return null;
        from += start.Length;
        var to = text.IndexOf(end, from, StringComparison.Ordinal);
        return to < 0 ? null : text[from..to];
    }

    /// <summary>
    /// 阿里云 OSS 不接受 .NET 默认的 multipart：字段名不带引号会被当成格式错误。
    /// </summary>
    private sealed class OssMultipartContent : HttpContent
    {
        private readonly byte[] _head;
        private readonly byte[] _tail;
        private readonly Stream _file;
        private readonly long _fileLength;

        public OssMultipartContent(Stream file, string fileName, IReadOnlyList<(string Name, string Value)> fields)
        {
            _file = file;
            _fileLength = file.Length;
            var boundary = "----PlayerDemo" + Guid.NewGuid().ToString("N");
            var builder = new StringBuilder();
            foreach (var (name, value) in fields)
            {
                builder.Append("--").Append(boundary).Append("\r\n");
                builder.Append("Content-Disposition: form-data; name=\"").Append(name).Append("\"\r\n\r\n");
                builder.Append(value).Append("\r\n");
            }

            builder.Append("--").Append(boundary).Append("\r\n");
            builder.Append("Content-Disposition: form-data; name=\"file\"; filename=\"").Append(fileName).Append("\"\r\n");
            builder.Append("Content-Type: application/octet-stream\r\n\r\n");
            _head = Encoding.UTF8.GetBytes(builder.ToString());
            _tail = Encoding.UTF8.GetBytes("\r\n--" + boundary + "--\r\n");
            Headers.TryAddWithoutValidation("Content-Type", "multipart/form-data; boundary=" + boundary);
            Headers.ContentLength = _head.Length + _fileLength + _tail.Length;
        }

        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context)
        {
            await stream.WriteAsync(_head);
            _file.Position = 0;
            await _file.CopyToAsync(stream);
            await stream.WriteAsync(_tail);
        }

        protected override bool TryComputeLength(out long length)
        {
            length = _head.Length + _fileLength + _tail.Length;
            return true;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
                _file.Dispose();
            base.Dispose(disposing);
        }
    }
}
