package chromahub.rhythm.app.features.online

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Exports independent files, not app-private download paths. */
object PublicSongExport {
    const val PERMISSION_REQUEST = 9461
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap.newKeySet<String>()
    private var awaitingPermission: Pair<Context, Song>? = null
    fun start(context: Context, song: Song) {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            var current: Context? = context
            while (current is ContextWrapper && current !is Activity) current = current.baseContext
            val activity = current as? Activity
            if (activity == null) { toast(context, "Не удалось запросить доступ к хранилищу"); return }
            awaitingPermission = context.applicationContext to song
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST)
            return
        }
        val app = context.applicationContext
        val id = ArtworkUtil.videoId(song.id) ?: song.id.removePrefix("ytm_").removePrefix("streaming_").removePrefix("piped_").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        if (song.uri.scheme in listOf("file", "content")) { copy(app, song); return }
        if (id != null && id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            OnlineDownloads.init(app)
            OnlineDownloads.download(OnlineTrack("https://www.youtube.com/watch?v=$id", song.title, song.artist, song.duration, song.artworkUri?.toString()), exportToStorage = true)
            toast(app, "Скачивание в Download/amorx/song началось")
        } else toast(app, "Для этой песни нет доступного аудиофайла")
    }
    fun permissionResult(granted: Boolean) {
        val pending = awaitingPermission; awaitingPermission = null
        pending?.let { if (granted) start(it.first, it.second) else toast(it.first, "Для сохранения нужен доступ к хранилищу") }
    }
    fun copy(context: Context, song: Song) {
        if (!active.add(song.id)) { toast(context, "Песня уже сохраняется"); return }
        scope.launch {
            var created: Uri? = null
            var partial: File? = null
            try {
                val resolver = context.contentResolver
                val prefs = context.getSharedPreferences("public_song_exports", Context.MODE_PRIVATE)
                val old = prefs.getString(song.id, null)?.let(Uri::parse)
                if (old != null && runCatching { resolver.openAssetFileDescriptor(old, "r")?.use { true } ?: false }.getOrDefault(false)) {
                    toast(context, "Уже сохранено в Download/amorx/song"); return@launch
                }
                val mime = resolver.getType(song.uri)
                val originalName = runCatching {
                    resolver.query(song.uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: song.uri.lastPathSegment.orEmpty()
                val suffix = when (mime) { "audio/mpeg" -> "mp3"; "audio/mp4", "audio/x-m4a" -> "m4a"; "audio/webm" -> "webm"; "audio/ogg" -> "ogg"; "audio/flac" -> "flac"; "audio/wav", "audio/x-wav" -> "wav"; else -> originalName.substringAfterLast('.', "").lowercase().takeIf { it in listOf("m4a","mp4","mp3","aac","webm","ogg","opus","flac","wav","aif","aiff","amr","3gp","mid","midi") }
                    ?: mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }
                    ?: throw IOException("Не удалось определить формат аудио") }
                val type = mime ?: when (suffix) { "mp3" -> "audio/mpeg"; "webm" -> "audio/webm"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aac" -> "audio/aac"; else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix) ?: "application/octet-stream" }
                val name = safeName("${song.artist} - ${song.title}") + ".$suffix"
                val destination: Uri
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, type)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/amorx/song/")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Не удалось создать файл")
                    created = destination
                    val count = resolver.openInputStream(song.uri)?.use { input ->
                        resolver.openOutputStream(destination, "w")?.use { output -> input.copyTo(output) } ?: throw IOException("Нет доступа к файлу")
                    } ?: throw IOException("Аудиофайл недоступен")
                    if (count <= 0) throw IOException("Аудиофайл пуст")
                    if (resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1) throw IOException("Не удалось завершить сохранение")
                    created = null
                } else {
                    @Suppress("DEPRECATION")
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "amorx/song")
                    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Не удалось создать папку")
                    var target = File(folder, name); var n = 1
                    while (target.exists()) { target = File(folder, name.removeSuffix(".$suffix") + " (${n++}).$suffix") }
                    val temp = File.createTempFile(".amorx-", ".part", folder); partial = temp
                    val count = resolver.openInputStream(song.uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: throw IOException("Аудиофайл недоступен")
                    if (count <= 0) throw IOException("Аудиофайл пуст")
                    while (true) {
                        try { java.nio.file.Files.move(temp.toPath(), target.toPath()); break }
                        catch (_: java.nio.file.FileAlreadyExistsException) { target = File(folder, name.removeSuffix(".$suffix") + " (${n++}).$suffix") }
                    }
                    partial = null; destination = Uri.fromFile(target)
                    MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(type), null)
                }
                prefs.edit().putString(song.id, destination.toString()).apply()
                toast(context, "Сохранено: Download/amorx/song/$name")
            } catch (e: Exception) {
                created?.let { runCatching { context.contentResolver.delete(it, null, null) } }; partial?.delete()
                toast(context, "Не удалось сохранить: ${e.message}")
            } finally { active.remove(song.id) }
        }
    }
    internal fun safeName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(80).ifBlank { "AmorX song" }
    private fun toast(context: Context, message: String) { scope.launch(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() } }
}
