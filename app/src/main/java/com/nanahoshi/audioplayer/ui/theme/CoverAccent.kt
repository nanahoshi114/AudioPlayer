package com.nanahoshi.audioplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nanahoshi.audioplayer.data.toHttps
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前播放封面的主色。没有封面、封面取不出彩色时，保持上一次取到的颜色。
 * [coverUri] 传 null 表示不取色。
 */
@Composable
fun rememberCoverAccent(coverUri: String?): Color? {
    val context = LocalContext.current
    var accent by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(coverUri) {
        if (coverUri.isNullOrBlank()) return@LaunchedEffect
        extractCoverAccent(context, coverUri)?.let { accent = it }
    }
    return accent
}

private suspend fun extractCoverAccent(context: Context, uri: String): Color? = withContext(Dispatchers.Default) {
    try {
        val model: Any = if (uri.startsWith("/")) File(uri) else uri.toHttps()
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(64)
            .allowHardware(false)
            .build()
        val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
            ?: return@withContext null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap(64, 64)
        dominantColor(bitmap)
    } catch (_: Exception) {
        null
    }
}

/** 按色相分 12 档，取「饱和度 × 亮度」加权最大的一档，返回这一档的平均色。灰、近黑、近白的像素不计。 */
internal fun dominantColor(bitmap: Bitmap): Color? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val weight = DoubleArray(BUCKETS)
    val red = DoubleArray(BUCKETS)
    val green = DoubleArray(BUCKETS)
    val blue = DoubleArray(BUCKETS)
    val hsv = FloatArray(3)
    for (pixel in pixels) {
        if ((pixel ushr 24) < 200) continue
        android.graphics.Color.colorToHSV(pixel, hsv)
        val s = hsv[1]
        val v = hsv[2]
        if (s < 0.25f || v < 0.25f || v > 0.97f) continue
        val bucket = (hsv[0] / (360f / BUCKETS)).toInt().coerceIn(0, BUCKETS - 1)
        val w = (s * v).toDouble()
        weight[bucket] += w
        red[bucket] += android.graphics.Color.red(pixel) * w
        green[bucket] += android.graphics.Color.green(pixel) * w
        blue[bucket] += android.graphics.Color.blue(pixel) * w
    }
    var best = -1
    for (i in 0 until BUCKETS) {
        if (best < 0 || weight[i] > weight[best]) best = i
    }
    // 封面几乎是黑白灰时，当作没有主色。
    if (best < 0 || weight[best] < pixels.size * 0.002) return null
    val w = weight[best]
    return Color(
        red = (red[best] / w / 255.0).toFloat(),
        green = (green[best] / w / 255.0).toFloat(),
        blue = (blue[best] / w / 255.0).toFloat(),
    )
}

private const val BUCKETS = 12
