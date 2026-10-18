package chromahub.rhythm.app.features.online

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Профиль пользователя. Хранится на телефоне: имя, «о себе» и аватар. */
object ProfileStore {
    private const val PREFS = "amorx_profile"

    val name = MutableStateFlow("")
    val bio = MutableStateFlow("")
    val avatarPath = MutableStateFlow<String?>(null)
    val isPublic = MutableStateFlow(false)

    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        name.value = p.getString("name", "") ?: ""
        bio.value = p.getString("bio", "") ?: ""
        avatarPath.value = p.getString("avatar", null)?.takeIf { File(it).exists() }
        isPublic.value = p.getBoolean("public", false)
    }

    fun setPublic(value: Boolean) {
        val ctx = appContext ?: return
        isPublic.value = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("public", value).apply()
    }

    fun save(newName: String, newBio: String) {
        val ctx = appContext ?: return
        name.value = newName.trim()
        bio.value = newBio.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", name.value)
            .putString("bio", bio.value)
            .apply()
    }

    fun setAvatar(uri: Uri) {
        val ctx = appContext ?: return
        try {
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val target = minOf(512, side)
            val scaled = Bitmap.createScaledBitmap(square, target, target, true)
            val file = File(ctx.filesDir, "profile_avatar_" + System.currentTimeMillis() + ".jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            avatarPath.value?.let { File(it).delete() }
            avatarPath.value = file.absolutePath
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("avatar", file.absolutePath).apply()
        } catch (e: Exception) {
            // оставляем прежний аватар
        }
    }
}
