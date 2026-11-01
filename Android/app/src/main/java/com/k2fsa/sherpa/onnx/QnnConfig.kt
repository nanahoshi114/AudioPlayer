// Vendored from https://github.com/k2-fsa/sherpa-onnx tag v2.0.1 (sherpa-onnx/kotlin-api/QnnConfig.kt), Apache-2.0. 只随 sherpa-onnx 版本更新，不做本地修改。
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
