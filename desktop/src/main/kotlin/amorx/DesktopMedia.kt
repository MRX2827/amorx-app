package amorx

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.InputEvents
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

internal data class PreparedFeedMedia(val file: File, val mimeType: String, val extension: String, val preview: File? = null)

private val videoTypes = mapOf("mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "3gp" to "video/3gpp")
internal const val MAX_FEED_VIDEO = 40L * 1024 * 1024
private const val MAX_PHOTO = 24L * 1024 * 1024

internal fun chooseSocialFile(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    return try {
        dialog.isVisible = true
        dialog.files.firstOrNull()
    } finally {
        dialog.dispose()
    }
}

internal suspend fun prepareDesktopFeedMedia(file: File): PreparedFeedMedia = withContext(Dispatchers.IO) {
    require(file.isFile && file.length() > 0L) { "Выберите фото или видео." }
    val extension = file.extension.lowercase()
    val videoMime = videoTypes[extension]
    if (videoMime != null) {
        require(file.length() <= MAX_FEED_VIDEO) { "Видео публикации должно быть до 40 МБ." }
        return@withContext PreparedFeedMedia(file, videoMime, extension)
    }
    require(file.length() <= MAX_PHOTO) { "Фото публикации должно быть до 24 МБ." }
    val photo = readPhoto(file)
    val ratio = minOf(1.0, 1600.0 / maxOf(photo.width, photo.height))
    val width = (photo.width * ratio).toInt().coerceAtLeast(1)
    val height = (photo.height * ratio).toInt().coerceAtLeast(1)
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(photo, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    val stage = File.createTempFile("amorx-post-", ".jpg")
    try {
        check(ImageIO.write(output, "jpeg", stage)) { "Не удалось сохранить фото." }
        PreparedFeedMedia(stage, "image/jpeg", "jpg")
    } catch (error: Throwable) {
        stage.delete()
        throw error
    }
}

private fun readPhoto(file: File): BufferedImage {
    return ImageIO.createImageInputStream(file).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
            ?: throw IOException("Выберите фото JPG или PNG либо видео MP4, MOV или WebM.")
        try {
            reader.input = input
            val size = maxOf(reader.getWidth(0), reader.getHeight(0))
            val parameter = reader.defaultReadParam
            val step = (size / 2200).coerceAtLeast(1)
            parameter.setSourceSubsampling(step, step, 0, 0)
            reader.read(0, parameter)
        } finally {
            reader.dispose()
        }
    }
}

@Composable
internal fun FeedPhoto(session: FeedSession, path: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val photo by produceState<ImageBitmap?>(null, path, session.userId) {
        value = withContext(Dispatchers.IO) {
            try {
                CloudProfiles.response(session.request(CloudFeed.mediaUrl(path)).build()).use { result ->
                    if (!result.isSuccessful) return@use null
                    val source = result.body?.source() ?: return@use null
                    if (source.request(6L * 1024 * 1024 + 1)) return@use null
                    source.inputStream().use { loadImageBitmap(it) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val loaded = photo
        if (loaded != null) Image(loaded, null, Modifier.fillMaxSize(), contentScale = contentScale)
        else Icon(Icons.Rounded.Image, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal suspend fun loadSocialMedia(request: Request, suffix: String, byteLimit: Long): File = withContext(Dispatchers.IO) {
    val file = File.createTempFile("amorx-view-", suffix)
    try {
        CloudProfiles.response(request).use { reply ->
            if (!reply.isSuccessful) throw IOException("Медиа недоступно (${reply.code})")
            val body = reply.body ?: throw IOException("Сервер вернул пустой файл")
            if (body.contentLength() > byteLimit) throw IOException("Файл превышает лимит размера")
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > byteLimit) throw IOException("Файл превышает лимит размера")
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        file
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

@Composable
internal fun DesktopVideo(location: String, modifier: Modifier = Modifier, onTime: (Float) -> Unit = {}, onFinished: () -> Unit = {}) {
    val component = remember { CallbackMediaPlayerComponent(Engine.videoFactory(), null, InputEvents.NONE, true, null) }
    val onPosition by rememberUpdatedState(onTime)
    val onFinish by rememberUpdatedState(onFinished)
    var paused by remember(location) { mutableStateOf(false) }
    var failed by remember(location) { mutableStateOf(false) }
    DisposableEffect(component) {
        onDispose { component.release() }
    }
    LaunchedEffect(location) {
        val player = component.mediaPlayer()
        failed = !player.media().play(location)
        while (!failed) {
            val status = player.status()
            onPosition(status.position().coerceIn(0f, 1f))
            when (status.state()) {
                uk.co.caprica.vlcj.player.base.State.ENDED -> {
                    onFinish()
                    break
                }
                uk.co.caprica.vlcj.player.base.State.ERROR -> failed = true
                else -> delay(200)
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (failed) Text("Не удалось воспроизвести видео", color = MaterialTheme.colorScheme.error)
        else SwingPanel(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            background = androidx.compose.ui.graphics.Color.Black,
            factory = { component }
        )
        IconButton(enabled = !failed, onClick = {
            paused = !paused
            component.mediaPlayer().controls().setPause(paused)
        }) {
            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Смотреть" else "Пауза")
        }
    }
}
