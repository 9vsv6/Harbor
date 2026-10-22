package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Languages

/*
 * Harbor's settings controls (src/views/settings/kit.tsx, shared.tsx and the language and
 * subtitle panels), drawn the way Harbor draws them: elevated tiles on the canvas, ink
 * for the chosen option, the accent for checks and focus.
 */

private val Tile = RoundedCornerShape(10.dp)

/** A section heading as Harbor's panels: bold 20 sp title, muted line under it. */
@Composable
fun HarborHeading(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        subtitle?.let { Text(it, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp) }
    }
}

/** A row heading with an icon in front ("Never auto-select tracks containing"). */
@Composable
fun IconHeading(icon: ImageVector, title: String, body: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = HarborColors.InkMuted, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
            body?.let { Text(it, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp) }
        }
    }
}

/**
 * Harbor's language order: numbered chips with the flag, "move earlier" and remove, then
 * "Add language", which opens a searchable two-column picker in place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageOrder(selected: List<String>, max: Int = 12, onChange: (List<String>) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            selected.forEachIndexed { i, code ->
                val lang = Languages.of(code)
                Row(
                    Modifier.heightIn(min = 48.dp).clip(Tile).background(HarborColors.Elevated).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${i + 1}", color = HarborColors.InkSubtle, fontSize = 12.sp)
                    Text(lang.flag, fontSize = 18.sp)
                    Text(lang.name, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (i > 0) ChipIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Move ${lang.name} earlier") {
                        onChange(selected.toMutableList().apply { add(i - 1, removeAt(i)) })
                    }
                    ChipIcon(Icons.Rounded.Close, "Remove ${lang.name}") { onChange(selected - code) }
                }
            }
            if (selected.size < max) {
                Row(
                    Modifier.heightIn(min = 48.dp).clip(Tile).background(HarborColors.Elevated).border(1.dp, Color(0x14FFFFFF), Tile)
                        .clickable { picking = !picking; query = "" }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(if (picking) Icons.Rounded.Check else Icons.Rounded.Add, null, tint = HarborColors.InkMuted, modifier = Modifier.size(18.dp))
                    Text(if (picking) "Done" else "Add language", color = HarborColors.InkMuted, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (picking) {
            Column(Modifier.fillMaxWidth().widthIn(max = 860.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Elevated)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search languages", color = HarborColors.InkSubtle, fontSize = 16.sp)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = HarborColors.Ink, fontSize = 16.sp), cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth())
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
                val options = Languages.all.filter { it.code !in selected && (query.isBlank() || it.name.contains(query, true) || it.native.contains(query, true)) }
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    options.chunked(2).forEach { pair ->
                        Row {
                            pair.forEach { lang ->
                                Row(
                                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onChange(selected + lang.code) }.padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(lang.flag, fontSize = 18.sp)
                                    Text(lang.name, color = HarborColors.Ink, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (options.isEmpty()) Text("No language matches.", color = HarborColors.InkSubtle, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun ChipIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = HarborColors.InkSubtle, modifier = Modifier.size(18.dp))
    }
}

/** Harbor's Home language tiles: native name, flag and English name, a radio circle. */
@Composable
fun LanguageTiles(codes: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when { maxWidth >= 1100.dp -> 5; maxWidth >= 760.dp -> 4; maxWidth >= 500.dp -> 3; else -> 2 }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            codes.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { code ->
                        val lang = Languages.of(code)
                        val on = code in selected
                        Row(
                            Modifier.weight(1f).clip(Tile).background(if (on) HarborColors.Elevated else HarborColors.Canvas)
                                .border(1.dp, if (on) HarborColors.Accent else Color(0x14FFFFFF), Tile).clickable { onToggle(code) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(lang.native, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(lang.flag, fontSize = 13.sp)
                                    Text(lang.name, color = HarborColors.InkSubtle, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(if (on) HarborColors.Accent else Color.Transparent)
                                    .border(1.dp, if (on) HarborColors.Accent else Color(0x26FFFFFF), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(Icons.Rounded.Check, null, tint = HarborColors.Canvas, modifier = Modifier.size(14.dp)) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Harbor's word list: type a word, Enter adds it; tap a chip to remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordList(words: List<String>, placeholder: String, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HTextField(
            text,
            { v ->
                if (v.endsWith("\n") || v.endsWith(",")) {
                    val word = v.trimEnd('\n', ',').trim().lowercase()
                    if (word.isNotEmpty() && word !in words) onChange(words + word)
                    text = ""
                } else text = v
            },
            label = { Text(placeholder) },
            singleLine = false,
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            words.forEach { w ->
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(HarborColors.Elevated).clickable { onChange(words - w) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(w, color = HarborColors.InkMuted, fontSize = 15.sp)
                    Icon(Icons.Rounded.Close, "Remove $w", tint = HarborColors.InkSubtle, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Harbor's select: a filled field with the value and a chevron, opening a menu. */
@Composable
fun <T> HarborSelect(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.widthIn(min = 260.dp, max = 520.dp).fillMaxWidth().heightIn(min = 52.dp).clip(Tile).background(HarborColors.Elevated)
                .clickable { open = true }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), color = HarborColors.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.InkSubtle)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (value == selected) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.5.sp) },
                    trailingIcon = { if (value == selected) Icon(Icons.Rounded.Check, null, tint = HarborColors.Accent) },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}

/** Harbor's option list: title and description rows, a check on the chosen one. */
@Composable
fun <T> CheckList(options: List<Triple<T, String, String?>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().widthIn(max = 820.dp)) {
        options.forEachIndexed { i, (value, title, body) ->
            if (i > 0) RowRule()
            Row(Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RowText(title, body, Modifier.weight(1f))
                if (value == selected) Icon(Icons.Rounded.Check, "Selected", tint = HarborColors.Accent, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Harbor's stepped slider: one track, the accent fill up to a white thumb, tick marks, the value and Reset. */
@Composable
fun TickSlider(value: Float, steps: List<Float>, label: (Float) -> String, defaultValue: Float, onChange: (Float) -> Unit) {
    val index = steps.indexOfFirst { kotlin.math.abs(it - value) < 0.001f }.takeIf { it >= 0 } ?: steps.indexOfLast { it <= value }.coerceAtLeast(0)
    val last = (steps.size - 1).coerceAtLeast(1)
    fun pick(x: Float, width: Int) {
        val i = ((x / width.coerceAtLeast(1)) * last).let { kotlin.math.round(it).toInt() }.coerceIn(0, steps.size - 1)
        if (i != index) onChange(steps[i])
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        BoxWithConstraints(
            Modifier.widthIn(max = 500.dp).weight(1f, fill = false).height(32.dp)
                .pointerInput(steps, index) { detectTapGestures { pick(it.x, size.width) } }
                .pointerInput(steps, index) { detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = index.toFloat() / last
            Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated))
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(16.dp).clip(RoundedCornerShape(8.dp)).background(HarborColors.Accent))
            // Tick marks, one per step.
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                steps.indices.forEach { i ->
                    Box(Modifier.width(1.5.dp).height(9.dp).background(if (i <= index) Color(0x66FFFFFF) else Color(0x40FFFFFF)))
                }
            }
            Box(Modifier.padding(start = (maxWidth - 6.dp) * fraction).width(6.dp).height(28.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
        }
        Text(label(steps.getOrElse(index) { value }), color = HarborColors.Ink, fontSize = 16.sp)
        Text("Reset", color = HarborColors.InkSubtle, fontSize = 13.sp, modifier = Modifier.clickable { onChange(defaultValue) })
    }
}

/** Harbor's colour swatches, then the hex value and Reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(colors: List<Long>, selected: Long, defaultColor: Long, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            colors.forEach { c ->
                val on = (c and 0xFFFFFF) == (selected and 0xFFFFFF)
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(c.toInt()))
                        .border(if (on) 3.dp else 1.dp, if (on) HarborColors.Ink else Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                        .clickable { onPick(c) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color(selected.toInt())).border(1.dp, Color(0x33FFFFFF), CircleShape))
                Text("#%06X".format(selected and 0xFFFFFF), color = HarborColors.Ink, fontSize = 15.5.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                "Reset",
                color = if (selected == defaultColor) HarborColors.InkSubtle.copy(alpha = 0.5f) else HarborColors.InkMuted,
                fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated).clickable { onPick(defaultColor) }.padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

/** Harbor's tile choices (fonts): a grid of buttons, the chosen one outlined in the accent. */
@Composable
fun TileChoices(options: List<Pair<String, String>>, selected: String, columns: Int = 3, fontFor: (String) -> FontFamily? = { null }, onPick: (String) -> Unit) {
    Column(Modifier.widthIn(max = 840.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (value, label) ->
                    val on = value == selected
                    Box(
                        Modifier.weight(1f).height(66.dp).clip(Tile).background(HarborColors.Elevated)
                            .border(1.dp, if (on) HarborColors.Accent else Color.Transparent, Tile).clickable { onPick(value) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = fontFor(value)) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Harbor's subtitle position grid: nine places, each with a little screen and its dot. */
@Composable
fun PositionGrid(selected: String, onPick: (String) -> Unit) {
    val places = listOf(
        "top-left" to "Top left", "top" to "Top", "top-right" to "Top right",
        "left" to "Left", "center" to "Center", "right" to "Right",
        "bottom-left" to "Bottom left", "bottom" to "Bottom", "bottom-right" to "Bottom right",
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 900.dp) 6 else 3
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            places.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (value, label) ->
                        val on = value == selected
                        Row(
                            Modifier.weight(1f).heightIn(min = 64.dp).clip(Tile).background(if (on) Color(0xFF3A2A1C) else HarborColors.Elevated)
                                .border(1.dp, if (on) HarborColors.Accent else Color.Transparent, Tile).clickable { onPick(value) }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(width = 46.dp, height = 30.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF121316)).padding(5.dp)) {
                                val align = when (value) {
                                    "top-left" -> Alignment.TopStart; "top" -> Alignment.TopCenter; "top-right" -> Alignment.TopEnd
                                    "left" -> Alignment.CenterStart; "center" -> Alignment.Center; "right" -> Alignment.CenterEnd
                                    "bottom-left" -> Alignment.BottomStart; "bottom-right" -> Alignment.BottomEnd; else -> Alignment.BottomCenter
                                }
                                Box(Modifier.align(align).size(7.dp).clip(CircleShape).background(if (on) HarborColors.Accent else HarborColors.InkMuted))
                            }
                            Text(label, color = HarborColors.Ink, fontSize = 15.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Harbor's plain segmented choice: the chosen word on a white pill, the rest as text. */
@Composable
fun <T> PillChoice(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                color = if (on) HarborColors.Canvas else HarborColors.InkMuted,
                fontSize = 16.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) HarborColors.Ink else Color.Transparent).clickable { onPick(value) }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
            )
        }
    }
}

/**
 * Harbor's provider block (Subtitle sources): logo, name and line, an on/off switch, then
 * "<NAME> API KEY" with a Saved dot, a masked field with show/hide, and where to get one.
 */
@Composable
fun ProviderKeyBlock(
    logo: String,
    logoColor: Color,
    name: String,
    body: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    keyLabel: String,
    key: String?,
    onKey: (String?) -> Unit,
    hint: String,
    linkLabel: String,
    onLink: () -> Unit,
) {
    var text by remember(key) { mutableStateOf(key.orEmpty()) }
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(logoColor), contentAlignment = Alignment.Center) {
                Text(logo, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = HarborColors.Ink, checkedThumbColor = HarborColors.Canvas,
                    uncheckedTrackColor = HarborColors.Raised, uncheckedThumbColor = HarborColors.InkSubtle, uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        if (enabled) {
            Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(keyLabel.uppercase(), color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                    if (!key.isNullOrBlank()) StatusDot("Saved")
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(Tile).background(HarborColors.Elevated).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(logoColor), contentAlignment = Alignment.Center) {
                        Text(logo, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text("Paste your API key", color = HarborColors.InkSubtle, fontSize = 16.sp)
                        BasicTextField(
                            text,
                            { text = it.trim(); onKey(text.ifBlank { null }) },
                            singleLine = true,
                            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation('•'),
                            textStyle = TextStyle(color = HarborColors.Ink, fontSize = 16.sp, letterSpacing = if (shown) 0.sp else 2.sp),
                            cursorBrush = SolidColor(HarborColors.Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Icon(
                        if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (shown) "Hide key" else "Show key",
                        tint = HarborColors.InkSubtle,
                        modifier = Modifier.size(20.dp).clickable { shown = !shown },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hint + " ", color = HarborColors.InkSubtle, fontSize = 14.5.sp)
                    Text("$linkLabel ↗", color = HarborColors.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onLink))
                }
            }
        }
    }
}
