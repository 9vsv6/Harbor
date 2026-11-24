package app.harbor.android.data

import app.harbor.android.data.plugins.FORMAT_ANDROID
import app.harbor.android.data.plugins.InstalledPlugin
import app.harbor.android.data.plugins.Plugins
import org.junit.Assert.*
import org.junit.Test

class PluginAddonTest {
    @Test fun pluginAddonDeclaresItsStreamResourceAndTypes() {
        val plugin = InstalledPlugin("test", "test", "https://repo.example", "Repo", "Test", "1", FORMAT_ANDROID, "fixture.cs3")
        val addon = Plugins.addonOf(plugin)
        assertEquals(listOf("stream"), addon.manifest.resourceNames)
        assertTrue(addon.manifest.supports("stream", "movie", "tt123"))
        assertTrue(addon.manifest.supports("stream", "series", "kitsu:123:2"))
        assertFalse(addon.manifest.supports("meta", "movie", "tt123"))
        assertFalse(Plugins.addonOf(plugin.copy(types = listOf("movie"))).manifest.supports("stream", "series", "tt123:1:2"))
    }
}
