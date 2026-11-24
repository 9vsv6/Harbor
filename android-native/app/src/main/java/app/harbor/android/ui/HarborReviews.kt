package app.harbor.android.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.HarborSocial
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.i18n.Text
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Harbor members' reviews of this title (PC 1.0.4's title reviews panel): the members' average,
 * then each review with its score, spoiler reveal, like and threaded comments. Reading needs no
 * account; liking and commenting use your Harbor sign-in.
 */
@Composable
fun HarborReviewsSection(repository: Repository, meta: Meta, imdbId: String?, side: Dp) {
    val context = LocalContext.current
    val harbor by repository.accounts.harbor.collectAsState()
    val scope = rememberCoroutineScope()
    var reviews by remember(meta.id) { mutableStateOf<List<HarborSocial.Review>?>(null) }
    var showAll by rememberSaveable(meta.id) { mutableStateOf(false) }
    var thread by remember(meta.id) { mutableStateOf<HarborSocial.Review?>(null) }
    // Your friends' ratings only (signed in): PC's Friends / Everyone switch.
    var friendsOnly by rememberSaveable(meta.id) { mutableStateOf(false) }
    val type = if (meta.type == "movie") "movie" else "series"
    LaunchedEffect(meta.id, imdbId, harbor?.token, friendsOnly) {
        val ids = listOfNotNull(imdbId, meta.id.substringBefore(':').takeIf { it.startsWith("tt") }, meta.id.takeIf { it.startsWith("tmdb:") })
        if (ids.isEmpty()) { reviews = emptyList(); return@LaunchedEffect }
        reviews = runCatching { HarborSocial.reviews(type, ids, harbor?.token, friends = friendsOnly && harbor != null) }.getOrDefault(emptyList())
    }
    val all = reviews ?: return
    if (all.isEmpty() && !friendsOnly) return
    val written = all.filter { it.text != null }
    fun signedIn(): Boolean {
        if (harbor != null) return true
        Toast.makeText(context, app.harbor.android.i18n.I18n.tr("Sign in to your Harbor account to like and comment."), Toast.LENGTH_SHORT).show()
        return false
    }
    fun like(review: HarborSocial.Review) {
        if (!signedIn()) return
        scope.launch {
            runCatching { repository.accounts.withHarbor { s -> HarborSocial.like(review.id, !review.liked, s.token) } }
                .onSuccess { (on, count) -> reviews = reviews?.map { if (it.id == review.id) it.copy(liked = on, likes = count) else it } }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't update the like.", Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.padding(horizontal = side).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(HarborColors.Accent))
            Text("Harbor members", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            HarborSocial.average(all)?.let { avg ->
                Text("★ ${"%.1f".format(Locale.US, avg)}", color = HarborColors.InkMuted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Text(if (all.size == 1) "1 rating" else "${all.size} ratings", color = HarborColors.InkSubtle, fontSize = 14.sp)
        }
        if (harbor != null) PillChoice(listOf(false to "Everyone", true to "Friends"), friendsOnly) { friendsOnly = it }
        if (all.isEmpty()) Text("None of your friends has rated this yet.", color = HarborColors.InkMuted, fontSize = 14.sp)
        (if (showAll) written else written.take(4)).forEach { r ->
            ReviewCard(
                name = r.author.name,
                avatar = r.author.avatar,
                badge = "★ ${"%.1f".format(Locale.US, r.score).removeSuffix(".0")}/10",
                kind = "Review",
                text = r.text.orEmpty(),
                spoiler = r.spoiler,
                likes = r.likes,
                replies = r.comments,
                date = r.at.take(10),
                blurred = HarborDisplay.settings.blurComments,
                liked = r.liked,
                onLike = { like(r) },
                onReplies = { thread = r },
            )
        }
        if (written.size > 4) Text(
            if (showAll) "Show fewer" else "Show all ${written.size}",
            color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { showAll = !showAll }.padding(vertical = 6.dp, horizontal = 2.dp),
        )
    }
    thread?.let { r ->
        ReviewThread(repository, r, signedIn = harbor != null, token = harbor?.token, onSignIn = { signedIn() },
            onPosted = { reviews = reviews?.map { if (it.id == r.id) it.copy(comments = it.comments + 1) else it } }) { thread = null }
    }
}

/** A review's comments and replies (one level, as PC threads them), and a box to add yours. */
@Composable
private fun ReviewThread(
    repository: Repository, review: HarborSocial.Review, signedIn: Boolean, token: String?,
    onSignIn: () -> Boolean, onPosted: () -> Unit, onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var comments by remember(review.id) { mutableStateOf<List<HarborSocial.Comment>?>(null) }
    var next by remember(review.id) { mutableStateOf<String?>(null) }
    var draft by remember(review.id) { mutableStateOf("") }
    var replyTo by remember(review.id) { mutableStateOf<HarborSocial.Comment?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(review.id) {
        runCatching { HarborSocial.comments(review.id, token = token) }
            .onSuccess { comments = it.comments; next = it.next }
            .onFailure { comments = emptyList() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = HarborColors.Elevated,
        title = { Text("Comments · ${review.author.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val list = comments
                when {
                    list == null -> Text("Loading…", color = HarborColors.InkMuted)
                    list.isEmpty() -> Text("No comments yet.", color = HarborColors.InkMuted)
                    else -> {
                        val tops = list.filter { it.parentId == null }
                        val replies = list.filter { it.parentId != null }.groupBy { it.parentId }
                        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(tops, key = { it.id }) { c ->
                                CommentRow(c, indent = false) { if (onSignIn()) replyTo = c }
                                replies[c.id].orEmpty().forEach { reply -> CommentRow(reply, indent = true, onReply = null) }
                            }
                            next?.let { cursor ->
                                item(key = "more") {
                                    Text("Load more", color = HarborColors.Accent, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable {
                                            scope.launch {
                                                runCatching { HarborSocial.comments(review.id, cursor, token) }.onSuccess { page ->
                                                    comments = (comments.orEmpty() + page.comments).distinctBy { it.id }; next = page.next
                                                }
                                            }
                                        }.padding(vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
                if (signedIn) {
                    replyTo?.let { Text("Replying to ${it.author?.name ?: "comment"} · Cancel", color = HarborColors.InkSubtle, fontSize = 12.5.sp,
                        modifier = Modifier.clickable { replyTo = null }) }
                    HTextField(draft, { draft = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text(if (replyTo != null) "Write a reply" else "Add a comment") })
                } else Text("Sign in to your Harbor account to comment.", color = HarborColors.InkSubtle, fontSize = 13.sp)
            }
        },
        confirmButton = {
            if (signedIn) TextButton(enabled = !busy && draft.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    runCatching { repository.accounts.withHarbor { s -> HarborSocial.postComment(review.id, draft.trim(), replyTo?.id, s.token) } }
                        .onSuccess { posted ->
                            posted?.let { comments = comments.orEmpty() + it }
                            draft = ""; replyTo = null; onPosted()
                        }
                        .onFailure { Toast.makeText(context, it.message ?: "Couldn't post the comment.", Toast.LENGTH_SHORT).show() }
                    busy = false
                }
            }) { Text(if (busy) "Posting…" else "Post") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun CommentRow(c: HarborSocial.Comment, indent: Boolean, onReply: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = if (indent) 22.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.author?.name ?: "Member", color = HarborColors.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(c.at.take(10) + if (c.edited) " · edited" else "", color = HarborColors.InkSubtle, fontSize = 11.5.sp)
        }
        Text(c.body, color = HarborColors.InkMuted, fontSize = 14.sp)
        onReply?.let { Text("Reply", color = HarborColors.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = it)) }
    }
}
