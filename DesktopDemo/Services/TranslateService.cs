using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PlayerDemo.Services;

public static partial class TranslateService
{
    private const int BatchSize = 40;
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(3) };

    public static async Task<string[]> TranslateAsync(ServiceSettings settings, IReadOnlyList<string> lines, CancellationToken cancellationToken)
    {
        if (lines.Count == 0)
            return [];
        var translated = new string[lines.Count];
        for (var start = 0; start < lines.Count; start += BatchSize)
        {
            var count = Math.Min(BatchSize, lines.Count - start);
            var batch = new string[count];
            for (var i = 0; i < count; i++)
                batch[i] = lines[start + i];
            var piece = await TranslateBatchAsync(settings, batch, cancellationToken);
            if (piece == null)
                throw new InvalidOperationException("翻译失败");
            for (var i = 0; i < count; i++)
                translated[start + i] = piece[i];
        }

        return translated;
    }

    public static string[]? ParseReply(string raw, int count)
    {
        if (count <= 0)
            return [];
        var cleaned = Fence().Replace(raw, "");
        var lines = new Dictionary<int, string>();
        foreach (var line in cleaned.Split('\n'))
        {
            var match = Row().Match(line.Trim());
            if (!match.Success || !int.TryParse(match.Groups[1].Value, out var number))
                continue;
            var text = match.Groups[2].Value.Trim();
            if (number >= 1 && number <= count && text.Length > 0)
                lines[number] = text;
        }

        if (Enumerable.Range(1, count).Any(number => !lines.ContainsKey(number)))
            return null;
        return Enumerable.Range(1, count).Select(number => lines[number]).ToArray();
    }

    public static string? SelfCheck()
    {
        var parsed = ParseReply("1|甲\n2|乙", 2);
        if (parsed == null || parsed.Length != 2 || parsed[0] != "甲" || parsed[1] != "乙")
            return "译文行对不上";
        if (ParseReply("1|甲", 2) != null)
            return "缺行仍被收下了";
        if (ParseReply("```\n1|甲\n```", 1) is not ["甲"])
            return "代码块没剥掉";
        return null;
    }

    private static async Task<string[]?> TranslateBatchAsync(ServiceSettings settings, IReadOnlyList<string> lines, CancellationToken cancellationToken)
    {
        var baseUrl = ServiceSettings.NormalizeTranslate(settings.TranslateBase);
        var model = settings.TranslateModel.Trim();
        var token = settings.TranslateToken.Trim();
        if (string.IsNullOrEmpty(baseUrl))
            throw new InvalidOperationException("没有翻译地址");
        if (model.Length == 0)
            throw new InvalidOperationException("没有模型名");
        if (token.Length == 0)
            throw new InvalidOperationException("没有翻译口令");

        var count = lines.Count;
        var rules = $"""
            把日语字幕译成简体中文。只输出译文本身。
            必须正好输出 {count} 行，不能多，不能少，不能空行。
            第 i 行只能是 i|译文。编号从 1 连续排到 {count}。
            竖线必须是半角字符 |。
            不要输出思考过程、解释、标题、Markdown 或引号。
            某一句很难译时，仍然写出该编号，并把意思译成简体中文。
            """;
        var source = new StringBuilder();
        source.Append("按上面的格式翻译下面 ").Append(count).Append(" 句。\n");
        for (var i = 0; i < lines.Count; i++)
        {
            var spoken = lines[i].Replace('\n', ' ').Replace('\r', ' ').Replace("|", " ").Replace("｜", " ").Trim();
            source.Append(i + 1).Append('|').Append(spoken).Append('\n');
        }

        var messages = new object[]
        {
            new { role = "system", content = rules },
            new { role = "user", content = source.ToString() },
        };
        var body = baseUrl.Contains("deepseek.com", StringComparison.OrdinalIgnoreCase)
            ? JsonSerializer.Serialize(new
            {
                model,
                temperature = 0,
                thinking = new { type = "disabled" },
                messages,
            })
            : JsonSerializer.Serialize(new
            {
                model,
                temperature = 0,
                enable_thinking = false,
                messages,
            });
        using var request = new HttpRequestMessage(HttpMethod.Post, baseUrl.TrimEnd('/') + "/chat/completions");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
        request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await Http.SendAsync(request, cancellationToken);
        var payload = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(Clip("HTTP " + (int)response.StatusCode + "\n" + payload));
        var content = MessageContent(payload);
        if (string.IsNullOrWhiteSpace(content))
            throw new InvalidOperationException(Clip(payload));
        var parsed = ParseReply(content, count);
        if (parsed == null)
            throw new InvalidOperationException(Clip(content));
        return parsed;
    }

    private static string? MessageContent(string payload)
    {
        try
        {
            using var document = JsonDocument.Parse(payload);
            if (!document.RootElement.TryGetProperty("choices", out var choices) || choices.GetArrayLength() == 0)
                return null;
            var message = choices[0].GetProperty("message");
            if (!message.TryGetProperty("content", out var content))
                return null;
            if (content.ValueKind == JsonValueKind.String)
                return content.GetString();
            if (content.ValueKind != JsonValueKind.Array)
                return null;
            var text = new StringBuilder();
            foreach (var part in content.EnumerateArray())
            {
                if (part.TryGetProperty("text", out var piece))
                    text.Append(piece.GetString());
            }

            return text.Length == 0 ? null : text.ToString();
        }
        catch
        {
            return null;
        }
    }

    private static string Clip(string text) => text.Length <= 8000 ? text : text[..8000];

    [GeneratedRegex("```[a-zA-Z]*")]
    private static partial Regex Fence();

    [GeneratedRegex(@"^\s*(\d+)\s*[|｜]\s*(.*)$")]
    private static partial Regex Row();
}
