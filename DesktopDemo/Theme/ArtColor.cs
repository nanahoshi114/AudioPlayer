using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace PlayerDemo.Theme;

public static class ArtColor
{
    public static Color FromArt(BitmapSource source)
    {
        const int size = 24;
        var scale = new ScaleTransform(
            size / (double)Math.Max(1, source.PixelWidth),
            size / (double)Math.Max(1, source.PixelHeight));
        var scaled = new TransformedBitmap(source, scale);
        var formatted = new FormatConvertedBitmap(scaled, PixelFormats.Bgra32, null, 0);
        var width = formatted.PixelWidth;
        var height = formatted.PixelHeight;
        var stride = width * 4;
        var pixels = new byte[stride * height];
        formatted.CopyPixels(pixels, stride, 0);

        double red = 0, green = 0, blue = 0;
        var count = 0;
        for (var i = 0; i < pixels.Length; i += 4)
        {
            var b = pixels[i] / 255.0;
            var g = pixels[i + 1] / 255.0;
            var r = pixels[i + 2] / 255.0;
            var max = Math.Max(r, Math.Max(g, b));
            var min = Math.Min(r, Math.Min(g, b));
            var saturation = max <= 0 ? 0 : (max - min) / max;
            if (saturation < 0.22 || max < 0.18)
                continue;
            red += r;
            green += g;
            blue += b;
            count++;
        }

        if (count < 8)
            return Color.FromRgb(0xC8, 0xC8, 0xC8);

        red /= count;
        green /= count;
        blue /= count;
        var peak = Math.Max(red, Math.Max(green, blue));
        if (peak < 0.62 && peak > 0)
        {
            var lift = 0.62 / peak;
            red = Math.Min(1, red * lift);
            green = Math.Min(1, green * lift);
            blue = Math.Min(1, blue * lift);
        }

        return Color.FromRgb(ToByte(red), ToByte(green), ToByte(blue));
    }

    public static Color Paper(Color accent) =>
        Mix(Color.FromRgb(0xF6, 0xF1, 0xEA), accent, 0.18);

    public static Color PlayFill(Color accent)
    {
        var luminance = (0.2126 * accent.R) + (0.7152 * accent.G) + (0.0722 * accent.B);
        if (luminance <= 120)
            return accent;

        var scale = 108 / luminance;
        return Color.FromRgb(Scale(accent.R, scale), Scale(accent.G, scale), Scale(accent.B, scale));
    }

    private static Color Mix(Color paper, Color accent, double amount)
    {
        byte Channel(byte baseChannel, byte tint) =>
            (byte)Math.Clamp((int)Math.Round((baseChannel * (1 - amount)) + (tint * amount)), 0, 255);
        return Color.FromRgb(Channel(paper.R, accent.R), Channel(paper.G, accent.G), Channel(paper.B, accent.B));
    }

    private static byte Scale(byte channel, double scale) =>
        (byte)Math.Clamp((int)Math.Round(channel * scale), 0, 255);

    private static byte ToByte(double channel) => (byte)Math.Clamp((int)Math.Round(channel * 255), 0, 255);
}
