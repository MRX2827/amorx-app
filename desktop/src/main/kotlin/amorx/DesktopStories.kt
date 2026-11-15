package amorx

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val STORY_LENGTH_MS = 30_000L
private const val MAX_STORY_AUDIO = 1_500_000L
private const val MAX_STORY_VIDEO = 40L * 1024 * 1024

private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)
private fun durationLabel(ms: Long): String =
    "%d:%02d".format(ms / 60_000L, ms / 1000L % 60L)

private suspend fun resolveTrack(track: Track): String {
    val local = track.localPath ?: Downloads.find(track.id)?.audioPath
    if (local != null) return File(local).toPath().toUri().toASCIIString()
    return Repo.resolveAudio(track.videoUrl ?: error("Сначала выберите песню с доступным аудиофайлом"))
}

internal suspend fun exportDesktopStory(track: Track, start: Long, end: Long): File = withContext(Dispatchers.IO) {
    require(end > start && end - start <= STORY_LENGTH_MS)
    val source = resolveTrack(track)
    val out = File.createTempFile("amorx-story-", ".m4a")
    val done = AtomicBoolean(false)
    val failed = AtomicBoolean(false)
    val player = Engine.newAudioPlayer()
    var keep = false
    try {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) { done.set(true) }
            override fun error(mediaPlayer: MediaPlayer) { failed.set(true) }
        })
        val safePath = out.absolutePath.replace('\\', '/').replace("'", "\\'")
        val output = ":sout=#transcode{acodec=mp4a,ab=96,channels=2,samplerate=44100}:std{access=file,mux=mp4,dst='$safePath'}"
        check(player.media().play(source, ":start-time=${seconds(start)}", ":stop-time=${seconds(end)}", ":no-video", output)) {
            "Не удалось открыть песню"
        }
        withTimeout(55_000L) {
            while (!done.get() && !failed.get() && player.status().state() != State.ERROR) delay(125)
        }
        player.controls().stop()
        check(!failed.get() && out.length() in 1024L..MAX_STORY_AUDIO) { "Не удалось подготовить звуковой фрагмент" }
        keep = true
        out
    } finally {
        player.release()
        if (!keep) out.delete()
    }
}

@Composable
internal fun CreateStoryDialog(track: Track?, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<File?>(null) }
    var audio by remember { mutableStateOf<File?>(null) }
    var songMode by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var startSecond by remember { mutableFloatStateOf(0f) }
    val endTrack = (track?.seconds?.times(1000L) ?: STORY_LENGTH_MS).takeIf { it > 0 } ?: STORY_LENGTH_MS
    val start = (startSecond * 1000L).toLong()
    val end = minOf(endTrack, start + STORY_LENGTH_MS)

    DisposableEffect(audio) {
        val prepared = audio
        onDispose { prepared?.delete() }
    }

    fun prepare(play: Boolean) {
        busy = true
        error = null
        scope.launch {
            var pending: File? = null
            try {
                if (songMode) {
                    val song = checkNotNull(track) { "Сначала включите нужную песню" }
                    val clip = audio ?: exportDesktopStory(song, start, end).also { pending = it }
                    if (play) {
                        audio = clip
                        pending = null
                        preview = true
                    } else {
                        CloudStories.postSong(CloudSong(song.id, song.title, song.artist, song.artwork, endTrack), clip, caption, start, end).getOrThrow()
                        close()
                    }
                } else {
                    val chosen = checkNotNull(video) { "Выберите видео MP4" }
                    require(chosen.extension.equals("mp4", true) && chosen.length() in 1L..MAX_STORY_VIDEO) {
                        "Для истории выберите видео MP4 размером до 40 МБ"
                    }
                    CloudStories.postVideo(chosen, caption).getOrThrow()
                    close()
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                error = "Подготовка звука заняла слишком много времени"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (problem: Exception) {
                error = problem.message ?: "Не удалось подготовить историю"
            } finally {
                pending?.delete()
                busy = false
            }
        }
    }

    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Новая история") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!songMode, { songMode = false; preview = false }, label = { Text("Видео") }, enabled = !busy)
                    FilterChip(songMode, { songMode = true }, label = { Text("Музыка") }, enabled = !busy && track != null)
                }
                if (songMode && track != null) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium)
                    Text("${durationLabel(start)} – ${durationLabel(end)}")
                    if (endTrack > STORY_LENGTH_MS) {
                        Slider(startSecond, { startSecond = it; preview = false; audio = null },
                            valueRange = 0f..((endTrack - STORY_LENGTH_MS) / 1000f), enabled = !busy && !preview)
                    }
                    TextButton(enabled = !busy, onClick = { if (preview) preview = false else prepare(true) }) {
                        Text(if (preview) "Остановить" else "Послушать фрагмент")
                    }
                    val clip = audio
                    if (preview && clip != null) StorySound(clip.toPath().toUri().toASCIIString(), 0L, end - start,
                        onPosition = {}, onFinish = { preview = false })
                } else {
                    FilledTonalButton(enabled = !busy, onClick = { chooseSocialFile("Выберите видео для истории")?.let { video = it } }) {
                        Text(video?.name ?: "Выбрать видео MP4")
                    }
                }
                OutlinedTextField(caption, { caption = it.take(120) }, label = { Text("Подпись") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { preview = false; prepare(false) }, enabled = !busy && if (songMode) track != null else video != null) {
                Text(if (busy) "Подождите…" else "Опубликовать")
            }
        }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Отмена") } })
}

@Composable
private fun StorySound(location: String, start: Long, length: Long, onPosition: (Float) -> Unit, onFinish: () -> Unit) {
    val position by rememberUpdatedState(onPosition)
    val finish by rememberUpdatedState(onFinish)
    var failed by remember(location) { mutableStateOf(false) }
    LaunchedEffect(location, start, length) {
        val player = withContext(Dispatchers.IO) { Engine.newAudioPlayer() }
        try {
            failed = !player.media().play(location, ":start-time=${seconds(start)}", ":stop-time=${seconds(start + length)}")
            while (!failed) {
                val time = player.status().time()
                if (time >= start) position(((time - start).toFloat() / length.coerceAtLeast(1)).coerceIn(0f, 1f))
                val state = player.status().state()
                if (state == State.ENDED || time >= start + length) {
                    finish()
                    break
                }
                if (state == State.ERROR) failed = true else delay(125)
            }
        } finally {
            player.release()
        }
    }
    if (failed) Text("Не удалось воспроизвести фрагмент", color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun StoryViewer(group: StoryGroup, player: PlayerState, close: () -> Unit) {
    var index by remember(group.ownerId) { mutableIntStateOf(0) }
    val story = group.stories.getOrNull(index) ?: return
    var stats by remember(story.id) { mutableStateOf(StoryStats()) }
    var progress by remember(story.id) { mutableFloatStateOf(0f) }
    var showViews by remember(story.id) { mutableStateOf(false) }
    var issue by remember(story.id) { mutableStateOf<String?>(null) }
    val files = remember(group.ownerId) { mutableStateMapOf<String, File>() }
    var legacySource by remember(story.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PauseMusicForMedia(player)

    fun next() {
        if (index + 1 < group.stories.size) index++ else close()
    }

    suspend fun cache(selected: Story) {
        val path = selected.audioPath ?: return
        if (files[selected.id] != null) return
        val session = CloudFeed.getSession().getOrThrow()
        files[selected.id] = loadSocialMedia(session.request(CloudStories.AUDIO_DOWNLOAD_PREFIX + path).get().build(), ".m4a", MAX_STORY_AUDIO)
    }

    DisposableEffect(group.ownerId) {
        onDispose { files.values.forEach { it.delete() } }
    }
    LaunchedEffect(story.id) {
        CloudStories.markSeen(story.id)
        if (!group.isMine) launch { CloudStories.recordView(story) }
        launch { CloudStories.loadStats(story, group.isMine).onSuccess { stats = it } }
        if (story.song != null) {
            try {
                if (story.audioPath != null) cache(story)
                else {
                    val song = story.song
                    val id = song.id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                    val original = if (id != null) "https://www.youtube.com/watch?v=$id"
                        else Repo.search("${song.artist} ${song.title}").firstOrNull()?.videoUrl
                    legacySource = Repo.resolveAudio(original ?: error("Аудио этой истории недоступно"))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (problem: Exception) {
                issue = problem.message ?: "Не удалось открыть историю"
            }
        }
        val following = group.stories.getOrNull(index + 1)
        if (following?.audioPath != null) {
            try { cache(following) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
        }
    }

    Dialog(onCloseRequest = close, title = "История · ${group.name}",
        state = rememberDialogState(size = DpSize(620.dp, 580.dp)),
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyUp) false else when (event.key) {
                Key.DirectionRight -> { next(); true }
                Key.DirectionLeft -> { if (index > 0) index--; true }
                Key.DirectionUp -> if (group.isMine) { showViews = true; true } else false
                else -> false
            }
        }) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).pointerInput(story.id) {
                var distance = 0f
                detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                    if (group.isMine && distance < -30f) showViews = true
                }) { _, delta -> distance += delta }
            }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    group.stories.forEachIndexed { at, _ ->
                        LinearProgressIndicator(progress = { if (at < index) 1f else if (at == index) progress else 0f },
                            modifier = Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    B64Avatar(group.avatarB64, 42.dp, group.name)
                    Text(group.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
                if (story.videoUrl != null) DesktopVideo(story.videoUrl, onTime = { if (!showViews) progress = it }, onFinished = { if (!showViews) next() })
                else {
                    val song = story.song
                    if (song != null) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(colorFor(song.id), 210.dp, 34.dp, url = song.artwork)
                            Spacer(Modifier.height(12.dp))
                            Text(song.title, style = MaterialTheme.typography.headlineSmall)
                            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val cached = files[story.id]
                    val location = cached?.toPath()?.toUri()?.toASCIIString() ?: legacySource
                    if (location != null) {
                        val length = (story.clipEndMs - story.clipStartMs).coerceIn(1000L, STORY_LENGTH_MS)
                        StorySound(location, if (cached != null) 0L else story.clipStartMs, length,
                            onPosition = { if (!showViews) progress = it }, onFinish = { if (!showViews) next() })
                    } else if (issue == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (story.caption.isNotBlank()) Text(story.caption)
                issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (index > 0) index-- }, enabled = index > 0) { Text("Назад") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = ::next) { Text(if (index + 1 < group.stories.size) "Дальше" else "Готово") }
                    Spacer(Modifier.weight(1f))
                    if (group.isMine) {
                        TextButton(onClick = {
                            showViews = true
                            scope.launch { CloudStories.loadStats(story, true).onSuccess { stats = it } }
                        }) { Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(8.dp)); Text(stats.views.size.toString()) }
                    } else IconButton(onClick = {
                        val liked = !stats.likedByMe
                        scope.launch { CloudStories.setLike(story, liked).onSuccess { stats = stats.copy(likedByMe = liked) }
                            .onFailure { issue = it.message } }
                    }) {
                        Icon(if (stats.likedByMe) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится")
                    }
                }
            }
        }
    }
    if (showViews) AlertDialog(onDismissRequest = { showViews = false },
        title = { Text("Кто посмотрел") },
        text = {
            LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stats.views, key = { it.id }) { person ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        B64Avatar(person.avatarB64, 36.dp, person.name)
                        Text(person.name)
                    }
                }
                if (stats.views.isEmpty()) item { Text("Пока нет просмотров") }
            }
        }, confirmButton = { TextButton(onClick = { showViews = false }) { Text("Закрыть") } })
}
