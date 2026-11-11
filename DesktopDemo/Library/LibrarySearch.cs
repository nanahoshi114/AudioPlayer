using System.IO;
using System.Text;

namespace PlayerDemo.Library;

public static class LibrarySearch
{
    public static bool Active(string query) => Terms(query).Length > 0;

    public static bool Matches(string query, params string?[] fields)
    {
        var terms = Terms(query);
        if (terms.Length == 0)
            return true;
        var hay = Fold(string.Join('\n', fields.Where(field => !string.IsNullOrWhiteSpace(field))));
        foreach (var term in terms)
        {
            if (!Loose(hay, term))
                return false;
        }

        return true;
    }

    public static bool MatchesTrack(string query, TrackItem track) =>
        Matches(query, track.Title, track.Artist, track.Album, Path.GetFileNameWithoutExtension(track.FilePath));

    public static bool MatchesAlbum(string query, AlbumItem album)
    {
        if (Matches(query, album.Title, album.Detail))
            return true;
        foreach (var track in album.Tracks)
        {
            if (MatchesTrack(query, track))
                return true;
        }

        return false;
    }

    public static string? SelfCheck()
    {
        if (!Matches("熟肉", "「熟肉合集」ヰ世界情緒球"))
            return "substring";
        if (!Matches("世界球", "ヰ世界情緒球"))
            return "gap";
        if (!Matches("aranzo", "-ARANZO-"))
            return "case";
        if (!Matches("aranzo 四月", "「速食肉」四月廿五日", "-ARANZO-"))
            return "terms";
        if (Matches("zzzz", "熟肉合集"))
            return "miss";
        if (Matches("az", "a" + new string('x', 40) + "z"))
            return "sparse";
        if (!Matches("壁炉", "壁炉"))
            return "exact";
        return null;
    }

    private static string[] Terms(string query) =>
        query.Split([' ', '\t', '\u3000'], StringSplitOptions.RemoveEmptyEntries)
            .Select(Fold)
            .Where(term => term.Length > 0)
            .ToArray();

    private static string Fold(string text)
    {
        var form = text.Normalize(NormalizationForm.FormKC).ToLowerInvariant();
        var buffer = new char[form.Length];
        var count = 0;
        foreach (var c in form)
        {
            if (char.IsWhiteSpace(c) || char.IsPunctuation(c) || char.IsSymbol(c) || char.IsControl(c))
                continue;
            buffer[count++] = c;
        }

        return new string(buffer, 0, count);
    }

    private static bool Loose(string hay, string needle)
    {
        if (needle.Length == 0)
            return true;
        if (hay.Contains(needle, StringComparison.Ordinal))
            return true;
        var start = -1;
        var last = -1;
        var from = 0;
        foreach (var c in needle)
        {
            var found = hay.IndexOf(c, from);
            if (found < 0)
                return false;
            if (start < 0)
                start = found;
            last = found;
            from = found + 1;
        }

        return last - start + 1 <= needle.Length * 2 + 2;
    }
}
