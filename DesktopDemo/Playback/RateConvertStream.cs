using System.Buffers;
using NAudio.Dsp;
using NAudio.Wave;
using PlayerDemo.Library;

namespace PlayerDemo.Playback;

/// <summary>
/// 把文件重采样到设备接受的格式。只在原样送不出去时使用。
/// </summary>
public sealed class RateConvertStream : WaveStream
{
    private readonly WaveStream _source;
    private readonly WdlResampler? _resampler;
    private readonly bool _sameRate;
    private long _length;
    private long _position;

    public RateConvertStream(WaveStream source, WaveFormat output)
    {
        _source = source;
        WaveFormat = output;
        SourceNote = NoteFor(source.WaveFormat, output);
        _sameRate = source.WaveFormat.SampleRate == output.SampleRate;
        if (!_sameRate)
        {
            _resampler = new WdlResampler();
            _resampler.SetMode(false, 0, true, 64, 32);
            _resampler.SetFilterParms(0.693f, 0.707f);
            _resampler.SetFeedMode(false);
            _resampler.SetRates(source.WaveFormat.SampleRate, output.SampleRate);
            _resampler.Reset(0);
        }

        var sourceBlock = Math.Max(1, source.WaveFormat.BlockAlign);
        var sourceFrames = source.Length / sourceBlock;
        var outFrames = _sameRate
            ? sourceFrames
            : (long)Math.Ceiling(sourceFrames * (double)output.SampleRate / source.WaveFormat.SampleRate);
        _length = outFrames * output.BlockAlign;
    }

    public string SourceNote { get; }

    public override WaveFormat WaveFormat { get; }

    public override long Length => _length;

    public override long Position
    {
        get => _position;
        set
        {
            var block = WaveFormat.BlockAlign;
            if (block <= 0)
                return;
            if (value < 0)
                value = 0;
            if (value > _length)
                value = _length;
            value -= value % block;

            var frames = value / block;
            var sourceBlock = Math.Max(1, _source.WaveFormat.BlockAlign);
            var sourceFrames = _sameRate
                ? frames
                : (long)Math.Round(frames * (double)_source.WaveFormat.SampleRate / WaveFormat.SampleRate);
            var sourcePos = sourceFrames * sourceBlock;
            var sourceLength = _source.Length - (_source.Length % sourceBlock);
            if (sourcePos > sourceLength)
                sourcePos = sourceLength;
            if (sourcePos < 0)
                sourcePos = 0;
            _source.Position = sourcePos;
            _position = value;
            _resampler?.Reset(0);
        }
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var frameBytes = WaveFormat.BlockAlign;
        if (frameBytes <= 0)
            return 0;
        var frames = count / frameBytes;
        if (frames <= 0)
            return 0;

        var channels = WaveFormat.Channels;
        var samples = ArrayPool<float>.Shared.Rent(frames * channels);
        try
        {
            var filled = _sameRate ? ReadSourceFrames(samples, 0, frames) : Resample(samples, frames);
            WriteSamples(samples, filled, buffer, offset);
            _position += (long)filled * frameBytes;
            return filled * frameBytes;
        }
        finally
        {
            ArrayPool<float>.Shared.Return(samples);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
            _source.Dispose();
        base.Dispose(disposing);
    }

    private int Resample(float[] destination, int frames)
    {
        var channels = WaveFormat.Channels;
        var filled = 0;
        while (filled < frames)
        {
            var need = frames - filled;
            var inNeeded = _resampler!.ResamplePrepare(need, channels, out var input, out var inputOffset);
            var got = ReadSourceFrames(input, inputOffset, inNeeded);
            var produced = _resampler.ResampleOut(destination, filled * channels, got, need, channels);
            if (produced <= 0)
                break;
            filled += produced;
            if (got < inNeeded)
                break;
        }

        return filled;
    }

    private int ReadSourceFrames(float[] destination, int offset, int frames)
    {
        if (frames <= 0)
            return 0;

        var source = _source.WaveFormat;
        var sourceBlock = source.BlockAlign;
        if (sourceBlock <= 0)
            return 0;

        var bytesNeeded = frames * sourceBlock;
        var bytes = ArrayPool<byte>.Shared.Rent(bytesNeeded);
        try
        {
            var read = _source.Read(bytes, 0, bytesNeeded);
            var got = read / sourceBlock;
            if (got <= 0)
                return 0;

            if (source.Channels == WaveFormat.Channels)
            {
                ConvertToFloat(bytes, got, destination, offset);
                return got;
            }

            var temp = ArrayPool<float>.Shared.Rent(got * source.Channels);
            try
            {
                ConvertToFloat(bytes, got, temp, 0);
                Remix(temp, got, destination, offset);
                return got;
            }
            finally
            {
                ArrayPool<float>.Shared.Return(temp);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(bytes);
        }
    }

    private void ConvertToFloat(byte[] bytes, int frames, float[] destination, int offset)
    {
        var channels = _source.WaveFormat.Channels;
        var index = offset;
        if (TrackLoader.IsFloat(_source.WaveFormat))
        {
            for (var i = 0; i < frames * channels; i++)
                destination[index++] = BitConverter.ToSingle(bytes, i * 4);
            return;
        }

        var bits = _source.WaveFormat.BitsPerSample;
        if (bits == 16)
        {
            for (var i = 0; i < frames * channels; i++)
                destination[index++] = BitConverter.ToInt16(bytes, i * 2) / 32768f;
            return;
        }

        if (bits == 24)
        {
            var cursor = 0;
            for (var i = 0; i < frames * channels; i++)
            {
                var value = bytes[cursor] | (bytes[cursor + 1] << 8) | (bytes[cursor + 2] << 16);
                cursor += 3;
                if ((value & 0x800000) != 0)
                    value |= unchecked((int)0xFF000000);
                destination[index++] = value / 8388608f;
            }

            return;
        }

        for (var i = 0; i < frames * channels; i++)
            destination[index++] = BitConverter.ToInt32(bytes, i * 4) / 2147483648f;
    }

    private void Remix(float[] source, int frames, float[] destination, int offset)
    {
        var from = _source.WaveFormat.Channels;
        var to = WaveFormat.Channels;
        var input = 0;
        var output = offset;
        for (var frame = 0; frame < frames; frame++)
        {
            if (from == 1 && to >= 2)
            {
                var sample = source[input];
                destination[output] = sample;
                destination[output + 1] = sample;
                for (var channel = 2; channel < to; channel++)
                    destination[output + channel] = sample;
            }
            else if (from >= 2 && to == 1)
            {
                destination[output] = (source[input] + source[input + 1]) * 0.5f;
            }
            else
            {
                var copy = Math.Min(from, to);
                for (var channel = 0; channel < copy; channel++)
                    destination[output + channel] = source[input + channel];
                for (var channel = copy; channel < to; channel++)
                    destination[output + channel] = 0;
            }

            input += from;
            output += to;
        }
    }

    private void WriteSamples(float[] samples, int frames, byte[] buffer, int offset)
    {
        var channels = WaveFormat.Channels;
        var count = frames * channels;
        if (TrackLoader.IsFloat(WaveFormat))
        {
            for (var i = 0; i < count; i++)
            {
                var bits = BitConverter.SingleToInt32Bits(samples[i]);
                buffer[offset++] = (byte)bits;
                buffer[offset++] = (byte)(bits >> 8);
                buffer[offset++] = (byte)(bits >> 16);
                buffer[offset++] = (byte)(bits >> 24);
            }

            return;
        }

        var bitsPerSample = WaveFormat.BitsPerSample;
        if (bitsPerSample == 16)
        {
            for (var i = 0; i < count; i++)
            {
                var value = Clamp16(samples[i]);
                buffer[offset++] = (byte)value;
                buffer[offset++] = (byte)(value >> 8);
            }

            return;
        }

        if (bitsPerSample == 24)
        {
            for (var i = 0; i < count; i++)
            {
                var value = Clamp24(samples[i]);
                buffer[offset++] = (byte)value;
                buffer[offset++] = (byte)(value >> 8);
                buffer[offset++] = (byte)(value >> 16);
            }

            return;
        }

        for (var i = 0; i < count; i++)
        {
            var value = Clamp32(samples[i]);
            buffer[offset++] = (byte)value;
            buffer[offset++] = (byte)(value >> 8);
            buffer[offset++] = (byte)(value >> 16);
            buffer[offset++] = (byte)(value >> 24);
        }
    }

    private static string NoteFor(WaveFormat source, WaveFormat output)
    {
        if (source.SampleRate != output.SampleRate)
            return $"源 {source.SampleRate / 1000.0:0.###} kHz";
        return TrackLoader.IsFloat(source)
            ? $"源 {source.BitsPerSample} bit float"
            : $"源 {source.BitsPerSample} bit";
    }

    private static short Clamp16(float sample)
    {
        if (sample >= 1f)
            return 32767;
        if (sample <= -1f)
            return -32768;
        return (short)Math.Round(sample * 32767.0);
    }

    private static int Clamp24(float sample)
    {
        if (sample >= 1f)
            return 8_388_607;
        if (sample <= -1f)
            return -8_388_608;
        return (int)Math.Round(sample * 8_388_607.0);
    }

    private static int Clamp32(float sample)
    {
        if (sample >= 1f)
            return int.MaxValue;
        if (sample <= -1f)
            return int.MinValue;
        return (int)Math.Round(sample * 2147483647.0);
    }
}
