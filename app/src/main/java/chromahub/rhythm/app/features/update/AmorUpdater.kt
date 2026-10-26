package chromahub.rhythm.app.features.update

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Куда смотреть за обновлениями. Значения подставляет install_update.py,
 * либо поменяйте вручную. Пустое значение: этот источник не проверяется.
 */
object AmorUpdateConfig {
    /** Репозиторий с релизами AmorX на GitHub, например "owner/AmorX". */
    const val GITHUB_REPO = "mrx2827/amorxsyte"

    /** Адрес update.json на сайте AmorX. */
    const val SITE_MANIFEST_URL = "https://mrx2827.github.io/amorxsyte/update.json"

    /** Как часто проверять само (при открытии приложения). */
    const val AUTO_CHECK_INTERVAL_MS = 3L * 60L * 60L * 1000L

    val githubEnabled: Boolean get() = GITHUB_REPO.contains('/') && GITHUB_REPO != "OWNER/REPO"
    val siteEnabled: Boolean get() = SITE_MANIFEST_URL.startsWith("http")
}

/** FileProvider только для установки обновления (отдельный класс, чтобы не мешать другим). */
class AmorUpdateFileProvider : FileProvider()

data class AmorRelease(
    /** Версия для показа и сравнения, например 5.5.500.1305. */
    val versionLabel: String,
    val versionParts: List<Int>,
    val notes: String,
    val apkUrl: String,
    /** 0, если размер неизвестен. */
    val apkSize: Long,
    /** SHA-256 файла (hex), если источник его сообщил. */
    val sha256: String?,
    /** Обязательное обновление: окно нельзя закрыть. */
    val mandatory: Boolean,
    /** "site" или "github". */
    val source: String
)

sealed interface AmorUpdateState {
    object Idle : AmorUpdateState
    object Checking : AmorUpdateState
    object UpToDate : AmorUpdateState
    data class Available(val release: AmorRelease) : AmorUpdateState
    data class Downloading(
        val release: AmorRelease,
        val done: Long,
        val total: Long,
        val bytesPerSec: Long
    ) : AmorUpdateState

    data class Ready(val release: AmorRelease, val file: File) : AmorUpdateState

    /** release == null: не получилось проверить; иначе не получилось скачать или установить. */
    data class Failed(val release: AmorRelease?, val message: String) : AmorUpdateState
}

val AmorUpdateState.currentRelease: AmorRelease?
    get() = when (this) {
        is AmorUpdateState.Available -> this.release
        is AmorUpdateState.Downloading -> this.release
        is AmorUpdateState.Ready -> this.release
        is AmorUpdateState.Failed -> this.release
        else -> null
    }

data class AmorInstalledVersion(val label: String, val parts: List<Int>)

object AmorUpdater {
    private const val PREFS = "amor_update"
    private const val UA = "AmorX-Updater"
    private const val CACHE_DIR = "amor_update"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<AmorUpdateState>(AmorUpdateState.Idle)
    val state: StateFlow<AmorUpdateState> = _state.asStateFlow()

    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    /** Версия, баннер которой пользователь закрыл. Для неё баннер больше не показываем. */
    private val _dismissed = MutableStateFlow("")
    val dismissedLabel: StateFlow<String> = _dismissed.asStateFlow()

    /** Подсказка под кнопкой «Установить» (например, про разрешение на установку). */
    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    private val checkLock = Mutex()
    private var downloadJob: Job? = null

    @Volatile
    private var currentCall: okhttp3.Call? = null

    @Volatile
    private var userCancelled = false

    @Volatile
    private var inited = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (inited) return
        inited = true
        _dismissed.value = ""
        prefs(context).edit().remove("dismissed").apply()
    }

    fun openSheet() {
        _sheetOpen.value = true
    }

    fun closeSheet() {
        _sheetOpen.value = false
        _hint.value = null
    }

    fun dismissBanner(context: Context) {
        val r = _state.value.currentRelease ?: return
        if (r.mandatory) return
        _dismissed.value = r.versionLabel
    }

    // ---------------------------------------------------------------- версии

    private fun parseParts(s: String): List<Int> =
        Regex("\\d+").findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun compareParts(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * Установленная версия. Имя файла у AmorX: versionName.versionCode (5.5.499.1302),
     * поэтому если в versionName три числа, четвёртым берём versionCode.
     */
    fun installed(context: Context): AmorInstalledVersion {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = pi.versionName.orEmpty()

        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        var parts = parseParts(name)
        if (parts.size == 3) parts = parts + code.toInt()
        if (parts.isEmpty()) parts = listOf(code.toInt())
        return AmorInstalledVersion(parts.joinToString("."), parts)
    }

    fun installedLabel(context: Context): String = installed(context).label

    // ---------------------------------------------------------------- проверка

    /** Тихая проверка при открытии приложения. Ошибок не показывает. */
    suspend fun autoCheck(context: Context) {
        val app = context.applicationContext
        init(app)
        runCheck(app, manual = false)
    }

    /** Проверка по кнопке: показывает «Проверяем…», «Последняя версия» или ошибку. */
    suspend fun checkNow(context: Context) {
        val app = context.applicationContext
        init(app)
        runCheck(app, manual = true)
    }

    private suspend fun runCheck(app: Context, manual: Boolean) {
        if (!checkLock.tryLock()) return
        try {
            val cur = _state.value
            if (cur is AmorUpdateState.Downloading || cur is AmorUpdateState.Ready || cur is AmorUpdateState.Checking) return
            if (!manual && cur is AmorUpdateState.Available) return
            if (manual) _state.value = AmorUpdateState.Checking

            val installed = installed(app)
            val outcome = withContext(Dispatchers.IO) { runCatching { fetchBest() } }
            prefs(app).edit().putLong("last_check", System.currentTimeMillis()).apply()

            val best = outcome.getOrNull()
            val err = outcome.exceptionOrNull()
            if (err != null) {
                if (manual) {
                    _state.value = AmorUpdateState.Failed(null, humanError(err))
                    scheduleReset { _state.value is AmorUpdateState.Failed && (_state.value as AmorUpdateState.Failed).release == null }
                } else {
                    _state.value = AmorUpdateState.Idle
                }
            } else if (best != null && compareParts(best.versionParts, installed.parts) > 0) {
                val existing = withContext(Dispatchers.IO) { existingDownload(app, best) }
                _state.value = if (existing != null) {
                    AmorUpdateState.Ready(best, existing)
                } else {
                    AmorUpdateState.Available(best)
                }
                if (best.mandatory) _sheetOpen.value = true
            } else {
                if (manual) {
                    _state.value = AmorUpdateState.UpToDate
                    scheduleReset { _state.value is AmorUpdateState.UpToDate }
                } else {
                    _state.value = AmorUpdateState.Idle
                }
            }
        } finally {
            checkLock.unlock()
        }
    }

    /** Через пару секунд возвращает статус «Проверяем/Последняя версия/Ошибка» в обычный вид. */
    private fun scheduleReset(stillSame: () -> Boolean) {
        scope.launch {
            delay(3800)
            if (stillSame()) _state.value = AmorUpdateState.Idle
        }
    }

    private suspend fun fetchBest(): AmorRelease? = coroutineScope {
        val useSite = AmorUpdateConfig.siteEnabled
        val useGh = AmorUpdateConfig.githubEnabled
        if (!useSite && !useGh) throw IOException("Адрес обновлений не настроен")
        val site = async(Dispatchers.IO) { if (useSite) runCatching { fetchSite() } else null }
        val gh = async(Dispatchers.IO) { if (useGh) runCatching { fetchGithub() } else null }
        val results = listOfNotNull(site.await(), gh.await())
        val ok = results.filter { it.isSuccess }
        if (ok.isEmpty()) {
            throw (results.firstOrNull()?.exceptionOrNull() ?: IOException("Источники обновлений недоступны"))
        }
        ok.mapNotNull { it.getOrNull() }.maxWithOrNull { a, b -> compareParts(a.versionParts, b.versionParts) }
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun httpGet(url: String, github: Boolean): String {
        val b = Request.Builder().url(url).header("User-Agent", UA).header("Cache-Control", "no-cache")
        if (github) b.header("Accept", "application/vnd.github+json")
        http.newCall(b.build()).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) {
                throw IOException(
                    when (r.code) {
                        404 -> "Релиз не найден (404)"
                        403, 429 -> "Слишком много запросов, попробуйте позже (${r.code})"
                        else -> "Сервер ответил ${r.code}"
                    }
                )
            }
            return text
        }
    }

    /** Выбирает APK под процессор телефона. */
    private fun <T> pickByAbi(items: List<T>, name: (T) -> String): T? {
        val apks = items.filter { name(it).endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        fun has(n: String, abi: String) =
            Regex("(?<![A-Za-z0-9])" + Regex.escape(abi) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE).containsMatchIn(n)
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { has(name(it), abi) }?.let { return it }
        }
        apks.firstOrNull { name(it).contains("universal", ignoreCase = true) }?.let { return it }
        val known = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        apks.firstOrNull { a -> known.none { has(name(a), it) } }?.let { return it }
        return apks.first()
    }

    private fun cleanNotes(raw: String): String =
        raw.replace("\r", "")
            .replace(Regex("\\[mandatory]", RegexOption.IGNORE_CASE), "")
            .lines()
            .map { it.trim().trimStart('#', ' ').removePrefix("- ").removePrefix("* ").replace("**", "").replace("`", "").trim() }
            .filter { it.isNotBlank() }
            .take(12)
            .joinToString("\n")

    /**
     * update.json на сайте. Пример:
     * { "version": "5.5.500.1305", "notes": "Строка 1\nСтрока 2", "mandatory": false,
     *   "apk": { "arm64-v8a": {"url": "https://...", "size": 31457280, "sha256": "..."},
     *            "armeabi-v7a": "https://...", "universal": "https://..." } }
     */
    private fun fetchSite(): AmorRelease {
        val o = JSONObject(httpGet(AmorUpdateConfig.SITE_MANIFEST_URL, github = false))
        val version = o.str("version").ifBlank { o.str("tag") }
        val parts = parseParts(version)
        if (parts.isEmpty()) throw IOException("В update.json нет версии")
        var url = ""
        var size = 0L
        var sha: String? = null
        val apk = o.optJSONObject("apk")
        if (apk != null) {
            val key = (Build.SUPPORTED_ABIS.toList() + "universal").firstOrNull { apk.has(it) }
            val e = key?.let { apk.opt(it) }
            when (e) {
                is String -> url = e
                is JSONObject -> {
                    url = e.str("url")
                    size = e.optLong("size", 0L)
                    sha = e.str("sha256").lowercase().takeIf { it.length == 64 }
                }
            }
        }
        if (url.isBlank()) {
            url = o.str("url")
            size = o.optLong("size", 0L)
            sha = o.str("sha256").lowercase().takeIf { it.length == 64 }
        }
        if (url.isBlank()) throw IOException("В update.json нет ссылки на APK")
        return AmorRelease(
            versionLabel = parts.joinToString("."),
            versionParts = parts,
            notes = cleanNotes(o.str("notes")),
            apkUrl = url,
            apkSize = size,
            sha256 = sha,
            mandatory = o.optBoolean("mandatory", false),
            source = "site"
        )
    }

    /** Последний релиз на GitHub. Тег должен быть вида v5.5.500.1305 (versionName.versionCode). */
    private fun fetchGithub(): AmorRelease {
        val o = JSONObject(
            httpGet("https://api.github.com/repos/${AmorUpdateConfig.GITHUB_REPO}/releases/latest", github = true)
        )
        val tag = o.str("tag_name")
        val parts = parseParts(tag)
        if (parts.isEmpty()) throw IOException("У релиза нет версии в теге")
        val arr = o.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until arr.length()).map { arr.getJSONObject(it) }
        val asset = pickByAbi(assets) { it.str("name") } ?: throw IOException("В релизе нет APK")
        val digest = asset.str("digest")
        val sha = digest.removePrefix("sha256:").lowercase().takeIf { digest.startsWith("sha256:") && it.length == 64 }
        val body = o.str("body")
        return AmorRelease(
            versionLabel = parts.joinToString("."),
            versionParts = parts,
            notes = cleanNotes(body),
            apkUrl = asset.str("browser_download_url"),
            apkSize = asset.optLong("size", 0L),
            sha256 = sha,
            mandatory = body.contains("[mandatory]", ignoreCase = true),
            source = "github"
        )
    }

    // ---------------------------------------------------------------- скачивание

    private fun fileName(r: AmorRelease) = "AmorX-" + r.versionLabel.replace(Regex("[^0-9A-Za-z._-]"), "_") + ".apk"

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Если эта версия уже скачана раньше и цела, второй раз не качаем. Старые файлы удаляет. */
    private fun existingDownload(app: Context, r: AmorRelease): File? {
        val dir = File(app.cacheDir, CACHE_DIR)
        val f = File(dir, fileName(r))
        dir.listFiles()?.filter { it.name != f.name }?.forEach { it.delete() }
        if (!f.exists()) return null
        if (r.apkSize > 0 && f.length() != r.apkSize) {
            f.delete()
            return null
        }
        if (r.sha256 != null && sha256Of(f) != r.sha256) {
            f.delete()
            return null
        }
        return f
    }

    fun startDownload(context: Context, release: AmorRelease) {
        if (downloadJob?.isActive == true) return
        val app = context.applicationContext
        userCancelled = false
        _hint.value = null
        downloadJob = scope.launch {
            _state.value = AmorUpdateState.Downloading(release, 0L, release.apkSize, 0L)
            try {
                val file = withContext(Dispatchers.IO) { download(app, release) }
                _state.value = AmorUpdateState.Ready(release, file)
            } catch (e: CancellationException) {
                _state.value = AmorUpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                _state.value = if (userCancelled) {
                    AmorUpdateState.Available(release)
                } else {
                    AmorUpdateState.Failed(release, humanError(e))
                }
            }
        }
    }

    fun cancelDownload() {
        userCancelled = true
        currentCall?.cancel()
        downloadJob?.cancel()
    }

    /** После ошибки: вернуться к кнопке «Обновить». */
    fun resetFailure() {
        val s = _state.value
        if (s is AmorUpdateState.Failed) {
            _state.value = s.release?.let { AmorUpdateState.Available(it) } ?: AmorUpdateState.Idle
        }
    }

    private suspend fun download(app: Context, r: AmorRelease): File {
        val dir = File(app.cacheDir, CACHE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, fileName(r))
        val tmp = File(dir, target.name + ".part")
        val call = http.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", UA).build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Сервер ответил ${resp.code}")
                val body = resp.body
                val total = if (body.contentLength() > 0) body.contentLength() else r.apkSize
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var speed = 0L
                var lastEmitAt = SystemClock.elapsedRealtime()
                var lastEmitBytes = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmitAt >= 120) {
                                val inst = (done - lastEmitBytes) * 1000L / (now - lastEmitAt)
                                speed = if (speed == 0L) inst else (speed * 0.7 + inst * 0.3).toLong()
                                lastEmitAt = now
                                lastEmitBytes = done
                                _state.value = AmorUpdateState.Downloading(r, done, total, speed)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("Файл скачан не полностью")
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256 != null && got != r.sha256) throw IOException("Контрольная сумма не совпала: файл повреждён")
            }
        } finally {
            currentCall = null
        }
        target.delete()
        if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл")
        return target
    }

    // ---------------------------------------------------------------- установка

    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun signerHashes(pi: PackageInfo): Set<String> {
        val list = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            pi.signatures
        }
        return list?.map { hex(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    /** null: файл можно ставить. Иначе текст причины. Android не даст поставить APK с чужой подписью. */
    @Suppress("DEPRECATION")
    private fun verifyApk(app: Context, file: File): String? {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "Файл обновления повреждён"
        if (archive.packageName != app.packageName) return "Это обновление для другого приложения"
        val mine = signerHashes(pm.getPackageInfo(app.packageName, flags))
        val theirs = signerHashes(archive)
        if (mine.isEmpty() || theirs.isEmpty()) return null
        if (mine.intersect(theirs).isEmpty()) {
            return "Подпись обновления не совпадает с установленной версией, поэтому Android не даст поставить его поверх. Соберите релиз тем же ключом"
        }
        return null
    }

    /** Открывает системный установщик. Вызывать из корутины (проверка подписи читает файл). */
    suspend fun install(context: Context) {
        val s = _state.value as? AmorUpdateState.Ready ?: return
        val app = context.applicationContext
        val problem = withContext(Dispatchers.IO) { verifyApk(app, s.file) }
        if (problem != null) {
            s.file.delete()
            _state.value = AmorUpdateState.Failed(s.release, problem)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            _hint.value = "Разрешите установку из AmorX в настройках и вернитесь сюда: кнопка «Установить» останется на месте"
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return
        }
        try {
            withContext(Dispatchers.IO) {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    s.file.inputStream().use { input ->
                        session.openWrite("amorx.apk", 0, s.file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, AmorInstallReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
            }
            _hint.value = "Устанавливаю обновление. Приложение закроется и его нужно будет открыть заново"
        } catch (e: Exception) {
            _state.value = AmorUpdateState.Failed(s.release, "Не удалось открыть установщик: " + (e.message ?: "ошибка"))
        }
    }

    // ---------------------------------------------------------------- тексты

    private fun humanError(e: Throwable): String = when (e) {
        is UnknownHostException, is SocketTimeoutException, is ConnectException ->
            "Нет связи с сервером. Проверьте интернет"
        is org.json.JSONException -> "Сервер ответил неожиданно"
        is IOException -> e.message ?: "Ошибка сети"
        else -> e.message ?: "Ошибка"
    }
}
