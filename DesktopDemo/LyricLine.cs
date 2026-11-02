using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace PlayerDemo;

/// <summary>
/// 白字黑边。网易云桌面歌词靠描边挂在任意背景上。
/// </summary>
public sealed class LyricLine : FrameworkElement
{
    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(
        nameof(Text),
        typeof(string),
        typeof(LyricLine),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty FillProperty = DependencyProperty.Register(
        nameof(Fill),
        typeof(Brush),
        typeof(LyricLine),
        new FrameworkPropertyMetadata(Brushes.White, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty StrokeProperty = DependencyProperty.Register(
        nameof(Stroke),
        typeof(Brush),
        typeof(LyricLine),
        new FrameworkPropertyMetadata(Brushes.Black, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty StrokeThicknessProperty = DependencyProperty.Register(
        nameof(StrokeThickness),
        typeof(double),
        typeof(LyricLine),
        new FrameworkPropertyMetadata(2.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty FontSizeProperty = TextElementFontSize();

    public static readonly DependencyProperty FontWeightProperty = DependencyProperty.Register(
        nameof(FontWeight),
        typeof(FontWeight),
        typeof(LyricLine),
        new FrameworkPropertyMetadata(FontWeights.Bold, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty MaxLinesProperty = DependencyProperty.Register(
        nameof(MaxLines),
        typeof(int),
        typeof(LyricLine),
        new FrameworkPropertyMetadata(2, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    public LyricLine()
    {
        IsHitTestVisible = false;
        SnapsToDevicePixels = true;
    }

    public string Text
    {
        get => (string)GetValue(TextProperty);
        set => SetValue(TextProperty, value);
    }

    public Brush Fill
    {
        get => (Brush)GetValue(FillProperty);
        set => SetValue(FillProperty, value);
    }

    public Brush Stroke
    {
        get => (Brush)GetValue(StrokeProperty);
        set => SetValue(StrokeProperty, value);
    }

    public double StrokeThickness
    {
        get => (double)GetValue(StrokeThicknessProperty);
        set => SetValue(StrokeThicknessProperty, value);
    }

    public double FontSize
    {
        get => (double)GetValue(FontSizeProperty);
        set => SetValue(FontSizeProperty, value);
    }

    public FontWeight FontWeight
    {
        get => (FontWeight)GetValue(FontWeightProperty);
        set => SetValue(FontWeightProperty, value);
    }

    public int MaxLines
    {
        get => (int)GetValue(MaxLinesProperty);
        set => SetValue(MaxLinesProperty, value);
    }

    protected override Size MeasureOverride(Size available)
    {
        var formatted = Format(WidthOf(available.Width));
        if (formatted == null)
            return new Size(0, 0);
        var pad = Pad * 2;
        return new Size(formatted.Width + pad, formatted.Height + pad + Overhang(formatted));
    }

    protected override void OnRender(DrawingContext drawingContext)
    {
        var formatted = Format(WidthOf(ActualWidth));
        if (formatted == null)
            return;

        var y = Pad + Math.Max(0, (ActualHeight - Pad * 2 - formatted.Height - Overhang(formatted)) / 2);
        var geometry = formatted.BuildGeometry(new Point(Pad, y));
        var pen = new Pen(Stroke, StrokeThickness)
        {
            LineJoin = PenLineJoin.Round,
            StartLineCap = PenLineCap.Round,
            EndLineCap = PenLineCap.Round,
        };
        drawingContext.DrawGeometry(null, pen, geometry);
        drawingContext.DrawGeometry(Fill, null, geometry);
    }

    private double Pad => Math.Max(3, StrokeThickness + 2);

    private static double Overhang(FormattedText formatted) =>
        Math.Max(0, formatted.OverhangAfter) + Math.Max(0, formatted.OverhangLeading);

    private double Inset => Pad;

    private FormattedText? Format(double width)
    {
        var text = Text?.Replace("\r", "") ?? "";
        if (text.Length == 0 || width <= 1 || FontSize <= 0)
            return null;

        var pixels = 1.0;
        if (PresentationSource.FromVisual(this) != null)
            pixels = VisualTreeHelper.GetDpi(this).PixelsPerDip;

        var limit = Math.Max(1, MaxLines);
        var size = FontSize;
        var formatted = Make(text, width, size, pixels);
        while (size > 12 && Lines(formatted, size) > limit)
        {
            size = Math.Max(12, size * 0.92);
            formatted = Make(text, width, size, pixels);
        }

        return formatted;
    }

    private static int Lines(FormattedText formatted, double size)
    {
        var line = Math.Max(1, size * 1.28);
        return Math.Max(1, (int)Math.Ceiling((formatted.Height - 0.5) / line));
    }

    private FormattedText Make(string text, double width, double size, double pixels)
    {
        return new FormattedText(
            text,
            CultureInfo.CurrentUICulture,
            FlowDirection.LeftToRight,
            new Typeface(new FontFamily("Microsoft YaHei UI"), FontStyles.Normal, FontWeight, FontStretches.Normal),
            size,
            Brushes.White,
            pixels)
        {
            MaxTextWidth = width,
            TextAlignment = TextAlignment.Center,
            LineHeight = size * 1.28,
        };
    }

    private double WidthOf(double available) => double.IsInfinity(available) ? 0 : Math.Max(0, available - Inset * 2);

    private static DependencyProperty TextElementFontSize() => DependencyProperty.Register(
        nameof(FontSize),
        typeof(double),
        typeof(LyricLine),
        new FrameworkPropertyMetadata(32.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));
}
