using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;

namespace PlayerDemo.Subtitles;

public static class CueDocument
{
    public static string SrtPath(string audioPath) => audioPath + ".whisper.srt";

    public static string JsonPath(string audioPath) => audioPath + ".cues.json";

    public static void Save(string audioPath, IReadOnlyList<SubtitleCue> cues)
    {
        var payload = cues.Select(cue => new StoredCue(cue.StartMs, cue.EndMs, cue.Text, cue.Translation)).ToList();
        File.WriteAllText(JsonPath(audioPath), JsonSerializer.Serialize(payload));
        WriteSrt(SrtPath(audioPath), cues);
    }

    public static List<SubtitleCue> Apply(string audioPath, List<SubtitleCue> cues)
    {
        var path = JsonPath(audioPath);
        if (!File.Exists(path))
            return cues;
        try
        {
            var stored = JsonSerializer.Deserialize<List<StoredCue>>(File.ReadAllText(path));
            if (stored == null || stored.Count == 0)
                return cues;
            var byStart = stored.GroupBy(item => item.StartMs).ToDictionary(group => group.Key, group => group.First().Translation ?? "");
            var merged = new List<SubtitleCue>(cues.Count);
            foreach (var cue in cues)
            {
                var translation = byStart.TryGetValue(cue.StartMs, out var line) ? line : cue.Translation;
                merged.Add(cue with { Translation = translation });
            }

            return merged;
        }
        catch
        {
            return cues;
        }
    }

    public static void WriteSrt(string path, IReadOnlyList<SubtitleCue> cues)
    {
        var text = new StringBuilder();
        var number = 1;
        foreach (var cue in cues)
        {
            if (cue.Text.Length == 0)
                continue;
            text.Append(number++).Append('\n');
            text.Append(Stamp(cue.StartMs)).Append(" --> ").Append(Stamp(cue.EndMs)).Append('\n');
            text.Append(cue.Text.Replace("\r\n", "\n").Replace('\r', '\n')).Append("\n\n");
        }

        File.WriteAllText(path, text.ToString(), new UTF8Encoding(encoderShouldEmitUTF8Identifier: true));
    }

    private static string Stamp(long ms)
    {
        if (ms < 0)
            ms = 0;
        var time = TimeSpan.FromMilliseconds(ms);
        return string.Create(CultureInfo.InvariantCulture, $"{(int)time.TotalHours:00}:{time.Minutes:00}:{time.Seconds:00},{time.Milliseconds:000}");
    }

    private sealed record StoredCue(long StartMs, long EndMs, string Text, string? Translation);
}
