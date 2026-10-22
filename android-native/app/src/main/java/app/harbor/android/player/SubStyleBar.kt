package app.harbor.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import app.harbor.android.data.PlayerPrefs
import app.harbor.android.data.SubLook
import app.harbor.android.ui.HarborColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val BarBg = Color(0xFF1C1E22)
private val GroupBg = Color(0xFF2A2C31)
private val Edge = Color(0x24FFFFFF)
private val Ink = Color(0xFFF2F2F3)
private val InkMuted = Color(0xFFB4B6BC)
private val InkSubtle = Color(0xFF7D8088)

/** sub-style-bar/controls.tsx: the six swatches. */
private val SWATCHES = listOf(0xFFFFFFFF, 0xFFFFE45E, 0xFF9AE6B4, 0xFF93C5FD, 0xFFFCA5A5, 0xFFC4B5FD)
private val MORE_COLORS = listOf(0xFF000000, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF5AC8FA, 0xFF007AFF, 0xFFAF52DE, 0xFFFF2D92)
private val FONTS = listOf("inter" to "Inter", "system" to "System", "condensed" to "Rounded", "serif" to "Serif", "arabic" to "Arabic")

/** Harbor's size is the font's px; 32 is Harbor's 100%. */
private const val BASE_PX = 32f
private fun px(prefs: PlayerPrefs) = (prefs.subScale * BASE_PX).roundToInt()

/** The look on screen now, as a saved look holds it. */
private fun current(prefs: PlayerPrefs, s: AppSettings) = SubLook(
    id = "", name = "", background = prefs.subBackground, font = s.subFont, size = px(prefs), bold = s.subBold, color = prefs.subColor,
    outlineColor = s.subOutlineColor, outlineSize = s.subOutlineSize, bottom = prefs.subBottom, assOverride = s.assOverride,
    boxOpacity = s.subBoxOpacity, boxColor = s.subBoxColor, opacity = s.subOpacity,
)

/**
 * Harbor's subtitle style bar (components/player/sub-style-bar.tsx): one row at the top of the
 * player with the font, a size stepper, bold, colours, your saved looks and more options. The
 * subtitles change as you go, and it closes itself after a few idle seconds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubStyleBar(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions) {
    val s = state.settings
    var touched by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(touched, naming, menuOpen) {
        if (naming || menuOpen) return@LaunchedEffect
        delay(7_000)
        state.panel = null
    }
    fun prefs(change: (PlayerPrefs) -> PlayerPrefs) { touched++; actions.updatePrefs(change) }
    fun settings(change: (AppSettings) -> AppSettings) { touched++; actions.updateSettings(change) }
    fun size(n: Int) = prefs { it.copy(subScale = n.coerceIn(16, 120) / BASE_PX) }

    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 54.dp, start = 10.dp, end = 10.dp), contentAlignment = Alignment.TopCenter) {
        FlowRow(
            Modifier.widthIn(max = 1100.dp).clip(RoundedCornerShape(10.dp)).background(BarBg).border(1.dp, Edge, RoundedCornerShape(10.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touched++ }
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Font · size · bold.
            Group {
                FontMenu(s.subFont) { f -> settings { it.copy(subFont = f) } }
                Divider()
                SizeStepper(px(prefs), ::size)
                Divider()
                Box(
                    Modifier.padding(4.dp).size(34.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (s.subBold) BarBg else Color.Transparent)
                        .clickable { settings { it.copy(subBold = !it.subBold) } },
                    contentAlignment = Alignment.Center,
                ) { Text("B", color = if (s.subBold) Ink else InkSubtle, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
            // Colours.
            Group {
                Row(Modifier.padding(horizontal = 7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    SWATCHES.forEach { c -> Swatch(c, prefs.subColor == c) { prefs { it.copy(subColor = c) } } }
                    MoreColors(prefs.subColor, onOpen = { menuOpen = it }) { c -> prefs { it.copy(subColor = c) } }
                }
            }
            // Saved looks.
            Group {
                Looks(prefs, s, naming, { naming = it; touched++ }, ::prefs, ::settings)
            }
            // Edge, thickness, position, embedded styles.
            Advanced(prefs, s, onOpen = { menuOpen = it }, onPrefs = ::prefs, onSettings = ::settings)
            Box(
                Modifier.size(36.dp, 44.dp).clip(RoundedCornerShape(8.dp)).clickable { state.panel = null },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Done", tint = InkSubtle, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(8.dp)).background(GroupBg), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Divider() = Box(Modifier.width(1.dp).height(24.dp).background(Edge))

@Composable
private fun FontMenu(value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = FONTS.firstOrNull { it.first == value }?.second ?: value.substringAfterLast('/').substringBeforeLast('.')
    Box {
        Row(
            Modifier.height(44.dp).widthIn(min = 96.dp).clickable { open = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = subFontFamily(value), modifier = Modifier.padding(end = 8.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = InkSubtle, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = BarBg) {
            FONTS.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, color = if (id == value) Ink else InkMuted, fontSize = 15.sp, fontFamily = subFontFamily(id)) },
                    trailingIcon = { if (id == value) Icon(Icons.Rounded.Check, null, tint = Ink, modifier = Modifier.size(15.dp)) },
                    onClick = { onPick(id); open = false },
                )
            }
        }
    }
}

/** A smaller · the size, dragged sideways · A larger. */
@Composable
private fun SizeStepper(value: Int, onChange: (Int) -> Unit) {
    var dragStart by remember { mutableStateOf(0f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp, 44.dp).clickable { onChange(value - 1) }, contentAlignment = Alignment.Center) {
            Text("A", color = InkMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.height(44.dp).widthIn(min = 40.dp).pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { dragStart = 0f }) { change, dx ->
                    change.consume()
                    dragStart += dx
                    val steps = (dragStart / 18f).toInt()
                    if (steps != 0) { onChange(value + steps); dragStart -= steps * 18f }
                }
            },
            contentAlignment = Alignment.Center,
        ) { Text("$value", color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
        Box(Modifier.size(30.dp, 44.dp).clickable { onChange(value + 1) }, contentAlignment = Alignment.Center) {
            Text("A", color = InkMuted, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Swatch(argb: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(Color(argb.toInt()))
            .border(if (selected) 2.dp else 1.dp, if (selected) Ink else Edge, CircleShape)
            .clickable(onClick = onClick),
    )
}

/** Harbor's colour picker dot: the current colour, opening more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreColors(value: Long, onOpen: (Boolean) -> Unit, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(28.dp).clip(CircleShape).clickable { open = true; onOpen(true) },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(value.toInt())).border(1.dp, Edge, CircleShape)) }
        DropdownMenu(open, { open = false; onOpen(false) }, containerColor = BarBg) {
            FlowRow(Modifier.width(196.dp).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (SWATCHES + MORE_COLORS).forEach { c -> Swatch(c, value == c) { onPick(c); open = false; onOpen(false) } }
            }
        }
    }
}

/**
 * looks-cluster.tsx: your saved looks (English, Foreign, Arabic to start). Tap one to wear it;
 * Saved when the look on screen is one of them, Override when you changed it, + for a new one,
 * long-press a look to delete it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Looks(
    prefs: PlayerPrefs,
    s: AppSettings,
    naming: Boolean,
    setNaming: (Boolean) -> Unit,
    onPrefs: ((PlayerPrefs) -> PlayerPrefs) -> Unit,
    settings: ((AppSettings) -> AppSettings) -> Unit,
) {
    val now = current(prefs, s)
    var selectedId by remember { mutableStateOf<String?>(null) }
    val matching = s.subLooks.firstOrNull { it.sameAs(now) }
    if (selectedId == null || s.subLooks.none { it.id == selectedId }) selectedId = matching?.id
    val selected = s.subLooks.firstOrNull { it.id == selectedId }
    val dirty = selected != null && !selected.sameAs(now)

    if (naming) {
        var draft by remember { mutableStateOf("") }
        fun create() {
            val name = draft.trim().ifEmpty { return }
            val id = name.lowercase().replace(Regex("\\s+"), "-").take(24) + "-" + s.subLooks.size
            settings { it.copy(subLooks = it.subLooks + now.copy(id = id, name = name)) }
            selectedId = id
            setNaming(false)
        }
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(150.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(BarBg).border(1.dp, Edge, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) Text(if (s.subLooks.isEmpty()) "Name your look" else "New look name", color = InkSubtle, fontSize = 13.sp)
                BasicTextField(
                    draft, { draft = it }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 13.sp), cursorBrush = SolidColor(Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { create() }),
                )
            }
            val ok = draft.isNotBlank()
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (ok) HarborColors.Accent else Color.Transparent).clickable(enabled = ok) { create() }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Check, null, tint = if (ok) HarborColors.Canvas else InkSubtle, modifier = Modifier.size(13.dp))
                Text("Save", color = if (ok) HarborColors.Canvas else InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { setNaming(false) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Cancel", tint = InkSubtle, modifier = Modifier.size(15.dp))
            }
        }
        return
    }

    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        s.subLooks.forEach { look ->
            val matches = look.sameAs(now)
            val isSel = look.id == selectedId
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (matches) BarBg else if (isSel) BarBg.copy(alpha = 0.6f) else Color.Transparent)
                    .border(if (matches) 1.dp else 0.dp, if (matches) Edge else Color.Transparent, RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = {
                            selectedId = look.id
                            applyLook(look, onPrefs, settings)
                        },
                        onLongClick = {
                            settings { it.copy(subLooks = it.subLooks.filterNot { l -> l.id == look.id }) }
                            if (selectedId == look.id) selectedId = null
                        },
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(look.name, color = if (matches || isSel) Ink else InkMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (isSel && dirty) Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFBBF24)))
            }
        }
        if (s.subLooks.isNotEmpty()) Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(Edge))
        val saved = selected != null && !dirty
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (saved) Color.Transparent else HarborColors.Accent)
                .clickable(enabled = !saved) {
                    if (selected != null) settings { it.copy(subLooks = it.subLooks.map { l -> if (l.id == selected.id) now.copy(id = l.id, name = l.name) else l }) }
                    else setNaming(true)
                }
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val tint = if (saved) InkSubtle else HarborColors.Canvas
            Icon(if (selected != null) Icons.Rounded.Check else Icons.Rounded.Add, null, tint = tint, modifier = Modifier.size(14.dp))
            Text(if (saved) "Saved" else if (selected != null) "Override ${selected.name}" else "Save look", color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (selected != null) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { setNaming(true) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "Save as a new look", tint = InkSubtle, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Puts a saved look on: the parts kept per player and the rest of the subtitle settings. */
private fun applyLook(look: SubLook, onPrefs: ((PlayerPrefs) -> PlayerPrefs) -> Unit, settings: ((AppSettings) -> AppSettings) -> Unit) {
    onPrefs { it.copy(subBackground = look.background, subScale = look.size / BASE_PX, subColor = look.color, subBottom = look.bottom) }
    settings {
        it.copy(
            subFont = look.font, subBold = look.bold, subOutlineColor = look.outlineColor, subOutlineSize = look.outlineSize,
            assOverride = look.assOverride, subBoxOpacity = look.boxOpacity, subBoxColor = look.boxColor, subOpacity = look.opacity,
        )
    }
}

/** advanced-menu.tsx: edge style, thickness, position and embedded styles. */
@Composable
private fun Advanced(
    prefs: PlayerPrefs,
    s: AppSettings,
    onOpen: (Boolean) -> Unit,
    onPrefs: ((PlayerPrefs) -> PlayerPrefs) -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(36.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(if (open) GroupBg else Color.Transparent).clickable { open = true; onOpen(true) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Tune, "More subtitle options", tint = InkMuted, modifier = Modifier.size(19.dp)) }
        DropdownMenu(open, { open = false; onOpen(false) }, containerColor = BarBg) {
            Column(Modifier.width(290.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionRow("Edge") {
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(GroupBg).padding(3.dp)) {
                        listOf("shadow" to "Shadow", "outline" to "Outline", "box" to "Box").forEach { (id, label) ->
                            val on = prefs.subBackground == id
                            Text(
                                label, color = if (on) Ink else InkMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) BarBg else Color.Transparent)
                                    .clickable { onPrefs { it.copy(subBackground = id) } }.padding(horizontal = 9.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (prefs.subBackground == "outline") OptionRow("Thickness") {
                    Stepper("${s.subOutlineSize.roundToInt()}px",
                        { onSettings { it.copy(subOutlineSize = (it.subOutlineSize - 1f).coerceIn(0f, 10f)) } },
                        { onSettings { it.copy(subOutlineSize = (it.subOutlineSize + 1f).coerceIn(0f, 10f)) } })
                }
                if (prefs.subBackground == "box") OptionRow("Box opacity") {
                    Stepper("${(s.subBoxOpacity * 100).roundToInt()}%",
                        { onSettings { it.copy(subBoxOpacity = (it.subBoxOpacity - 0.1f).coerceIn(0.1f, 1f)) } },
                        { onSettings { it.copy(subBoxOpacity = (it.subBoxOpacity + 0.1f).coerceIn(0.1f, 1f)) } })
                }
                OptionRow("Position") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconBox(Icons.Rounded.KeyboardArrowUp, "Raise subtitles") { onPrefs { it.copy(subBottom = (it.subBottom + 0.02f).coerceIn(0f, 0.45f)) } }
                        IconBox(Icons.Rounded.KeyboardArrowDown, "Lower subtitles") { onPrefs { it.copy(subBottom = (it.subBottom - 0.02f).coerceIn(0f, 0.45f)) } }
                    }
                }
                OptionRow("Opacity") {
                    Stepper("${(s.subOpacity * 100).roundToInt()}%",
                        { onSettings { it.copy(subOpacity = (it.subOpacity - 0.1f).coerceIn(0.4f, 1f)) } },
                        { onSettings { it.copy(subOpacity = (it.subOpacity + 0.1f).coerceIn(0.4f, 1f)) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Override embedded styles", color = Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Force your look onto subtitles that carry their own styling.", color = InkSubtle, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    Switch(
                        s.assOverride == "force", { on -> onSettings { it.copy(assOverride = if (on) "force" else "scale") } },
                        colors = SwitchDefaults.colors(checkedTrackColor = HarborColors.Accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = InkMuted, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun Stepper(value: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconBox(Icons.Rounded.Remove, "Less", onDec)
        Text(value, color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.widthIn(min = 44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconBox(Icons.Rounded.Add, "More", onInc)
    }
}

@Composable
private fun IconBox(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(GroupBg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Ink, modifier = Modifier.size(16.dp))
    }
}
