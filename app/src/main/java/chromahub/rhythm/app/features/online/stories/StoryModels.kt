package chromahub.rhythm.app.features.online.stories

import chromahub.rhythm.app.features.online.CloudSong

/** Одна история: видео из галереи или песня из плейлиста. */
data class Story(
    val id: String,
    val ownerId: String,
    val videoUrl: String?,
    val song: CloudSong?,
    val caption: String,
    val createdAtMs: Long,
    val expiresAtMs: Long
) {
    val isVideo: Boolean get() = videoUrl != null
}

/** Все активные истории одного человека. Старые первыми, как в Instagram. */
data class StoryGroup(
    val ownerId: String,
    val name: String,
    val avatarB64: String?,
    val isMine: Boolean,
    val stories: List<Story>
)
