package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.HarborPeople
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.i18n.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shares through Android's existing DM client, without changing the social screen or its state. */
@Composable
internal fun HeroShareDialog(repository: Repository, meta: Meta, onClose: () -> Unit) {
    val account by repository.accounts.harbor.collectAsState()
    val coroutine = rememberCoroutineScope()
    var friends by remember(account?.user?.id) { mutableStateOf<List<HarborPeople.Friend>>(emptyList()) }
    var conversations by remember(account?.user?.id) { mutableStateOf<List<HarborPeople.Conversation>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember(account?.user?.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account?.user?.id) {
        if (account == null) return@LaunchedEffect
        try {
            repository.accounts.withHarbor { session ->
                friends = HarborPeople.friends(session.token)
                conversations = HarborPeople.inbox(session.token).conversations.take(6)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "That could not be sent." }
        loaded = true
    }
    fun send(label: String, conversationId: String? = null, userId: String? = null) {
        if (busy) return
        busy = true
        error = null
        val owner = account?.user?.id
        coroutine.launch {
            try {
                repository.accounts.withHarbor { session ->
                    check(session.user.id == owner)
                    val id = conversationId ?: HarborPeople.openConversation(userId ?: error("That could not be sent."), session.token)
                    check(repository.accounts.harbor.value?.user?.id == owner)
                    checkNotNull(HarborPeople.send(id, "", session.token, HarborPeople.Share("title", meta.id, meta.name, meta.poster)))
                }
                sentTo = label
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = when ((e as? app.harbor.android.data.HarborSocial.ApiError)?.code) {
                    "slow_down" -> "You are sending messages too quickly. Wait a moment."
                    "dm_friends_only" -> "That account only accepts messages from friends."
                    "blocked" -> "You cannot message that account."
                    else -> "That could not be sent."
                }
            } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(24.dp))
            .background(HarborColors.Surface).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send to a friend", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(meta.name, color = HarborColors.InkMuted, fontSize = 14.sp)
            sentTo?.let { Text(heroText("Sent to {name}", "name" to it), color = HarborColors.Accent, fontSize = 13.sp) }
            error?.let { Text(it, color = HeroActionDanger, fontSize = 13.sp) }
            when {
                account == null -> Text("Sign in to add friends and send messages.", color = HarborColors.InkMuted, fontSize = 14.sp)
                !loaded -> Text("Loading…", color = HarborColors.InkMuted, fontSize = 14.sp)
                else -> {
                    if (conversations.isNotEmpty()) Text("Recent conversations", color = HarborColors.InkSubtle, fontSize = 12.sp)
                    conversations.forEach { conversation ->
                        val label = conversation.other?.name ?: conversation.other?.handle ?: "Conversation"
                        Text(label, color = HarborColors.Ink, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) { send(label, conversationId = conversation.id) }.padding(12.dp))
                    }
                    Text("Friends", color = HarborColors.InkSubtle, fontSize = 12.sp)
                    HTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search friends") }, singleLine = true)
                    val matching = friends.filter { it.name.contains(query.trim(), true) || it.handle.contains(query.trim(), true) }.take(8)
                    if (matching.isEmpty()) Text("No friends match.", color = HarborColors.InkSubtle, fontSize = 13.sp)
                    matching.forEach { friend ->
                        Text(friend.name, color = HarborColors.Ink, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy && friend.id != null) { send(friend.name, userId = friend.id) }.padding(12.dp))
                    }
                }
            }
            HGhostButton(onClick = onClose) { Text("Close") }
        }
    }
}
