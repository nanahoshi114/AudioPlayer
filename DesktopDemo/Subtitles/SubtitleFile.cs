using System.IO;

namespace PlayerDemo.Subtitles;

public sealed record SubtitleFile(string FullPath, string Name)
{
    public string Kind => Path.GetExtension(Name).TrimStart('.').ToUpperInvariant();
}

public readonly record struct SubtitleCue(long StartMs, long EndMs, string Text, string Translation = "");
