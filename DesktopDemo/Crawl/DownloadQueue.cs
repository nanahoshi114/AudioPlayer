using System.IO;
using System.Text.Json;

namespace PlayerDemo.Crawl;

public static class DownloadQueue
{
    public static void Save(IReadOnlyList<CrawlHit> hits, IReadOnlyList<string> extensions)
    {
        try
        {
            if (hits.Count == 0)
            {
                Clear();
                return;
            }

            var file = new QueueFile
            {
                Extensions = extensions.ToList(),
                Items = hits.Select(FromHit).ToList(),
            };
            Write(file);
        }
        catch (IOException)
        {
        }
    }

    public static (List<CrawlHit> Hits, List<string> Extensions)? Load()
    {
        try
        {
            var path = QueuePath();
            if (!File.Exists(path))
                return null;
            var file = JsonSerializer.Deserialize<QueueFile>(File.ReadAllText(path));
            if (file?.Items == null || file.Items.Count == 0)
                return null;
            var hits = file.Items.Where(item => (item.SourceId ?? "").Length > 0 || item.Id > 0).Select(ToHit).ToList();
            return hits.Count == 0 ? null : (hits, file.Extensions ?? []);
        }
        catch (IOException)
        {
            return null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static void Clear()
    {
        try
        {
            var path = QueuePath();
            if (File.Exists(path))
                File.Delete(path);
        }
        catch (IOException)
        {
        }
    }

    private static void Write(QueueFile file)
    {
        var path = QueuePath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(file));
        File.Move(temp, path, true);
    }

    private static string QueuePath() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PlayerDemo", "download.json");

    private static QueueItem FromHit(CrawlHit hit) => new()
    {
        Id = hit.Id,
        SourceId = hit.SourceId,
        Title = hit.Title,
        Circle = hit.Circle,
        CoverUrl = hit.CoverUrl,
        RemoteCid = hit.RemoteCid,
        Page = hit.Page,
        Origin = hit.Origin,
        AlbumTitle = hit.AlbumTitle,
        MediaExtension = hit.MediaExtension,
        CoverReferer = hit.CoverReferer,
        SeasonId = hit.SeasonId,
        OwnerMid = hit.OwnerMid,
        WorkCircle = hit.WorkCircle,
        SeedBvid = hit.SeedBvid,
        PlaylistId = hit.PlaylistId,
        ShelfId = hit.ShelfId,
        ShelfTitle = hit.ShelfTitle,
        ShelfCircle = hit.ShelfCircle,
        ShelfOrder = hit.ShelfOrder,
    };

    private static CrawlHit ToHit(QueueItem item)
    {
        var hit = new CrawlHit(item.Id, item.SourceId ?? "", item.Title ?? "", item.Circle ?? "", item.CoverUrl)
        {
            RemoteCid = item.RemoteCid,
            Page = item.Page <= 0 ? 1 : item.Page,
            Origin = string.IsNullOrEmpty(item.Origin) ? "asmr" : item.Origin,
            AlbumTitle = item.AlbumTitle ?? "",
            MediaExtension = string.IsNullOrEmpty(item.MediaExtension) ? ".m4a" : item.MediaExtension,
            CoverReferer = string.IsNullOrEmpty(item.CoverReferer) ? "https://asmr.one/" : item.CoverReferer,
            SeasonId = item.SeasonId,
            OwnerMid = item.OwnerMid,
            WorkCircle = item.WorkCircle ?? "",
            SeedBvid = item.SeedBvid ?? "",
            PlaylistId = item.PlaylistId ?? "",
        };
        hit.ShelfId = item.ShelfId ?? "";
        hit.ShelfTitle = item.ShelfTitle ?? "";
        hit.ShelfCircle = item.ShelfCircle ?? "";
        hit.ShelfOrder = item.ShelfOrder;
        return hit;
    }

    private sealed class QueueFile
    {
        public List<string> Extensions { get; set; } = [];
        public List<QueueItem> Items { get; set; } = [];
    }

    private sealed class QueueItem
    {
        public long Id { get; set; }
        public string SourceId { get; set; } = "";
        public string Title { get; set; } = "";
        public string Circle { get; set; } = "";
        public string? CoverUrl { get; set; }
        public long RemoteCid { get; set; }
        public int Page { get; set; } = 1;
        public string Origin { get; set; } = "asmr";
        public string AlbumTitle { get; set; } = "";
        public string MediaExtension { get; set; } = ".m4a";
        public string CoverReferer { get; set; } = "";
        public long SeasonId { get; set; }
        public long OwnerMid { get; set; }
        public string WorkCircle { get; set; } = "";
        public string SeedBvid { get; set; } = "";
        public string PlaylistId { get; set; } = "";
        public string ShelfId { get; set; } = "";
        public string ShelfTitle { get; set; } = "";
        public string ShelfCircle { get; set; } = "";
        public int ShelfOrder { get; set; }
    }
}
