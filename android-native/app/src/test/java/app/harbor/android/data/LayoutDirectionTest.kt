package app.harbor.android.data

import app.harbor.android.i18n.UiLanguages
import org.junit.Assert.*
import org.junit.Test

class LayoutDirectionTest {
    @Test fun recognisesRtlLanguageTagsAndExplicitScripts() {
        listOf("ar", "ar-SA", "he", "fa_IR", "ur-PK", "dv", "az-Arab", "ckb").forEach { assertTrue(it, UiLanguages.isRtl(it)) }
        listOf("en", "fr", "az-Latn", "ku-Latn", "ar-Latn", "ar-Cyrl", "ku").forEach { assertFalse(it, UiLanguages.isRtl(it)) }
    }
}
