using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using PlayerDemo.Crawl;
using PlayerDemo.Services;
using QRCoder;

namespace PlayerDemo;

public partial class SettingsPage : UserControl
{
    private ServiceSettings? _settings;
    private bool _loading = true;
    private CancellationTokenSource? _qr;

    public SettingsPage()
    {
        InitializeComponent();
    }

    public void Bind(ServiceSettings settings)
    {
        _settings = settings;
        _loading = true;
        if (WhisperModel.Items.Count == 0)
        {
            foreach (var model in new[]
            {
                ServiceSettings.Whisper1,
                ServiceSettings.WhisperLocal,
                ServiceSettings.WhisperQwen,
                ServiceSettings.WhisperQwen3,
                ServiceSettings.WhisperJobs,
            })
            {
                WhisperModel.Items.Add(new ComboBoxItem
                {
                    Content = ServiceSettings.ModelLabel(model),
                    Tag = model,
                });
            }
        }

        SelectModel(settings.WhisperModel);
        WhisperBase.Text = settings.WhisperBase;
        WhisperToken.Password = settings.WhisperToken;
        TranslateBase.Text = settings.TranslateBase;
        TranslateToken.Password = settings.TranslateToken;
        TranslateModel.Text = settings.TranslateModel;
        if (settings.HasWhisper())
            WhisperSaved.Text = "已保存";
        if (settings.HasTranslate())
            TranslateSaved.Text = "已保存";
        BiliCookie.Password = settings.BiliCookie;
        YouTubeCookie.Password = settings.YouTubeCookie;
        if (settings.BiliCookie.Length > 0)
            BiliSaved.Text = "已保存";
        if (settings.YouTubeCookie.Length > 0)
            YouTubeSaved.Text = "已保存";
        _loading = false;
        ApplyModel();
    }

    private ServiceSettings Settings => _settings ?? throw new InvalidOperationException("设置还没接上");

    private void WhisperModel_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading)
            return;
        ApplyModel();
    }

    private void SaveWhisper_Click(object sender, RoutedEventArgs e)
    {
        var model = SelectedModel();
        var token = WhisperToken.Password.Trim();
        var cloud = model is ServiceSettings.Whisper1 or ServiceSettings.WhisperQwen;
        if (token.Length == 0)
        {
            WhisperSaved.Text = cloud ? "没有 API Key" : "没有听写口令";
            return;
        }

        if (!cloud && WhisperBase.Text.Trim().Length == 0)
        {
            WhisperSaved.Text = "没有听写地址";
            return;
        }

        Settings.WhisperModel = model;
        Settings.WhisperBase = WhisperBase.Text.Trim().TrimEnd('/');
        Settings.WhisperToken = token;
        Settings.Save();
        WhisperSaved.Text = "已保存";
    }

    private void ClearWhisper_Click(object sender, RoutedEventArgs e)
    {
        Settings.WhisperModel = ServiceSettings.WhisperJobs;
        Settings.WhisperBase = "";
        Settings.WhisperToken = "";
        Settings.Save();
        SelectModel(ServiceSettings.WhisperJobs);
        WhisperBase.Text = "";
        WhisperToken.Password = "";
        WhisperSaved.Text = "已清除";
        ApplyModel();
    }

    private void SaveTranslate_Click(object sender, RoutedEventArgs e)
    {
        var baseUrl = ServiceSettings.NormalizeTranslate(TranslateBase.Text);
        var token = TranslateToken.Password.Trim();
        var model = TranslateModel.Text.Trim();
        if (baseUrl == null)
        {
            TranslateSaved.Text = "没有翻译地址";
            return;
        }

        if (token.Length == 0)
        {
            TranslateSaved.Text = "没有翻译口令";
            return;
        }

        if (model.Length == 0)
        {
            TranslateSaved.Text = "没有模型名";
            return;
        }

        Settings.TranslateBase = baseUrl;
        Settings.TranslateToken = token;
        Settings.TranslateModel = model;
        Settings.Save();
        TranslateBase.Text = baseUrl;
        TranslateSaved.Text = "已保存";
    }

    private void ClearTranslate_Click(object sender, RoutedEventArgs e)
    {
        Settings.TranslateBase = "";
        Settings.TranslateToken = "";
        Settings.TranslateModel = "";
        Settings.Save();
        TranslateBase.Text = "";
        TranslateToken.Password = "";
        TranslateModel.Text = "";
        TranslateSaved.Text = "已清除";
    }

    private void WhisperHelp_Click(object sender, RoutedEventArgs e) =>
        ShowHelp("听写", """
            whisper-1 和 qwen-audio-3.1-asr 是线上模型，只填写 API Key，不显示地址。whisper-1 使用 https://api.openai.com，qwen-audio-3.1-asr 使用 https://dashscope.aliyuncs.com。whisper（本地部署）、Qwen3-ASR 和本机任务接口要填写地址和口令。

            whisper-1：模型名固定为 whisper-1，语言为日语，并要求返回每个分段的开始时间、结束时间和文本。单次请求最长等待 10 分钟。文件超过 25MB 时，会先询问是否仍要发送；确认后仍会上传。

            whisper（本地部署）：地址填写本机服务基址，不能留空。基址若不以 /v1 结尾，请求时会补上。请求内容与 whisper-1 相同，模型名仍发送 whisper-1。文件超过 25MB 时直接发送。服务不检查口令时，填写任意非空内容即可。

            qwen-audio-3.1-asr：语言提示为日语。桌面端没有手机上的短音频组件，一律先上传到临时存储再转写。超过 1GB 时不会上传。

            Qwen3-ASR：本地部署的开源模型，与 qwen-audio-3.1-asr 分开。地址填写本机服务基址，不能留空；基址若不以 /v1 结尾，请求时会补上。应用先读取服务上已加载的模型名，再把音频提交到音频转写接口，语言为日语。服务返回每个分段的开始和结束时间时，按段生成字幕。只返回整段文字时，整段文字作为一条字幕，覆盖整首的时长。没有读出时长、也没有分段时间时，记为「没有分段时间」。口令会放在请求里；服务不检查口令时，填写任意非空内容即可。

            本机任务接口：按 docs/whisper-api.md。应用将音频提交到 POST /jobs，用 GET /jobs/{id} 查询进度，用 GET /jobs/{id}/cues 读取字幕。中止只停止这里的等待，服务上已经开始的转写会自己跑完。
            """);

    private async void SaveBili_Click(object sender, RoutedEventArgs e)
    {
        var cookie = BilibiliClient.NormalizeCookie(BiliCookie.Password);
        if (cookie.Length == 0)
        {
            BiliSaved.Text = "没有 Cookie";
            return;
        }

        if (cookie.Contains('=', StringComparison.Ordinal) && !cookie.Contains("SESSDATA=", StringComparison.Ordinal))
        {
            BiliSaved.Text = "保存的内容里没有 SESSDATA，请重新粘贴";
            return;
        }

        if (cookie != Settings.BiliCookie)
        {
            Settings.BiliRefreshToken = "";
            Settings.BiliRefreshChecked = 0;
        }

        Settings.BiliCookie = cookie;
        Settings.Save();
        BiliCookie.Password = cookie;
        BiliSaved.Text = "正在确认登录…";
        try
        {
            BiliSaved.Text = await BilibiliClient.AccountNameAsync(Settings, CancellationToken.None);
        }
        catch (Exception ex)
        {
            BiliSaved.Text = string.IsNullOrWhiteSpace(ex.Message) ? "登录信息无效或已过期" : ex.Message;
        }
    }

    private void ClearBili_Click(object sender, RoutedEventArgs e)
    {
        _qr?.Cancel();
        Settings.BiliCookie = "";
        Settings.BiliRefreshToken = "";
        Settings.BiliRefreshChecked = 0;
        Settings.Save();
        BiliCookie.Password = "";
        BiliQr.Visibility = Visibility.Collapsed;
        BiliSaved.Text = "已清除";
    }

    private async void BiliQr_Click(object sender, RoutedEventArgs e)
    {
        if (_qr != null)
        {
            _qr.Cancel();
            return;
        }

        var job = new CancellationTokenSource();
        _qr = job;
        BiliQrButton.Content = "取消扫码";
        try
        {
            BiliSaved.Text = "正在获取二维码";
            var login = await BilibiliClient.RequestQrAsync(Settings, job.Token);
            ShowQr(login.Url);
            BiliSaved.Text = "等待扫码";
            while (!job.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(2), job.Token);
                var state = await BilibiliClient.PollQrAsync(Settings, login.Key, job.Token);
                if (state == BilibiliClient.QrState.Waiting)
                {
                    BiliSaved.Text = "等待扫码";
                    continue;
                }

                if (state == BilibiliClient.QrState.Scanned)
                {
                    BiliSaved.Text = "已扫码，请在手机上确认";
                    continue;
                }

                if (state == BilibiliClient.QrState.Expired)
                {
                    BiliSaved.Text = "二维码已过期";
                    break;
                }

                BiliCookie.Password = Settings.BiliCookie;
                BiliSaved.Text = await BilibiliClient.AccountNameAsync(Settings, job.Token);
                break;
            }
        }
        catch (OperationCanceledException)
        {
            if (BiliSaved.Text is "等待扫码" or "已扫码，请在手机上确认" or "正在获取二维码" or "正在确认登录…")
                BiliSaved.Text = "已取消扫码";
        }
        catch (Exception ex)
        {
            BiliSaved.Text = string.IsNullOrWhiteSpace(ex.Message) ? "扫码登录失败" : ex.Message;
        }
        finally
        {
            if (ReferenceEquals(_qr, job))
                _qr = null;
            job.Dispose();
            BiliQr.Source = null;
            BiliQr.Visibility = Visibility.Collapsed;
            BiliQrButton.Content = "扫码登录";
        }
    }

    private async void SaveYouTube_Click(object sender, RoutedEventArgs e)
    {
        var cookie = YouTubeClient.NormalizeCookie(YouTubeCookie.Password);
        if (cookie.Length == 0 || !cookie.Contains('=', StringComparison.Ordinal))
        {
            YouTubeSaved.Text = "请粘贴浏览器里 youtube.com 的整段 Cookie";
            return;
        }

        Settings.YouTubeCookie = cookie;
        Settings.Save();
        YouTubeCookie.Password = cookie;
        YouTubeSaved.Text = "正在确认登录…";
        try
        {
            YouTubeSaved.Text = await YouTubeClient.AccountStatusAsync(Settings, CancellationToken.None);
        }
        catch (Exception ex)
        {
            YouTubeSaved.Text = string.IsNullOrWhiteSpace(ex.Message) ? "保存了，但连不上 YouTube" : ex.Message;
        }
    }

    private void ClearYouTube_Click(object sender, RoutedEventArgs e)
    {
        Settings.YouTubeCookie = "";
        Settings.Save();
        YouTubeCookie.Password = "";
        YouTubeSaved.Text = "已清除";
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e) => _qr?.Cancel();

    private void ShowQr(string url)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(url, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8);
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = new MemoryStream(png);
        image.EndInit();
        image.Freeze();
        BiliQr.Source = image;
        BiliQr.Visibility = Visibility.Visible;
    }

    private void BiliHelp_Click(object sender, RoutedEventArgs e) =>
        ShowHelp("B站", """
            Cookie 只保存在这台电脑上，用来读取收藏夹，以及下载这个账号可以收听的音频，包括需要登录或大会员的视频。

            可以粘贴 SESSDATA，也可以粘贴整段 Cookie。扫码登录后，登录信息会在需要时自动更新。粘贴的内容不会自动更新。

            扫码时用手机 B 站扫这里的二维码，并在手机上确认。
            """);

    private void YouTubeHelp_Click(object sender, RoutedEventArgs e) =>
        ShowHelp("YouTube", """
            公开视频不登录也能下载。爬取页可以输入关键词，和网页搜索一样列出视频，也可以粘贴视频或播放列表链接。地址如果带签名，会用本机的 Node.js 按当前播放器脚本解开。

            Cookie 只保存在这台电脑上，用来读取「喜欢的视频」和「稍后观看」。请从浏览器里复制 youtube.com 的整段 Cookie。不要只粘贴一个值。
            """);

    private void TranslateHelp_Click(object sender, RoutedEventArgs e) =>
        ShowHelp("翻译", """
            本地与线上使用同一套接口，只支持通用大模型。服务需提供 OpenAI 兼容的对话接口。Ollama 默认提供该接口。

            模型名必须与服务中已加载的名称一致，应用会原样发送。

            地址填写服务基址。未以 /v1 结尾时，保存后会自动补上，末尾斜杠会被去掉。不要写到 /chat/completions。例如 http://192.168.1.20:11434。口令不能为空，请求时通过 Authorization 发送；服务不校验口令时，填写任意非空内容即可。

            应用请求 POST {基址}/chat/completions。选择「生成字幕和翻译」时，听写完成后会自动把日文译成简体中文。只选择「生成字幕」时不会请求翻译。

            线上模型的地址同样填写到 /v1，例如 https://api.openai.com/v1，或服务商提供的兼容地址。模型名填写该服务商的对话模型名称。

            只把日语字幕译成简体中文。每批最多 40 句，超出部分会分成多次请求并按顺序发送。每一行必须是「编号|译文」，编号从 1 连续排到本批句数，不能缺行、空行或附加说明。任一批格式不符，整次翻译失败，已完成的批次不会保存。单次请求最长等待 3 分钟。模型若仍进行思考，可能超过该时限而失败。
            """);

    private void HideHelp_Click(object sender, RoutedEventArgs e) => HelpCard.Visibility = Visibility.Collapsed;

    private void ShowHelp(string title, string text)
    {
        HelpTitle.Text = title;
        HelpBody.Text = text;
        HelpCard.Visibility = Visibility.Visible;
    }

    private void ApplyModel()
    {
        var cloud = SelectedModel() is ServiceSettings.Whisper1 or ServiceSettings.WhisperQwen;
        WhisperAddressLabel.Visibility = cloud ? Visibility.Collapsed : Visibility.Visible;
        WhisperBase.Visibility = cloud ? Visibility.Collapsed : Visibility.Visible;
        WhisperTokenLabel.Text = cloud ? "API Key" : "口令";
    }

    private void SelectModel(string model)
    {
        foreach (ComboBoxItem item in WhisperModel.Items)
        {
            if ((string)item.Tag == model)
            {
                WhisperModel.SelectedItem = item;
                return;
            }
        }

        WhisperModel.SelectedIndex = WhisperModel.Items.Count - 1;
    }

    private string SelectedModel() =>
        WhisperModel.SelectedItem is ComboBoxItem item ? (string)item.Tag : ServiceSettings.WhisperJobs;
}
