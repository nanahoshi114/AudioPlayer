using System.IO;
using System.Text.Json;

namespace PlayerDemo.Library;

public sealed class AlbumItem
{
    public required string Key { get; init; }
    public required string Title { get; init; }
    public required string Detail { get; init; }
    public required string DurationText { get; init; }
    public System.Windows.Media.Imaging.BitmapSource? Art { get; init; }
    public required IReadOnlyList<TrackItem> Tracks { get; init; }
}

public static class AlbumShelf
{
    public static List<AlbumItem> Group(IReadOnlyList<TrackItem> tracks, bool showDuplicates)
    {
        var visible = tracks.Where(track => showDuplicates || !track.IsDuplicate).ToList();
        var albums = new List<AlbumItem>();
        foreach (var group in visible.GroupBy(KeyOf))
        {
            var items = group.ToList();
            items.Sort(CompareTracks);
            var artist = items.Select(track => track.Artist).FirstOrDefault(name => name.Length > 0) ?? "";
            var (title, circle) = TitleOf(group.Key, items);
            if (circle.Length > 0)
                artist = circle;
            var duration = TimeSpan.FromTicks(items.Sum(track => track.Duration.Ticks));
            var detail = artist.Length == 0 ? $"{items.Count} 首" : $"{artist} · {items.Count} 首";
            albums.Add(new AlbumItem
            {
                Key = group.Key,
                Title = title,
                Detail = detail,
                DurationText = TrackItem.FormatTime(duration),
                Art = items.Select(track => track.Art).FirstOrDefault(art => art != null),
                Tracks = items,
            });
        }

        albums.Sort((a, b) => string.Compare(a.Title, b.Title, StringComparison.OrdinalIgnoreCase));
        return albums;
    }

    public static void Number(IReadOnlyList<TrackItem> tracks)
    {
        for (var i = 0; i < tracks.Count; i++)
            tracks[i].Order = i + 1;
    }

    private static int CompareTracks(TrackItem a, TrackItem b)
    {
        var an = a.TrackNumber > 0 ? a.TrackNumber : int.MaxValue;
        var bn = b.TrackNumber > 0 ? b.TrackNumber : int.MaxValue;
        var number = an.CompareTo(bn);
        if (number != 0)
            return number;
        return Natural(a.FilePath, b.FilePath);
    }

    private static string KeyOf(TrackItem track)
    {
        var library = TrackLoader.LibraryRoot();
        var prefix = library + Path.DirectorySeparatorChar;
        if (track.FilePath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            var rest = track.FilePath[prefix.Length..];
            var split = rest.IndexOf(Path.DirectorySeparatorChar);
            if (split > 0)
                return "work:" + rest[..split];
        }

        if (!string.IsNullOrWhiteSpace(track.Album))
            return "tag:" + track.Album.Trim();
        return "dir:" + (Path.GetDirectoryName(track.FilePath) ?? track.FilePath);
    }

    private static (string Title, string Circle) TitleOf(string key, IReadOnlyList<TrackItem> tracks)
    {
        if (key.StartsWith("work:", StringComparison.Ordinal))
        {
            var sourceId = key["work:".Length..];
            var note = ReadWorkNote(sourceId);
            if (note.Title.Length > 0)
                return note;
            var tagged = tracks.Select(track => track.Album.Trim()).FirstOrDefault(name => name.Length > 0);
            return (tagged ?? sourceId, note.Circle);
        }

        if (key.StartsWith("tag:", StringComparison.Ordinal))
            return (key["tag:".Length..], "");
        var directory = key["dir:".Length..];
        var folder = Path.GetFileName(directory.TrimEnd(Path.DirectorySeparatorChar));
        return (folder.Length == 0 ? "未归类" : folder, "");
    }

    private static (string Title, string Circle) ReadWorkNote(string sourceId)
    {
        try
        {
            var path = Path.Combine(TrackLoader.LibraryRoot(), sourceId, "work.json");
            if (!File.Exists(path))
                return ("", "");
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var title = document.RootElement.TryGetProperty("title", out var titleValue) ? titleValue.GetString() ?? "" : "";
            var circle = document.RootElement.TryGetProperty("circle", out var circleValue) ? circleValue.GetString() ?? "" : "";
            return (title.Trim(), circle.Trim());
        }
        catch
        {
            return ("", "");
        }
    }

    private static int Natural(string a, string b)
    {
        var i = 0;
        var j = 0;
        while (i < a.Length && j < b.Length)
        {
            if (char.IsDigit(a[i]) && char.IsDigit(b[j]))
            {
                var startA = i;
                var startB = j;
                while (i < a.Length && char.IsDigit(a[i]))
                    i++;
                while (j < b.Length && char.IsDigit(b[j]))
                    j++;
                var left = a.AsSpan(startA, i - startA).TrimStart('0');
                var right = b.AsSpan(startB, j - startB).TrimStart('0');
                if (left.Length == 0)
                    left = "0";
                if (right.Length == 0)
                    right = "0";
                var length = left.Length.CompareTo(right.Length);
                if (length != 0)
                    return length;
                var text = left.CompareTo(right, StringComparison.Ordinal);
                if (text != 0)
                    return text;
            }
            else
            {
                var letter = char.ToUpperInvariant(a[i]).CompareTo(char.ToUpperInvariant(b[j]));
                if (letter != 0)
                    return letter;
                i++;
                j++;
            }
        }

        return a.Length.CompareTo(b.Length);
    }
}
