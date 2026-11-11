using System.Globalization;
using System.IO;
using System.Text;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PlayerDemo.Library;
using PlayerDemo.Subtitles;

namespace PlayerDemo.Playback;

/// <summary>推到 DLNA 时一并带上的封面和歌词。设备自己来拉这两个地址。</summary>
public sealed class DlnaSidecar
{
    public string? Album { get; init; }
    public byte[]? CoverJpeg { get; init; }
    public byte[]? Lyrics { get; init; }

    public static DlnaSidecar FromTrack(TrackItem track)
    {
        return new DlnaSidecar
        {
            Album = string.IsNullOrWhiteSpace(track.Album) ? null : track.Album.Trim(),
            CoverJpeg = Jpeg(track.CoverPath ?? TrackLoader.CoverFile(track.FilePath), track.ArtBytes),
            Lyrics = LyricsOf(track),
        };
    }

    public static string? FormatLrc(IReadOnlyList<SubtitleCue> cues)
    {
        var builder = new StringBuilder();
        foreach (var cue in cues)
        {
            var text = OneLine(cue.Text);
            if (text.Length == 0)
                continue;
            var translation = OneLine(cue.Translation);
            if (translation.Length > 0)
                text += " / " + translation;
            builder.Append('[').Append(Stamp(cue.StartMs)).Append(']').Append(text).Append('\n');
        }

        return builder.Length == 0 ? null : builder.ToString();
    }

    private static byte[]? LyricsOf(TrackItem track)
    {
        var text = LyricsText(track);
        if (string.IsNullOrWhiteSpace(text))
            return null;
        var payload = Encoding.UTF8.GetBytes(text);
        var bom = Encoding.UTF8.GetPreamble();
        var bytes = new byte[bom.Length + payload.Length];
        bom.CopyTo(bytes, 0);
        payload.CopyTo(bytes, bom.Length);
        return bytes;
    }

    private static string? LyricsText(TrackItem track)
    {
        var file = track.SelectedSubtitle;
        if (file == null)
        {
            foreach (var item in track.Subtitles)
            {
                if (item.Kind.Equals("LRC", StringComparison.OrdinalIgnoreCase))
                {
                    file = item;
                    break;
                }
            }

            file ??= track.Subtitles.FirstOrDefault();
        }

        if (file != null && File.Exists(file.FullPath))
        {
            try
            {
                var lrc = FormatLrc(SubtitleParser.Read(file.FullPath));
                if (!string.IsNullOrWhiteSpace(lrc))
                    return lrc;
            }
            catch
            {
                // 字幕读坏了就改看音频标签。
            }
        }

        try
        {
            using var tags = TagLib.File.Create(track.FilePath);
            var lyrics = tags.Tag.Lyrics;
            if (string.IsNullOrWhiteSpace(lyrics))
                return null;
            return PlainOrLrc(lyrics);
        }
        catch
        {
            return null;
        }
    }

    private static string? PlainOrLrc(string text)
    {
        var trimmed = text.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n').Trim();
        if (trimmed.Length == 0)
            return null;
        if (trimmed.Contains('[', StringComparison.Ordinal) && trimmed.Contains(':', StringComparison.Ordinal))
            return trimmed + "\n";
        var lines = trimmed.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (lines.Length == 0)
            return null;
        var builder = new StringBuilder();
        foreach (var line in lines)
            builder.Append("[00:00.00]").Append(line).Append('\n');
        return builder.ToString();
    }

    private static string OneLine(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
            return "";
        return text.Replace("\r", " ", StringComparison.Ordinal).Replace("\n", " ", StringComparison.Ordinal).Trim();
    }

    private static string Stamp(long milliseconds)
    {
        if (milliseconds < 0)
            milliseconds = 0;
        var time = TimeSpan.FromMilliseconds(milliseconds);
        var hundredths = time.Milliseconds / 10;
        return ((int)time.TotalMinutes).ToString("00", CultureInfo.InvariantCulture)
            + ":" + time.Seconds.ToString("00", CultureInfo.InvariantCulture)
            + "." + hundredths.ToString("00", CultureInfo.InvariantCulture);
    }

    private static byte[]? Jpeg(string? path, byte[]? embedded)
    {
        BitmapSource? image = null;
        if (!string.IsNullOrWhiteSpace(path))
            image = TrackLoader.DecodeFile(path);
        if (image == null && embedded is { Length: > 0 })
            image = TrackLoader.DecodeArt(embedded);
        if (image == null)
            return null;
        try
        {
            if (image.PixelWidth > 480)
            {
                var ratio = 480.0 / image.PixelWidth;
                var scaled = new TransformedBitmap(image, new ScaleTransform(ratio, ratio));
                if (scaled.CanFreeze)
                    scaled.Freeze();
                image = scaled;
            }

            var encoder = new JpegBitmapEncoder { QualityLevel = 85 };
            encoder.Frames.Add(BitmapFrame.Create(image));
            using var memory = new MemoryStream();
            encoder.Save(memory);
            return memory.ToArray();
        }
        catch
        {
            return null;
        }
    }
}
