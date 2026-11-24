package app.harbor.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Harbor's people features on the same server and session as the account (Harbor PC 1.0.4:
 * lib/social/user-discovery.ts, friends.ts, dm.ts, party-invites.ts, profile settings):
 * finding people, profiles, friends, direct messages and watch-party invites.
 */
object HarborPeople {
    data class Match(val score: Int?, val shared: Int)
    data class Person(
        val id: String, val handle: String, val name: String, val avatar: String?, val verified: Boolean,
        val memberSince: String?, val showJoinDate: Boolean, val ratings: Int, val match: Match?,
        val friendStatus: String, val friendEdgeId: String?,
    )
    data class Page(val people: List<Person>, val total: Int, val next: String?)

    data class Profile(
        val id: String, val handle: String, val name: String, val avatar: String?, val verified: Boolean,
        val level: Int?, val status: String, val showJoinDate: Boolean, val memberSince: String?,
        val slogan: String?, val watched: Int, val friends: Int, val hoursWatched: Int,
        val ratingCount: Int, val ratingAverage: Double?, val isOwner: Boolean,
        /** The profile card's own look (PC's profileCardStyle): gradient from/to and accent, or none. */
        val cardFrom: Long?, val cardTo: Long?, val cardAccent: Long?,
        /** The card's banner: its background image, else the profile banner. */
        val banner: String?,
        val friendStatus: String = "none", val friendEdgeId: String? = null,
    )

    /** PC's card themes (lib/social/profile-card-appearance.ts CARD_THEMES): from, to, accent. */
    private val THEMES = mapOf(
        "harbor" to Triple(0xFF7B5CFF, 0xFF1B1030, 0xFF7B5CFF),
        "aurora" to Triple(0xFF22D3EE, 0xFF4F46E5, 0xFF22D3EE),
        "midnight" to Triple(0xFF1E293B, 0xFF020617, 0xFF94A3B8),
        "ember" to Triple(0xFFF97316, 0xFF7F1D1D, 0xFFFB923C),
        "forest" to Triple(0xFF10B981, 0xFF064E3B, 0xFF34D399),
        "rose" to Triple(0xFFF43F5E, 0xFF4C0519, 0xFFFB7185),
        "mono" to Triple(0xFF262626, 0xFF0A0A0A, 0xFFA3A3A3),
    )

    data class Friend(val id: String?, val handle: String, val name: String, val avatar: String?, val status: String, val slogan: String?)
    data class Pending(val edgeId: String, val handle: String, val name: String, val avatar: String?, val at: String)

    data class Conversation(val id: String, val other: HarborSocial.Author?, val otherId: String?, val presence: String?, val last: String?, val lastMine: Boolean, val unread: Int, val muted: Boolean, val at: String)
    data class Inbox(val conversations: List<Conversation>, val unread: Int)
    data class Reaction(val emoji: String, val count: Int, val mine: Boolean)
    data class Share(val type: String, val id: String, val title: String?, val poster: String?)
    data class Message(
        val id: String, val body: String, val at: String, val mine: Boolean, val edited: Boolean, val deleted: Boolean,
        val reactions: List<Reaction>, val share: Share?, val sender: HarborSocial.Author?,
    )
    data class MessagePage(val messages: List<Message>, val next: String?)

    data class Invite(
        val id: String, val status: String, val incoming: Boolean, val roomCode: String, val title: String?,
        val mediaId: String?, val poster: String?, val from: HarborSocial.Author?, val to: HarborSocial.Author?,
        val at: String, val expiresAt: String, val expired: Boolean,
    )

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun color(v: String?): Long? = v?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }

    private fun person(o: JsonObject): Person? {
        val handle = o.str("handle") ?: return null
        val match = o.obj("match")?.let { Match((it["score"] as? JsonPrimitive)?.doubleOrNull?.toInt(), it.int("count") ?: 0) }
        return Person(o.str("id").orEmpty(), handle, o.str("displayName") ?: o.str("alias") ?: handle, o.str("avatarUrl"), o.bool("verified"),
            o.str("memberSince"), o["showJoinDate"]?.let { (it as? JsonPrimitive)?.booleanOrNull } != false,
            o.int("ratingCount") ?: 0, match, o.str("friendStatus") ?: "none", o.str("friendEdgeId"))
    }

    // ---- People discovery ----

    /** Sorts PC offers: newest, oldest, random, username, activity, reviews. */
    val SORTS = listOf("newest" to "Newest", "activity" to "Active", "reviews" to "Most reviews", "username" to "A–Z", "random" to "Random")

    /**
     * Finds members. `match` keeps people whose ratings agree with yours (at least `minMatch`%
     * over `minShared` titles you both rated); `rated` keeps people with at least `minRatings`.
     */
    suspend fun discover(
        query: String, sort: String, token: String?, rated: Boolean = false, minRatings: Int = 1,
        match: Boolean = false, minMatch: Int = 50, minShared: Int = 10, cursor: String? = null, canvas: Boolean = false,
    ): Page {
        val q = query.trim().removePrefix("@")
        val params = buildList {
            add("sort=${HarborSocial.enc(sort)}"); add("limit=24")
            if (q.isNotEmpty()) add("q=${HarborSocial.enc(q)}")
            if (rated) { add("rated=1"); add("minRatings=$minRatings") }
            if (match && token != null) { add("minMatch=$minMatch"); add("minShared=$minShared") }
            if (canvas) add("canvas=1")
            cursor?.let { add("cursor=${HarborSocial.enc(it)}") }
        }.joinToString("&")
        val d = HarborSocial.call("GET", "/social/users?$params", token) as? JsonObject ?: return Page(emptyList(), 0, null)
        val people = d["users"].objects().mapNotNull(::person)
        return Page(people, d.int("total") ?: people.size, d.str("nextCursor"))
    }

    suspend fun profile(handle: String, token: String?): Profile? {
        val o = HarborSocial.call("GET", "/social/u/${HarborSocial.enc(handle)}", token) as? JsonObject ?: return null
        val counts = o.obj("counts")
        val ratings = o.obj("ratings")
        val card = o.obj("profileCard")
        return Profile(
            o.str("id").orEmpty(), o.str("handle") ?: handle, o.str("alias") ?: o.str("displayName") ?: handle, o.str("avatarUrl"),
            o.bool("verified") && !o.bool("hideVerified"), o.int("level"), o.str("status") ?: if (o.bool("online")) "online" else "offline",
            o["showJoinDate"]?.let { (it as? JsonPrimitive)?.booleanOrNull } != false, o.str("memberSince"), o.str("slogan")?.takeIf { it.isNotBlank() },
            counts?.int("watched") ?: 0, counts?.int("friends") ?: 0, counts?.int("hoursWatched") ?: 0,
            ratings?.int("count") ?: 0, (ratings?.get("avg") as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }, o.bool("isOwner"),
            cardColors(card).first, cardColors(card).second, cardColors(card).third,
            (card?.str("bgUrl") ?: o.str("bannerUrl"))?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) "https://harborcommunity.site$it" else it },
            o.str("friendStatus") ?: "none", o.str("friendEdgeId"),
        )
    }

    /** As PC: a custom theme's own from/to, else the named theme; a valid accent wins over the theme's. */
    private fun cardColors(card: JsonObject?): Triple<Long?, Long?, Long?> {
        card ?: return Triple(null, null, null)
        val theme = card.str("theme")
        val preset = if (theme == "custom" && color(card.str("from")) != null && color(card.str("to")) != null)
            Triple(color(card.str("from"))!!, color(card.str("to"))!!, 0xFF7B5CFF) else THEMES[theme]
        return Triple(preset?.first, preset?.second, color(card.str("accent")) ?: preset?.third)
    }

    /** Your profile's visible settings (PATCH /social/me/profile): slogan, join date, card colors. */
    suspend fun updateProfile(token: String, slogan: String? = null, showJoinDate: Boolean? = null, accent: String? = null) {
        HarborSocial.call("PATCH", "/social/me/profile", token, buildJsonObject {
            slogan?.let { put("slogan", it) }
            showJoinDate?.let { put("showJoinDate", it) }
            accent?.let { put("profileCard", buildJsonObject { put("accent", it) }) }
        })
    }

    // ---- Friends ----

    suspend fun friends(token: String): List<Friend> {
        val d = HarborSocial.call("GET", "/social/friends", token)
        val list = (d as? JsonArray) ?: ((d as? JsonObject)?.get("friends") as? JsonArray)
        return list.objects().mapNotNull { o ->
            val handle = o.str("handle") ?: return@mapNotNull null
            Friend(o.str("id"), handle, o.str("alias") ?: handle, o.str("avatarUrl"),
                o.str("presence") ?: o.str("status")?.takeIf { it in setOf("online", "away", "dnd", "offline") } ?: if (o.bool("online")) "online" else "offline",
                o.str("slogan"))
        }
    }

    suspend fun pending(token: String): List<Pending> {
        val d = HarborSocial.call("GET", "/social/friends/pending", token)
        val list = (d as? JsonArray) ?: ((d as? JsonObject)?.get("pending") as? JsonArray)
        return list.objects().mapNotNull { o ->
            val from = o.obj("from") ?: return@mapNotNull null
            val handle = from.str("handle") ?: return@mapNotNull null
            Pending(o.str("edgeId") ?: return@mapNotNull null, handle, from.str("alias") ?: handle, from.str("avatarUrl"), o.str("createdAt").orEmpty())
        }
    }

    suspend fun requestFriend(handle: String, token: String) { HarborSocial.call("POST", "/social/friends/request", token, buildJsonObject { put("handle", handle.trim().lowercase()) }) }
    suspend fun accept(edgeId: String, token: String) { HarborSocial.call("POST", "/social/friends/${HarborSocial.enc(edgeId)}/accept", token) }
    suspend fun decline(edgeId: String, token: String) { HarborSocial.call("POST", "/social/friends/${HarborSocial.enc(edgeId)}/decline", token) }
    suspend fun removeFriend(handle: String, token: String) { HarborSocial.call("POST", "/social/friends/remove", token, buildJsonObject { put("handle", handle.trim().lowercase()) }) }

    // ---- Direct messages ----

    suspend fun inbox(token: String): Inbox {
        val d = HarborSocial.call("GET", "/social/dm/conversations", token) as? JsonObject ?: return Inbox(emptyList(), 0)
        val list = d["conversations"].objects().mapNotNull { o ->
            val other = o.obj("other")
            val last = o.obj("lastMessage")
            Conversation(o.str("id") ?: return@mapNotNull null, HarborSocial.author(other), other?.str("id"), other?.str("presence"),
                last?.let { if (it.bool("deleted")) "Message deleted" else it.str("body") }, last?.bool("mine") == true,
                o.int("unread") ?: 0, o.bool("muted"), o.str("at").orEmpty())
        }
        return Inbox(list, d.int("unread") ?: list.sumOf { it.unread })
    }

    /** Opens (or finds) the conversation with someone. Friends-only and blocked people say so. */
    suspend fun openConversation(userId: String, token: String): String {
        try {
            val d = HarborSocial.call("POST", "/social/dm/conversations", token, buildJsonObject { put("userId", userId) }) as? JsonObject
            return d?.obj("conversation")?.str("id") ?: error("Couldn't open the conversation.")
        } catch (e: HarborSocial.ApiError) {
            when {
                e.code == "dm_friends_only" -> throw HarborSocial.ApiError(403, "This person only accepts messages from friends.")
                e.code == "blocked" -> throw HarborSocial.ApiError(403, "You cannot message this person.")
                else -> throw e
            }
        }
    }

    private fun message(o: JsonObject): Message? {
        val id = o.str("id") ?: return null
        return message(id, o)
    }

    private fun message(id: String, o: JsonObject) = Message(
        id, o.str("body").orEmpty(), o.str("at").orEmpty(), o.bool("mine"), o.bool("edited"), o.bool("deleted"),
        o["reactions"].objects().mapNotNull { r -> Reaction(r.str("emoji") ?: return@mapNotNull null, r.int("count") ?: 0, r.bool("mine")) },
        o.obj("share")?.let { s -> Share(s.str("type").orEmpty(), s.str("id").orEmpty(), s.str("title"), s.str("poster")) },
        HarborSocial.author(o.obj("sender")),
    )

    suspend fun messages(conversationId: String, token: String, cursor: String? = null): MessagePage {
        val d = HarborSocial.call("GET", "/social/dm/conversations/${HarborSocial.enc(conversationId)}/messages?limit=40" +
            (cursor?.let { "&cursor=${HarborSocial.enc(it)}" } ?: ""), token) as? JsonObject ?: return MessagePage(emptyList(), null)
        return MessagePage(d["messages"].objects().mapNotNull(::message), d.str("nextCursor"))
    }

    /** Sends a message; `share` attaches a title (type "title", its id, name and poster). */
    suspend fun send(conversationId: String, body: String, token: String, share: Share? = null): Message? =
        (HarborSocial.call("POST", "/social/dm/conversations/${HarborSocial.enc(conversationId)}/messages", token, buildJsonObject {
            put("body", body)
            share?.let { s -> put("share", buildJsonObject { put("type", s.type); put("id", s.id); s.title?.let { put("title", it) }; s.poster?.let { put("poster", it) } }) }
        }) as? JsonObject)?.let(::message)

    /** One emoji, on or off; the server answers with the updated message. */
    suspend fun react(messageId: String, emoji: String, on: Boolean, token: String): Message? {
        val path = "/social/dm/messages/${HarborSocial.enc(messageId)}/reaction"
        val d = if (on) HarborSocial.call("POST", path, token, buildJsonObject { put("emoji", emoji) })
        else HarborSocial.call("DELETE", "$path?emoji=${HarborSocial.enc(emoji)}", token)
        return (d as? JsonObject)?.let(::message)
    }

    suspend fun markRead(conversationId: String, token: String) {
        HarborSocial.call("POST", "/social/dm/conversations/${HarborSocial.enc(conversationId)}/read", token)
    }

    // ---- Watch-party invites ----

    suspend fun invites(token: String): List<Invite> {
        val d = HarborSocial.call("GET", "/social/parties/invites", token) as? JsonObject ?: return emptyList()
        val all = d["pending"].objects() + d["recent"].objects()
        return all.mapNotNull { o ->
            Invite(o.str("id") ?: return@mapNotNull null, o.str("status") ?: "pending", o.str("direction") != "outgoing",
                o.str("roomCode").orEmpty(), o.str("mediaTitle"), o.str("mediaId"), o.str("posterUrl"),
                HarborSocial.author(o.obj("from")), HarborSocial.author(o.obj("to")), o.str("createdAt").orEmpty(),
                o.str("expiresAt").orEmpty(), o.bool("expired"))
        }.distinctBy { it.id }
    }

    suspend fun acceptInvite(id: String, token: String) { HarborSocial.call("POST", "/social/parties/invites/${HarborSocial.enc(id)}/accept", token) }
    suspend fun declineInvite(id: String, token: String) { HarborSocial.call("POST", "/social/parties/invites/${HarborSocial.enc(id)}/decline", token) }
}
