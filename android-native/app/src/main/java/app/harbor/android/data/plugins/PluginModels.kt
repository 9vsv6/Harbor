package app.harbor.android.data.plugins

import kotlinx.serialization.Serializable

/*
 * Harbor's stream plugins (src/lib/streams/plugins/types.ts): repositories you add by link, the
 * plugins they list, and the ones you installed. Three formats as on the desktop; Android runs
 * "android-extension" (Cloudstream .cs3) plugins, and lists the JavaScript formats as not yet supported.
 */

const val FORMAT_ANDROID = "android-extension"
const val FORMAT_HARBOR = "harbor"
const val FORMAT_PROVIDER = "provider-script"

/** One plugin a repository offers (StreamRepoEntry). */
@Serializable
data class PluginEntry(
    val id: String,
    val name: String,
    val version: String,
    /** Where the plugin file is downloaded from. */
    val entry: String,
    val format: String,
    val icon: String? = null,
    val description: String? = null,
    val author: String? = null,
    val lang: List<String> = emptyList(),
    /** "movie", "series". */
    val types: List<String> = listOf("movie", "series"),
    val nsfw: Boolean = false,
    /** False when the repository marks it down. */
    val enabled: Boolean = true,
    /** The repository's status note ("slow", "beta"…). */
    val note: String? = null,
)

/** A repository you added (StreamRepoRecord). */
@Serializable
data class PluginRepo(
    /** The link you pasted, normalised to the manifest's address. */
    val url: String,
    val name: String,
    val format: String,
    val icon: String? = null,
    val description: String? = null,
    val entries: List<PluginEntry> = emptyList(),
    val checkedAt: Long = 0,
    val error: String? = null,
)

/** A plugin you installed (InstalledStreamPlugin). */
@Serializable
data class InstalledPlugin(
    /** repo url + entry id, so the same plugin from two repositories stays apart. */
    val id: String,
    val entryId: String,
    val repoUrl: String,
    val repoName: String,
    val name: String,
    val version: String,
    val format: String,
    /** The downloaded plugin, in the app's storage. */
    val file: String,
    val icon: String? = null,
    val description: String? = null,
    val lang: List<String> = emptyList(),
    val types: List<String> = listOf("movie", "series"),
    val nsfw: Boolean = false,
    val enabled: Boolean = true,
    val installedAt: Long = 0,
    val updatedAt: Long = 0,
    /** Why it couldn't load here, when it couldn't. */
    val error: String? = null,
)

@Serializable
data class PluginState(
    val repos: List<PluginRepo> = emptyList(),
    val installed: List<InstalledPlugin> = emptyList(),
)
