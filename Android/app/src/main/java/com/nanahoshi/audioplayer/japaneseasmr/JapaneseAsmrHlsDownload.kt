package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.UserFacingIOException
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.nanahoshi.audioplayer.data.ProgressPhase
import com.nanahoshi.audioplayer.data.TaskReporter
import com.nanahoshi.audioplayer.data.taskProgress
import com.nanahoshi.audioplayer.data.withResponse
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.Companion.isChallenge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.abs

internal class JapaneseAsmrHlsDownload(private val http: OkHttpClient) {
    suspend fun download(url: String, staging: File, destination: File, report: TaskReporter): Long? {
        val manifest = http.newCall(JapaneseAsmrClient.mediaRequest(url).taskProgress(report).build())
            .withResponse { response ->
                if (response.expired()) return@withResponse null
                parseJaPlaylist(response.body?.string().orEmpty(), response.request.url.toString())
            } ?: return null
        val fingerprint = manifest.fingerprint
        val marker = File(staging, "playlist.sha256")
        if (marker.takeIf(File::isFile)?.readText() != fingerprint) {
            if (staging.exists() && !staging.deleteRecursively()) {
                throw UserFacingException(TextRef.Res(R.string.ja_error_hls_update_staging))
            }
            if (!staging.mkdirs()) {
                throw UserFacingException(TextRef.Res(R.string.ja_error_hls_create_staging))
            }
            marker.writeText(fingerprint)
        }
        val total = manifest.segments.size
        val files = ArrayList<File>(total)
        var bytes = 0L
        var downloadedSegment = false
        val progress = report.phase(ProgressPhase.DOWNLOADING)
        for ((index, segment) in manifest.segments.withIndex()) {
            currentCoroutineContext().ensureActive()
            val file = File(staging, "seg-$index.ts")
            report.units(index.toLong(), total.toLong(), detail = TextRef.Res(R.string.progress_detail_segments, listOf(index + 1, total)))
            files += file
            if (!file.validTs()) {
                if (!fetchSegment(segment.url, file, report, bytes, progress)) return null
                downloadedSegment = true
            }
            bytes += file.length()
            report.units((index + 1).toLong(), total.toLong(), detail = TextRef.Res(R.string.progress_detail_segments, listOf(index + 1, total)))
        }
        report.phase(ProgressPhase.MUXING)
        try {
            muxJaSegments(files, destination)
            val probe = com.nanahoshi.audioplayer.data.LocalAudio.probeAudio(destination.absolutePath)
                val duration = probe?.durationMs
                if (probe?.hasAudio != true || duration == null || abs(duration - manifest.durationMs) > 2_000) {
                    throw UserFacingException(TextRef.Res(R.string.ja_error_hls_output_incomplete))
                }
                if (!downloadedSegment) report.reusedIfNoTransfer()
                return duration
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private suspend fun fetchSegment(
        url: String, file: File, report: TaskReporter, priorBytes: Long,
        progress: com.nanahoshi.audioplayer.data.TransferProgress,
    ): Boolean {
        val part = File(file.parentFile, file.name + ".part")
        try {
            repeat(3) { attempt ->
                try {
                    return http.newCall(JapaneseAsmrClient.mediaRequest(url)
                        .header("Accept-Encoding", "identity").taskProgress(report).build()).withResponse { response ->
                        if (response.expired()) return@withResponse false
                        val body = response.body ?: throw UserFacingIOException(TextRef.Res(R.string.ja_error_hls_segment_missing))
                        val total = com.nanahoshi.audioplayer.data.HttpByteRange.parse(response.header("Content-Range"))?.total
                            ?: body.contentLength().takeIf { it >= 0 }
                        body.byteStream().use { input ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    progress.bytes(read.toLong(), priorBytes + done, null)
                                }
                                if (total != null && done != total) throw UserFacingIOException(TextRef.Res(R.string.ja_error_hls_segment_incomplete))
                            }
                        }
                        if (!part.validTs()) throw UserFacingIOException(TextRef.Res(R.string.ja_error_hls_segment_corrupt))
                        if (!part.renameTo(file)) {
                            throw UserFacingException(TextRef.Res(R.string.ja_error_hls_save_segment))
                        }
                        true
                    }
                } catch (failure: JapaneseAsmrException) {
                    throw failure
                } catch (_: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (attempt == 2) throw UserFacingException(TextRef.Res(R.string.ja_error_hls_segment_failed))
                    report.retry()
                }
            }
        } finally {
            part.delete()
        }
        throw UserFacingException(TextRef.Res(R.string.ja_error_hls_segment_failed))
    }

    private fun Response.expired(): Boolean {
        if (isChallenge(if (code == 403) peekBody(4096).string() else "")) throw JapaneseAsmrException(JaFailure.VERIFY)
        if (code == 403 || code == 404) return true
        if (!isSuccessful) throw JapaneseAsmrException(if (code >= 500) JaFailure.SERVER else JaFailure.MEDIA)
        return false
    }
}

private fun File.validTs(): Boolean {
    if (!isFile || length() == 0L || length() % 188L != 0L) return false
    return inputStream().buffered().use { input ->
        val packet = ByteArray(188)
        while (true) {
            val first = input.read()
            if (first == -1) return@use true
            if (first != 0x47) return@use false
            var read = 1
            while (read < packet.size) {
                val count = input.read(packet, read, packet.size - read)
                if (count == -1) return@use false
                read += count
            }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
}

@OptIn(UnstableApi::class)
private suspend fun muxJaSegments(segments: List<File>, output: File) {
    val context = currentCoroutineContext()
    val extractor = TsExtractor(
        TsExtractor.MODE_HLS, 0, SubtitleParser.Factory.UNSUPPORTED, TimestampAdjuster(0),
        DefaultTsPayloadReaderFactory(0), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
    )
    try {
        output.outputStream().use { fileOutput ->
            Mp4Muxer.Builder(SeekableMuxerOutput.of(fileOutput)).build().use { muxer ->
                val audio = AacTrack(muxer)
                extractor.init(object : ExtractorOutput {
                    private var audioId: Int? = null
                    override fun track(id: Int, type: Int): TrackOutput {
                        if (type != C.TRACK_TYPE_AUDIO) return DiscardingTrackOutput()
                        if (audioId != null && audioId != id) throw UserFacingException(TextRef.Res(R.string.ja_error_hls_multiple_audio))
                        audioId = id
                        return audio
                    }
                    override fun endTracks() = Unit
                    override fun seekMap(seekMap: SeekMap) = Unit
                })
                // MODE_HLS 顺序读取，保留跨分片的时间戳调整状态。
                var next = 0
                var current: InputStream? = null
                try {
                    val reader = DataReader { buffer, offset, length ->
                        context.ensureActive()
                        var count = -1
                        while (true) {
                            val stream = current ?: segments.getOrNull(next++)?.inputStream()?.buffered()
                            current = stream
                            if (stream == null) break
                            count = stream.read(buffer, offset, length)
                            if (count >= 0) break
                            stream.close()
                            current = null
                        }
                        count
                    }
                    val input = DefaultExtractorInput(reader, 0L, C.LENGTH_UNSET.toLong())
                    val position = PositionHolder()
                    while (true) {
                        context.ensureActive()
                        when (extractor.read(input, position)) {
                            Extractor.RESULT_END_OF_INPUT -> break
                            Extractor.RESULT_SEEK -> throw UserFacingException(TextRef.Res(R.string.ja_error_hls_sequential_read))
                        }
                    }
                    if (!audio.hasSamples) {
                        throw UserFacingException(TextRef.Res(R.string.ja_error_hls_unsupported))
                    }
                } finally {
                    current?.close()
                }
            }
        }
    } finally {
        extractor.release()
    }
}

@OptIn(UnstableApi::class)
private class AacTrack(private val muxer: Mp4Muxer) : TrackOutput {
    // 复用一块可增长的待写缓冲：采样帧约 43 帧/秒，逐帧分配与 BAOS 扩容
    // 都会在长音频上产生可观的临时垃圾。
    private var pending = ByteArray(INITIAL_SAMPLE_BUFFER)
    private var pendingSize = 0
    private var trackId: Int? = null
    private var firstTime: Long? = null
    private var previousTime = -1L
    val hasSamples get() = previousTime >= 0

    override fun format(format: Format) {
        if (format.sampleMimeType != MimeTypes.AUDIO_AAC) {
            throw UserFacingException(TextRef.Res(R.string.ja_error_hls_unsupported))
        }
        if (trackId == null) trackId = muxer.addTrack(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        ensureCapacity(pendingSize + length)
        val count = input.read(pending, pendingSize, length)
        if (count == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
        if (count > 0) pendingSize += count
        return count
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        ensureCapacity(pendingSize + length)
        data.readBytes(pending, pendingSize, length)
        pendingSize += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val start = pendingSize - offset - size
        if (start < 0 || size <= 0 || cryptoData != null) {
            throw UserFacingException(TextRef.Res(R.string.ja_error_hls_sample_read))
        }
        val first = firstTime ?: timeUs.also { firstTime = it }
        val time = timeUs - first
        if (time <= previousTime) {
            throw UserFacingException(TextRef.Res(R.string.ja_error_hls_discontinuous))
        }
        val data = ByteBuffer.wrap(pending, start, size).slice()
        muxer.writeSampleData(requireNotNull(trackId), data, BufferInfo(time, size, flags))
        previousTime = time
        // 末尾多余的 offset 字节属于下一帧；先搬回缓冲头部再继续累积。
        if (offset > 0) System.arraycopy(pending, start + size, pending, 0, offset)
        pendingSize = offset
    }

    private fun ensureCapacity(required: Int) {
        if (required <= pending.size) return
        var size = pending.size
        while (size < required) size = if (size > Int.MAX_VALUE / 2) required else size shl 1
        pending = pending.copyOf(size)
    }

    private companion object {
        const val INITIAL_SAMPLE_BUFFER = 64 * 1024
    }
}
