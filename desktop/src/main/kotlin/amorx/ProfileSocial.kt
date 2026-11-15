package amorx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
internal fun ProfileSocialStats(authorId: String?) {
    val account by CloudProfiles.activeAccount
    val changes by CloudFeed.changes.collectAsState()
    var counts by remember(authorId, account) { mutableStateOf<FeedCounts?>(null) }
    LaunchedEffect(authorId, account, changes) {
        if (account != null) CloudFeed.counts(authorId).onSuccess { counts = it }
    }
    val stats = counts ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stats.followers.toString(), style = MaterialTheme.typography.titleLarge)
            Text("Подписчики", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stats.following.toString(), style = MaterialTheme.typography.titleLarge)
            Text("Подписки", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FollowAndFriendButtons(authorId: String) {
    val account by CloudProfiles.activeAccount
    if (account == null) return
    val scope = rememberCoroutineScope()
    val friends by CloudFriends.state.collectAsState()
    var following by remember(authorId, account) { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authorId, account) {
        launch { CloudFriends.refresh() }
        CloudFeed.isFollowing(authorId).onSuccess { following = it }.onFailure { problem = it.message }
    }
    following?.let { current ->
        FilledTonalButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                CloudFeed.setFollowing(authorId, !current).onSuccess { following = !current }.onFailure { problem = it.message }
                busy = false
            }
        }) { Text(if (current) "Отписаться" else "Подписаться") }
    }
    val friendStatus = remember(friends, authorId) { CloudFriends.statusOf(authorId) }
    OutlinedButton(enabled = !busy, onClick = {
        scope.launch {
            busy = true
            val request = when (friendStatus) {
                FriendStatus.NONE -> CloudFriends.request(authorId)
                FriendStatus.INCOMING -> CloudFriends.accept(authorId)
                FriendStatus.OUTGOING, FriendStatus.FRIENDS -> CloudFriends.remove(authorId)
            }
            request.onFailure { problem = it.message }
            busy = false
        }
    }) {
        Text(when (friendStatus) {
            FriendStatus.NONE -> "В друзья"
            FriendStatus.INCOMING -> "Принять"
            FriendStatus.OUTGOING -> "Отменить запрос"
            FriendStatus.FRIENDS -> "Убрать из друзей"
        })
    }
    val message = problem
    if (message != null) AlertDialog(onDismissRequest = { problem = null },
        title = { Text("AmorX") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { problem = null }) { Text("Закрыть") } })
}

@Composable
internal fun ProfilePostGallery(authorId: String?, player: PlayerState) {
    val account by CloudProfiles.activeAccount
    if (account == null) return
    val changes by CloudFeed.changes.collectAsState()
    var page by remember(authorId, account) { mutableStateOf<FeedPage?>(null) }
    var selected by remember(authorId) { mutableStateOf<FeedPost?>(null) }
    LaunchedEffect(authorId, account, changes) {
        CloudFeed.page(authorId, null, own = authorId == null).onSuccess { page = it }
    }
    val current = page ?: return
    if (current.posts.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Публикации", style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(current.posts, key = FeedPost::id) { post ->
                Box(Modifier.size(160.dp).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { selected = post },
                    contentAlignment = Alignment.Center) {
                    val photo = if (post.isVideo) post.previewPath else post.path
                    if (photo != null) FeedPhoto(current.session, photo, Modifier.fillMaxSize())
                    if (post.isVideo) Icon(Icons.Rounded.PlayArrow, "Смотреть видео")
                }
            }
        }
    }
    val post = selected ?: return
    if (post.isVideo) FeedVideoDialog(post, player) { selected = null }
    else Dialog(onCloseRequest = { selected = null }, title = post.authorName) {
        Surface {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                FeedPhoto(current.session, post.path, Modifier.fillMaxWidth().weight(1f), ContentScale.Fit)
                if (post.caption.isNotBlank()) Text(post.caption, Modifier.padding(8.dp))
                TextButton(onClick = { selected = null }) { Text("Закрыть") }
            }
        }
    }
}
