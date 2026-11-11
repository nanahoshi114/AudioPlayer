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
    public IReadOnlyList<object> Entries { get; init; } = [];
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
            var folders = NestedFolders(group.Key, items);
            albums.Add(folders.Count == 0 ? Flat(group.Key, items) : Nest(group.Key, items, folders));
        }

        albums.Sort((a, b) => string.Compare(a.Title, b.Title, StringComparison.OrdinalIgnoreCase));
        return albums;
    }

    private static AlbumItem Flat(string key, List<TrackItem> items)
    {
        items.Sort(CompareTracks);
        return Make(key, items, items.Cast<object>().ToList(), items);
    }

    private static AlbumItem Nest(string key, List<TrackItem> items, Dictionary<string, List<TrackItem>> folders)
    {
        var sourceId = key["work:".Length..];
        var root = Path.Combine(TrackLoader.LibraryRoot(), sourceId);
        var nested = new HashSet<TrackItem>(folders.SelectMany(pair => pair.Value));
        var loose = items.Where(track => !nested.Contains(track)).ToList();
        var pieces = new List<(string Name, object Entry, List<TrackItem> Tracks)>();
        foreach (var track in loose)
            pieces.Add((Path.GetRelativePath(root, track.FilePath), track, [track]));
        foreach (var (folder, tracks) in folders)
        {
            tracks.Sort(CompareTracks);
            var directory = Path.Combine(root, folder);
            var childKey = "nest:" + sourceId + "/" + folder;
            var child = Make(childKey, tracks, tracks.Cast<object>().ToList(), tracks, directory);
            pieces.Add((folder, child, tracks));
        }

        pieces.Sort((a, b) => Natural(a.Name, b.Name));
        var entries = pieces.Select(piece => piece.Entry).ToList();
        var flat = pieces.SelectMany(piece => piece.Tracks).ToList();
        return Make(key, items, entries, flat, root);
    }

    private static Dictionary<string, List<TrackItem>> NestedFolders(string key, IReadOnlyList<TrackItem> tracks)
    {
        var found = new Dictionary<string, List<TrackItem>>(StringComparer.OrdinalIgnoreCase);
        if (!key.StartsWith("work:", StringComparison.Ordinal))
            return found;
        var root = Path.Combine(TrackLoader.LibraryRoot(), key["work:".Length..]);
        foreach (var track in tracks)
        {
            var relative = Path.GetRelativePath(root, track.FilePath);
            var split = relative.IndexOf(Path.DirectorySeparatorChar);
            if (split <= 0)
                continue;
            var folder = relative[..split];
            if (folder is "." or "..")
                continue;
            if (!File.Exists(Path.Combine(root, folder, "work.json")))
                continue;
            if (!found.TryGetValue(folder, out var list))
            {
                list = [];
                found[folder] = list;
            }

            list.Add(track);
        }

        return found;
    }

    private static AlbumItem Make(string key, IReadOnlyList<TrackItem> titled, IReadOnlyList<object> entries, IReadOnlyList<TrackItem> flat, string? coverDirectory = null)
    {
        var artist = titled.Select(track => track.Artist).FirstOrDefault(name => name.Length > 0) ?? "";
        var (title, circle) = TitleOf(key, titled);
        if (circle.Length > 0)
            artist = circle;
        var duration = TimeSpan.FromTicks(flat.Sum(track => track.Duration.Ticks));
        var detail = artist.Length == 0 ? $"{flat.Count} 首" : $"{artist} · {flat.Count} 首";
        return new AlbumItem
        {
            Key = key,
            Title = title,
            Detail = detail,
            DurationText = TrackItem.FormatTime(duration),
            Art = ArtOf(coverDirectory, flat),
            Tracks = flat,
            Entries = entries,
        };
    }

    private static System.Windows.Media.Imaging.BitmapSource? ArtOf(string? directory, IReadOnlyList<TrackItem> tracks)
    {
        if (!string.IsNullOrEmpty(directory))
        {
            var cover = TrackLoader.DirectoryCover(directory);
            if (cover != null)
            {
                var known = tracks.FirstOrDefault(track => track.CoverPath != null && track.CoverPath.Equals(cover, StringComparison.OrdinalIgnoreCase))?.Art;
                if (known != null)
                    return known;
                var decoded = TrackLoader.DecodeFile(cover);
                if (decoded != null)
                    return decoded;
            }
        }

        return tracks.Select(track => track.Art).FirstOrDefault(art => art != null);
    }

    public static string? SelfCheck()
    {
        var root = TrackLoader.LibraryRoot();
        var seasonDir = Path.Combine(root, "SS-nest-check");
        var plainDir = Path.Combine(root, "BV1nestcheck0");
        try
        {
            var partDir = Path.Combine(seasonDir, "002 multi");
            Directory.CreateDirectory(partDir);
            Directory.CreateDirectory(plainDir);
            File.WriteAllText(Path.Combine(seasonDir, "work.json"), "{\"title\":\"合集甲\",\"circle\":\"作者\"}");
            File.WriteAllText(Path.Combine(partDir, "work.json"), "{\"title\":\"分P视频\",\"circle\":\"作者\"}");
            File.WriteAllText(Path.Combine(plainDir, "work.json"), "{\"title\":\"普通视频\",\"circle\":\"作者\"}");
            TrackItem Track(string path, string title) => new()
            {
                FilePath = path,
                Title = title,
                Artist = "作者",
                Duration = TimeSpan.FromSeconds(10),
                SampleRate = 44100,
                BitsPerSample = 16,
                Channels = 2,
            };
            var albums = Group(
            [
                Track(Path.Combine(seasonDir, "001 single.m4a"), "单集"),
                Track(Path.Combine(partDir, "02 b.m4a"), "P2"),
                Track(Path.Combine(partDir, "01 a.m4a"), "P1"),
                Track(Path.Combine(plainDir, "song.m4a"), "普通"),
            ], true);
            var season = albums.FirstOrDefault(album => album.Key == "work:SS-nest-check");
            if (season == null)
                return "没有合集专辑";
            if (season.Title != "合集甲")
                return season.Title;
            if (season.Entries.Count != 2 || season.Entries[0] is not TrackItem || season.Entries[1] is not AlbumItem child)
                return "合集层级不对";
            if (child.Title != "分P视频" || child.Tracks.Count != 2 || child.Tracks[0].Title != "P1")
                return "分P专辑不对";
            if (season.Tracks.Count != 3 || season.Tracks[1].Title != "P1")
                return "播放顺序不对";
            var plain = albums.FirstOrDefault(album => album.Title == "普通视频");
            if (plain == null || plain.Entries.OfType<AlbumItem>().Any())
                return "普通专辑被嵌套了";
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
                if (Directory.Exists(seasonDir))
                    Directory.Delete(seasonDir, true);
                if (Directory.Exists(plainDir))
                    Directory.Delete(plainDir, true);
            }
            catch (IOException)
            {
            }
        }
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
        if (key.StartsWith("nest:", StringComparison.Ordinal))
        {
            var rest = key["nest:".Length..];
            var split = rest.LastIndexOf('/');
            var nestedName = split >= 0 ? rest[(split + 1)..] : rest;
            var note = ReadWorkNote(rest.Replace('/', Path.DirectorySeparatorChar));
            if (note.Title.Length > 0)
                return note;
            return (nestedName.Length == 0 ? "未归类" : nestedName, note.Circle);
        }

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
