package app.harbor.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class StremioException(message: String) : Exception(message)

/** The Stremio account API (api.strem.io): sign-in, the addon collection and the library. */
object StremioApi {
    private const val API = "https://api.strem.io/api/"

    private suspend fun call(method: String, payload: JsonObject): JsonElement {
        val text = Net.postJson(API + method, payload.toString())
        val response = Net.json.parseToJsonElement(text).jsonObject
        val error = response["error"]
        if (error != null && error !is JsonNull) {
            throw StremioException(error.jsonObject.string("message") ?: "Stremio request failed")
        }
        return response["result"] ?: JsonNull
    }

    data class Login(val authKey: String, val email: String)

    suspend fun login(email: String, password: String): Login {
        val result = call(
            "login",
            buildJsonObject {
                put("email", email)
                put("password", password)
                put("facebook", false)
            },
        ).jsonObject
        val authKey = result.string("authKey") ?: throw StremioException("No session returned")
        val user = result["user"]?.jsonObject
        return Login(authKey, user?.string("email") ?: email)
    }

    suspend fun logout(authKey: String) {
        runCatching { call("logout", buildJsonObject { put("authKey", authKey) }) }
    }

    suspend fun addons(authKey: String): List<Addon> {
        val result = call(
            "addonCollectionGet",
            buildJsonObject {
                put("authKey", authKey)
                put("type", "user")
                put("update", false)
            },
        )
        val list = (result as? JsonObject)?.get("addons") ?: return emptyList()
        // One malformed addon must not hide the rest.
        return list.jsonArray.mapNotNull { runCatching { Net.json.decodeFromJsonElement(Addon.serializer(), it) }.getOrNull() }
    }

    /** The collection exactly as Stremio stores it, so a write sends every field back untouched. */
    suspend fun addonsRaw(authKey: String): List<JsonObject>? {
        val result = call(
            "addonCollectionGet",
            buildJsonObject {
                put("authKey", authKey)
                put("type", "user")
                put("update", false)
            },
        )
        return ((result as? JsonObject)?.get("addons") as? JsonArray)?.mapNotNull { it as? JsonObject }
    }

    /** Replaces the account's addon collection, as Stremio and Harbor do when you install or remove one. */
    suspend fun setAddonsRaw(authKey: String, addons: List<JsonObject>) {
        // An empty collection would leave the account with no addons at all; never send one.
        require(addons.isNotEmpty()) { "Refusing to save an empty addon collection" }
        call(
            "addonCollectionSet",
            buildJsonObject {
                put("authKey", authKey)
                put("type", "user")
                put("addons", JsonArray(addons))
            },
        )
    }

    suspend fun library(authKey: String): List<LibraryItem> {
        val meta = call(
            "datastoreMeta",
            buildJsonObject {
                put("authKey", authKey)
                put("collection", "libraryItem")
            },
        )
        val ids = (meta as? JsonArray)?.mapNotNull { (it as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content } ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val items = call(
            "datastoreGet",
            buildJsonObject {
                put("authKey", authKey)
                put("collection", "libraryItem")
                put("all", true)
                putJsonArray("ids") { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            },
        )
        return (items as? JsonArray)?.mapNotNull {
            runCatching { Net.json.decodeFromJsonElement(LibraryItem.serializer(), it) }.getOrNull()
        } ?: emptyList()
    }
}
