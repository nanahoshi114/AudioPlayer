using System.IO;
using NAudio.Wave;

namespace PlayerDemo.Playback;

public static class PlayTest
{
    public static int Run()
    {
        var path = Path.Combine(Path.GetTempPath(), "player-demo-silence.wav");
        try
        {
            WriteSilence(path);
            using var player = new ExclusivePlayer();
            var result = player.Play(path);
            Console.WriteLine(result.Ok ? result.Message : "FAIL " + result.Message);
            if (!result.Ok)
                return 2;

            Thread.Sleep(400);
            var playing = player.IsPlaying;
            var position = player.Position;
            Console.WriteLine(playing ? "STATE playing" : "STATE " + player.IsPaused);
            Console.WriteLine("POS " + position.TotalMilliseconds.ToString("0"));
            player.Pause();
            var held = player.IsPaused;
            Console.WriteLine(held ? "PAUSE held" : "PAUSE lost");
            player.Stop();
            return playing && held ? 0 : 3;
        }
        catch (Exception ex)
        {
            Console.WriteLine("FAIL " + ex);
            return 1;
        }
        finally
        {
            try
            {
                File.Delete(path);
            }
            catch
            {
                // 临时文件留着也没关系。
            }
        }
    }

    private static void WriteSilence(string path)
    {
        var format = new WaveFormat(44100, 16, 2);
        using var writer = new WaveFileWriter(path, format);
        var bytes = new byte[format.AverageBytesPerSecond];
        writer.Write(bytes, 0, bytes.Length);
    }
}
