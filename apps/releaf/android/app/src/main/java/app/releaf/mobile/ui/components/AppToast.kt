/*
 * AppToast.kt
 *
 * In-app toast surface. Replaces `android.widget.Toast` — the
 * platform default paints a Material system pill that ignores the
 * app's cream-and-coral palette and typography, so it reads as "this
 * message is coming from outside the app" every time it fires.
 *
 * The host renders a single [ToastState]-driven card near the bottom
 * of the screen; calling [ToastState.show] replaces the active
 * message and schedules auto-dismiss. Use [rememberToastState] at the
 * screen root and hoist the `show` action into sub-composables.
 */

package app.releaf.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handle returned from [rememberToastState]. Each [show] replaces the
 * active message — subsequent shows cancel the prior auto-dismiss so
 * the messages don't stack.
 */
@Stable
class ToastState internal constructor(
    private val scope: CoroutineScope,
) {
    internal var message: String? by mutableStateOf(null)
        private set

    private var autoDismiss: Job? = null

    fun show(message: String, durationMs: Long = 2_000L) {
        this.message = message
        autoDismiss?.cancel()
        autoDismiss = scope.launch {
            delay(durationMs)
            // Guard against a newer show() beating the cancel().
            if (this@ToastState.message == message) {
                this@ToastState.message = null
            }
        }
    }

    fun clear() {
        autoDismiss?.cancel()
        message = null
    }
}

@Composable
fun rememberToastState(scope: CoroutineScope): ToastState =
    remember(scope) { ToastState(scope) }

/**
 * Host composable. Place as a sibling at the leaf of a screen so it
 * layers above content — typically inside the same Box that holds the
 * screen's body. The pill floats from the bottom; [padding] leaves
 * room for whatever persistent chrome (format bar, bottom nav, etc.)
 * the screen has pinned to the bottom edge.
 */
@Composable
fun AppToastHost(
    state: ToastState,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(
        horizontal = AppSpacing.s4,
        vertical   = AppSpacing.s6,
    ),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = state.message != null,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit  = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(AppRadius.pill))
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.CardSolid)
                    .border(
                        width = 1.dp,
                        color = AppColors.BorderDefault,
                        shape = RoundedCornerShape(AppRadius.pill),
                    )
                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            ) {
                Text(
                    text  = state.message.orEmpty(),
                    style = AppTypography.Body,
                    color = AppColors.TextPrimary,
                )
            }
        }
    }
}
