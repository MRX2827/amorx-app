package chromahub.rhythm.app.features.online

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val SEED_QUERIES = listOf(
    "русский рэп", "русский рок", "поп музыка",
    "hip hop", "rock", "электронная музыка"
)

@Composable
fun WaveArtistPicker(onDismiss: () -> Unit) {
    val selected by WaveStore.artists.collectAsState()
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<WaveArtist>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<WaveArtist>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) {
        loading = true
        suggestions = coroutineScope {
            SEED_QUERIES.map { s ->
                async { runCatching { OnlineRepository.searchArtists(s) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.flatten().distinctBy { it.name.lowercase() }.shuffled()
        loading = false
    }

    fun doSearch() {
        if (query.isBlank()) {
            searched = false
            message = null
            return
        }
        focus.clearFocus()
        scope.launch {
            loading = true
            searched = true
            message = null
            found = runCatching { OnlineRepository.searchArtists(query.trim()) }
                .getOrElse {
                    message = "Ошибка: ${it.message}"
                    emptyList()
                }
            if (found.isEmpty() && message == null) message = "Артисты не найдены"
            loading = false
        }
    }

    val base = if (searched) found else suggestions
    val shown = (selected + base).distinctBy { it.name.lowercase() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Text(
                    text = "Выберите артистов",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)
                )
                Text(
                    text = if (selected.isEmpty()) "Нажмите на артистов, чтобы добавить их в волну"
                    else "Выбрано: ${selected.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (it.isBlank()) {
                            searched = false
                            message = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Найти артиста") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            TextButton(onClick = { doSearch() }) { Text("Найти") }
                        }
                    }
                )
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
                val msg = message
                if (msg != null) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(shown, key = { it.name.lowercase() }) { artist ->
                        val isSel = selected.any { it.name.equals(artist.name, ignoreCase = true) }
                        ArtistTile(artist = artist, selected = isSel) {
                            if (isSel) WaveStore.remove(artist.name) else WaveStore.add(artist)
                        }
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text(if (selected.isEmpty()) "Закрыть" else "Готово (${selected.size})")
                }
            }
        }
    }
}

@Composable
private fun ArtistTile(artist: WaveArtist, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            AsyncImage(
                model = artist.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
            )
            if (selected) {
                Icon(
                    imageVector = OnlineIcons.Done,
                    contentDescription = "Выбрано",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
