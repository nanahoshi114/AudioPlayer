package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 多声道样本混成单声道：交错存放时逐帧平均，单声道时原样返回。 */
internal fun toMono(samples: FloatArray, channels: Int): FloatArray {
    if (channels <= 1) return samples
    val frames = samples.size / channels
    val mono = FloatArray(frames)
    for (frame in 0 until frames) {
        var sum = 0f
        for (channel in 0 until channels) {
            sum += samples[frame * channels + channel]
        }
        mono[frame] = sum / channels
    }
    return mono
}

/**
 * 线性插值重采样。[carry] 是上一块结束时剩下的相位小数。块间插值需要上一块的最后一个
 * 样本做锚点：调用方把上一块最后一个样本拼在本块开头再传入。
 * 返回结果和本块结束时的新相位。采样率相同就直接返回原样。
 */
internal fun resampleLinear(
    samples: FloatArray,
    inRate: Int,
    outRate: Int,
    carry: Float = 0f,
): Pair<FloatArray, Float> {
    if (inRate == outRate || samples.isEmpty()) return samples to carry
    val step = inRate.toFloat() / outRate.toFloat()
    val out = FloatArray((samples.size / step).toInt() + 1)
    var position = carry
    var count = 0
    while (position < samples.size - 1) {
        val index = position.toInt()
        val frac = position - index
        out[count++] = samples[index] + (samples[index + 1] - samples[index]) * frac
        position += step
    }
    return out.copyOf(count) to (position - (samples.size - 1)).coerceAtLeast(0f)
}

/** 按指定 PCM 编码把小端字节缓冲区解码为 [-1, 1] 交错 float 样本。 */
internal fun decodePcmSamples(buffer: ByteBuffer, byteCount: Int, encoding: Int): FloatArray {
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    return when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> {
            val count = byteCount / 2
            FloatArray(count) { buffer.short / 32768f }
        }
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val count = byteCount / 4
            FloatArray(count) { buffer.float }
        }
        else -> throw WhisperException(TextRef.Res(R.string.whisper_error_audio_decode))
    }
}

/**
 * 把音频文件流式解码成 16kHz PCM float（[-1, 1]）。默认混成单声道，可保留恰好两个声道。
 */
class AudioPcmReader(file: File, private val keepStereo: Boolean = false) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var channels: Int
    private var sourceRate: Int
    private var pcmEncoding: Int
    private val outputStereo: Boolean
    val isStereo: Boolean get() = outputStereo
    private val resampleCarry = FloatArray(2)
    private val resampleAnchor = arrayOfNulls<Float>(2)
    private var pending = emptyArray<FloatArray>()
    private var pendingOffset = 0
    private var inputDone = false
    private var outputDone = false
    private var released = false

    init {
        // 构造期任何一步失败都要释放已创建的原生句柄；全部成功后才赋给 val codec。
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw WhisperException(TextRef.Res(R.string.whisper_error_audio_decode))
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            outputStereo = keepStereo && channels == 2
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            pcmEncoding = readPcmEncoding(decoder.outputFormat)
            codec = decoder
        } catch (error: Exception) {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            throw if (error is WhisperException) error else WhisperException(TextRef.Res(R.string.whisper_error_audio_decode))
        }
    }

    /** 读取下一块 16kHz 单声道样本；读完返回 null。 */
    fun readChunk(sampleCount: Int = CHUNK_SAMPLES): FloatArray? = readChannels(sampleCount)?.single()

    /** 各声道的采样位置和返回长度一致。 */
    internal fun readChannels(sampleCount: Int): Array<FloatArray>? {
        require(sampleCount > 0)
        val out = Array(if (isStereo) 2 else 1) { FloatArray(sampleCount) }
        var filled = 0
        while (filled < sampleCount) {
            filled += drainPending(out, filled)
            if (filled >= sampleCount) break
            if (outputDone && pending.isEmpty()) break
            pump()
        }
        if (filled == 0) return null
        return if (filled == sampleCount) out else Array(out.size) { out[it].copyOf(filled) }
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private fun drainPending(out: Array<FloatArray>, offset: Int): Int {
        val available = (pending.firstOrNull()?.size ?: 0) - pendingOffset
        if (available <= 0) return 0
        val take = minOf(available, out[0].size - offset)
        for (channel in out.indices) {
            System.arraycopy(pending[channel], pendingOffset, out[channel], offset, take)
        }
        pendingOffset += take
        if (pendingOffset >= pending[0].size) {
            pending = emptyArray()
            pendingOffset = 0
        }
        return take
    }

    private fun pump() {
        feedInput()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                applyOutputFormat(codec.outputFormat)
                continue
            }
            if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)!!
                if (info.size > 0) {
                    buffer.limit(info.offset + info.size)
                    buffer.position(info.offset)
                    val interleaved = decodePcmSamples(buffer, info.size, pcmEncoding)
                    val decoded = if (outputStereo) {
                        if (channels == 2) {
                            val frames = interleaved.size / 2
                            Array(2) { channel -> FloatArray(frames) { interleaved[it * 2 + channel] } }
                        } else {
                            val mono = toMono(interleaved, channels)
                            arrayOf(mono, mono.copyOf())
                        }
                    } else {
                        arrayOf(toMono(interleaved, channels))
                    }
                    pending = Array(decoded.size) { resampleChannel(decoded[it], it) }
                    if (pending[0].isEmpty()) pending = emptyArray()
                    pendingOffset = 0
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    return
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                    return
                }
            }
        }
    }

    private fun applyOutputFormat(outputFormat: MediaFormat) {
        if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        }
        if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            val nextRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            if (nextRate != sourceRate) {
                sourceRate = nextRate
                resampleCarry.fill(0f)
                resampleAnchor.fill(null)
            }
        }
        pcmEncoding = readPcmEncoding(outputFormat)
    }

    private fun readPcmEncoding(format: MediaFormat): Int {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            throw WhisperException(TextRef.Res(R.string.whisper_error_audio_decode))
        }
        return encoding
    }

    private fun resampleChannel(samples: FloatArray, channel: Int): FloatArray {
        if (samples.isEmpty() || sourceRate == TARGET_RATE) return samples
        val anchor = resampleAnchor[channel]
        resampleAnchor[channel] = samples.last()
        val input = if (anchor == null) samples else floatArrayOf(anchor) + samples
        val (out, carry) = resampleLinear(input, sourceRate, TARGET_RATE, resampleCarry[channel])
        resampleCarry[channel] = carry
        return out
    }

    private fun feedInput() {
        if (inputDone) return
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
                return
            }
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private companion object {
        const val TARGET_RATE = 16000
        const val CHUNK_SAMPLES = 16000
        const val TIMEOUT_US = 10_000L
    }
}
