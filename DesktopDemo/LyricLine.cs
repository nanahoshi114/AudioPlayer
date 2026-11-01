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

    protected override Size MeasureOverride(Size available)
    {
        var formatted = Format(WidthOf(available.Width), HeightOf(available.Height));
        if (formatted == null)
            return new Size(0, 0);
        var inset = Inset * 2;
        return new Size(formatted.Width + inset, formatted.Height + inset);
    }

    protected override void OnRender(DrawingContext drawingContext)
    {
        var formatted = Format(WidthOf(ActualWidth), HeightOf(ActualHeight));
        if (formatted == null)
            return;

        var y = Inset + Math.Max(0, (ActualHeight - Inset * 2 - formatted.Height) / 2);
        var geometry = formatted.BuildGeometry(new Point(Inset, y));
        var pen = new Pen(Stroke, StrokeThickness)
        {
            LineJoin = PenLineJoin.Round,
            StartLineCap = PenLineCap.Round,
            EndLineCap = PenLineCap.Round,
        };
        drawingContext.DrawGeometry(null, pen, geometry);
        drawingContext.DrawGeometry(Fill, null, geometry);
    }

    private double Inset => Math.Max(1, StrokeThickness);

    private FormattedText? Format(double width, double height)
    {
        var text = Text?.Replace("\r", "") ?? "";
        if (text.Length == 0 || width <= 1 || FontSize <= 0)
            return null;

        var pixels = 1.0;
        if (PresentationSource.FromVisual(this) != null)
            pixels = VisualTreeHelper.GetDpi(this).PixelsPerDip;

        var size = FontSize;
        var formatted = Make(text, width, size, pixels, 0);
        while (height > 1 && formatted.Height > height + 0.5 && size > 15)
        {
            size -= Math.Max(1, size * 0.08);
            formatted = Make(text, width, Math.Max(15, size), pixels, 0);
        }

        if (height > 1 && formatted.Height > height + 0.5)
            formatted = Make(text, width, Math.Max(15, size), pixels, height);
        return formatted;
    }

    private FormattedText Make(string text, double width, double size, double pixels, double clipHeight)
    {
        var formatted = new FormattedText(
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
        if (clipHeight > 1)
        {
            formatted.Trimming = TextTrimming.CharacterEllipsis;
            formatted.MaxTextHeight = clipHeight;
        }

        return formatted;
    }

    private double WidthOf(double available) => double.IsInfinity(available) ? 0 : Math.Max(0, available - Inset * 2);

    private static double HeightOf(double available) => double.IsInfinity(available) ? 0 : Math.Max(0, available);

    private static DependencyProperty TextElementFontSize() => DependencyProperty.Register(
        nameof(FontSize),
        typeof(double),
        typeof(LyricLine),
        new FrameworkPropertyMetadata(32.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));
}
