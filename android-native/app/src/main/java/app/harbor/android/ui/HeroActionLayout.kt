package app.harbor.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import app.harbor.android.data.heroActionStage

/** Measure each group once, including the expanded groups, to allow unfolding after a resize. */
@Composable
internal fun HeroActionLayout(
    onStage: (Int) -> Unit,
    /** Send to a friend didn't fit on the buttons' line, so ⋯ offers it instead. */
    onShareHidden: (Boolean) -> Unit = {},
    play: @Composable () -> Unit,
    library: @Composable () -> Unit,
    trackers: @Composable () -> Unit,
    rate: @Composable () -> Unit,
    circles: @Composable () -> Unit,
    overflow: @Composable () -> Unit,
    share: @Composable () -> Unit,
) {
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Box { play() }; Box { library() }; Box { trackers() }; Box { rate() }
        Box { circles() }; Box { overflow() }; Box { share() }
    }) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val children = measurables.mapIndexed { index, measurable ->
            measurable.measure(if (index == 2 || index == 4) Constraints() else Constraints(maxWidth = constraints.maxWidth))
        }
        fun visible(indices: List<Int>) = indices.filter { children[it].width > 0 }
        fun width(indices: List<Int>): Int = visible(indices).let { slots -> slots.sumOf { children[it].width } + gap * (slots.size - 1).coerceAtLeast(0) }
        val expanded = listOf(0, 1, 2, 3, 4, 6)
        val folded = listOf(0, 1, 2, 3, 5, 6)
        val stage = heroActionStage(constraints.maxWidth, width(expanded), width(folded))
        onStage(stage)
        val chosen = visible(when (stage) { 0 -> expanded; 1 -> folded; else -> listOf(0, 1, 3, 5, 6) })
        // One line of buttons: Send to a friend goes into ⋯ rather than wrapping onto a line of its own.
        val shareHidden = 6 in chosen && width(chosen) > constraints.maxWidth
        onShareHidden(shareHidden)
        val slots = if (shareHidden) chosen - 6 else chosen
        var x = 0
        var y = 0
        var rowHeight = 0
        val positions = slots.map { index ->
            val child = children[index]
            // Compact phones may need two rows even after folding every optional action.
            if (x > 0 && x + child.width > constraints.maxWidth) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            val position = x to y
            x += child.width + gap
            rowHeight = maxOf(rowHeight, child.height)
            index to position
        }
        layout(constraints.maxWidth, constraints.constrainHeight(y + rowHeight)) {
            positions.forEach { (index, position) -> children[index].placeRelative(position.first, position.second) }
        }
    }
}
