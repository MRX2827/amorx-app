package amorx

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class DesktopSearchState(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
        private set
    var results by mutableStateOf(emptyList<Track>())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var request = 0

    fun submit(): Boolean {
        val value = query.trim()
        if (value.isEmpty()) return false
        if (loading && submitted == value) return true
        val id = ++request
        job?.cancel()
        submitted = value
        results = emptyList()
        error = null
        loading = true
        job = scope.launch {
            try {
                results = Repo.search(value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Не удалось выполнить поиск. Попробуйте ещё раз."
            } finally {
                if (id == request) loading = false
            }
        }
        return true
    }
}

@Composable
internal fun OnlineScreen(player: PlayerState, search: DesktopSearchState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text(
            "Поиск музыки",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
        )
        if (search.submitted.isNotBlank()) Text(
            search.submitted,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        when {
            search.loading -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            search.results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    search.error ?: if (search.submitted.isEmpty()) "Введите название трека или исполнителя вверху."
                        else "По этому запросу ничего не найдено.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(search.results, key = { it.id }) { track ->
                    TrackRow(
                        track, player.current?.id == track.id && player.playing,
                        modifier = Modifier.animateItem(),
                        onClick = { player.play(track, search.results) }
                    ) {
                        DownloadButton(track)
                        LibraryTrackMenu(track)
                    }
                }
            }
        }
    }
}
