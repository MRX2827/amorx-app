package chromahub.rhythm.app.features.online

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** Бесконечный список рекомендаций под карточкой «Моя волна». Хранится в памяти, пока живо приложение. */
object WaveRecs {
    private const val PAGE = 20

    val tracks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val loading = MutableStateFlow(false)
    val loadingMore = MutableStateFlow(false)

    private var builtFor: String? = null
    private var feed: RecsFeed? = null
    private val moreLock = Mutex()

    suspend fun refresh(artists: List<WaveArtist>, force: Boolean = false) {
        if (artists.isEmpty()) {
            tracks.value = emptyList()
            builtFor = null
            feed = null
            return
        }
        val key = artists.map { it.name.lowercase() }.sorted().joinToString("|")
        if (!force && key == builtFor && tracks.value.isNotEmpty()) return
        loading.value = true
        try {
            val f = RecsFeed(artists)
            val list = f.next(PAGE)
            if (list.isNotEmpty()) {
                feed = f
                tracks.value = list
                builtFor = key
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // при следующем открытии вкладки попробуем снова
        } finally {
            loading.value = false
        }
    }

    /** Подгружает следующую порцию в конец списка. Параллельные вызовы игнорируются. */
    suspend fun loadMore() {
        val f = feed ?: return
        val key = builtFor
        if (!moreLock.tryLock()) return
        loadingMore.value = true
        try {
            val more = f.next(PAGE)
            if (more.isNotEmpty() && key == builtFor) {
                tracks.value = tracks.value + more
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // повторим при следующей прокрутке
        } finally {
            loadingMore.value = false
            moreLock.unlock()
        }
    }
}
