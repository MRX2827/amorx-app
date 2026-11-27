package chromahub.rhythm.app.util

/** One YouTube recording keeps its lyrics and corrections when its audio is downloaded. */
internal object LyricsIdentity {
    fun canonical(id: String?): String? = if(id?.startsWith("offline_") == true) "online_" + id.removePrefix("offline_") else id
    fun isVideo(id: String?) = id?.startsWith("online_") == true || id?.startsWith("offline_") == true
    fun aliases(id: String?): List<String?> {
        val canonical = canonical(id)
        val offline = canonical?.takeIf { it.startsWith("online_") }?.let { "offline_" + it.removePrefix("online_") }
        return if(canonical == null) listOf(null) else listOfNotNull(canonical, id, offline).distinct()
    }
}
