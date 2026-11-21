package amorx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

// ---------- Профили и сессия Supabase ----------

object CloudConfig {
    const val URL = "https://nxnshkyvpsvckwxquqxm.supabase.co"
    const val KEY = "sb_publishable_tBZRKUt1QsjbfAjXGtkPSA_39rbXY61"
}

data class CloudArtist(val name: String, val avatar: String?)
data class CloudSong(val id: String, val title: String, val artist: String, val artwork: String?, val dur: Long)
data class RemoteProfile(
    val id: String,
    val username: String,
    val bio: String,
    val avatarB64: String?,
    val artists: List<CloudArtist>,
    val songs: List<CloudSong>
)

private data class CloudSess(
    val access: String? = null,
    val refresh: String? = null,
    val exp: Long = 0L,
    val uid: String? = null,
    val email: String? = null
)

internal data class DesktopCloudAccount(val uid: String, val email: String, val revision: Long)

internal data class OwnProfileState(
    val account: DesktopCloudAccount?,
    val profile: MyProfile,
    val syncing: Boolean = account != null,
    val ready: Boolean = account == null,
    val problem: String? = null
)

object CloudProfiles {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    internal val JSON = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val file = File(AppDir.root, "cloud.json")
    @Volatile private var sess =
        runCatching { gson.fromJson(file.readText(), CloudSess::class.java) }.getOrNull() ?: CloudSess()
    private var revision = 0L
    private var profileRequest = 0L
    private val initialAccount = accountOf(sess, revision)
    private var guestProfile = MyProfileCache.loadGuest(allowLegacy = initialAccount == null)
    private val own = mutableStateOf(OwnProfileState(initialAccount, initialAccount?.let(MyProfileCache::load) ?: guestProfile))
    internal val ownProfile: State<OwnProfileState> get() = own
    internal val activeAccount = derivedStateOf { own.value.account }
    private val remote = CloudProfileRemote(::exec)
    private val profileWrites = Mutex()

    private fun accountOf(session: CloudSess, revision: Long): DesktopCloudAccount? {
        val uid = session.uid ?: return null
        val email = session.email?.takeIf(String::isNotBlank) ?: return null
        return DesktopCloudAccount(uid, email, revision)
    }

    fun myId(): String? = sess.uid

    private fun saveSession(next: CloudSess) {
        writeLocalJson(file, gson.toJson(next))
        sess = next
    }

    @Synchronized
    private fun activate(next: CloudSess) {
        val account = accountOf(next, ++revision)
        val profile = account?.let(MyProfileCache::load) ?: guestProfile
        saveSession(next)
        ++profileRequest
        Snapshot.withMutableSnapshot {
            CloudStories.resetFeed()
            CloudFriends.resetState()
            own.value = OwnProfileState(account, profile)
        }
    }

    @Synchronized
    internal fun runForActiveSession(uid: String, action: () -> Unit): Boolean {
        if (sess.uid != uid) return false
        action()
        return true
    }

    internal suspend fun emailAuth(mode: String, email: String, password: String, code: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = email.trim().lowercase()
                val (path, body) = when (mode) {
                    "Login" -> "/auth/v1/token?grant_type=password" to JsonObject().apply {
                        addProperty("email", address)
                        addProperty("password", password)
                    }
                    "Register" -> "/auth/v1/signup" to JsonObject().apply {
                        addProperty("email", address)
                        addProperty("password", password)
                    }
                    else -> "/auth/v1/verify" to JsonObject().apply {
                        addProperty("type", "signup")
                        addProperty("email", address)
                        addProperty("token", code)
                    }
                }
                val (http, response) = authPost(path, body.toString())
                val json = JsonParser.parseString(response).asJsonObject
                if (http !in 200..299) {
                    throw IOException(if (mode == "Login") "Проверьте почту и пароль" else
                        json.str("msg") ?: json.str("message") ?: "Не удалось подтвердить аккаунт")
                }
                if (json.str("access_token") == null && mode == "Register") return@runCatching false
                val session = parseSession(json, address)
                ensureActive()
                activate(session)
                true
            }
        }

    internal suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { activate(CloudSess()) }
    }

    internal fun response(req: Request): okhttp3.Response = client.newCall(req).execute()

    internal fun exec(req: Request): Pair<Int, String> =
        response(req).use { r -> r.code to (r.body?.string() ?: "") }

    private fun authPost(path: String, body: String): Pair<Int, String> =
        exec(
            Request.Builder()
                .url(CloudConfig.URL + path)
                .header("apikey", CloudConfig.KEY)
                .post(body.toRequestBody(JSON))
                .build()
        )

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun parseSession(o: JsonObject, email: String? = null): CloudSess {
        val user = o.getAsJsonObject("user")
        return CloudSess(
            access = o.str("access_token") ?: throw IOException("Сервер не вернул токен входа"),
            refresh = o.str("refresh_token"),
            exp = System.currentTimeMillis() / 1000 + (o.get("expires_in")?.takeIf { !it.isJsonNull }?.asLong ?: 3600L),
            uid = user.str("id") ?: throw IOException("Сервер не вернул ID аккаунта"),
            email = user.str("email")?.takeIf { email != null } ?: email
        )
    }

    @Synchronized
    internal fun session(): Pair<String, String> {
        val current = sess
        val now = System.currentTimeMillis() / 1000
        if (current.access != null && current.uid != null && current.exp - 60 > now) return current.access to current.uid

        val refresh = current.refresh
        if (!refresh.isNullOrBlank()) {
            val body = JsonObject().apply { addProperty("refresh_token", refresh) }.toString()
            val (code, resp) = authPost("/auth/v1/token?grant_type=refresh_token", body)
            if (code in 200..299) {
                val next = parseSession(JsonParser.parseString(resp).asJsonObject, current.email)
                if (current.uid != null && current.uid != next.uid) throw IOException("Войдите в аккаунт снова")
                saveSession(next)
                return next.access!! to next.uid!!
            }
            if (code !in 400..499 || code == 429) throw IOException("Сервер недоступен ($code)")
        }
        if (current.email != null) throw IOException("Войдите в аккаунт снова")

        val (code, resp) = authPost("/auth/v1/signup", "{}")
        if (code !in 200..299) {
            throw IOException(
                if (code == 422 || code == 400) "Вход отключён: включите Anonymous sign-ins в Supabase"
                else "Не удалось войти ($code)"
            )
        }
        val next = parseSession(JsonParser.parseString(resp).asJsonObject)
        saveSession(next)
        return next.access!! to next.uid!!
    }

    @Synchronized
    private fun sessionFor(account: DesktopCloudAccount?): Pair<String, String> {
        if (own.value.account != account) throw IOException("Аккаунт изменился")
        val credentials = session()
        if (account != null && credentials.second != account.uid) throw IOException("Аккаунт изменился")
        return credentials
    }

    @Synchronized
    internal fun <T> forAccount(account: DesktopCloudAccount, action: () -> T): T {
        if (own.value.account != account) throw IOException("Аккаунт изменился")
        return action()
    }

    internal suspend fun loadMusicRestore(account: DesktopCloudAccount): MusicRestorePlan =
        withContext(Dispatchers.IO) {
            val (token, uid) = sessionFor(account)
            val plan = CloudMusicBackup(::exec).fetch(token, uid)
            ensureActive()
            forAccount(account) { plan }
        }

    internal suspend fun syncOwnProfile(account: DesktopCloudAccount) = withContext(Dispatchers.IO) {
        val request = synchronized(this@CloudProfiles) {
            if (own.value.account != account) return@withContext
            own.value = own.value.copy(syncing = true, problem = null)
            ++profileRequest
        }
        val result = try {
            val (token, uid) = sessionFor(account)
            val profile = remote.fetch(token, uid)
            ensureActive()
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        synchronized(this@CloudProfiles) {
            if (own.value.account != account || profileRequest != request) return@withContext
            result.onSuccess { profile ->
                MyProfileCache.save(account, profile)
                own.value = OwnProfileState(account, profile, syncing = false, ready = true)
            }.onFailure {
                own.value = own.value.copy(syncing = false, problem = "Не удалось синхронизировать профиль. ${it.message.orEmpty()}".trim())
            }
        }
        Unit
    }

    internal suspend fun saveProfile(account: DesktopCloudAccount?, profile: MyProfile): Result<Unit> =
        withContext(Dispatchers.IO) {
            profileWrites.withLock {
                runCatching {
                    val current = own.value
                    if (current.account != account || !current.ready || current.syncing) {
                        throw IOException("Дождитесь синхронизации профиля")
                    }
                    if (account != null || profile.isPublic || current.profile.isPublic) {
                        val (token, uid) = sessionFor(account)
                        if (account != null) remote.save(token, uid, profile)
                        else remote.save(token, uid, profile, WaveStore.artists.value.map {
                            CloudArtist(it.name, it.avatar)
                        }, Likes.asCloud())
                    }
                    synchronized(this@CloudProfiles) {
                        if (own.value.account != account) throw IOException("Аккаунт изменился")
                        ++profileRequest
                        if (account == null) guestProfile = profile
                        MyProfileCache.save(account, profile)
                        own.value = OwnProfileState(account, profile, syncing = false, ready = true)
                    }
                }
            }
        }

    internal suspend fun setFavorite(account: DesktopCloudAccount?, song: CloudSong, liked: Boolean) =
        withContext(Dispatchers.IO) {
            profileWrites.withLock {
                val current = own.value
                if (current.account != account || !current.ready || !current.profile.isPublic) return@withLock
                try {
                    val (token, uid) = sessionFor(account)
                    remote.setFavorite(token, uid, song, liked)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The local like is kept when the published collection is unavailable.
                }
            }
        }

    private fun parse(arr: JsonArray): List<RemoteProfile> = arr.map { e ->
        val o = e.asJsonObject
        val artists = o.get("favorite_artists")?.takeIf { it.isJsonArray }?.asJsonArray?.map {
            val x = it.asJsonObject
            CloudArtist(x.str("name") ?: "", x.str("avatar"))
        } ?: emptyList()
        val songs = o.get("favorite_songs")?.takeIf { it.isJsonArray }?.asJsonArray?.map {
            val x = it.asJsonObject
            CloudSong(
                x.str("id") ?: "", x.str("title") ?: "", x.str("artist") ?: "", x.str("artwork"),
                x.get("dur")?.takeIf { v -> !v.isJsonNull }?.asLong ?: 0L
            )
        } ?: emptyList()
        RemoteProfile(o.get("id").asString, o.str("username") ?: "", o.str("bio") ?: "", o.str("avatar_b64"), artists, songs)
    }

    suspend fun search(query: String): Result<List<RemoteProfile>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = runCatching { session().first }.getOrNull()
            val q = query.trim().lowercase().filter { it !in "%_*,()\\" }
            val url = StringBuilder(
                CloudConfig.URL + "/rest/v1/profiles?select=id,username,bio,avatar_b64,favorite_artists,favorite_songs" +
                    "&is_public=eq.true&limit=30"
            )
            if (q.isNotEmpty()) {
                url.append("&username_lower=like.").append(URLEncoder.encode(q, "UTF-8")).append("*")
                url.append("&order=username_lower.asc")
            } else {
                url.append("&order=updated_at.desc")
            }
            val rb = Request.Builder().url(url.toString()).header("apikey", CloudConfig.KEY)
            if (token != null) rb.header("Authorization", "Bearer $token")
            val (code, body) = exec(rb.build())
            if (code == 404) throw IOException("Таблица profiles не найдена в Supabase")
            if (code !in 200..299) throw IOException("Ошибка поиска ($code)")
            parse(JsonParser.parseString(body).asJsonArray)
        }
    }
}

// ---------- Кеш профиля для каждого аккаунта ----------

data class MyProfile(
    val name: String = "",
    val bio: String = "",
    val avatar: String? = null,
    val isPublic: Boolean = false
)

private object MyProfileCache {
    private val guest = File(AppDir.root, "profile-guest.json")
    private val legacy = File(AppDir.root, "profile.json")
    private val directory = File(AppDir.root, "account-profiles")
    private val gson = Gson()

    private fun file(account: DesktopCloudAccount?): File {
        if (account == null) return guest
        val key = MessageDigest.getInstance("SHA-256").digest(account.uid.toByteArray(Charsets.UTF_8))
        return File(directory, "${HexFormat.of().formatHex(key)}.json")
    }

    private fun loadFile(file: File): MyProfile =
        runCatching { gson.fromJson(file.readText(), MyProfile::class.java) }.getOrNull() ?: MyProfile()

    fun loadGuest(allowLegacy: Boolean): MyProfile {
        if (guest.isFile) return loadFile(guest)
        if (!legacy.isFile) return MyProfile()
        val profile = if (allowLegacy) loadFile(legacy) else MyProfile()
        save(null, profile)
        return profile
    }

    fun load(account: DesktopCloudAccount): MyProfile = loadFile(file(account))

    fun save(account: DesktopCloudAccount?, profile: MyProfile) {
        val file = file(account)
        runCatching {
            file.parentFile.mkdirs()
            writeLocalJson(file, gson.toJson(profile))
        }
    }
}

private fun encodeAvatar(f: File): String? = runCatching {
    val img = ImageIO.read(f) ?: return@runCatching null
    val side = minOf(img.width, img.height)
    val target = minOf(160, side)
    val sq = img.getSubimage((img.width - side) / 2, (img.height - side) / 2, side, side)
    val out = BufferedImage(target, target, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(sq, 0, 0, target, target, null)
    g.dispose()
    val bos = ByteArrayOutputStream()
    ImageIO.write(out, "jpg", bos)
    Base64.getEncoder().encodeToString(bos.toByteArray())
}.getOrNull()

@Composable
fun B64Avatar(b64: String?, size: Dp, name: String, modifier: Modifier = Modifier) {
    val bmp: ImageBitmap? = remember(b64) {
        b64?.let { runCatching { loadImageBitmap(ByteArrayInputStream(Base64.getMimeDecoder().decode(it))) }.getOrNull() }
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                name.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ---------- Экраны ----------

internal enum class ProfileEntry { Profile, Chats, People }

@Composable
internal fun ProfileScreen(p: PlayerState, entry: ProfileEntry = ProfileEntry.Profile) {
    var people by remember(entry) { mutableStateOf(entry == ProfileEntry.People) }
    var chats by remember(entry) { mutableStateOf(entry == ProfileEntry.Chats) }
    var liked by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<RemoteProfile?>(null) }
    var thread by remember { mutableStateOf<ChatPeer?>(null) }
    var unread by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            CloudChat.unreadTotal().onSuccess { unread = it }
            kotlinx.coroutines.delay(5000)
        }
    }
    val t = thread
    val cur = person
    when {
        t != null -> ThreadScreen(t, p) { thread = null }
        cur != null -> PersonScreen(cur, p, { person = null }) { thread = ChatPeer(cur.id, cur.username, cur.avatarB64) }
        liked -> LikedScreen(p) { liked = false }
        chats -> ChatsScreen({ chats = false }) { thread = it }
        people -> PeopleScreen({ people = false }) { selected ->
            if (selected.id == CloudProfiles.myId()) people = false
            else person = selected
        }
        else -> MyProfileScreen(p, { people = true }, { chats = true }, unread) { liked = true }
    }
}

@Composable
private fun MyProfileScreen(player: PlayerState, onPeople: () -> Unit, onChats: () -> Unit, unread: Int, onLiked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by CloudProfiles.ownProfile
    val saved = state.profile
    var name by remember(saved) { mutableStateOf(saved.name) }
    var bio by remember(saved) { mutableStateOf(saved.bio) }
    var avatar by remember(saved) { mutableStateOf(saved.avatar) }
    var isPublic by remember(saved) { mutableStateOf(saved.isPublic) }
    var editing by remember(state.ready) { mutableStateOf(state.ready && saved.name.isBlank()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val artists by WaveStore.artists

    fun pickAvatar() {
        val d = FileDialog(null as Frame?, "Выберите фото", FileDialog.LOAD)
        d.isVisible = true
        val f = d.file ?: return
        val enc = encodeAvatar(File(d.directory, f))
        if (enc == null) msg = "Не удалось прочитать картинку (нужен JPG или PNG)" else { avatar = enc; msg = null }
    }

    fun save() {
        val n = name.trim()
        if (n.length !in 2..30) { msg = "Имя: от 2 до 30 символов"; return }
        val b = bio.take(120)
        val profile = MyProfile(n, b, avatar, isPublic)
        val account = state.account
        scope.launch {
            busy = true
            msg = null
            CloudProfiles.saveProfile(account, profile).fold(
                { msg = "Профиль сохранён" },
                { msg = it.message ?: "Не удалось сохранить профиль" }
            )
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                B64Avatar(saved.avatar, 100.dp, saved.name.ifBlank { "?" })
                Text(saved.name.ifBlank { "Мой профиль" }, style = MaterialTheme.typography.headlineMedium)
                if (saved.bio.isNotBlank()) Text(saved.bio, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.syncing) Text("Синхронизируем профиль…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.problem?.let { problem ->
                    Text(problem, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { state.account?.let { scope.launch { CloudProfiles.syncOwnProfile(it) } } }) {
                        Text("Повторить")
                    }
                }
                ProfileSocialStats(null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = state.ready && !state.syncing, onClick = { editing = !editing }) {
                        Text(if (editing) "Скрыть настройки" else "Редактировать профиль")
                    }
                    FilledTonalButton(onClick = onPeople) {
                        Text("Найти людей")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onChats) {
                        Text(if (unread > 0) "Сообщения ($unread)" else "Сообщения")
                    }
                    TextButton(onClick = onLiked) { Text("Понравившиеся") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedVisibility(editing) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { pickAvatar() }) { Text("Выбрать фото") }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(30) },
                    label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bio, onValueChange = { bio = it.take(120) },
                    label = { Text("О себе") }, supportingText = { Text("${bio.length}/120") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Показывать мой профиль другим", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Имя, фото и описание увидят другие в «Найти людей»",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                if (state.account == null) Text(
                    "Артисты в профиле: " + (if (artists.isEmpty()) "пока нет (добавьте во вкладке «Волна»)" else artists.size.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { save() }, enabled = !busy && state.ready && !state.syncing) { Text(if (busy) "Сохраняю…" else "Сохранить") }
                msg?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        ProfilePostGallery(null, player)
    }
}

@Composable
private fun PeopleScreen(onBack: () -> Unit, onOpen: (RemoteProfile) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<RemoteProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            err = null
            CloudProfiles.search(query)
                .onSuccess { list = it }
                .onFailure { if (it !is CancellationException) err = it.message ?: "Ошибка поиска" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Text("Найти людей", style = MaterialTheme.typography.headlineSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Имя пользователя") }, singleLine = true,
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { load(); true } else false
                }
            )
            Button(onClick = { load() }) { Text("Найти") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (!loading && err == null && list.isEmpty()) {
            Text("Никого не нашли", modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(list, key = { it.id }) { r ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onOpen(r) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        B64Avatar(r.avatarB64, 48.dp, r.username)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.username, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.bio.isNotBlank()) {
                                Text(
                                    r.bio, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonScreen(r: RemoteProfile, p: PlayerState, onBack: () -> Unit, onWrite: () -> Unit) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }

    fun playSong(s: CloudSong) {
        scope.launch {
            msg = "Ищу «${s.title}»…"
            try {
                val t = Repo.search(s.artist + " " + s.title).firstOrNull()
                if (t == null) msg = "Песня не найдена" else { msg = null; p.play(t, listOf(t)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                msg = "Ошибка: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            B64Avatar(r.avatarB64, 112.dp, r.username)
            Spacer(Modifier.height(12.dp))
            Text(r.username, style = MaterialTheme.typography.headlineMedium)
            if (r.bio.isNotBlank()) {
                Text(r.bio, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ProfileSocialStats(r.id)
        }
        if (r.id != CloudProfiles.myId()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                FollowAndFriendButtons(r.id)
                Button(onClick = onWrite) {
                    Icon(Icons.Rounded.Chat, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Написать")
                }
            }
        }
        ProfilePostGallery(r.id, p)
        if (r.artists.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Любимые артисты", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(r.artists) { a ->
                    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ArtistAvatar(Artist(a.name, a.avatar), 64.dp)
                        Text(a.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (r.songs.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Любимые песни", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            r.songs.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { playSong(s) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(colorFor(s.id), 48.dp, url = s.artwork)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        msg?.let { Text(it, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary) }
    }
}
