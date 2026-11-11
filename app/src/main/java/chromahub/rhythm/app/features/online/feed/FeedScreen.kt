package chromahub.rhythm.app.features.online.feed

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.PeopleScreen
import chromahub.rhythm.app.features.online.PlayerLikeSheet
import chromahub.rhythm.app.features.online.ProfileStore
import chromahub.rhythm.app.features.online.RemoteProfile
import chromahub.rhythm.app.features.online.stories.CloudFriends
import chromahub.rhythm.app.features.online.stories.StoriesRow
import chromahub.rhythm.app.features.online.stories.timeAgo
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.UUID

private val commentIcon = MaterialSymbolIcon("chat_bubble")
private val photoIcon = MaterialSymbolIcon("photo")
private val videoIcon = MaterialSymbolIcon("videocam")

private class FeedTimeline(private val authorId: String? = null, private val own: Boolean = false) {
    var posts by mutableStateOf<List<FeedPost>>(emptyList())
        private set
    var session by mutableStateOf<FeedSession?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var request = 0

    suspend fun load(more: Boolean = false) {
        if (more && (loading || !hasMore)) return
        val generation = ++request
        loading = true
        error = null
        try {
            val page = CloudFeed.page(authorId, if (more) posts.lastOrNull() else null, own)
            if (generation != request) return
            page.onSuccess {
                val append = more && session?.userId == it.session.userId
                session = it.session
                posts = if (append) (posts + it.posts).distinctBy(FeedPost::id) else it.posts
                hasMore = it.hasMore
            }.onFailure { error = it.message }
        } finally {
            if (generation == request) loading = false
        }
    }

    fun change(postId: String, transform: (FeedPost) -> FeedPost) {
        posts = posts.map { if (it.id == postId) transform(it) else it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedHome(musicViewModel: MusicViewModel, bottomPadding: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember(context) { CloudProfiles.init(context); ProfileStore.init(context) }
    val timeline = remember { FeedTimeline() }
    val revision by CloudFeed.changes.collectAsState()
    val friends by CloudFriends.state.collectAsState()
    val friendIds = friends.friends.map { it.id }
    val viewer = timeline.session
    var composing by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf(false) }
    var selectedAuthor by remember { mutableStateOf<RemoteProfile?>(null) }
    var opened by remember { mutableStateOf<FeedPost?>(null) }
    var commentsFor by remember { mutableStateOf<FeedPost?>(null) }
    var deleting by remember { mutableStateOf<FeedPost?>(null) }
    val liking = remember { mutableSetOf<String>() }

    LaunchedEffect(revision, friendIds) { timeline.load() }

    Box(modifier) {
        PullToRefreshBox(isRefreshing = timeline.loading, onRefresh = { scope.launch { timeline.load() } },
            modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + bottomPadding)
            ) {
                item(key = "stories") { StoriesRow(musicViewModel) }
                item(key = "feed_actions") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Лента", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { selectedAuthor = null; people = true }) { Icon(RhythmIcons.Search, "Найти людей") }
                        Button(onClick = { composing = true }) {
                            Icon(RhythmIcons.Add, null, Modifier.size(19.dp))
                            Text("Публикация", Modifier.padding(start = 5.dp))
                        }
                    }
                }
                if (timeline.posts.isEmpty() && !timeline.loading && timeline.error == null) {
                    item(key = "empty") {
                        Text("Здесь появятся публикации друзей и подписок",
                            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (viewer != null) items(timeline.posts, key = { it.id }) { post ->
                    FeedPostCard(post, viewer, onOpen = { opened = post }, onComments = { commentsFor = post },
                        onAuthor = {
                            selectedAuthor = RemoteProfile(post.authorId, post.authorName, "", null, emptyList(), emptyList())
                            people = true
                        },
                        onDelete = if (post.authorId == viewer.userId) ({ deleting = post }) else null,
                        onLike = {
                            if (liking.add(post.id)) scope.launch {
                                val next = !post.liked
                                fun update(liked: Boolean, count: Int) = timeline.change(post.id) {
                                    it.copy(liked = liked, likes = count.coerceAtLeast(0))
                                }
                                update(next, post.likes + if (next) 1 else -1)
                                try {
                                    CloudFeed.setLiked(post, next).onFailure {
                                        update(post.liked, post.likes)
                                        Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                                    }
                                } finally {
                                    liking.remove(post.id)
                                }
                            }
                        })
                }
                val error = timeline.error
                if (error != null || timeline.hasMore) {
                    item(key = "next") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (error != null) Text(error, Modifier.padding(horizontal = 16.dp))
                            TextButton(enabled = !timeline.loading,
                                onClick = { scope.launch { timeline.load(more = timeline.posts.isNotEmpty() && timeline.hasMore) } }) {
                                Text(if (error != null) "Повторить" else "Показать ещё")
                            }
                        }
                    }
                }
            }
        }
        PlayerLikeSheet(visible = people) {
            PeopleScreen(musicViewModel, onBack = { people = false }, initialPerson = selectedAuthor)
        }
    }

    if (composing) FeedComposer(onDismiss = { composing = false })
    opened?.let { post -> FeedMediaDialog(post, viewer, onDismiss = { opened = null }) }
    commentsFor?.let { post ->
        FeedCommentsSheet(post, onDismiss = { commentsFor = null }, onAdded = {
            timeline.change(post.id) { it.copy(comments = it.comments + 1) }
        })
    }
    deleting?.let { post ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить публикацию?") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        CloudFeed.delete(post).onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("Удалить") }
            }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
    }
}

@Composable
private fun FeedPostCard(post: FeedPost, session: FeedSession, onOpen: () -> Unit, onLike: () -> Unit,
                         onComments: () -> Unit, onAuthor: () -> Unit, onDelete: (() -> Unit)?) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Text(post.authorName.take(1).uppercase(), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Column(Modifier.weight(1f).clickable(onClick = onAuthor).padding(vertical = 5.dp)) {
                    Text(post.authorName, style = MaterialTheme.typography.titleSmall)
                    Text(feedTime(post.createdAt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(RhythmIcons.Delete, "Удалить публикацию") }
            }
            FeedThumbnail(post, session, Modifier.fillMaxWidth().aspectRatio(1f), onOpen)
            Row(Modifier.padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike) {
                    Icon(if (post.liked) RhythmIcons.FavoriteFilled else RhythmIcons.Favorite,
                        if (post.liked) "Убрать отметку «Нравится»" else "Нравится",
                        tint = if (post.liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                Text(post.likes.toString(), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onComments) { Icon(commentIcon, "Комментарии") }
                Text(post.comments.toString(), style = MaterialTheme.typography.labelLarge)
            }
            if (post.caption.isNotBlank()) Text(post.caption, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
        }
    }
}

@Composable
internal fun FeedThumbnail(post: FeedPost, session: FeedSession, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val path = if (post.isVideo) post.previewPath else post.path
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (path != null) {
            val model = remember(context, session, path) { feedImage(context, session, path) }
            AsyncImage(model, if (post.isVideo) null else "Фото", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (post.isVideo) Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape) {
            Icon(RhythmIcons.Play, "Смотреть видео", Modifier.padding(8.dp).size(24.dp), tint = Color.White)
        }
    }
}

private fun feedImage(context: Context, session: FeedSession, path: String): ImageRequest =
    ImageRequest.Builder(context).data(CloudFeed.mediaUrl(path))
        .memoryCacheKey("${session.userId}:$path").diskCachePolicy(CachePolicy.DISABLED).apply {
            session.headers.forEach { (name, value) -> addHeader(name, value) }
        }.build()

private fun feedTime(value: String): String =
    runCatching { timeAgo(OffsetDateTime.parse(value).toInstant().toEpochMilli()) }.getOrDefault("")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedComposer(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var id by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            picked = uri
            id = UUID.randomUUID().toString()
            error = null
        }
    }
    val uri = picked
    val isVideo = remember(uri) { uri?.let { context.contentResolver.getType(it)?.startsWith("video/") } == true }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Новая публикация", style = MaterialTheme.typography.titleLarge)
            if (uri != null) {
                val previewModifier = Modifier.fillMaxWidth().heightIn(max = 270.dp).aspectRatio(1.35f)
                    .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                if (isVideo) Box(previewModifier, contentAlignment = Alignment.Center) {
                    Icon(videoIcon, "Выбрано видео", Modifier.size(44.dp))
                } else AsyncImage(uri, "Выбранное фото", previewModifier, contentScale = ContentScale.Fit)
            }
            TextButton(enabled = !busy, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }) {
                Icon(photoIcon, null, Modifier.padding(end = 8.dp).size(23.dp))
                Text(if (uri == null) "Выбрать фото или видео" else "Заменить файл")
            }
            OutlinedTextField(caption, { caption = it.take(1000) }, Modifier.fillMaxWidth(),
                enabled = !busy, label = { Text("Подпись") }, maxLines = 4)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = uri != null && !busy, onClick = {
                if (uri != null) {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val media = prepareFeedMedia(context, uri)
                            try {
                                CloudFeed.publish(id, ProfileStore.name.value, caption, media).getOrThrow()
                                onDismiss()
                            } finally {
                                media.cleanup()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Не удалось опубликовать"
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text(if (busy) "Публикуем…" else "Опубликовать") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedCommentsSheet(post: FeedPost, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<FeedComment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var draftId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(more: Boolean = false) {
        loading = true
        error = null
        try {
            CloudFeed.comments(post.id, if (more) comments.size else 0).onSuccess {
                hasMore = it.size == CloudFeed.COMMENT_PAGE_SIZE
                comments = if (more) (comments + it).distinctBy(FeedComment::id) else it
            }.onFailure { error = it.message }
        } finally {
            loading = false
        }
    }
    LaunchedEffect(post.id) { load() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Комментарии", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(comments, key = { it.id }) { comment ->
                    Column {
                        Text(comment.authorName, style = MaterialTheme.typography.titleSmall)
                        Text(comment.text)
                        Text(feedTime(comment.createdAt), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (comments.isEmpty() && !loading && error == null) item { Text("Пока нет комментариев") }
                if (hasMore && !loading) item {
                    TextButton(onClick = { scope.launch { load(more = true) } }) { Text("Показать ещё") }
                }
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp))
            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { load() } }) { Text("Обновить") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.take(500); draftId = UUID.randomUUID().toString() },
                    Modifier.weight(1f), enabled = !sending, label = { Text("Комментарий") }, maxLines = 3)
                TextButton(enabled = text.isNotBlank() && !sending, onClick = {
                    sending = true
                    scope.launch {
                        try {
                            CloudFeed.comment(post.id, draftId, ProfileStore.name.value, text).onSuccess {
                                text = ""
                                draftId = UUID.randomUUID().toString()
                                onAdded()
                                load()
                            }.onFailure { error = it.message }
                        } finally {
                            sending = false
                        }
                    }
                }) { Text("Отправить") }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun FeedMediaDialog(post: FeedPost, session: FeedSession?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    var player by remember(post.id) { mutableStateOf<ExoPlayer?>(null) }
    LaunchedEffect(post.id) {
        if (!post.isVideo) return@LaunchedEffect
        CloudFeed.getSession().onSuccess { fresh ->
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(fresh.headers)
            val source = DefaultMediaSourceFactory(context).setDataSourceFactory(http)
            player = ExoPlayer.Builder(context).setMediaSourceFactory(source)
                .setAudioAttributes(AudioAttributes.DEFAULT, true).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(errorValue: PlaybackException) {
                        error = "Не удалось воспроизвести видео"
                    }
                })
                setMediaItem(MediaItem.Builder().setUri(CloudFeed.mediaUrl(post.path)).setMimeType(post.mimeType).build())
                prepare()
                playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }.onFailure { error = it.message }
    }
    val currentPlayer = player
    if (currentPlayer != null) DisposableEffect(currentPlayer, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) currentPlayer.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            currentPlayer.release()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (post.isVideo) {
                if (currentPlayer != null) AndroidView(
                    factory = { PlayerView(it).apply { useController = true } },
                    update = { it.player = currentPlayer },
                    modifier = Modifier.fillMaxSize()
                ) else if (error == null) CircularProgressIndicator()
            } else if (session != null) {
                val image = remember(context, session, post.path) { feedImage(context, session, post.path) }
                AsyncImage(image, "Фото", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    onError = { error = "Не удалось открыть фото" })
            }
            error?.let { Text(it, color = Color.White, modifier = Modifier.padding(20.dp)) }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                Icon(RhythmIcons.Close, "Закрыть", tint = Color.White)
            }
        }
    }
}

@Composable
internal fun FeedProfileGrid(authorId: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember(context) { CloudProfiles.init(context) }
    val timeline = remember(authorId) { FeedTimeline(authorId, own = authorId == null) }
    val revision by CloudFeed.changes.collectAsState()
    var opened by remember { mutableStateOf<FeedPost?>(null) }
    LaunchedEffect(authorId, revision) { timeline.load() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Публикации", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleLarge)
        val session = timeline.session
        if (session != null) timeline.posts.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { post ->
                    FeedThumbnail(post, session, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))) { opened = post }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (!timeline.loading && timeline.posts.isEmpty() && timeline.error == null) {
            Text("Пока нет публикаций", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val error = timeline.error
        if (error != null || timeline.hasMore) {
            TextButton(enabled = !timeline.loading, onClick = {
                scope.launch { timeline.load(more = timeline.posts.isNotEmpty() && timeline.hasMore) }
            }) { Text(if (error != null) "Повторить" else "Показать ещё") }
        }
    }
    opened?.let { FeedMediaDialog(it, timeline.session) { opened = null } }
}
