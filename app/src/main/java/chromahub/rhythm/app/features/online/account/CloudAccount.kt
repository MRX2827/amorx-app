package chromahub.rhythm.app.features.online.account

import android.content.Context
import android.util.Base64
import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Ошибка с готовым русским текстом. code: код ошибки Supabase (например, email_not_confirmed), если он был. */
class CloudException(message: String, val code: String = "") : Exception(message)

/**
 * email пустой: никто не вошёл (isGuest оставлен ради совместимости с остальным кодом).
 * pendingEmail: почта, на которую отправлен код регистрации, но ещё не подтверждён.
 */
data class AccountInfo(val email: String, val pendingEmail: String) {
    val isGuest: Boolean get() = email.isBlank()
}

data class CloudBackup(val payload: String, val updatedAt: String)

/**
 * Аккаунт AmorX на Supabase. Гостевого (анонимного) входа больше нет: без аккаунта плеер закрыт.
 * Регистрация: почта + пароль → Supabase шлёт код → код подтверждает почту и сразу даёт вход.
 */
object CloudAccount {
    private const val AUTH_PREFS = "amorx_cloud" // файл настроек CloudProfiles
    private const val MY_PREFS = "amorx_account"
    private const val MIN_AUTO_PUSH_MS = 2 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val _autoSync = MutableStateFlow(true)
    val autoSync: StateFlow<Boolean> = _autoSync.asStateFlow()

    /** true: нужно показать окно входа поверх приложения (AccountGate). */
    private val _gateRequired = MutableStateFlow(false)
    val gateRequired: StateFlow<Boolean> = _gateRequired.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    private var lastAutoPushMs = 0L

    private fun myPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(MY_PREFS, Context.MODE_PRIVATE)

    private fun authPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            CloudProfiles.init(context)
            _autoSync.value = myPrefs(context).getBoolean("auto", true)
            loaded = true
        }
    }

    // ---------------------------------------------------------------- окно входа

    fun gatePassed() {
        _gateRequired.value = false
    }

    /** Есть ли на телефоне сохранённый вход (без запросов в сеть). */
    fun hasStoredSession(context: Context): Boolean =
        !authPrefs(context).getString("refresh", null).isNullOrBlank()

    /** Регистрация на этом телефоне была закончена, и вход ещё сохранён. */
    fun looksSignedIn(context: Context): Boolean =
        hasStoredSession(context) && setupComplete(context)

    /**
     * Проверка при запуске: нужен ли вход. Без сети пускаем внутрь только тех,
     * кто уже входил на этом телефоне (плеер должен работать офлайн).
     */
    suspend fun checkGate(context: Context) {
        load(context)
        if (!hasStoredSession(context)) {
            _gateRequired.value = true
            return
        }
        val info = info(context).getOrNull()
        _gateRequired.value = if (info != null) info.isGuest else !looksSignedIn(context)
    }

    /** Устарело: оставлено, чтобы не сломать другой код. Кнопки «Пропустить» больше нет. */
    fun gateDone(context: Context): Boolean = looksSignedIn(context)

    fun setGateDone(context: Context) {
        // ничего не делает: окно входа теперь зависит от реального входа, а не от флага
    }

    /** Регистрация закончена: почта подтверждена кодом (или выполнен вход по паролю). */
    fun setupComplete(context: Context): Boolean = myPrefs(context).getBoolean("setup_done", false)

    fun setSetupDone(context: Context, done: Boolean) {
        myPrefs(context).edit().putBoolean("setup_done", done).apply()
    }

    fun setAutoSync(context: Context, enabled: Boolean) {
        myPrefs(context).edit().putBoolean("auto", enabled).apply()
        _autoSync.value = enabled
    }

    /** Это устройство уже сверено с облаком: после этого можно сохранять автоматически. */
    fun markSynced(context: Context) {
        myPrefs(context).edit().putBoolean("synced", true).apply()
    }

    private fun setMember(context: Context, member: Boolean) {
        myPrefs(context).edit().putBoolean("member", member).apply()
    }

    /** Почта, на которую отправлен код регистрации (чтобы после перезапуска вернуться на ввод кода). */
    fun pendingEmail(context: Context): String = myPrefs(context).getString("pending_email", "").orEmpty()

    private fun setPending(context: Context, email: String) {
        myPrefs(context).edit().putString("pending_email", email).apply()
    }

    fun clearPending(context: Context) = setPending(context, "")

    // ---------------------------------------------------------------- тексты ошибок

    fun errorMessage(e: Throwable): String = when (e) {
        is CloudException -> e.message ?: "Ошибка"
        is UnknownHostException, is SocketTimeoutException, is ConnectException ->
            "Нет связи с сервером. Проверьте интернет"
        is org.json.JSONException -> "Сервер ответил неожиданно. Попробуйте ещё раз"
        is IOException -> e.message ?: "Ошибка сети"
        else -> e.message ?: "Ошибка"
    }

    private fun JSONObject.s(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun codeOf(body: String): String {
        val o = try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
        return o?.s("error_code").orEmpty().ifBlank { o?.s("code").orEmpty() }
    }

    private fun errorText(code: Int, body: String): String {
        val o = try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
        val errCode = codeOf(body)
        val msg = listOf("msg", "error_description", "message").map { o?.s(it).orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
        return when {
            errCode == "invalid_credentials" || msg.contains("Invalid login", ignoreCase = true) ->
                "Неверная почта или пароль"
            errCode == "email_not_confirmed" || msg.contains("not confirmed", ignoreCase = true) ->
                "Почта ещё не подтверждена. Введите код из письма"
            errCode == "email_exists" || errCode == "user_already_exists" ||
                msg.contains("already been registered", ignoreCase = true) ||
                msg.contains("already registered", ignoreCase = true) ->
                "Эта почта уже зарегистрирована. Если это ваш аккаунт, войдите по почте и паролю"
            errCode == "anonymous_provider_disabled" ->
                "Гостевой вход отключён. Создайте аккаунт или войдите"
            errCode == "same_password" -> "Новый пароль должен отличаться от старого"
            errCode == "weak_password" -> "Пароль слишком простой. Нужно минимум 6 символов"
            errCode == "otp_expired" || msg.contains("expired", ignoreCase = true) ->
                "Неверный код или он устарел. Отправьте код заново"
            errCode == "email_address_not_authorized" ->
                "Supabase не отправляет письма на этот адрес: подключите свой SMTP (Authentication → Emails → SMTP Settings)"
            msg.contains("sending", ignoreCase = true) && msg.contains("email", ignoreCase = true) ->
                "Supabase не смог отправить письмо: проверьте SMTP (логин, ключ и подтверждённый адрес отправителя)"
            errCode == "email_address_invalid" || msg.contains("invalid email", ignoreCase = true) ->
                "Некорректная почта"
            errCode == "over_email_send_rate_limit" || errCode == "over_request_rate_limit" || code == 429 ->
                "Слишком много попыток. Подождите немного и повторите"
            errCode == "42501" || msg.contains("row-level security", ignoreCase = true) || code == 401 || code == 403 ->
                "Сначала войдите в аккаунт"
            msg.isNotBlank() -> msg
            else -> "Ошибка сервера ($code)"
        }
    }

    private fun cloudError(code: Int, body: String): CloudException =
        CloudException(errorText(code, body), codeOf(body))

    // ---------------------------------------------------------------- сеть (блокирующая, только с Dispatchers.IO)

    private fun execute(req: Request): Pair<Int, String> =
        http.newCall(req).execute().use { r -> r.code to r.body.string() }

    private fun authReq(path: String, method: String, body: JSONObject?, bearer: String?): Pair<Int, String> {
        val b = Request.Builder()
            .url(SupabaseConfig.URL + "/auth/v1/" + path)
            .header("apikey", SupabaseConfig.KEY)
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        val rb = (body ?: JSONObject()).toString().toRequestBody(jsonType)
        when (method) {
            "PUT" -> b.put(rb)
            "POST" -> b.post(rb)
            else -> b.get()
        }
        return execute(b.build())
    }

    private fun restReq(token: String, path: String, postBody: String?, prefer: String?): Pair<Int, String> {
        val b = Request.Builder()
            .url(SupabaseConfig.URL + "/rest/v1/" + path)
            .header("apikey", SupabaseConfig.KEY)
            .header("Authorization", "Bearer $token")
        if (prefer != null) b.header("Prefer", prefer)
        if (postBody != null) b.post(postBody.toRequestBody(jsonType)) else b.get()
        return execute(b.build())
    }

    private fun jwtClaims(jwt: String): JSONObject? = try {
        val part = jwt.split(".")[1]
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /**
     * Токен и id текущего входа, либо null, если никто не вошёл. Гостя НЕ создаёт.
     * Если токен скоро истечёт, обновляет его через refresh-токен. Если вход недействителен
     * (пользователя удалили, токен отозван), сбрасывает его и возвращает null.
     */
    private fun currentSession(context: Context): Pair<String, String>? {
        CloudProfiles.init(context)
        val p = authPrefs(context)
        val access = p.getString("access", null).orEmpty()
        val claims = if (access.isNotBlank()) jwtClaims(access) else null
        if (claims != null) {
            val exp = claims.optLong("exp", 0L) * 1000L
            val sub = claims.optString("sub", "")
            if (sub.isNotBlank() && exp - System.currentTimeMillis() > 60_000L) return access to sub
        }
        val refresh = p.getString("refresh", null)?.takeIf { it.isNotBlank() } ?: return null
        val (code, text) = authReq(
            "token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh), null
        )
        if (code in 200..299) {
            val o = JSONObject(text)
            CloudProfiles.storeSession(o)
            val token = o.s("access_token")
            val sub = jwtClaims(token)?.optString("sub", "").orEmpty()
            return if (token.isBlank() || sub.isBlank()) null else token to sub
        }
        if (code in 400..499 && code != 429) {
            signOut(context)
            return null
        }
        throw cloudError(code, text)
    }

    private fun requireSession(context: Context): Pair<String, String> =
        currentSession(context) ?: throw CloudException("Сначала войдите в аккаунт")

    private fun fetchUser(context: Context): JSONObject {
        val (token, _) = requireSession(context)
        val (code, text) = authReq("user", "GET", null, token)
        if (code !in 200..299) throw cloudError(code, text)
        return JSONObject(text)
    }

    /** Вход по почте и паролю, вход сохраняется на телефоне. */
    private fun passwordGrant(context: Context, email: String, password: String): JSONObject {
        val (code, text) = authReq(
            "token?grant_type=password", "POST",
            JSONObject().put("email", email.trim()).put("password", password), null
        )
        if (code !in 200..299) throw cloudError(code, text)
        val o = JSONObject(text)
        CloudProfiles.storeSession(o)
        return o
    }

    /** Проверка текущего пароля перед опасными действиями (смена пароля или почты). */
    private fun reauth(context: Context, email: String, password: String) {
        try {
            passwordGrant(context, email, password)
        } catch (e: CloudException) {
            if (e.code == "invalid_credentials") throw CloudException("Текущий пароль неверный")
            throw e
        }
    }

    // ---------------------------------------------------------------- аккаунт

    /** Кто сейчас вошёл. Если никто (или остался старый гостевой вход), email пустой. */
    suspend fun info(context: Context): Result<AccountInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, _) = currentSession(context)
                ?: return@runCatching AccountInfo("", pendingEmail(context))
            val (code, text) = authReq("user", "GET", null, token)
            if (code == 401 || code == 403 || code == 404) {
                signOut(context)
                return@runCatching AccountInfo("", "")
            }
            if (code !in 200..299) throw cloudError(code, text)
            val o = JSONObject(text)
            if (o.optBoolean("is_anonymous", false) || o.s("email").isBlank()) {
                // старый гостевой вход: гостей больше нет
                signOut(context)
                return@runCatching AccountInfo("", "")
            }
            val info = AccountInfo(o.s("email"), o.s("new_email"))
            setMember(context, true)
            info
        }
    }

    /** Шаг 1 регистрации: создаёт аккаунт, Supabase шлёт письмо с кодом. Пока код не введён, войти нельзя. */
    suspend fun register(context: Context, email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                CloudProfiles.init(context)
                val e = email.trim().lowercase()
                val (code, text) = authReq(
                    "signup", "POST", JSONObject().put("email", e).put("password", password), null
                )
                if (code !in 200..299) throw cloudError(code, text)
                val o = JSONObject(text)
                if (o.has("access_token")) {
                    throw CloudException("В Supabase выключено подтверждение почты: включите Confirm email")
                }
                // Для уже занятой почты Supabase отвечает «успехом», но без identities
                val ids = o.optJSONArray("identities")
                if (ids != null && ids.length() == 0) throw CloudException(errorText(422, "{\"error_code\":\"email_exists\"}"))
                setPending(context, e)
                Unit
            }
        }

    /** Отправить код регистрации ещё раз. */
    suspend fun resendCode(context: Context, email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val e = email.trim().lowercase()
            val (code, text) = authReq(
                "resend", "POST", JSONObject().put("type", "signup").put("email", e), null
            )
            if (code !in 200..299) throw cloudError(code, text)
            setPending(context, e)
            Unit
        }
    }

    /** Шаг 2 регистрации: код из письма подтверждает почту и сразу выполняет вход. */
    suspend fun confirmCode(context: Context, email: String, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                CloudProfiles.init(context)
                val (c, t) = authReq(
                    "verify", "POST",
                    JSONObject().put("type", "signup").put("email", email.trim().lowercase()).put("token", code.trim()),
                    null
                )
                if (c !in 200..299) throw cloudError(c, t)
                CloudProfiles.storeSession(JSONObject(t))
                setMember(context, true)
                setSetupDone(context, true)
                clearPending(context)
                Unit
            }
        }

    /** Вход в уже существующий аккаунт (например, на новом телефоне). */
    suspend fun signIn(context: Context, email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                CloudProfiles.init(context)
                try {
                    passwordGrant(context, email, password)
                } catch (e: CloudException) {
                    if (e.code == "email_not_confirmed") setPending(context, email.trim().lowercase())
                    throw e
                }
                setMember(context, true)
                setSetupDone(context, true)
                clearPending(context)
                Unit
            }
        }

    /** Выход. После него приложение снова закрыто окном входа. */
    fun signOut(context: Context) {
        authPrefs(context).edit()
            .remove("access").remove("refresh").remove("exp").remove("uid").apply()
        myPrefs(context).edit().putBoolean("member", false).putBoolean("synced", false)
            .putBoolean("setup_done", false).putString("pending_email", "").apply()
        _gateRequired.value = true
    }

    // ---------------------------------------------------------------- смена пароля и почты

    /** Смена пароля: сначала проверяем текущий, потом ставим новый. */
    suspend fun changePassword(context: Context, current: String, newPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val email = fetchUser(context).s("email")
                reauth(context, email, current)
                val (token, _) = requireSession(context)
                val (c, t) = authReq("user", "PUT", JSONObject().put("password", newPassword), token)
                if (c !in 200..299) throw cloudError(c, t)
                Unit
            }
        }

    /** Смена почты, шаг 1: проверяем пароль, Supabase шлёт код на НОВУЮ почту. */
    suspend fun startEmailChange(context: Context, newEmail: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val e = newEmail.trim().lowercase()
                val cur = fetchUser(context).s("email")
                if (cur.equals(e, ignoreCase = true)) throw CloudException("Это уже ваша текущая почта")
                reauth(context, cur, password)
                val (token, _) = requireSession(context)
                val (c, t) = authReq("user", "PUT", JSONObject().put("email", e), token)
                if (c !in 200..299) throw cloudError(c, t)
                Unit
            }
        }

    /** Смена почты, шаг 2: код из письма на новую почту. */
    suspend fun confirmEmailChange(context: Context, newEmail: String, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (c, t) = authReq(
                    "verify", "POST",
                    JSONObject().put("type", "email_change").put("email", newEmail.trim().lowercase())
                        .put("token", code.trim()),
                    null
                )
                if (c !in 200..299) throw cloudError(c, t)
                CloudProfiles.storeSession(JSONObject(t))
                setMember(context, true)
                Unit
            }
        }

    // ---------------------------------------------------------------- облачная копия

    /** Кладёт копию в облако. Одна копия на аккаунт, новая заменяет старую. */
    suspend fun push(context: Context, payload: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = requireSession(context)
            val row = JSONObject()
                .put("user_id", uid)
                .put("payload", payload)
                .put("updated_at", java.time.Instant.now().toString())
            val (code, text) = restReq(
                token, "backups?on_conflict=user_id", row.toString(), "resolution=merge-duplicates,return=minimal"
            )
            if (code !in 200..299) throw cloudError(code, text)
            Unit
        }
    }

    /** Скачивает копию из облака. null: копии ещё нет. */
    suspend fun pull(context: Context): Result<CloudBackup?> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = requireSession(context)
            val (code, text) = restReq(token, "backups?select=payload,updated_at&user_id=eq.$uid", null, null)
            if (code !in 200..299) throw cloudError(code, text)
            val arr = JSONArray(text)
            if (arr.length() == 0) {
                null
            } else {
                val o = arr.getJSONObject(0)
                CloudBackup(o.s("payload"), o.s("updated_at"))
            }
        }
    }

    /**
     * Тихая отправка при уходе приложения в фон. Ничего не делает, если никто не вошёл,
     * автосохранение выключено, устройство не сверено с облаком или прошлая отправка была недавно.
     */
    suspend fun autoPush(context: Context, createBackup: suspend () -> String) {
        load(context)
        val p = myPrefs(context)
        if (!_autoSync.value || !p.getBoolean("member", false) || !p.getBoolean("synced", false)) return
        val now = System.currentTimeMillis()
        if (now - lastAutoPushMs < MIN_AUTO_PUSH_MS) return
        lastAutoPushMs = now
        try {
            push(context, createBackup())
        } catch (_: Exception) {
        }
    }
}
