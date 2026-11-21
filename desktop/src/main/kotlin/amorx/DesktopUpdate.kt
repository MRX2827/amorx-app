package amorx

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

internal data class DesktopArtifact(val url: String, val size: Long, val sha256: String)
internal data class DesktopRelease(val version: String, val notes: String, val artifact: DesktopArtifact)

internal object DesktopUpdate {
    const val currentVersion = "5.5.499.1325"
    private const val channel = "https://mrx2827.github.io/amorxsyte/desktop-update.json"
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val platform = if (windows) "windows-x64" else "macos-intel"
    private val root get() = File(AppDir.root, "desktop-updates")
    private val validVersion = Regex("(0|[1-9][0-9]{0,8})(\\.(0|[1-9][0-9]{0,8})){3}")
    private val sha = Regex("[0-9a-f]{64}")

    private fun filename(version: String) =
        "AmorX-Desktop-$version-" + if (windows) "Windows-x64.exe" else "macOS-Intel.zip"

    private fun newer(version: String): Boolean {
        if (!validVersion.matches(version)) return false
        return version.split(".").zip(currentVersion.split("."))
            .map { (remote, current) -> remote.toInt().compareTo(current.toInt()) }
            .firstOrNull { it != 0 } == 1
    }

    private fun valid(release: DesktopRelease): Boolean = runCatching {
        if (!newer(release.version) || release.artifact.size !in 1..600_000_000L || !sha.matches(release.artifact.sha256)) return false
        val uri = URI(release.artifact.url)
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path.equals("/MRX2827/amorxsyte/releases/download/desktop-${release.version}/${filename(release.version)}", false)
    }.getOrDefault(false)

    suspend fun check(): DesktopRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(channel).cacheControl(CacheControl.FORCE_NETWORK).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val source = body.source()
                if (source.request(65_537)) return@withContext null
                val data = source.readByteArray()
                if (data.size > 65_536) return@withContext null
                val json = JsonParser.parseString(String(data, Charsets.UTF_8)).asJsonObject
                val out = DesktopRelease(json.get("version").asString, json.get("notes")?.asString.orEmpty(),
                    gson.fromJson(json.getAsJsonObject("assets").get(platform), DesktopArtifact::class.java))
                out.takeIf(::valid)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun install(release: DesktopRelease) = withContext(Dispatchers.IO) {
        check(valid(release)) { "Сборка обновления не прошла проверку" }
        root.mkdirs()
        val stage = Files.createTempDirectory(root.toPath(), ".update-").toFile()
        try {
            val asset = File(stage, filename(release.version))
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(release.artifact.url).build()
            client.newCall(request).execute().use { reply ->
                if (!reply.isSuccessful || reply.request.url.scheme != "https") throw IOException("Не удалось скачать новую версию")
                val body = reply.body ?: throw IOException("Сервер вернул пустой файл")
                body.byteStream().use { input ->
                    asset.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > release.artifact.size) throw IOException("Размер обновления отличается от ожидаемого")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            if (asset.length() != release.artifact.size || HexFormat.of().formatHex(digest.digest()) != release.artifact.sha256) {
                throw IOException("Контрольная сумма обновления не совпала")
            }
            val program = if (windows) asset else {
                val target = File(stage, "AmorX.app")
                val extraction = ProcessBuilder("/usr/bin/ditto", "-x", "-k", asset.path, stage.path).redirectErrorStream(true).start()
                extraction.inputStream.use { it.readBytes() }
                check(extraction.waitFor() == 0 && target.isDirectory) { "Не удалось распаковать приложение" }
                val verify = ProcessBuilder("/usr/bin/codesign", "--verify", "--strict", "--deep", target.path).redirectErrorStream(true).start()
                verify.inputStream.use { it.readBytes() }
                check(verify.waitFor() == 0) { "Подпись нового AmorX не прошла проверку" }
                asset.delete()
                target
            }
            val destination = File(root, release.version)
            if (destination.exists() && !validInstalled(File(destination, program.name), release)) {
                check(destination.deleteRecursively()) { "Не удалось заменить повреждённую копию обновления" }
            }
            if (!destination.exists()) Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            start(File(destination, program.name))
            writeLocalJson(File(root, "installed.json"), gson.toJson(release))
        } finally {
            stage.deleteRecursively()
        }
    }

    fun openInstalled(): Boolean = runCatching {
        val active = gson.fromJson(File(root, "installed.json").readText(), DesktopRelease::class.java)
        if (!valid(active)) return false
        val program = File(File(root, active.version), if (windows) filename(active.version) else "AmorX.app")
        if (!validInstalled(program, active)) return false
        start(program)
        true
    }.getOrDefault(false)

    private fun validInstalled(program: File, release: DesktopRelease): Boolean {
        if (windows) {
            if (!program.isFile || program.length() != release.artifact.size) return false
            val digest = MessageDigest.getInstance("SHA-256")
            program.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest()) == release.artifact.sha256
        }
        if (!program.isDirectory) return false
        val verify = ProcessBuilder("/usr/bin/codesign", "--verify", "--strict", "--deep", program.path).redirectErrorStream(true).start()
        verify.inputStream.use { it.readBytes() }
        return verify.waitFor() == 0
    }

    private fun start(program: File) {
        if (windows) ProcessBuilder(program.absolutePath).start()
        else ProcessBuilder("/usr/bin/open", "-n", program.absolutePath).start()
    }
}

@Composable
internal fun DesktopUpdateBanner(onInstalled: () -> Unit) {
    var release by remember { mutableStateOf<DesktopRelease?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { release = DesktopUpdate.check() }
    val update = release ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(message ?: "Доступно обновление AmorX ${update.version}")
            TextButton(enabled = !busy, onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        DesktopUpdate.install(update)
                        onInstalled()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        message = "Обновление не установилось. Повторите попытку."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Загружаем…" else "Обновить") }
        }
    }
}
