using System.IO;
using NAudio.Wave;
using PlayerDemo.Library;

namespace PlayerDemo.Playback;

public static class SeekTest
{
    public static int Run()
    {
        try
        {
            var path = FindAudio();
            if (path == null)
            {
                Console.WriteLine("NOFILE");
                return 2;
            }

            Console.WriteLine("FILE " + path);
            Console.WriteLine("SIZE " + new FileInfo(path).Length);
            Console.WriteLine("BEFORE " + Header(path));
            var clipped = new List<string>();
            var stillLong = RepairLibrary(clipped);
            Console.WriteLine("AFTER " + Header(path));
            if (!TryMediaSeconds(path, out var real))
            {
                Console.WriteLine("RESULT bad duration");
                return 1;
            }

            Console.WriteLine(Describe(path));
            Console.WriteLine(Probe("before-read", path, seekBeforeRead: true, prime: false));
            Console.WriteLine(Probe("after-read", path, seekBeforeRead: false, prime: false));
            Console.WriteLine(Probe("prime-then-seek", path, seekBeforeRead: false, prime: true));
            Console.WriteLine(ProbeWorker(path));
            Console.WriteLine(Drain(path, 10));
            Console.WriteLine(Drain(path, Math.Max(1, real * 0.35)));
            var checks = new List<string>
            {
                ProbePlayer(path, TimeSpan.FromSeconds(Math.Min(10, real / 2)), real),
                ProbePlayer(path, TimeSpan.FromSeconds(real * 0.35), real),
                ProbePlayer(path, TimeSpan.FromSeconds(Math.Max(1, real - 20)), real),
            };
            foreach (var file in clipped)
                checks.AddRange(ProbeCopy(file));
            var ok = true;
            foreach (var line in checks)
            {
                Console.WriteLine(line);
                ok &= line.StartsWith("PLAYER ok", StringComparison.Ordinal);
            }

            ok &= stillLong == 0 && !MovieTooLong(path);
            Console.WriteLine(ok ? "RESULT ok" : "RESULT bad");
            return ok ? 0 : 1;
        }
        catch (Exception ex)
        {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
    }

    private static string? FindAudio()
    {
        var root = TrackLoader.LibraryRoot();
        if (!Directory.Exists(root))
            return null;
        string? best = null;
        var bestLength = 0L;
        foreach (var file in Directory.EnumerateFiles(root, "*.m4a", SearchOption.AllDirectories))
        {
            long length;
            try
            {
                length = new FileInfo(file).Length;
            }
            catch (IOException)
            {
                continue;
            }

            if (length > bestLength)
            {
                best = file;
                bestLength = length;
            }
        }

        return best;
    }

    private static string Probe(string name, string path, bool seekBeforeRead, bool prime)
    {
        using var stream = TrackLoader.OpenPcm(path);
        var buffer = new byte[Math.Max(stream.WaveFormat.AverageBytesPerSecond / 8, 4096)];
        var primed = 0;
        if (!seekBeforeRead)
        {
            primed = stream.Read(buffer, 0, buffer.Length);
            if (prime)
                stream.Position = 0;
        }

        var target = TimeSpan.FromSeconds(stream.TotalTime.TotalSeconds * 0.4);
        Exception? error = null;
        var read = 0;
        try
        {
            stream.CurrentTime = target;
            read = stream.Read(buffer, 0, buffer.Length);
        }
        catch (Exception ex)
        {
            error = ex;
        }

        var at = stream.CurrentTime.TotalSeconds;
        return name
            + " primed=" + primed
            + " read=" + read
            + " at=" + at.ToString("0.00")
            + " target=" + target.TotalSeconds.ToString("0.00")
            + " dur=" + stream.TotalTime.TotalSeconds.ToString("0.00")
            + (error == null ? "" : " ERR " + error.GetType().Name + " " + error.Message);
    }

    private static string ProbeWorker(string path)
    {
        using var stream = TrackLoader.OpenPcm(path);
        var buffer = new byte[Math.Max(stream.WaveFormat.AverageBytesPerSecond / 8, 4096)];
        var first = 0;
        var second = 0.0;
        var read = 0;
        Exception? error = null;
        var thread = new Thread(() =>
        {
            try
            {
                first = stream.Read(buffer, 0, buffer.Length);
                var target = TimeSpan.FromSeconds(stream.TotalTime.TotalSeconds * 0.4);
                stream.CurrentTime = target;
                second = stream.CurrentTime.TotalSeconds;
                read = stream.Read(buffer, 0, buffer.Length);
            }
            catch (Exception ex)
            {
                error = ex;
            }
        });
        thread.IsBackground = true;
        thread.Start();
        if (!thread.Join(TimeSpan.FromSeconds(8)))
            return "worker timeout";
        return "worker first=" + first
            + " read=" + read
            + " at=" + second.ToString("0.00")
            + (error == null ? "" : " ERR " + error.GetType().Name + " " + error.Message);
    }

    private static string Describe(string path)
    {
        using var stream = TrackLoader.OpenPcm(path);
        var format = stream.WaveFormat;
        var header = Header(path);
        return "fmt " + format.SampleRate + "Hz " + format.BitsPerSample + "bit x" + format.Channels
            + " avg=" + format.AverageBytesPerSecond
            + " bytes=" + stream.Length
            + " " + header;
    }

    private static string Header(string path)
    {
        using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        foreach (var moov in Boxes(stream, 0, stream.Length, "moov"))
        {
            var mvhd = Boxes(stream, moov.Payload, moov.End, "mvhd").FirstOrDefault();
            if (mvhd.End == 0)
                continue;
            stream.Position = mvhd.Payload;
            var version = stream.ReadByte();
            stream.Position = mvhd.Payload + (version == 0 ? 12 : 20);
            var scale = ReadU32(stream);
            var duration = version == 0 ? ReadU32(stream) : ReadU64(stream);
            var seconds = scale == 0 ? 0 : duration / (double)scale;
            var detail = "mvhd scale=" + scale + " duration=" + duration + " seconds=" + seconds.ToString("0.00");
            foreach (var trak in Boxes(stream, moov.Payload, moov.End, "trak"))
            foreach (var mdia in Boxes(stream, trak.Payload, trak.End, "mdia"))
            {
                var mdhd = Boxes(stream, mdia.Payload, mdia.End, "mdhd").FirstOrDefault();
                if (mdhd.End != 0)
                {
                    stream.Position = mdhd.Payload;
                    var mdVersion = stream.ReadByte();
                    stream.Position = mdhd.Payload + (mdVersion == 0 ? 12 : 20);
                    var mdScale = ReadU32(stream);
                    var mdDuration = mdVersion == 0 ? ReadU32(stream) : ReadU64(stream);
                    detail += " mdhd scale=" + mdScale + " duration=" + mdDuration;
                }

                foreach (var minf in Boxes(stream, mdia.Payload, mdia.End, "minf"))
                foreach (var stbl in Boxes(stream, minf.Payload, minf.End, "stbl"))
                {
                    var stsz = Boxes(stream, stbl.Payload, stbl.End, "stsz").FirstOrDefault();
                    if (stsz.End != 0)
                    {
                        stream.Position = stsz.Payload + 4;
                        var sampleSize = ReadU32(stream);
                        var sampleCount = ReadU32(stream);
                        detail += " stsz size=" + sampleSize + " count=" + sampleCount;
                    }

                    var stts = Boxes(stream, stbl.Payload, stbl.End, "stts").FirstOrDefault();
                    if (stts.End != 0)
                    {
                        stream.Position = stts.Payload + 4;
                        var entries = ReadU32(stream);
                        detail += " stts entries=" + entries;
                        for (var i = 0; i < Math.Min(entries, 3); i++)
                            detail += " [" + ReadU32(stream) + " x " + ReadU32(stream) + "]";
                    }
                }
            }

            return detail;
        }

        return "mvhd missing";
    }

    private readonly record struct Box(long Start, long End, int Header)
    {
        public long Payload => Start + Header;
    }

    private static IEnumerable<Box> Boxes(Stream stream, long start, long end, string type)
    {
        var header = new byte[8];
        var cursor = start;
        while (cursor + 8 <= end)
        {
            stream.Position = cursor;
            if (stream.Read(header, 0, 8) < 8)
                yield break;
            var size = ((long)header[0] << 24) | ((long)header[1] << 16) | ((long)header[2] << 8) | header[3];
            var name = System.Text.Encoding.ASCII.GetString(header, 4, 4);
            var headerSize = 8;
            if (size == 1)
            {
                if (stream.Read(header, 0, 8) < 8)
                    yield break;
                size = 0;
                foreach (var part in header)
                    size = (size << 8) | part;
                headerSize = 16;
            }
            else if (size == 0)
            {
                size = end - cursor;
            }

            if (size < headerSize || cursor + size > end)
                yield break;
            if (name == type)
                yield return new Box(cursor, cursor + size, headerSize);
            cursor += size;
        }
    }

    private static uint ReadU32(Stream stream)
    {
        var buffer = new byte[4];
        stream.ReadExactly(buffer);
        return ((uint)buffer[0] << 24) | ((uint)buffer[1] << 16) | ((uint)buffer[2] << 8) | buffer[3];
    }

    private static ulong ReadU64(Stream stream)
    {
        var buffer = new byte[8];
        stream.ReadExactly(buffer);
        ulong value = 0;
        foreach (var part in buffer)
            value = (value << 8) | part;
        return value;
    }

    private static string Drain(string path, double seconds)
    {
        using var stream = TrackLoader.OpenPcm(path);
        stream.CurrentTime = TimeSpan.FromSeconds(seconds);
        var buffer = new byte[64 * 1024];
        var total = 0;
        var reads = 0;
        while (reads < 8)
        {
            var n = stream.Read(buffer, 0, buffer.Length);
            reads++;
            if (n <= 0)
                break;
            total += n;
        }

        return "drain " + seconds.ToString("0") + "s bytes=" + total + " reads=" + reads + " pos=" + stream.CurrentTime.TotalSeconds.ToString("0.00");
    }

    private static List<string> ProbeCopy(string file)
    {
        var lines = new List<string>();
        var copy = Path.Combine(Path.GetTempPath(), "player-seek-clip.m4a");
        File.Copy(file, copy, true);
        var info = new FileInfo(copy);
        info.IsReadOnly = true;
        try
        {
            if (!TryMediaSeconds(copy, out var media))
            {
                lines.Add("PLAYER bad copy-duration");
                return lines;
            }

            lines.Add(ProbePlayer(copy, TimeSpan.FromSeconds(Math.Min(10, media / 2)), media));
            lines.Add(ProbePlayer(copy, TimeSpan.FromSeconds(media * 0.35), media));
            lines.Add(ProbePlayer(copy, TimeSpan.FromSeconds(Math.Max(1, media - 20)), media));
            return lines;
        }
        finally
        {
            info.IsReadOnly = false;
            try
            {
                File.Delete(copy);
            }
            catch (IOException)
            {
                // 副本还被播放器占着时，下一次测试会覆盖。
            }
        }
    }

    private static int RepairLibrary(List<string> clipped)
    {
        var root = TrackLoader.LibraryRoot();
        var files = 0;
        var still = 0;
        if (Directory.Exists(root))
        {
            foreach (var file in Directory.EnumerateFiles(root, "*.m4a", SearchOption.AllDirectories))
            {
                files++;
                FragmentedMp4.EnsureProgressive(file);
                if (!MovieTooLong(file))
                    continue;
                if (TryMediaSeconds(file, out var media))
                {
                    using var stream = TrackLoader.OpenPcm(file);
                    var reported = stream.TotalTime.TotalSeconds;
                    if (Math.Abs(reported - media) < 2.5)
                    {
                        clipped.Add(file);
                        Console.WriteLine("CLIP ok " + reported.ToString("0.00") + " media=" + media.ToString("0.00"));
                        continue;
                    }
                }

                still++;
                if (still <= 8)
                {
                    Console.WriteLine("STILL " + file);
                    Console.WriteLine(Header(file));
                    if (FragmentedMp4.LastError != null)
                        Console.WriteLine("ERR " + FragmentedMp4.LastError.GetType().Name + " " + FragmentedMp4.LastError.Message);
                }
            }
        }

        Console.WriteLine("LIBRARY files=" + files + " stillLong=" + still);
        return still;
    }

    private static bool MovieTooLong(string path)
    {
        if (!TryMediaSeconds(path, out var media) || !TryMovieSeconds(path, out var movie))
            return false;
        return movie > media * 1.5 + 1;
    }

    private static bool TryMediaSeconds(string path, out double seconds) =>
        TryClock(path, "mdhd", out seconds);

    private static bool TryMovieSeconds(string path, out double seconds) =>
        TryClock(path, "mvhd", out seconds);

    private static bool TryClock(string path, string type, out double seconds)
    {
        seconds = 0;
        using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        foreach (var moov in Boxes(stream, 0, stream.Length, "moov"))
        {
            if (type == "mvhd")
            {
                var mvhd = Boxes(stream, moov.Payload, moov.End, "mvhd").FirstOrDefault();
                if (mvhd.End == 0 || !ReadClock(stream, mvhd, out seconds))
                    continue;
                return true;
            }

            foreach (var trak in Boxes(stream, moov.Payload, moov.End, "trak"))
            foreach (var mdia in Boxes(stream, trak.Payload, trak.End, "mdia"))
            {
                var mdhd = Boxes(stream, mdia.Payload, mdia.End, "mdhd").FirstOrDefault();
                if (mdhd.End != 0 && ReadClock(stream, mdhd, out seconds))
                    return true;
            }
        }

        return false;
    }

    private static bool ReadClock(Stream stream, Box box, out double seconds)
    {
        seconds = 0;
        stream.Position = box.Payload;
        var version = stream.ReadByte();
        if (version < 0)
            return false;
        stream.Position = box.Payload + (version == 0 ? 12 : 20);
        var scale = ReadU32(stream);
        var duration = version == 0 ? ReadU32(stream) : ReadU64(stream);
        if (scale == 0 || duration == 0)
            return false;
        seconds = duration / (double)scale;
        return true;
    }

    private static string ProbePlayer(string path, TimeSpan target, double realSeconds)
    {
        using var player = new ExclusivePlayer();
        var ended = 0;
        string? failed = null;
        player.Ended += (_, _) => ended++;
        player.Failed += (_, message) => failed = message;
        var result = player.Play(path);
        if (!result.Ok)
            return "PLAYER open " + result.Message;
        Thread.Sleep(600);
        if (!player.IsPlaying)
            return "PLAYER not-playing ended=" + ended + " fail=" + failed;
        var duration = player.Duration;
        var seek = player.Seek(target);
        var until = DateTime.UtcNow.AddSeconds(2);
        while (DateTime.UtcNow < until)
        {
            System.Windows.Threading.Dispatcher.CurrentDispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.Background);
            Thread.Sleep(50);
        }
        var position = player.Position;
        var playing = player.IsPlaying;
        player.Stop();
        var ahead = position.TotalSeconds - target.TotalSeconds;
        var near = ahead >= -1.5 && ahead < 5;
        var durationOk = Math.Abs(duration.TotalSeconds - realSeconds) < 2.5;
        var ok = seek.Ok && ended == 0 && playing && near && durationOk && failed == null;
        return (ok ? "PLAYER ok" : "PLAYER bad")
            + " seek=" + (seek.Ok ? "ok" : seek.Message)
            + " ended=" + ended
            + " playing=" + playing
            + " pos=" + position.TotalSeconds.ToString("0.00")
            + " target=" + target.TotalSeconds.ToString("0.00")
            + " dur=" + duration.TotalSeconds.ToString("0.00")
            + (failed == null ? "" : " fail=" + failed);
    }
}
