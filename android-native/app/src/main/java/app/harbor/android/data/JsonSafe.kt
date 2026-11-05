package app.harbor.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Addons and services send `"field": null` (or the wrong shape) for fields they don't fill. `?.jsonArray`
// only skips a missing field: a JSON null is an element, and reading it as an array throws. These read
// null, or anything that isn't the expected shape, as missing.

val JsonElement?.arrayOrNull: JsonArray? get() = this as? JsonArray

val JsonElement?.objectOrNull: JsonObject? get() = this as? JsonObject
