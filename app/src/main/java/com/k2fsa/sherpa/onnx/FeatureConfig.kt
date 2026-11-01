// Vendored from https://github.com/k2-fsa/sherpa-onnx tag v2.0.1 (sherpa-onnx/kotlin-api/FeatureConfig.kt), Apache-2.0. 只随 sherpa-onnx 版本更新，不做本地修改。
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
