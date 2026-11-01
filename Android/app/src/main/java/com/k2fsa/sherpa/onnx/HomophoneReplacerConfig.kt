// Vendored from https://github.com/k2-fsa/sherpa-onnx tag v2.0.1 (sherpa-onnx/kotlin-api/HomophoneReplacerConfig.kt), Apache-2.0. 只随 sherpa-onnx 版本更新，不做本地修改。
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
