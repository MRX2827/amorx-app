package amorx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
internal fun DesktopHome(player: PlayerState, openChats: () -> Unit, openPeople: () -> Unit) {
    val account by CloudProfiles.connectedAccount
    val changes by CloudFeed.changes.collectAsState()
    val groups by CloudStories.feed.collectAsState()
    val seen by CloudStories.seen.collectAsState()
    val scope = rememberCoroutineScope()
    val profile by MyProfileStore.data
    var posts by remember(account) { mutableStateOf(emptyList<FeedPost>()) }
    var pendingLikes by remember(account) { mutableStateOf(emptySet<String>()) }
    var session by remember(account) { mutableStateOf<FeedSession?>(null) }
    var more by remember(account) { mutableStateOf(false) }
    var loading by remember(account) { mutableStateOf(false) }
    var failure by remember(account) { mutableStateOf<String?>(null) }
    var selectedPost by remember(account) { mutableStateOf<FeedPost?>(null) }
    var video by remember(account) { mutableStateOf<FeedPost?>(null) }
    var author by remember(account) { mutableStateOf<FeedPost?>(null) }
    var storyGroup by remember(account) { mutableStateOf<StoryGroup?>(null) }
    var signIn by remember { mutableStateOf(false) }
    var writePost by remember { mutableStateOf(false) }
    var writeStory by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var unread by remember(account) { mutableIntStateOf(0) }

    suspend fun load(before: FeedPost? = null) {
        loading = true
        failure = null
        try {
            CloudFeed.page(null, before, own = false).onSuccess { page ->
                posts = if (before == null) page.posts else (posts + page.posts).distinctBy { it.id }
                session = page.session
                more = page.hasMore
            }.onFailure { failure = it.message ?: "Не удалось загрузить публикации" }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(account, changes, revision) {
        if (account != null) {
            launch { CloudStories.refresh() }
            launch { CloudChat.unreadTotal().onSuccess { unread = it } }
            load()
        }
    }

    fun requireAccount(action: () -> Unit) {
        if (account == null) signIn = true else action()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().fillMaxHeight(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Главное", style = MaterialTheme.typography.headlineLarge)
                        Text("Истории и публикации", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BadgedBox(badge = { if (unread > 0) Badge { Text(unread.coerceAtMost(99).toString()) } }) {
                        FilledTonalIconButton(onClick = { requireAccount(openChats) }) {
                            Icon(Icons.AutoMirrored.Rounded.Chat, "Сообщения")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { if (account == null) signIn = true else revision++ }) {
                        Icon(Icons.Rounded.Refresh, "Обновить")
                    }
                }
            }
            item {
                val background = Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f)
                ))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(background).padding(16.dp)) {
                    Text("Истории", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            StoryPill("Добавить", null, false, add = true) { requireAccount { writeStory = true } }
                        }
                        if (account != null) items(groups, key = { it.ownerId }) { group ->
                            StoryPill(if (group.isMine) "Моя история" else group.name, group.avatarB64,
                                group.stories.any { it.id !in seen }) { storyGroup = group }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Публикации", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Button(onClick = { requireAccount { writePost = true } }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Новая публикация")
                    }
                }
            }
            if (account == null) {
                item {
                    FeedEmpty("Войдите в AmorX", "После входа здесь появятся публикации друзей и авторов, на которых вы подписаны.", "Войти") { signIn = true }
                }
            } else {
                if (failure != null) item {
                    FeedEmpty("Лента недоступна", failure.orEmpty(), "Повторить") { revision++ }
                } else if (posts.isEmpty() && !loading) item {
                    FeedEmpty("В ленте пока пусто", "Откройте профили друзей или опубликуйте своё фото или видео.", "Найти людей", openPeople)
                }
                val current = session
                if (current != null) items(posts, key = { it.id }) { post ->
                    FeedPostCard(post, current, onVideo = { video = post }, onAuthor = { author = post },
                        onComments = { selectedPost = post }, likeEnabled = post.id !in pendingLikes, onLike = {
                            if (post.id !in pendingLikes) {
                                pendingLikes = pendingLikes + post.id
                                scope.launch {
                                    try {
                                        val liked = !post.liked
                                        CloudFeed.setLiked(post, liked).onSuccess {
                                            posts = posts.map { item -> if (item.id == post.id && item.liked != liked) item.copy(
                                                liked = liked, likes = (item.likes + if (liked) 1 else -1).coerceAtLeast(0)
                                            ) else item }
                                        }.onFailure { failure = it.message }
                                    } finally {
                                        pendingLikes = pendingLikes - post.id
                                    }
                                }
                            }
                        }, onDelete = {
                            scope.launch { CloudFeed.delete(post).onSuccess { posts = posts.filterNot { it.id == post.id } }
                                .onFailure { failure = it.message } }
                        })
                }
                if (loading) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (more && failure == null) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FilledTonalButton(onClick = { scope.launch { load(posts.lastOrNull()) } }) { Text("Загрузить ещё") }
                    }
                }
            }
        }
    }

    if (signIn) AccountDialog { signIn = false }
    if (writePost) CreatePostDialog(profile.name) { writePost = false }
    if (writeStory) CreateStoryDialog(player.current?.takeIf(Track::isReal)?.copy(seconds = player.duration)) {
        writeStory = false
    }
    storyGroup?.let { group -> StoryViewer(group, player) { storyGroup = null } }
    selectedPost?.let { post -> CommentsDialog(post, profile.name, onAdded = {
        posts = posts.map { if (it.id == post.id) it.copy(comments = it.comments + 1) else it }
    }) { selectedPost = null } }
    video?.let { post -> FeedVideoDialog(post, player) { video = null } }
    author?.let { post -> FeedAuthorDialog(post, currentUser = session?.userId, onDismiss = { author = null }) }
}

@Composable
internal fun FeedEmpty(title: String, explanation: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun StoryPill(name: String, avatar: String?, unseen: Boolean, add: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(84.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(62.dp).border(2.dp,
            if (unseen || add) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center) {
            if (add) {
                Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null) }
            } else B64Avatar(avatar, 52.dp, name)
        }
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeedPostCard(
    post: FeedPost, session: FeedSession, onVideo: () -> Unit, onAuthor: () -> Unit, onComments: () -> Unit,
    likeEnabled: Boolean, onLike: () -> Unit, onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ElevatedCard(shape = RoundedCornerShape(25.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(onClick = onAuthor),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                B64Avatar(null, 37.dp, post.authorName)
                Text(post.authorName, style = MaterialTheme.typography.titleMedium)
            }
            if (post.authorId == session.userId) IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.DeleteOutline, "Удалить публикацию")
            }
        }
        val frame = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 470.dp).aspectRatio(16f / 10f)
        if (post.isVideo) {
            Box(frame.background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onVideo),
                contentAlignment = Alignment.Center) {
                post.previewPath?.let { FeedPhoto(session, it, Modifier.fillMaxSize()) }
                FilledTonalIconButton(onClick = onVideo, modifier = Modifier.size(58.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "Смотреть видео")
                }
            }
        } else FeedPhoto(session, post.path, frame, contentScale = ContentScale.Fit)
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLike, enabled = likeEnabled) {
                    Icon(if (post.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится")
                    Spacer(Modifier.width(5.dp))
                    Text(post.likes.toString())
                }
                TextButton(onClick = onComments) {
                    Icon(Icons.AutoMirrored.Rounded.Comment, "Комментарии")
                    Spacer(Modifier.width(5.dp))
                    Text(post.comments.toString())
                }
            }
            if (post.caption.isNotBlank()) Text(post.caption, Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
    }

    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить публикацию?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}

@Composable
internal fun CreatePostDialog(name: String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var id by remember { mutableStateOf(UUID.randomUUID().toString()) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Новая публикация") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(enabled = !busy, onClick = {
                    chooseSocialFile("Выберите фото или видео")?.let {
                        file = it
                        id = UUID.randomUUID().toString()
                        error = null
                    }
                }) { Text(file?.name ?: "Выбрать фото или видео", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                OutlinedTextField(caption, { caption = it.take(1000) }, label = { Text("Подпись") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = file != null && !busy, onClick = {
                val chosen = file ?: return@Button
                busy = true
                error = null
                scope.launch {
                    try {
                        val prepared = prepareDesktopFeedMedia(chosen)
                        try {
                            CloudFeed.publish(id, name, caption, prepared).getOrThrow()
                            close()
                        } finally {
                            if (prepared.file != chosen) prepared.file.delete()
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        error = failure.message ?: "Не удалось опубликовать"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Публикуем…" else "Опубликовать") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("Отмена") } })
}

@Composable
private fun CommentsDialog(post: FeedPost, name: String, onAdded: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember(post.id) { mutableStateOf(emptyList<FeedComment>()) }
    var comment by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    suspend fun fetch(offset: Int = 0) {
        loading = true
        problem = null
        CloudFeed.comments(post.id, offset).onSuccess {
            rows = if (offset == 0) it else (rows + it).distinctBy(FeedComment::id)
            more = it.size == CloudFeed.COMMENT_PAGE_SIZE
        }.onFailure { problem = it.message }
        loading = false
    }
    LaunchedEffect(post.id) { fetch() }
    AlertDialog(onDismissRequest = close, title = { Text("Комментарии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 270.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows, key = { it.id }) { item ->
                        Column {
                            Text(item.authorName, style = MaterialTheme.typography.titleSmall)
                            Text(item.text)
                        }
                    }
                    if (rows.isEmpty() && !loading && problem == null) item { Text("Пока нет комментариев") }
                    if (more) item {
                        TextButton(enabled = !loading, onClick = { scope.launch { fetch(rows.size) } }) { Text("Загрузить ещё") }
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(comment, { comment = it.take(500) }, label = { Text("Ваш комментарий") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = comment.isNotBlank() && !loading, onClick = {
                val body = comment
                scope.launch {
                    loading = true
                    problem = null
                    CloudFeed.comment(post.id, UUID.randomUUID().toString(), name, body)
                        .onSuccess { comment = ""; onAdded(); fetch() }.onFailure { problem = it.message }
                    loading = false
                }
            }) { Text("Отправить") }
        }, dismissButton = { TextButton(onClick = close) { Text("Закрыть") } })
}

@Composable
internal fun FeedVideoDialog(post: FeedPost, player: PlayerState, close: () -> Unit) {
    var media by remember(post.id) { mutableStateOf<File?>(null) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    PauseMusicForMedia(player)
    LaunchedEffect(post.id) {
        try {
            val session = CloudFeed.getSession().getOrThrow()
            val extension = post.path.substringAfterLast('.', "").takeIf { it.length in 1..6 && it.all(Char::isLetterOrDigit) } ?: "mp4"
            media = loadSocialMedia(session.request(CloudFeed.mediaUrl(post.path)).get().build(), ".$extension", MAX_FEED_VIDEO)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "Не удалось загрузить видео"
        }
    }
    DisposableEffect(media) {
        val local = media
        onDispose { local?.delete() }
    }
    Dialog(onCloseRequest = close, title = post.authorName) {
        Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val local = media
                if (local != null) DesktopVideo(local.toPath().toUri().toASCIIString()) else if (error == null) CircularProgressIndicator()
                TextButton(onClick = close) { Text("Закрыть") }
            }
        }
    }
}

@Composable
internal fun PauseMusicForMedia(player: PlayerState) {
    DisposableEffect(player) {
        val track = player.current?.id
        val playing = track != null && player.playing
        if (playing) player.toggle()
        onDispose { if (playing && player.current?.id == track && !player.playing) player.toggle() }
    }
}

@Composable
private fun FeedAuthorDialog(post: FeedPost, currentUser: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf<Boolean?>(null) }
    var counts by remember { mutableStateOf<FeedCounts?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(post.authorId) {
        launch { CloudFeed.counts(post.authorId).onSuccess { counts = it } }
        if (post.authorId != currentUser) CloudFeed.isFollowing(post.authorId).onSuccess { following = it }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(post.authorName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                B64Avatar(null, 68.dp, post.authorName)
                counts?.let { Text("Подписчики · ${it.followers}     Подписки · ${it.following}") }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            following?.let { subscribed ->
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        CloudFeed.setFollowing(post.authorId, !subscribed)
                            .onSuccess { following = !subscribed; CloudFeed.counts(post.authorId).onSuccess { counts = it } }
                            .onFailure { problem = it.message }
                        busy = false
                    }
                }) { Text(if (subscribed) "Отписаться" else "Подписаться") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
