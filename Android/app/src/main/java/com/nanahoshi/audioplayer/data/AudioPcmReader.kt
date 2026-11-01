package com.nanahoshi.audioplayer.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
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
 * 样本做锚点：调用方把上一块最后一个样本拼在本块开头再传入（见 [AudioPcmReader.pump]）。
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

/**
 * 把音频文件流式解码成 16kHz 单声道 PCM float（[-1, 1]）。每次 [readChunk] 只占一块内存，
 * 不会把整首音频读进内存；供本机听写模型按段消费。
 */
class AudioPcmReader(file: File) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val channels: Int
    private val sourceRate: Int
    private var resampleCarry = 0f
    private var resampleAnchor: Float? = null
    private var pending = FloatArray(0)
    private var pendingOffset = 0
    private var inputDone = false
    private var outputDone = false
    private var released = false

    init {
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (error: Exception) {
            throw WhisperException("音频解码失败")
        }
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
        if (trackIndex < 0 || format == null) throw WhisperException("音频解码失败")
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
        codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Exception) {
            throw WhisperException("音频解码失败")
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** 读取下一块 16kHz 单声道样本；读完返回 null。 */
    fun readChunk(): FloatArray? {
        val out = FloatArray(CHUNK_SAMPLES)
        var filled = 0
        while (filled < CHUNK_SAMPLES) {
            filled += drainPending(out, filled)
            if (filled >= CHUNK_SAMPLES) break
            if (outputDone && pendingOffset >= pending.size) break
            pump()
        }
        if (filled == 0) return null
        return if (filled == CHUNK_SAMPLES) out else out.copyOf(filled)
    }

    fun close() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        extractor.release()
    }

    private fun drainPending(out: FloatArray, offset: Int): Int {
        val available = pending.size - pendingOffset
        if (available <= 0) return 0
        val take = minOf(available, out.size - offset)
        System.arraycopy(pending, pendingOffset, out, offset, take)
        pendingOffset += take
        if (pendingOffset >= pending.size) {
            pending = FloatArray(0)
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
            if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)!!
                if (info.size > 0) {
                    val shorts = ShortArray(info.size / 2)
                    buffer.position(info.offset)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in shorts.indices) {
                        shorts[i] = buffer.short
                    }
                    val floats = toMono(FloatArray(shorts.size) { shorts[it] / 32768f }, channels)
                    val resampled: FloatArray = if (floats.isEmpty() || sourceRate == TARGET_RATE) {
                        floats
                    } else {
                        val anchor = resampleAnchor
                        resampleAnchor = floats[floats.size - 1]
                        val input = if (anchor == null) floats else floatArrayOf(anchor) + floats
                        val (out, carry) = resampleLinear(input, sourceRate, TARGET_RATE, resampleCarry)
                        resampleCarry = carry
                        out
                    }
                    pending = resampled
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
            // INFO_OUTPUT_FORMAT_CHANGED 等负值继续循环
        }
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
