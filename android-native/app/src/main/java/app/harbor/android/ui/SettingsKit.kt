package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Harbor's settings kit (src/views/settings/kit.tsx, shared.tsx and the .hset-* styles in
 * index.css), in Compose: flat rows with hairlines between them, uppercase section labels,
 * segmented option scales, and Harbor's three button styles. No cards.
 */

private val Hairline = Color.White.copy(alpha = 0.05f)
private val Edge = Color(0x40505257)

/** "‹ Back", the page title, and the search field, as Harbor's phone settings header. */
@Composable
fun SettingsHeader(title: String, onBack: (() -> Unit)?, query: String, onQuery: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(HarborColors.Canvas).padding(horizontal = 16.dp)) {
        if (onBack != null) {
            Row(
                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack)
                    .heightIn(min = 44.dp).padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = HarborColors.InkMuted, modifier = Modifier.size(26.dp))
                Text("Back", color = HarborColors.InkMuted, fontSize = 16.5.sp)
            }
        } else Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = HarborColors.Ink,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.6).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 14.dp),
        )
        SettingsSearchField(query, onQuery)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search settings", color = HarborColors.InkSubtle, fontSize = 16.5.sp)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = HarborColors.Ink, fontSize = 16.5.sp),
                cursorBrush = SolidColor(HarborColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(44.dp).clickable { onQuery("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Clear", tint = HarborColors.InkSubtle, modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** A rail row on the settings home: outline icon, label, and a chevron for groups. */
@Composable
fun NavRow(
    icon: ImageVector,
    label: String,
    chevron: Boolean,
    selected: Boolean = false,
    expanded: Boolean = false,
    onClick: () -> Unit,
) = NavRow(androidx.compose.ui.graphics.vector.rememberVectorPainter(icon), label, chevron, selected, expanded, onClick)

/** The same row with one of Harbor's settings icons (res/drawable/set_*). */
@Composable
fun NavRow(
    icon: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    chevron: Boolean,
    selected: Boolean = false,
    expanded: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = if (selected) HarborColors.Ink else HarborColors.InkMuted
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) HarborColors.Elevated else Color.Transparent).clickable(onClick = onClick)
            .heightIn(min = 54.dp).padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, color = ink, fontSize = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
        if (chevron) Icon(
            if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = HarborColors.InkSubtle,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A section under the open group in the tablet rail; tapping scrolls to it. */
@Composable
fun NavChildRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .heightIn(min = 44.dp).padding(start = 50.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = HarborColors.InkSubtle, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Harbor's uppercase group label ("SETUP", "SUBTITLES"). */
@Composable
fun SettingsLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = HarborColors.InkSubtle,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.72.sp,
        modifier = modifier.padding(top = 6.dp, bottom = 4.dp),
    )
}

/** A Harbor panel section: a 20 sp title and a muted line under it ("Your Trakt account"). */
@Composable
fun SettingsSection(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp)
            subtitle?.let { Text(it, color = HarborColors.InkMuted, fontSize = 15.5.sp, lineHeight = 22.sp) }
        }
        content()
    }
}

/** Harbor's flat group of rows under a label; the space between groups is the only frame. */
@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** The 1 px rule Harbor draws between rows. */
@Composable
fun RowRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

/** Title and description, as Harbor's row text (16.5 / 15.5 sp). */
@Composable
fun RowText(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
        body?.let { Text(it, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp) }
    }
}

/** A tappable row with a chevron: Harbor's group landing rows. */
@Composable
fun LinkRow(title: String, body: String?, onClick: () -> Unit) {
    Column {
        RowRule()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).heightIn(min = 68.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowText(title, body, Modifier.weight(1f).padding(end = 12.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        RowRule()
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 64.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowText(title, body, Modifier.weight(1f).padding(end = 16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = HarborColors.Ink,
                    checkedThumbColor = HarborColors.Canvas,
                    uncheckedTrackColor = HarborColors.Raised,
                    uncheckedThumbColor = HarborColors.InkSubtle,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** Harbor's OptionScale: a segmented strip, the chosen option in ink. Wraps on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OptionScale(options: List<Pair<T, String>>, isSelected: (T) -> Boolean, onPick: (T) -> Unit) {
    FlowRow(
        Modifier.clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = isSelected(value)
            Box(
                Modifier.heightIn(min = 40.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) HarborColors.Ink else Color.Transparent)
                    .clickable { onPick(value) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) HarborColors.Canvas else HarborColors.InkSubtle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val ButtonShape = RoundedCornerShape(8.dp)

/** Harbor's ROW_ACTION_PRIMARY: ink on canvas. */
@Composable
fun HButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    HarborButton(onClick, modifier, enabled, HarborColors.Ink, HarborColors.Canvas, null, content)
}

/** Harbor's ROW_ACTION: elevated, soft edge, muted text. */
@Composable
fun HGhostButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    HarborButton(onClick, modifier, enabled, HarborColors.Elevated, HarborColors.InkMuted, Edge, content)
}

/** A quiet text action, as Harbor's "About Trakt ↗" links. */
@Composable
fun HLinkButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    HarborButton(onClick, modifier, enabled, Color.Transparent, HarborColors.InkMuted, null, content, padding = 4.dp)
}

@Composable
private fun HarborButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    background: Color,
    ink: Color,
    border: Color?,
    content: @Composable RowScope.() -> Unit,
    padding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Row(
        modifier.alpha(if (enabled) 1f else 0.42f).clip(ButtonShape).background(background)
            .then(if (border != null) Modifier.border(1.dp, border, ButtonShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).height(44.dp).padding(horizontal = padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides ink,
            androidx.compose.material3.LocalTextStyle provides TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        ) { content() }
    }
}

/** Harbor's text input: a filled 44 dp field, the label as a placeholder. */
@Composable
fun HTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Box(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && label != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides TextStyle(color = HarborColors.InkSubtle, fontSize = 16.sp),
                androidx.compose.material3.LocalContentColor provides HarborColors.InkSubtle,
            ) { label() }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(color = HarborColors.Ink, fontSize = 16.sp),
            cursorBrush = SolidColor(HarborColors.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "3 of 3 connected", with Harbor's green dot. */
@Composable
fun StatusDot(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Success))
        Text(text, color = HarborColors.InkMuted, fontSize = 15.5.sp)
    }
}

val Success = Color(0xFF3FB950)
