package app.harbor.android.ui

import androidx.compose.material.icons.rounded.Check

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.harbor.android.i18n.Text

@Preview(name = "Arabic settings and profile fields", widthDp = 380, showBackground = true, backgroundColor = 0xFF121316)
@Composable
private fun ArabicSettingsPreview() {
    HarborTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsSearchField("بحث Harbor", {})
                HTextField("مريم Harbor", {}, label = { Text("Name") }, singleLine = true)
                ToggleRow("المحتوى", "إعدادات العرض والترجمة", true, {})
                NavRow(androidx.compose.material.icons.Icons.Rounded.Check, "الملفات الشخصية", true, onClick = {})
            }
        }
    }
}
