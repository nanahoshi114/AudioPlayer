using System.Windows;

namespace PlayerDemo;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        if (Has(e.Args, "--playtest"))
        {
            var code = Playback.PlayTest.Run();
            Shutdown(code);
            return;
        }

        if (Has(e.Args, "--seektest"))
        {
            var code = Playback.SeekTest.Run();
            Shutdown(code);
            return;
        }

        if (Has(e.Args, "--nesttest"))
        {
            var error = Library.AlbumShelf.SelfCheck();
            Console.WriteLine(error == null ? "NEST ok" : "NEST " + error);
            Shutdown(error == null ? 0 : 1);
            return;
        }

        if (Has(e.Args, "--searchtest"))
        {
            var error = Library.LibrarySearch.SelfCheck();
            Console.WriteLine(error == null ? "SEARCH ok" : "SEARCH " + error);
            Shutdown(error == null ? 0 : 1);
            return;
        }

        if (Has(e.Args, "--dlnatest"))
        {
            var error = Playback.DlnaTest.Run();
            Console.WriteLine(error == null ? "DLNA ok" : "DLNA " + error);
            Shutdown(error == null ? 0 : 1);
            return;
        }

        if (Has(e.Args, "--subtest"))
        {
            var error = Subtitles.SubtitleFinder.SelfCheck() ?? Services.TranslateService.SelfCheck();
            Console.WriteLine(error == null ? "SUBS ok" : "SUBS " + error);
            Shutdown(error == null ? 0 : 1);
            return;
        }

        var window = new MainWindow();
        var shot = ArgValue(e.Args, "--shot");
        if (shot != null && Has(e.Args, "--filled"))
            window.LoadFilledSample();

        window.Show();

        if (shot != null)
            window.CaptureAndExit(shot);
    }

    private static bool Has(string[] args, string flag) =>
        args.Any(arg => string.Equals(arg, flag, StringComparison.OrdinalIgnoreCase));

    private static string? ArgValue(string[] args, string flag)
    {
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (string.Equals(args[i], flag, StringComparison.OrdinalIgnoreCase))
                return args[i + 1];
        }

        return null;
    }
}
