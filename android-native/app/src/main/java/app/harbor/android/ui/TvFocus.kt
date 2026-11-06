package app.harbor.android.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** An Android TV (or another leanback device). */
fun isTv(context: Context): Boolean = isRealTv(context)

/** Android itself says it's a TV: driven by a remote, no touch screen. */
fun isRealTv(context: Context): Boolean {
    val ui = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return ui == Configuration.UI_MODE_TYPE_TELEVISION || context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/**
 * The press ripple every clickable has, plus a bright fill and outline while it has focus, so a
 * remote's D-pad shows where it is (the ripple's own focus tint is barely visible on Harbor's dark
 * surfaces). Shapes clipped before `clickable` clip the outline too.
 */
object HarborIndication : IndicationNodeFactory {
    private val press = ripple()

    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusNode(interactionSource, press.create(interactionSource))

    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7

    private class FocusNode(private val source: InteractionSource, ripple: DelegatableNode) : DelegatingNode(), DrawModifierNode {
        private var focused = false

        init {
            delegate(ripple)
        }

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    val now = when (interaction) {
                        is FocusInteraction.Focus -> true
                        is FocusInteraction.Unfocus -> false
                        else -> return@collect
                    }
                    if (now != focused) {
                        focused = now
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (!focused) return
            drawRect(Color.White.copy(alpha = 0.16f))
            drawRect(Color.White, style = Stroke(2.5.dp.toPx()))
        }
    }
}
