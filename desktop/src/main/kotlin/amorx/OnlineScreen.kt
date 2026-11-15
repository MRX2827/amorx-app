package amorx

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object OnlineState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun OnlineScreen(p: PlayerState) {
    val scope = rememberCoroutineScope()
    val s = OnlineState

    fun doSearch() {
        val q = s.query.trim()
        if (q.isEmpty() || s.loading) return
        scope.launch {
            s.loading = true
            s.error = null
            try {
                val r = Repo.search(q)
                s.results = r
                if (r.isEmpty()) s.error = "Ничего не найдено"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                s.error = "Ошибка поиска: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                s.loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            "Онлайн",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
        )
        OutlinedTextField(
            value = s.query,
            onValueChange = { s.query = it },
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                    doSearch(); true
                } else false
            },
            placeholder = { Text("Найти песню или исполнителя") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (s.query.isNotBlank()) TextButton(onClick = { doSearch() }) { Text("Найти") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp)
        )
        Spacer(Modifier.height(12.dp))
        when {
            s.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            s.error != null && s.results.isEmpty() -> Text(
                s.error ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            s.results.isEmpty() -> Text(
                "Введите название и нажмите Enter",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(s.results, key = { it.id }) { t ->
                    AnimateIn(Modifier.animateItem()) {
                        TrackRow(t, p.current?.id == t.id && p.playing, onClick = { p.play(t, s.results) }) {
                            DownloadButton(t)
                        }
                    }
                }
            }
        }
    }
}
