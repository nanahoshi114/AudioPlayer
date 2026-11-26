// Vendored from https://github.com/k2-fsa/sherpa-onnx tag v2.0.1 (sherpa-onnx/kotlin-api/OfflineStream.kt), Apache-2.0.
// JNI 兼容性：ptr 字段及本文件中的 external native 方法签名由 libsherpa-onnx-jni.so 直接读取，
// 禁止重命名、增删或调整；仅移除了上游 demo 中未使用的 setOption/getOption/use 封装。
package com.k2fsa.sherpa.onnx

class OfflineStream(var ptr: Long) {
    init {
        require(ptr != 0L) { "Failed to create native OfflineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun setOption(ptr: Long, key: String, value: String)
    private external fun getOption(ptr: Long, key: String): String
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
