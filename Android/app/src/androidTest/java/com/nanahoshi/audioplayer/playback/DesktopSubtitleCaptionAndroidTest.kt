package com.nanahoshi.audioplayer.playback

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopSubtitleCaptionAndroidTest {
    @Test
    fun longSentenceWrapsWithoutEllipsis() = onMain { context ->
        val caption = caption(context)
        caption.label.text = "长句可以在桌面字幕里自动换行。".repeat(5)
        val width = context.dp(320)
        val height = caption.fitHeight(width, context.dp(800), controlsHeight = 0)
        layout(caption, width, height)

        assertTrue(caption.label.lineCount > 2)
        assertEquals(caption.height, caption.label.height)
        assertEquals(caption.label.text.length, caption.label.layout.getLineEnd(caption.label.lineCount - 1))
        assertTrue((0 until caption.label.lineCount).all { caption.label.layout.getEllipsisCount(it) == 0 })
    }

    @Test
    fun overflowingCaptionScrollsToEndAndResetsForShorterText() = onMain { context ->
        val caption = caption(context)
        caption.label.text = "字幕が長くても、画面の中で続きを表示できます。".repeat(30)
        val width = context.dp(320)
        val safeHeight = context.dp(300)
        val controls = context.dp(48)
        val height = caption.fitHeight(width, safeHeight, controls)
        layout(caption, width, height)
        assertEquals(safeHeight / 2, caption.height)
        assertTrue(caption.height + controls <= safeHeight)
        assertTrue(caption.label.height > caption.height)

        caption.follow(1f)
        assertEquals(caption.label.height - caption.height, caption.scrollY)

        caption.label.text = "下一句"
        caption.follow(0f)
        layout(caption, width, caption.fitHeight(width, safeHeight, controls))
        assertEquals(0, caption.scrollY)
        assertEquals(1, caption.label.lineCount)
    }

    private fun onMain(test: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result = Result.success(Unit)
        instrumentation.runOnMainSync { result = runCatching { test(instrumentation.targetContext) } }
        result.getOrThrow()
    }

    private fun caption(context: Context) = DesktopSubtitleCaption(context).also {
        FrameLayout(context).addView(it, FrameLayout.LayoutParams(-1, -2))
    }

    private fun layout(caption: DesktopSubtitleCaption, width: Int, height: Int) {
        caption.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        caption.layout(0, 0, width, height)
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
