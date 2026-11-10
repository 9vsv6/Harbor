package app.harbor.android.data

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LanguageCodesTest {
    @Test fun languageCodesDoNotDependOnTheDevicesTurkishCaseRules() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("en", LanguageCodes.code("ENGLISH"))
            assertEquals("ita", LanguageCodes.code("ITA"))
            assertEquals("it", LanguageCodes.code("ITALIAN"))
        } finally { Locale.setDefault(old) }
    }
    @Test fun regionalCodesUseConsistentCaseAndSeparators() {
        assertEquals("pt-br", LanguageCodes.code("PT_BR"))
        assertEquals("zh-hant", LanguageCodes.code("zh-Hant"))
        assertEquals(listOf("en-us"), LanguageCodes.codes(listOf("EN_us", "en-US")))
    }
    @Test fun namesAndUnknownCustomLabelsKeepTheirExistingMeaning() {
        assertEquals("ar", LanguageCodes.code("Arabic"))
        assertEquals("pt", LanguageCodes.code("Portuguese (Brazil)"))
        assertEquals("Custom language", LanguageCodes.code("Custom language"))
    }
}
