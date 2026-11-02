using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using PlayerDemo.Services;
using PlayerDemo.Theme;

namespace PlayerDemo;

public partial class SubtitleFloatWindow : Window
{
    private const double Grip = 12;

    private Color _accent = Color.FromRgb(0x8A, 0x5A, 0x32);
    private static readonly SolidColorBrush HitFill = Freeze(Color.FromArgb(0x01, 0, 0, 0));
    private static readonly SolidColorBrush HoverFill = Freeze(Color.FromArgb(0x28, 0, 0, 0));
    private static readonly SolidColorBrush HoverStroke = Freeze(Color.FromArgb(0x80, 255, 255, 255));

    private readonly ServiceSettings _settings;
    private Edge _edge;
    private bool _resizing;
    private Point _anchor;
    private double _originLeft;
    private double _originTop;
    private double _originWidth;
    private double _originHeight;
    private bool _bright = true;
    private bool _fitting;

    public bool IsClosed { get; private set; }

    public event EventHandler? OriginalRequested;

    public event EventHandler? CloseRequested;

    public event EventHandler? PreviousRequested;

    public event EventHandler? NextRequested;

    public event EventHandler? PlayRequested;

    public SubtitleFloatWindow(ServiceSettings settings)
    {
        _settings = settings;
        InitializeComponent();
        Width = settings.FloatWidth is double width && width is >= 420 and <= 1400 ? width : 860;
        Height = settings.FloatHeight is double height && height is >= 180 and <= 520 ? height : 240;
        WindowStartupLocation = WindowStartupLocation.Manual;
        if (settings.FloatLeft is double left && settings.FloatTop is double top)
        {
            Left = left;
            Top = top;
        }
        else
        {
            var area = SystemParameters.WorkArea;
            Left = area.Left + Math.Max(0, (area.Width - Width) / 2);
            Top = area.Bottom - Height - 72;
        }

        SizeChanged += (_, _) => UpdateScale();
        UpdateScale();
    }

    public void SetOriginalShown(bool shown) => OriginalButton.Content = shown ? "隐藏原文" : "显示原文";

    public void SetPlaying(bool playing)
    {
        PlayMark.Visibility = playing ? Visibility.Collapsed : Visibility.Visible;
        PauseMark.Visibility = playing ? Visibility.Visible : Visibility.Collapsed;
    }

    public void UseAccent(Color accent)
    {
        _accent = accent;
        CurrentLine.Fill = Freeze(ArtColor.Lyric(accent));
        CurrentLine.Stroke = Freeze(ArtColor.LyricEdge(accent));
        NextLine.Fill = Freeze(ArtColor.LyricSoft(accent));
        NextLine.Stroke = Freeze(ArtColor.LyricEdge(accent));
    }

    public void ShowPair(string? current, string? next)
    {
        var currentText = Clean(current);
        var nextText = Clean(next);
        var bright = currentText.Length > 0;
        if (!bright && nextText.Length == 0)
        {
            if (IsVisible)
            {
                CurrentLine.Text = "";
                NextLine.Text = "";
                NextLine.Visibility = Visibility.Collapsed;
            }

            return;
        }

        if (!bright)
        {
            currentText = nextText;
            nextText = "";
        }

        if (CurrentLine.Text == currentText && NextLine.Text == nextText && _bright == bright && IsVisible)
            return;

        _bright = bright;
        UseAccent(_accent);
        CurrentLine.Text = currentText;
        NextLine.Text = nextText;
        NextLine.Visibility = nextText.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        UpdateScale();
        if (!IsVisible)
            Show();
    }

    public void Shutdown()
    {
        if (IsClosed)
            return;
        Close();
    }

    protected override void OnClosed(EventArgs e)
    {
        IsClosed = true;
        base.OnClosed(e);
    }

    protected override void OnMouseEnter(MouseEventArgs e)
    {
        base.OnMouseEnter(e);
        ShowChrome(true);
    }

    protected override void OnMouseLeave(MouseEventArgs e)
    {
        base.OnMouseLeave(e);
        if (!_resizing)
        {
            Cursor = Cursors.Arrow;
            ShowChrome(false);
        }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (_resizing)
        {
            if (e.LeftButton != MouseButtonState.Pressed)
            {
                FinishResize();
                return;
            }

            ApplyResize(CursorDip(e));
            return;
        }

        _edge = Hit(e.GetPosition(this));
        Cursor = CursorFor(_edge);
    }

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonDown(e);
        if (InHoverBar(e.OriginalSource as DependencyObject))
            return;
        _edge = Hit(e.GetPosition(this));
        if (_edge == Edge.None)
        {
            try
            {
                DragMove();
            }
            catch (InvalidOperationException)
            {
                return;
            }

            Remember();
            return;
        }

        _resizing = true;
        _anchor = CursorDip(e);
        _originLeft = Left;
        _originTop = Top;
        _originWidth = Width;
        _originHeight = Height;
        CaptureMouse();
        ShowChrome(true);
        e.Handled = true;
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonUp(e);
        if (_resizing)
            FinishResize();
    }

    private void FinishResize()
    {
        _resizing = false;
        if (IsMouseCaptured)
            ReleaseMouseCapture();
        Remember();
        if (!IsMouseOver)
        {
            Cursor = Cursors.Arrow;
            ShowChrome(false);
        }
    }

    private void ApplyResize(Point cursor)
    {
        var dx = cursor.X - _anchor.X;
        var dy = cursor.Y - _anchor.Y;
        var left = _originLeft;
        var top = _originTop;
        var width = _originWidth;
        var height = _originHeight;
        var pullLeft = _edge is Edge.Left or Edge.TopLeft or Edge.BottomLeft;
        var pullRight = _edge is Edge.Right or Edge.TopRight or Edge.BottomRight;
        var pullTop = _edge is Edge.Top or Edge.TopLeft or Edge.TopRight;
        var pullBottom = _edge is Edge.Bottom or Edge.BottomLeft or Edge.BottomRight;

        if (pullLeft)
        {
            width = _originWidth - dx;
            left = _originLeft + dx;
        }
        else if (pullRight)
        {
            width = _originWidth + dx;
        }

        if (pullTop)
        {
            height = _originHeight - dy;
            top = _originTop + dy;
        }
        else if (pullBottom)
        {
            height = _originHeight + dy;
        }

        if (width < MinWidth)
        {
            if (pullLeft)
                left = _originLeft + _originWidth - MinWidth;
            width = MinWidth;
        }
        else if (width > MaxWidth)
        {
            if (pullLeft)
                left = _originLeft + _originWidth - MaxWidth;
            width = MaxWidth;
        }

        if (height < MinHeight)
        {
            if (pullTop)
                top = _originTop + _originHeight - MinHeight;
            height = MinHeight;
        }
        else if (height > MaxHeight)
        {
            if (pullTop)
                top = _originTop + _originHeight - MaxHeight;
            height = MaxHeight;
        }

        Left = left;
        Top = top;
        Width = width;
        Height = height;
    }

    private void UpdateScale()
    {
        if (_fitting || ActualWidth <= 1)
            return;
        _fitting = true;
        try
        {
            const double main = 36;
            CurrentLine.FontSize = main;
            CurrentLine.StrokeThickness = 2.2;
            NextLine.FontSize = 22;
            NextLine.StrokeThickness = 1.3;
            var width = Math.Max(1, ActualWidth - Frame.Padding.Left - Frame.Padding.Right - LinesHost.Margin.Left - LinesHost.Margin.Right);
            LinesHost.Measure(new Size(width, double.PositiveInfinity));
            var bar = 0d;
            if (HoverBar.Visibility == Visibility.Visible)
            {
                HoverBar.Measure(new Size(width, double.PositiveInfinity));
                bar = HoverBar.DesiredSize.Height + 8;
            }

            var needed = LinesHost.DesiredSize.Height + bar + Frame.Padding.Top + Frame.Padding.Bottom + LinesHost.Margin.Top + LinesHost.Margin.Bottom + 12;
            if (needed < MinHeight)
                needed = MinHeight;
            if (needed > MaxHeight)
                needed = MaxHeight;
            if (Math.Abs(Height - needed) > 1)
            {
                var grow = needed - Height;
                var area = SystemParameters.WorkArea;
                if (Top + needed > area.Bottom)
                    Top = Math.Max(area.Top, Top - grow);
                Height = needed;
            }
        }
        finally
        {
            _fitting = false;
        }
    }

    private void ShowChrome(bool visible)
    {
        Frame.Background = visible ? HoverFill : HitFill;
        Frame.BorderBrush = visible ? HoverStroke : Brushes.Transparent;
        HoverBar.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        UpdateScale();
    }

    private void Original_Click(object sender, RoutedEventArgs e) => Raise(OriginalRequested, e);

    private void Close_Click(object sender, RoutedEventArgs e) => Raise(CloseRequested, e);

    private void Previous_Click(object sender, RoutedEventArgs e) => Raise(PreviousRequested, e);

    private void Next_Click(object sender, RoutedEventArgs e) => Raise(NextRequested, e);

    private void Play_Click(object sender, RoutedEventArgs e) => Raise(PlayRequested, e);

    private static void Raise(EventHandler? handler, RoutedEventArgs e)
    {
        e.Handled = true;
        handler?.Invoke(null, EventArgs.Empty);
    }

    private bool InHoverBar(DependencyObject? node)
    {
        while (node != null)
        {
            if (ReferenceEquals(node, HoverBar))
                return true;
            node = VisualTreeHelper.GetParent(node);
        }

        return false;
    }

    private void Remember()
    {
        _settings.FloatLeft = Left;
        _settings.FloatTop = Top;
        _settings.FloatWidth = Width;
        _settings.FloatHeight = Height;
        _settings.Save();
    }

    private Edge Hit(Point point)
    {
        var left = point.X <= Grip;
        var right = point.X >= ActualWidth - Grip;
        var top = point.Y <= Grip;
        var bottom = point.Y >= ActualHeight - Grip;
        if (top && left)
            return Edge.TopLeft;
        if (top && right)
            return Edge.TopRight;
        if (bottom && left)
            return Edge.BottomLeft;
        if (bottom && right)
            return Edge.BottomRight;
        if (left)
            return Edge.Left;
        if (right)
            return Edge.Right;
        if (top)
            return Edge.Top;
        if (bottom)
            return Edge.Bottom;
        return Edge.None;
    }

    private Point CursorDip(MouseEventArgs e)
    {
        var pixel = PointToScreen(e.GetPosition(this));
        var source = PresentationSource.FromVisual(this);
        if (source?.CompositionTarget == null)
            return pixel;
        return source.CompositionTarget.TransformFromDevice.Transform(pixel);
    }

    private static Cursor CursorFor(Edge edge) => edge switch
    {
        Edge.Left or Edge.Right => Cursors.SizeWE,
        Edge.Top or Edge.Bottom => Cursors.SizeNS,
        Edge.TopLeft or Edge.BottomRight => Cursors.SizeNWSE,
        Edge.TopRight or Edge.BottomLeft => Cursors.SizeNESW,
        _ => Cursors.SizeAll,
    };

    private static string Clean(string? text) => (text ?? "").Replace("\r", "").Trim();

    private static SolidColorBrush Freeze(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private enum Edge
    {
        None,
        Left,
        Right,
        Top,
        Bottom,
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
    }
}
