using System.IO;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Media.Imaging;
using NAudio.Wave;
using PlayerDemo.Subtitles;

namespace PlayerDemo.Library;

public static class TrackLoader
{
    public static string LibraryRoot() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyMusic), "PlayerDemo");

    private static readonly string[] AudioExtensions = [".wav", ".flac", ".aiff", ".aif", ".mp3", ".m4a", ".aac", ".ogg", ".webm"];
    private static readonly string[] CoverNames = ["cover.png", "cover.webp", "cover.jpg", "cover.jpeg", "folder.png", "folder.jpg", "folder.jpeg", "front.png", "front.jpg"];

    public static bool IsAudioFile(string path)
    {
        var ext = Path.GetExtension(path);
        return AudioExtensions.Any(item => item.Equals(ext, StringComparison.OrdinalIgnoreCase));
    }

    public static List<string> Expand(IEnumerable<string> paths, int limit = 2000)
    {
        var files = new List<string>();
        foreach (var path in paths)
        {
            if (Directory.Exists(path))
            {
                foreach (var file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
                {
                    if (IsAudioFile(file))
                        files.Add(file);
                    if (files.Count >= limit)
                        return files.OrderBy(item => item, StringComparer.OrdinalIgnoreCase).ToList();
                }
            }
            else if (File.Exists(path) && IsAudioFile(path))
            {
                files.Add(path);
            }
        }

        return files
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(item => item, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public static TrackItem? TryRead(string path)
    {
        try
        {
            using var stream = OpenPcm(path);
            var format = stream.WaveFormat;
            if (!IsPlayable(format))
                return null;

            string title = Path.GetFileNameWithoutExtension(path);
            string artist = "";
            string album = "";
            var trackNumber = 0;
            byte[]? art = null;
            try
            {
                using var tags = TagLib.File.Create(path);
                if (!string.IsNullOrWhiteSpace(tags.Tag.Title))
                    title = tags.Tag.Title;
                artist = tags.Tag.FirstPerformer ?? "";
                album = tags.Tag.Album ?? "";
                trackNumber = tags.Tag.Track > int.MaxValue ? 0 : (int)tags.Tag.Track;
                var picture = tags.Tag.Pictures.FirstOrDefault(item => item.Type == TagLib.PictureType.FrontCover)
                    ?? tags.Tag.Pictures.FirstOrDefault();
                if (picture?.Data.Data is { Length: > 0 } data)
                    art = data;
            }
            catch
            {
                // 没有标签时用文件名。
            }

            var coverPath = CoverFile(path);
            if (coverPath != null)
                art = null;
            var subtitles = SubtitleFinder.ForAudio(path);
            var item = new TrackItem
            {
                FilePath = path,
                Title = title,
                Artist = artist,
                Album = album,
                TrackNumber = trackNumber,
                Duration = stream.TotalTime,
                SampleRate = format.SampleRate,
                BitsPerSample = format.BitsPerSample,
                IsFloat = IsFloat(format),
                Channels = format.Channels,
                Codec = stream is OpusWaveStream ? "Opus" : CodecName(path),
                ArtBytes = art,
                CoverPath = coverPath,
                Subtitles = subtitles,
                Fingerprint = FingerprintOf(path),
            };
            item.SelectDefault();
            return item;
        }
        catch
        {
            return null;
        }
    }

    public static WaveStream OpenPcm(string path)
    {
        var ext = Path.GetExtension(path);
        if (ext.Equals(".mp3", StringComparison.OrdinalIgnoreCase))
            return new Mp3FileReader(path);
        if (ext.Equals(".aiff", StringComparison.OrdinalIgnoreCase) || ext.Equals(".aif", StringComparison.OrdinalIgnoreCase))
            return new AiffFileReader(path);
        if (ext.Equals(".wav", StringComparison.OrdinalIgnoreCase))
            return new WaveFileReader(path);
        if (ext.Equals(".webm", StringComparison.OrdinalIgnoreCase) || ext.Equals(".ogg", StringComparison.OrdinalIgnoreCase) || ext.Equals(".opus", StringComparison.OrdinalIgnoreCase))
        {
            if (OpusWaveStream.TryOpen(path, out var opus) && opus != null)
                return opus;
        }

        if (ext.Equals(".m4a", StringComparison.OrdinalIgnoreCase) || ext.Equals(".mp4", StringComparison.OrdinalIgnoreCase) || ext.Equals(".m4s", StringComparison.OrdinalIgnoreCase))
            FragmentedMp4.EnsureProgressive(path);
        return new MediaFoundationReader(path);
    }

    private static readonly Guid IeeeFloatSubType = new("00000003-0000-0010-8000-00aa00389b71");

    public static bool IsFloat(WaveFormat format)
    {
        if (format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32)
            return true;
        return format is WaveFormatExtensible extensible
            && extensible.SubFormat == IeeeFloatSubType
            && extensible.BitsPerSample == 32;
    }

    public static bool IsIntegerPcm(WaveFormat format) =>
        !IsFloat(format)
        && format.Encoding is WaveFormatEncoding.Pcm or WaveFormatEncoding.Extensible
        && format.BitsPerSample is 16 or 24 or 32;

    public static bool IsPlayable(WaveFormat format) => IsFloat(format) || IsIntegerPcm(format);

    public static BitmapSource? DecodeArt(byte[]? data)
    {
        if (data == null || data.Length == 0)
            return null;
        try
        {
            using var stream = new MemoryStream(data);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            image.DecodePixelWidth = 640;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            return CenterSquare(image);
        }
        catch
        {
            return null;
        }
    }

    public static BitmapSource CenterSquare(BitmapSource source)
    {
        var width = source.PixelWidth;
        var height = source.PixelHeight;
        if (width < 2 || height < 2 || Math.Abs(width - height) <= 1)
            return source;

        var side = Math.Min(width, height);
        var cropped = new CroppedBitmap(source, new Int32Rect((width - side) / 2, (height - side) / 2, side, side));
        if (cropped.CanFreeze)
            cropped.Freeze();
        return cropped;
    }

    public static string? CoverFile(string audioPath)
    {
        string? best = null;
        var bestSize = -1L;
        var directory = Path.GetDirectoryName(audioPath);
        for (var depth = 0; depth < 4 && !string.IsNullOrEmpty(directory); depth++)
        {
            foreach (var name in CoverNames)
            {
                var candidate = Path.Combine(directory, name);
                if (!File.Exists(candidate))
                    continue;
                var length = new FileInfo(candidate).Length;
                if (length > bestSize)
                {
                    best = candidate;
                    bestSize = length;
                }
            }

            var parent = Path.GetDirectoryName(directory);
            if (string.IsNullOrEmpty(parent) || parent.Equals(directory, StringComparison.OrdinalIgnoreCase))
                break;
            directory = parent;
        }

        return best;
    }

    public static BitmapSource? DecodeFile(string? path)
    {
        if (string.IsNullOrEmpty(path) || !File.Exists(path))
            return null;
        try
        {
            using var stream = File.OpenRead(path);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            image.DecodePixelWidth = 640;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            return CenterSquare(image);
        }
        catch
        {
            return null;
        }
    }

    public static string FingerprintOf(string path)
    {
        const int sample = 256 * 1024;
        using var stream = File.OpenRead(path);
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var length = BitConverter.GetBytes(stream.Length);
        hash.AppendData(length);
        var buffer = new byte[sample];
        var head = stream.Read(buffer, 0, buffer.Length);
        if (head > 0)
            hash.AppendData(buffer, 0, head);
        if (stream.Length > head)
        {
            var tail = (int)Math.Min(sample, stream.Length - head);
            stream.Seek(-tail, SeekOrigin.End);
            var read = stream.Read(buffer, 0, tail);
            if (read > 0)
                hash.AppendData(buffer, 0, read);
        }

        return Convert.ToHexString(hash.GetHashAndReset());
    }

    private static string CodecName(string path)
    {
        var ext = Path.GetExtension(path).ToLowerInvariant();
        return ext switch
        {
            ".mp3" => "MP3",
            ".wav" => "WAV",
            ".aiff" or ".aif" => "AIFF",
            ".flac" => "FLAC",
            ".m4a" or ".aac" => "AAC",
            ".ogg" => "OGG",
            _ => ext.TrimStart('.').ToUpperInvariant(),
        };
    }
}
