using System.IO;

namespace PlayerDemo.Subtitles;

public static class SubtitleFinder
{
    private static readonly string[] Extensions = ["lrc", "vtt", "srt"];
    private static readonly Dictionary<string, string[]> NamesByDirectory = new(StringComparer.OrdinalIgnoreCase);
    private static readonly object Gate = new();

    public static void ClearCache()
    {
        lock (Gate)
            NamesByDirectory.Clear();
    }

    public static List<SubtitleFile> ForAudio(string audioPath)
    {
        var directory = Path.GetDirectoryName(audioPath);
        if (string.IsNullOrEmpty(directory))
            return [];

        var audioName = Path.GetFileName(audioPath);
        var stem = Path.GetFileNameWithoutExtension(audioPath);
        var names = DirectoryNames(directory);
        var chosen = new List<string>();

        void Take(string fileName)
        {
            var actual = names.FirstOrDefault(name => name.Equals(fileName, StringComparison.OrdinalIgnoreCase));
            if (actual != null && !chosen.Contains(actual, StringComparer.OrdinalIgnoreCase))
                chosen.Add(actual);
        }

        foreach (var extension in Extensions)
            Take(audioName + "." + extension);
        foreach (var extension in Extensions)
            Take(stem + "." + extension);

        var variants = names
            .Where(name => name.StartsWith(stem + ".", StringComparison.OrdinalIgnoreCase))
            .Where(name => Extensions.Contains(Path.GetExtension(name).TrimStart('.'), StringComparer.OrdinalIgnoreCase))
            .Where(name => !chosen.Contains(name, StringComparer.OrdinalIgnoreCase))
            .OrderBy(name => ExtensionRank(name))
            .ThenBy(name => name, StringComparer.OrdinalIgnoreCase);
        chosen.AddRange(variants);

        return chosen
            .Select(name => new SubtitleFile(Path.Combine(directory, name), name))
            .ToList();
    }

    public static string? SelfCheck()
    {
        var directory = Path.Combine(Path.GetTempPath(), "player-demo-subs-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            File.WriteAllText(Path.Combine(directory, "01.mp3"), "");
            File.WriteAllText(Path.Combine(directory, "01.mp3.lrc"), "[00:01.50]甲");
            File.WriteAllText(Path.Combine(directory, "01.lrc"), "[00:02.00]乙");
            File.WriteAllText(Path.Combine(directory, "01.vtt"), "WEBVTT\n\n00:00:03.000 --> 00:00:04.000\n丙\n");
            File.WriteAllText(Path.Combine(directory, "01.ja.srt"), "1\n00:00:05,000 --> 00:00:06,000\n丁\n");
            File.WriteAllText(Path.Combine(directory, "other.srt"), "1\n00:00:01,000 --> 00:00:02,000\n无关\n");
            ClearCache();
            var matched = ForAudio(Path.Combine(directory, "01.mp3"));
            var names = matched.Select(item => item.Name).ToArray();
            if (names.Length != 4 || names[0] != "01.mp3.lrc" || names[1] != "01.lrc" || names[2] != "01.vtt")
                return "匹配顺序不对：" + string.Join(", ", names);
            if (names.Contains("other.srt"))
                return "无关字幕被认进来了";

            var cues = SubtitleParser.Parse(File.ReadAllText(Path.Combine(directory, "01.mp3.lrc")));
            if (cues.Count != 1 || cues[0].StartMs != 1500 || cues[0].Text != "甲")
                return "lrc 时间不对";
            if (SubtitleParser.TextAt(cues, 1500) != "甲" || SubtitleParser.TextAt(cues, 1499) != "")
                return "字幕跟进度对不上";

            var timed = SubtitleParser.Parse(File.ReadAllText(Path.Combine(directory, "01.ja.srt")));
            if (timed.Count != 1 || timed[0].StartMs != 5000 || timed[0].Text != "丁")
                return "srt 解析不对";
            return null;
        }
        catch (Exception ex)
        {
            return ex.Message;
        }
        finally
        {
            try
            {
                Directory.Delete(directory, true);
            }
            catch
            {
                // 临时目录留着也没关系。
            }
        }
    }

    private static string[] DirectoryNames(string directory)
    {
        lock (Gate)
        {
            if (NamesByDirectory.TryGetValue(directory, out var cached))
                return cached;
            var names = Directory.Exists(directory)
                ? Directory.GetFiles(directory).Select(Path.GetFileName).Where(name => name != null).Cast<string>().ToArray()
                : [];
            NamesByDirectory[directory] = names;
            return names;
        }
    }

    private static int ExtensionRank(string name)
    {
        var extension = Path.GetExtension(name).TrimStart('.');
        var rank = Array.FindIndex(Extensions, item => item.Equals(extension, StringComparison.OrdinalIgnoreCase));
        return rank < 0 ? Extensions.Length : rank;
    }
}
