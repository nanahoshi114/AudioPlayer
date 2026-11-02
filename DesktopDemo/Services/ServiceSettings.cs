using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace PlayerDemo.Services;

public sealed class ServiceSettings
{
    public const string Whisper1 = "whisper-1";
    public const string WhisperLocal = "whisper-local";
    public const string WhisperQwen = "qwen-audio-3.1-asr";
    public const string WhisperQwen3 = "qwen3-asr";
    public const string WhisperJobs = "jobs";
    public const string OpenAiBase = "https://api.openai.com";
    public const string QwenBase = "https://dashscope.aliyuncs.com";

    public string WhisperModel { get; set; } = WhisperJobs;
    public string WhisperBase { get; set; } = "";
    public string WhisperToken { get; set; } = "";
    public string TranslateBase { get; set; } = "";
    public string TranslateToken { get; set; } = "";
    public string TranslateModel { get; set; } = "";
    public double? FloatLeft { get; set; }
    public double? FloatTop { get; set; }
    public double? FloatWidth { get; set; }
    public double? FloatHeight { get; set; }
    public bool ShowOriginal { get; set; } = true;
    public bool ShowDuplicates { get; set; }
    public bool Exclusive { get; set; }
    public string PlayMode { get; set; } = "order";
    public string DeviceId { get; set; } = "";
    public string BiliCookie { get; set; } = "";
    public string BiliBuvid3 { get; set; } = "";
    public string BiliBuvid4 { get; set; } = "";
    public string BiliTicket { get; set; } = "";
    public long BiliTicketExpires { get; set; }
    public string BiliRefreshToken { get; set; } = "";
    public long BiliRefreshChecked { get; set; }
    public string YouTubeCookie { get; set; } = "";

    public static ServiceSettings Load()
    {
        try
        {
            if (!File.Exists(Path()))
                return OverlaySession(new ServiceSettings());
            var stored = JsonSerializer.Deserialize<Stored>(File.ReadAllText(Path()));
            if (stored == null)
                return OverlaySession(new ServiceSettings());
            return OverlaySession(new ServiceSettings
            {
                WhisperModel = KnownModel(stored.WhisperModel) ?? WhisperJobs,
                WhisperBase = stored.WhisperBase?.Trim().TrimEnd('/') ?? "",
                WhisperToken = Unprotect(stored.WhisperToken),
                TranslateBase = NormalizeTranslate(stored.TranslateBase) ?? "",
                TranslateToken = Unprotect(stored.TranslateToken),
                TranslateModel = stored.TranslateModel?.Trim() ?? "",
                FloatLeft = stored.FloatLeft,
                FloatTop = stored.FloatTop,
                FloatWidth = stored.FloatWidth,
                FloatHeight = stored.FloatHeight,
                ShowOriginal = stored.ShowOriginal,
                ShowDuplicates = stored.ShowDuplicates,
                Exclusive = stored.Exclusive,
                PlayMode = KnownPlayMode(stored.PlayMode),
                DeviceId = stored.DeviceId ?? "",
                BiliCookie = Unprotect(stored.BiliCookie),
                BiliBuvid3 = stored.BiliBuvid3 ?? "",
                BiliBuvid4 = stored.BiliBuvid4 ?? "",
                BiliTicket = Unprotect(stored.BiliTicket),
                BiliTicketExpires = stored.BiliTicketExpires,
                BiliRefreshToken = Unprotect(stored.BiliRefreshToken),
                BiliRefreshChecked = stored.BiliRefreshChecked,
                YouTubeCookie = Unprotect(stored.YouTubeCookie),
            });
        }
        catch
        {
            return OverlaySession(new ServiceSettings());
        }
    }

    public void Save()
    {
        WhisperBase = WhisperBase.Trim().TrimEnd('/');
        TranslateBase = NormalizeTranslate(TranslateBase) ?? "";
        TranslateModel = TranslateModel.Trim();
        WhisperToken = WhisperToken.Trim();
        TranslateToken = TranslateToken.Trim();
        if (KnownModel(WhisperModel) == null)
            WhisperModel = WhisperJobs;
        var stored = new Stored
        {
            WhisperModel = WhisperModel,
            WhisperBase = WhisperBase,
            WhisperToken = Protect(WhisperToken),
            TranslateBase = TranslateBase,
            TranslateToken = Protect(TranslateToken),
            TranslateModel = TranslateModel,
            FloatLeft = FloatLeft,
            FloatTop = FloatTop,
            FloatWidth = FloatWidth,
            FloatHeight = FloatHeight,
            ShowOriginal = ShowOriginal,
            ShowDuplicates = ShowDuplicates,
            Exclusive = Exclusive,
            PlayMode = KnownPlayMode(PlayMode),
            DeviceId = DeviceId,
            BiliCookie = Protect(BiliCookie),
            BiliBuvid3 = BiliBuvid3,
            BiliBuvid4 = BiliBuvid4,
            BiliTicket = Protect(BiliTicket),
            BiliTicketExpires = BiliTicketExpires,
            BiliRefreshToken = Protect(BiliRefreshToken),
            BiliRefreshChecked = BiliRefreshChecked,
            YouTubeCookie = Protect(YouTubeCookie),
        };
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path())!);
        File.WriteAllText(Path(), JsonSerializer.Serialize(stored, new JsonSerializerOptions { WriteIndented = true }));
        WriteSession();
    }

    public bool CloudWhisper => WhisperModel is Whisper1 or WhisperQwen;

    public string? EffectiveWhisperBase()
    {
        if (WhisperModel == Whisper1)
            return OpenAiBase;
        if (WhisperModel == WhisperQwen)
            return QwenBase;
        return string.IsNullOrWhiteSpace(WhisperBase) ? null : WhisperBase.Trim().TrimEnd('/');
    }

    public bool HasWhisper() =>
        !string.IsNullOrWhiteSpace(WhisperToken) && EffectiveWhisperBase() != null;

    public bool HasTranslate() =>
        !string.IsNullOrWhiteSpace(TranslateBase)
        && !string.IsNullOrWhiteSpace(TranslateToken)
        && !string.IsNullOrWhiteSpace(TranslateModel);

    public static string? NormalizeTranslate(string? raw)
    {
        var value = raw?.Trim().TrimEnd('/');
        if (string.IsNullOrEmpty(value))
            return null;
        return value.EndsWith("/v1", StringComparison.OrdinalIgnoreCase) ? value : value + "/v1";
    }

    public static string TranscriptionBase(string baseUrl)
    {
        var trimmed = baseUrl.Trim().TrimEnd('/');
        return trimmed.EndsWith("/v1", StringComparison.OrdinalIgnoreCase) ? trimmed : trimmed + "/v1";
    }

    public static string ModelLabel(string model) => model switch
    {
        Whisper1 => Whisper1,
        WhisperLocal => "whisper（本地部署）",
        WhisperQwen => WhisperQwen,
        WhisperQwen3 => "Qwen3-ASR",
        _ => "本机任务接口",
    };

    private static string KnownPlayMode(string? mode) => mode switch
    {
        "shuffle" or "repeat" or "order" => mode,
        _ => "order",
    };

    private static string? KnownModel(string? model) => model switch
    {
        Whisper1 or WhisperLocal or WhisperQwen or WhisperQwen3 or WhisperJobs => model,
        _ => null,
    };

    private static string Path() =>
        System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PlayerDemo", "services.json");

    private static string SessionPath() =>
        System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PlayerDemo", "session.json");

    private static ServiceSettings OverlaySession(ServiceSettings settings)
    {
        try
        {
            if (!File.Exists(SessionPath()))
                return settings;
            var stored = JsonSerializer.Deserialize<SessionFile>(File.ReadAllText(SessionPath()));
            if (stored == null)
                return settings;
            settings.BiliCookie = Unprotect(stored.BiliCookie);
            settings.BiliBuvid3 = stored.BiliBuvid3 ?? "";
            settings.BiliBuvid4 = stored.BiliBuvid4 ?? "";
            settings.BiliTicket = Unprotect(stored.BiliTicket);
            settings.BiliTicketExpires = stored.BiliTicketExpires;
            settings.BiliRefreshToken = Unprotect(stored.BiliRefreshToken);
            settings.BiliRefreshChecked = stored.BiliRefreshChecked;
            settings.YouTubeCookie = Unprotect(stored.YouTubeCookie);
        }
        catch
        {
        }

        return settings;
    }

    private void WriteSession()
    {
        var stored = new SessionFile
        {
            BiliCookie = Protect(BiliCookie),
            BiliBuvid3 = BiliBuvid3,
            BiliBuvid4 = BiliBuvid4,
            BiliTicket = Protect(BiliTicket),
            BiliTicketExpires = BiliTicketExpires,
            BiliRefreshToken = Protect(BiliRefreshToken),
            BiliRefreshChecked = BiliRefreshChecked,
            YouTubeCookie = Protect(YouTubeCookie),
        };
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(SessionPath())!);
        File.WriteAllText(SessionPath(), JsonSerializer.Serialize(stored, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static string Protect(string plain)
    {
        if (plain.Length == 0)
            return "";
        var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(plain), null, DataProtectionScope.CurrentUser);
        return "dpapi:" + Convert.ToBase64String(bytes);
    }

    private static string Unprotect(string? stored)
    {
        if (string.IsNullOrWhiteSpace(stored))
            return "";
        if (!stored.StartsWith("dpapi:", StringComparison.Ordinal))
            return stored.Trim();
        try
        {
            var bytes = ProtectedData.Unprotect(Convert.FromBase64String(stored[6..]), null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch
        {
            return "";
        }
    }

    private sealed class Stored
    {
        public string? WhisperModel { get; set; }
        public string? WhisperBase { get; set; }
        public string? WhisperToken { get; set; }
        public string? TranslateBase { get; set; }
        public string? TranslateToken { get; set; }
        public string? TranslateModel { get; set; }
        public double? FloatLeft { get; set; }
        public double? FloatTop { get; set; }
        public double? FloatWidth { get; set; }
        public double? FloatHeight { get; set; }
        public bool ShowOriginal { get; set; } = true;
        public bool ShowDuplicates { get; set; }
        public bool Exclusive { get; set; }
        public string? PlayMode { get; set; }
        public string? DeviceId { get; set; }
        public string? BiliCookie { get; set; }
        public string? BiliBuvid3 { get; set; }
        public string? BiliBuvid4 { get; set; }
        public string? BiliTicket { get; set; }
        public long BiliTicketExpires { get; set; }
        public string? BiliRefreshToken { get; set; }
        public long BiliRefreshChecked { get; set; }
        public string? YouTubeCookie { get; set; }
    }

    private sealed class SessionFile
    {
        public string? BiliCookie { get; set; }
        public string? BiliBuvid3 { get; set; }
        public string? BiliBuvid4 { get; set; }
        public string? BiliTicket { get; set; }
        public long BiliTicketExpires { get; set; }
        public string? BiliRefreshToken { get; set; }
        public long BiliRefreshChecked { get; set; }
        public string? YouTubeCookie { get; set; }
    }
}
