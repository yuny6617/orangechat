package me.rerere.rikkahub.ui.components.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val LocalExportContext = staticCompositionLocalOf { false }

/** For bounded standalone chart captures, not long chat pagination. */
class BitmapComposer(@Suppress("UNUSED_PARAMETER") mainScope: CoroutineScope) {
    suspend fun composableToBitmap(
        activity: Activity,
        width: Dp? = null,
        height: Dp? = null,
        screenDensity: Density,
        content: @Composable () -> Unit,
    ): Bitmap {
        var captured: Bitmap? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                val widthPx = (screenDensity.density * (width?.value ?: 10000f)).roundToInt()
                val heightPx = (screenDensity.density * (height?.value ?: 10000f)).roundToInt()
                require(widthPx > 0 && heightPx > 0)
                val root = activity.window.decorView as ViewGroup
                val container = FrameLayout(activity).apply { visibility = View.INVISIBLE }
                val view = ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                }
                try {
                    container.addView(view)
                    root.addView(container)
                    view.setContent { CompositionLocalProvider(LocalExportContext provides true) { content() } }
                    fun measure() {
                        container.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPx, if (width == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(heightPx, if (height == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY),
                        )
                        check(height != null || container.measuredHeight < heightPx) {
                            "内容超过截图高度，请使用分页导出"
                        }
                        container.layout(0, 0, container.measuredWidth, container.measuredHeight)
                    }
                    measure()
                    // Preserve the original chart composition settling interval; cancellable, no detached Handler callbacks.
                    delay(100)
                    measure()
                    currentCoroutineContext().ensureActive()
                    view.drawToBitmap().also { captured = it }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        view.disposeComposition()
                        root.removeView(container)
                    }
                }
            }
        } catch (error: Throwable) {
            captured?.recycle()
            throw error
        }
    }
}
