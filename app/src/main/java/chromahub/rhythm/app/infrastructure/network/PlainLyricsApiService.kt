package chromahub.rhythm.app.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

/** Public fallback API, https://github.com/NTag/lyrics.ovh#api */
interface PlainLyricsApiService {
    @GET("v1/{artist}/{title}")
    suspend fun getLyrics(@Path("artist") artist: String, @Path("title") title: String): PlainLyricsResponse
}

data class PlainLyricsResponse(@SerializedName("lyrics") val lyrics: String?)
