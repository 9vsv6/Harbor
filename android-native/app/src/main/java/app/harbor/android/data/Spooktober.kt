package app.harbor.android.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** A title from Harbor's Spooktober catalog (public/spooktober/catalog.json, films and shows only). */
@Serializable
data class SpookItem(
    val imdb: String,
    val title: String,
    val year: String? = null,
    val type: String,
    val section: String,
    val creator: String? = null,
    val description: String? = null,
    val poster: String? = null,
) {
    fun toMeta() = Meta(id = imdb, type = type, name = title, poster = poster, description = description, releaseInfo = year)
}

/** A festival shelf: its number, eyebrow, title and line, as Harbor's festival lays them out. */
data class SpookShelf(val section: String, val number: String, val eyebrow: String, val title: String, val line: String)

/** Harbor's seasonal Spooktober festival (src/lib/spooktober-season.ts and public/spooktober). */
object Spooktober {
    /** Local calendar dates; the early 2026 launch must never repeat. */
    fun inSeason(date: LocalDate = LocalDate.now()): Boolean =
        date.monthValue == 10 || (date.year == 2026 && date.monthValue == 9 && date.dayOfMonth >= 28)

    /** The festival's watchable shelves, in its order (app.js). */
    val shelves = listOf(
        SpookShelf("classics", "01", "THE OLD GROUNDS", "Halloween classics", "Essential horror films."),
        SpookShelf("series", "03", "THE LONG WAY HOME", "Horror series", "Find your next series."),
        SpookShelf("cozy", "06", "THE PUMPKIN PATCH", "Halloween favorites", "For a lighter Halloween."),
        SpookShelf("modern", "07", "", "Modern horror", "Contemporary horror."),
        SpookShelf("international", "08", "", "Horror from around the world", "International films."),
    )

    fun load(context: Context): List<SpookItem> = runCatching {
        val text = context.assets.open("spooktober/catalog.json").bufferedReader().use { it.readText() }
        Net.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SpookItem.serializer()), text)
    }.getOrDefault(emptyList())
}
