package com.nanahoshi.audioplayer.data

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.util.concurrent.atomic.AtomicInteger

internal class ProgressRequestTag(val reporter: TaskReporter, val download: Boolean)
internal fun Request.Builder.taskProgress(reporter: TaskReporter?, download: Boolean = true): Request.Builder =
    apply { if (reporter != null) tag(ProgressRequestTag::class.java, ProgressRequestTag(reporter, download)) }

internal class ProgressNetworkEvents(private val tag: ProgressRequestTag?) : EventListener() {
    private fun before(phase: ProgressPhase) {
        val report = tag?.takeIf { it.download }?.reporter ?: return
        val current = report.snapshot() ?: return
        if (!current.summary.phase.let { it?.transfer == true }) report.phase(phase)
    }
    override fun callStart(call: Call) = before(ProgressPhase.REQUESTING)
    override fun requestHeadersEnd(call: Call, request: Request) = before(ProgressPhase.WAITING_RESPONSE)
    companion object {
        val factory = EventListener.Factory { call -> ProgressNetworkEvents(call.request().tag(ProgressRequestTag::class.java)) }
    }
}

/** 只包装文件部分，不统计 multipart 表单边界；writeTo 重入时重置本次上传窗口。 */
internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val reporter: TaskReporter,
    private val afterUpload: ProgressPhase = ProgressPhase.WAITING_RESPONSE,
) : RequestBody() {
    private val writes = AtomicInteger()
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = delegate.isOneShot()
    override fun isDuplex(): Boolean = delegate.isDuplex()
    override fun writeTo(sink: BufferedSink) {
        if (writes.getAndIncrement() > 0) reporter.retry()
        val transfer = reporter.phase(ProgressPhase.UPLOADING, force = true)
        var sent = 0L
        val total = contentLength().takeIf { it >= 0 }
        val counted = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                transfer.bytes(byteCount, sent, total)
            }
        }.buffer()
        delegate.writeTo(counted)
        counted.flush()
        reporter.phase(afterUpload)
    }
}
internal fun RequestBody.withUploadProgress(reporter: TaskReporter?, after: ProgressPhase = ProgressPhase.WAITING_RESPONSE): RequestBody =
    if (reporter == null) this else ProgressRequestBody(this, reporter, after)
