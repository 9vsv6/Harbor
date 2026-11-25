package app.harbor.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.Meta
import app.harbor.android.data.Video
import app.harbor.android.i18n.Text
import coil.compose.AsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * Harbor PC's Connecting screen (play-picker/auto-play-transition.tsx), for phones and tablets:
 * the title's art blurred and dark behind a black veil, its logo pulsing, the episode, Harbor's
 * sailing boat with CONNECTING under it, and Cancel at the bottom.
 */
@Composable
internal fun HarborConnecting(meta: Meta?, episode: Video?, caption: String = "Connecting", onCancel: () -> Unit) {
    // Taps don't reach the page underneath while a source connects.
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        (episode?.thumbnail ?: meta?.background ?: meta?.poster)?.let { art ->
            // opacity-40 blur-[28px] saturate-150
            AsyncImage(
                art, null, contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.5f) }),
                modifier = Modifier.fillMaxSize().then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(28.dp) else Modifier).alpha(0.4f),
            )
        }
        // from-black/65 via-black/55 to-black/85
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.85f))))

        val pulse by rememberInfiniteTransition(label = "connecting").animateFloat(
            0.42f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
        )
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // A phone held sideways (the player) has little height: everything shrinks to fit above Cancel.
        val short = maxHeight < 520.dp
        val cancelBottom = if (short) 14.dp else 40.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(top = if (short) 12.dp else 0.dp, bottom = cancelBottom + 56.dp),
            verticalArrangement = Arrangement.spacedBy(if (short) 10.dp else 28.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var badLogo by remember(meta?.id) { mutableStateOf(false) }
            val logo = meta?.logoUrl
            if (logo != null && !badLogo) {
                AsyncImage(
                    logo, meta.name, contentScale = ContentScale.Fit, onError = { badLogo = true },
                    modifier = Modifier.fillMaxWidth(if (short) 0.42f else 0.72f).heightIn(max = if (short) 64.dp else 140.dp).alpha(pulse),
                )
            } else meta?.name?.let {
                androidx.compose.material3.Text(it, color = Color.White, fontSize = if (short) 28.sp else 44.sp, lineHeight = if (short) 28.sp else 42.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.alpha(pulse))
            }
            episode?.let { v ->
                val s = v.season; val e = v.episode
                if (s != null && e != null) {
                    val name = (v.title ?: v.name)?.takeIf { it.isNotBlank() && app.harbor.android.data.EpisodeText.specificTitle(it, e) != null }
                    androidx.compose.material3.Text(
                        ("S$s · E${e.toString().padStart(2, '0')}" + (name?.let { " · $it" } ?: "")).uppercase(),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp,
                        textAlign = TextAlign.Center, maxLines = 2,
                    )
                }
            }
            HarborLoader(caption, if (short) 72.dp else 128.dp)
        }

        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = cancelBottom).height(44.dp)
                .clip(CircleShape).background(Color(0xFF34343B)).clickable(onClick = onCancel).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Close, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
            Text("Cancel", color = Color.White.copy(alpha = 0.85f), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        }
        }
    }
    androidx.activity.compose.BackHandler(onBack = onCancel)
}

/** PC's HarborLoader (components/harbor-loader.tsx, size md): the sailing boat and its caption. */
@Composable
internal fun HarborLoader(caption: String?, size: androidx.compose.ui.unit.Dp = 128.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.harbor_loader))
        LottieAnimation(composition, iterations = LottieConstants.IterateForever, modifier = Modifier.size(size))
        caption?.let {
            androidx.compose.material3.Text(app.harbor.android.i18n.I18n.tr(it).uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.25.sp)
        }
    }
}
