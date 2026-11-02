package chromahub.rhythm.app.features.online.stories

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Кнопка на странице человека: добавить в друзья, принять запрос, отменить, удалить. */
@Composable
fun FriendButton(personId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by CloudFriends.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(personId) { CloudFriends.refresh() }
    val status = remember(state, personId) { CloudFriends.statusOf(personId) }

    fun act(okMessage: String?, block: suspend () -> Result<Unit>) {
        if (busy) return
        scope.launch {
            busy = true
            block()
                .onSuccess { if (okMessage != null) Toast.makeText(context, okMessage, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Ошибка сети", Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }

    Row(
        modifier = modifier.animateContentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            FriendStatus.NONE -> FilledTonalButton(
                onClick = { act("Запрос отправлен") { CloudFriends.request(personId) } },
                enabled = !busy
            ) {
                Icon(StoryIcons.PersonAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("В друзья")
            }
            FriendStatus.OUTGOING -> OutlinedButton(
                onClick = { act("Запрос отменён") { CloudFriends.remove(personId) } },
                enabled = !busy
            ) {
                Icon(StoryIcons.Hourglass, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Запрос отправлен")
            }
            FriendStatus.INCOMING -> {
                Button(
                    onClick = { act("Теперь вы друзья") { CloudFriends.accept(personId) } },
                    enabled = !busy
                ) {
                    Icon(StoryIcons.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Принять")
                }
                TextButton(
                    onClick = { act("Запрос отклонён") { CloudFriends.remove(personId) } },
                    enabled = !busy
                ) { Text("Отклонить") }
            }
            FriendStatus.FRIENDS -> FilledTonalButton(onClick = { confirmRemove = true }, enabled = !busy) {
                Icon(StoryIcons.Group, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Друзья")
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Удалить из друзей?") },
            text = { Text("Человек перестанет видеть ваши истории. Снова добавить можно в любой момент.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    act("Удалён из друзей") { CloudFriends.remove(personId) }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Отмена") } }
        )
    }
}

/** Список друзей и заявок. Открывается значком «Друзья» рядом с историями. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by CloudFriends.state.collectAsState()
    var pendingRemove by remember { mutableStateOf<FriendEntry?>(null) }
    LaunchedEffect(Unit) { CloudFriends.refresh() }

    fun run(okMessage: String, block: suspend () -> Result<Unit>) {
        scope.launch {
            block()
                .onSuccess { Toast.makeText(context, okMessage, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Ошибка сети", Toast.LENGTH_SHORT).show() }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            item {
                Text("Друзья", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ваши истории видят только друзья, которых вы приняли сами.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
            if (state.incoming.isNotEmpty()) {
                item { SectionTitle("Заявки · " + state.incoming.size) }
                items(state.incoming, key = { "in_" + it.id }) { e ->
                    FriendRow(e) {
                        Button(onClick = { run("Теперь вы друзья") { CloudFriends.accept(e.id) } }) {
                            Icon(StoryIcons.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Принять")
                        }
                        IconButton(onClick = { run("Запрос отклонён") { CloudFriends.remove(e.id) } }) {
                            Icon(StoryIcons.Close, contentDescription = "Отклонить")
                        }
                    }
                }
            }
            item { SectionTitle("Ваши друзья · " + state.friends.size) }
            if (state.friends.isEmpty()) {
                item {
                    Text(
                        "Пока никого. Найдите человека в Профиле, в разделе «Найти людей», и отправьте запрос.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
            items(state.friends, key = { "fr_" + it.id }) { e ->
                FriendRow(e) {
                    IconButton(onClick = { pendingRemove = e }) {
                        Icon(StoryIcons.Delete, contentDescription = "Удалить из друзей")
                    }
                }
            }
            if (state.outgoing.isNotEmpty()) {
                item { SectionTitle("Отправленные запросы") }
                items(state.outgoing, key = { "out_" + it.id }) { e ->
                    FriendRow(e) {
                        TextButton(onClick = { run("Запрос отменён") { CloudFriends.remove(e.id) } }) { Text("Отменить") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingRemove?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Удалить из друзей?") },
            text = { Text(e.name + " перестанет видеть ваши истории.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    run("Удалён из друзей") { CloudFriends.remove(e.id) }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun FriendRow(entry: FriendEntry, trailing: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StoryAvatar(entry.avatarB64, null, entry.name, 44.dp)
        Text(
            entry.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}
