using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace PlayerDemo.Subtitles;

public static partial class SubtitleParser
{
    static SubtitleParser()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    public static List<SubtitleCue> Read(string path) => Parse(ReadText(path));

    public static List<SubtitleCue> Parse(string raw)
    {
        var text = raw.Trim().TrimStart('\uFEFF');
        if (text.Length == 0)
            return [];
        if (text.StartsWith('{'))
        {
            var bilibili = ParseBilibili(text);
            if (bilibili.Count > 0)
                return bilibili;
        }

        if (text.StartsWith("WEBVTT", StringComparison.OrdinalIgnoreCase) || text.Contains("-->", StringComparison.Ordinal))
            return ParseTimedBlocks(text);
        return ParseLrc(text);
    }

    public static string TextAt(IReadOnlyList<SubtitleCue> cues, long positionMs)
    {
        for (var i = cues.Count - 1; i >= 0; i--)
        {
            if (positionMs >= cues[i].StartMs && positionMs < cues[i].EndMs)
                return cues[i].Text;
        }

        return "";
    }

    private static string ReadText(string path)
    {
        var bytes = File.ReadAllBytes(path);
        if (bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF)
            return Encoding.UTF8.GetString(bytes, 3, bytes.Length - 3);
        if (bytes.Length >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE)
            return Encoding.Unicode.GetString(bytes, 2, bytes.Length - 2);
        if (bytes.Length >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF)
            return Encoding.BigEndianUnicode.GetString(bytes, 2, bytes.Length - 2);

        try
        {
            return new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true).GetString(bytes);
        }
        catch (DecoderFallbackException)
        {
            return Encoding.GetEncoding(54936).GetString(bytes);
        }
    }

    private static List<SubtitleCue> ParseTimedBlocks(string text)
    {
        var lines = text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
        var cues = new List<SubtitleCue>();
        var index = 0;
        while (index < lines.Length)
        {
            var line = lines[index].Trim();
            if (!line.Contains("-->", StringComparison.Ordinal))
            {
                index++;
                continue;
            }

            var parts = line.Split("-->", 2);
            var start = ParseClock(parts[0]);
            var end = parts.Length > 1 ? ParseClock(parts[1]) : null;
            index++;
            if (start == null || end == null)
                continue;

            var body = new List<string>();
            while (index < lines.Length && lines[index].Trim().Length > 0)
            {
                body.Add(lines[index].Trim());
                index++;
            }

            var spoken = StripTags(string.Join("\n", body).Trim());
            if (spoken.Length > 0)
                cues.Add(new SubtitleCue(start.Value, Math.Max(end.Value, start.Value + 1), spoken));
        }

        cues.Sort((left, right) => left.StartMs.CompareTo(right.StartMs));
        return cues;
    }

    private static List<SubtitleCue> ParseLrc(string text)
    {
        var stamped = new List<(long Start, string Text)>();
        foreach (var line in text.Replace("\r\n", "\n").Split('\n'))
        {
            var marks = LrcStamp().Matches(line);
            if (marks.Count == 0)
                continue;
            var spoken = WordStamp().Replace(line[(marks[^1].Index + marks[^1].Length)..], "").Trim();
            spoken = StripTags(spoken);
            if (spoken.Length == 0)
                continue;
            foreach (Match mark in marks)
            {
                if (!long.TryParse(mark.Groups[1].Value, out var minute))
                    continue;
                if (!long.TryParse(mark.Groups[2].Value, out var second))
                    continue;
                var start = minute * 60_000L + second * 1000L + FractionMillis(mark.Groups[3].Value);
                stamped.Add((start, spoken));
            }
        }

        stamped.Sort((left, right) => left.Start.CompareTo(right.Start));
        var cues = new List<SubtitleCue>(stamped.Count);
        for (var i = 0; i < stamped.Count; i++)
        {
            var end = i + 1 < stamped.Count ? stamped[i + 1].Start : long.MaxValue;
            cues.Add(new SubtitleCue(stamped[i].Start, Math.Max(end, stamped[i].Start + 1), stamped[i].Text));
        }

        return cues;
    }

    private static List<SubtitleCue> ParseBilibili(string text)
    {
        try
        {
            using var document = System.Text.Json.JsonDocument.Parse(text);
            if (!document.RootElement.TryGetProperty("body", out var body) || body.ValueKind != System.Text.Json.JsonValueKind.Array)
                return [];
            var cues = new List<SubtitleCue>();
            foreach (var item in body.EnumerateArray())
            {
                var line = item.TryGetProperty("content", out var content) ? content.GetString() ?? "" : "";
                line = BreakTag().Replace(line, "\n");
                line = StripTags(line).Trim();
                if (line.Length == 0)
                    continue;
                var start = item.TryGetProperty("from", out var from) ? (long)Math.Max(0, from.GetDouble() * 1000) : 0;
                var end = item.TryGetProperty("to", out var to) ? (long)Math.Max(start, to.GetDouble() * 1000) : start;
                cues.Add(new SubtitleCue(start, end == start ? start + 1 : end, line));
            }

            cues.Sort((left, right) => left.StartMs.CompareTo(right.StartMs));
            return cues;
        }
        catch
        {
            return [];
        }
    }

    private static long? ParseClock(string token)
    {
        var cleaned = token.Trim();
        var space = cleaned.IndexOf(' ');
        if (space >= 0)
            cleaned = cleaned[..space];
        var match = Clock().Match(cleaned);
        if (!match.Success)
            return null;
        var hours = match.Groups[1].Success && long.TryParse(match.Groups[1].Value, out var parsedHours) ? parsedHours : 0;
        if (!long.TryParse(match.Groups[2].Value, out var minutes))
            return null;
        if (!long.TryParse(match.Groups[3].Value, out var seconds))
            return null;
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + FractionMillis(match.Groups[4].Value);
    }

    private static long FractionMillis(string raw)
    {
        if (raw.Length == 0)
            return 0;
        var digits = raw.Length <= 3 ? raw : raw[..3];
        if (!long.TryParse(digits, out var value))
            return 0;
        return digits.Length switch
        {
            1 => value * 100,
            2 => value * 10,
            _ => value,
        };
    }

    private static string StripTags(string text) => Tag().Replace(text, "");

    [GeneratedRegex(@"\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]")]
    private static partial Regex LrcStamp();

    [GeneratedRegex(@"<\d+:\d{1,2}(?:[.:]\d{1,3})?>")]
    private static partial Regex WordStamp();

    [GeneratedRegex(@"^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$")]
    private static partial Regex Clock();

    [GeneratedRegex("<[^>]+>")]
    private static partial Regex Tag();

    [GeneratedRegex("(?i)<br\\s*/?>")]
    private static partial Regex BreakTag();
}
