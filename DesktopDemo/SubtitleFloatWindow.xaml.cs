using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using PlayerDemo.Services;

namespace PlayerDemo;

public partial class SubtitleFloatWindow : Window
{
    private const double Grip = 12;

    private static readonly SolidColorBrush BrightFill = Freeze(Color.FromRgb(255, 255, 255));
    private static readonly SolidColorBrush BrightStroke = Freeze(Color.FromArgb(0xE6, 0, 0, 0));
    private static readonly SolidColorBrush DimFill = Freeze(Color.FromRgb(0xD0, 0xD0, 0xD0));
    private static readonly SolidColorBrush DimStroke = Freeze(Color.FromArgb(0xE6, 0, 0, 0));
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

    public bool IsClosed { get; private set; }

    public SubtitleFloatWindow(ServiceSettings settings)
    {
        _settings = settings;
        InitializeComponent();
        Width = settings.FloatWidth is double width && width is >= 280 and <= 1400 ? width : 760;
        Height = settings.FloatHeight is double height && height is >= 96 and <= 520 ? height : 168;
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
        CurrentLine.Fill = bright ? BrightFill : DimFill;
        CurrentLine.Stroke = bright ? BrightStroke : DimStroke;
        NextLine.Fill = DimFill;
        NextLine.Stroke = DimStroke;
        CurrentLine.Text = currentText;
        NextLine.Text = nextText;
        Grid.SetRowSpan(CurrentLine, nextText.Length == 0 ? 2 : 1);
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
        if (ActualHeight <= 0)
            return;
        var current = Math.Clamp(ActualHeight * 0.30, 20, 78);
        CurrentLine.FontSize = current;
        CurrentLine.StrokeThickness = Math.Max(1.5, current * 0.075);
        var next = Math.Max(15, current * 0.58);
        NextLine.FontSize = next;
        NextLine.StrokeThickness = Math.Max(1.1, next * 0.07);
    }

    private void ShowChrome(bool visible)
    {
        Frame.Background = visible ? HoverFill : HitFill;
        Frame.BorderBrush = visible ? HoverStroke : Brushes.Transparent;
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
