package chromahub.rhythm.app.features.online

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import chromahub.rhythm.app.features.online.feed.FeedProfileGrid
import chromahub.rhythm.app.features.online.feed.SocialProfileHeader
import coil.compose.AsyncImage
import java.io.File

@Composable
fun ProfileScreenBody(
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { ProfileStore.init(context); WaveStore.init(context); true }

    val name by ProfileStore.name.collectAsState()
    val bio by ProfileStore.bio.collectAsState()
    val avatar by ProfileStore.avatarPath.collectAsState()
    val artists by WaveStore.artists.collectAsState()
    val favIds by musicViewModel.favoriteSongs.collectAsState()
    val allSongs by musicViewModel.filteredSongs.collectAsState()
    val recent by musicViewModel.recentlyPlayed.collectAsState()

    remember { ProfileSongs.init(context); true }
    val favSongs by ProfileSongs.songs.collectAsState()
    LaunchedEffect(favIds, allSongs, recent) {
        ProfileSongs.migrateFromLikes((recent + allSongs).distinctBy { it.id }.filter { it.id in favIds })
    }

    var showEdit by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ProfileStore.setAvatar(uri)
    }

    if (showAccount) {
        chromahub.rhythm.app.features.online.account.AccountDialogHost(onDismiss = { showAccount = false })
    }

    if (showEdit) {
        var editName by remember { mutableStateOf(name) }
        var editBio by remember { mutableStateOf(bio) }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Профиль") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it.take(30) },
                        label = { Text("Имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editBio,
                        onValueChange = { editBio = it.take(120) },
                        label = { Text("О себе") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { showEdit = false; showAccount = true }) { Text("Аккаунт: вход и облако") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ProfileStore.save(editName, editBio)
                    showEdit = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Отмена") } }
        )
    }

    remember { CloudProfiles.init(context); true }
    val isPublic by ProfileStore.isPublic.collectAsState()
    var publishStatus by remember { mutableStateOf<String?>(null) }
    var showPeople by remember { ProfileNavState.people }

    LaunchedEffect(isPublic, name, bio, avatar, favSongs, artists) {
        if (!isPublic) {
            CloudProfiles.unpublishIfNeeded()
            publishStatus = null
            return@LaunchedEffect
        }
        delay(1500)
        if (name.trim().length < 2) {
            publishStatus = "Укажите имя (минимум 2 символа), иначе профиль не опубликуется"
            return@LaunchedEffect
        }
        publishStatus = "Публикую…"
        val result = CloudProfiles.publish(
            name = name,
            bio = bio,
            avatarPath = avatar,
            isPublic = true,
            artists = artists.map { CloudArtist(it.name, it.avatarUrl) },
            songs = favSongs
        )
        publishStatus = result.fold(
            onSuccess = { "Профиль опубликован" },
            onFailure = { it.message ?: "Не удалось опубликовать" }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("‹ Назад") }
        }

        item {
            SocialProfileHeader(authorId = null, name = name, bio = bio, avatar = {
                RoundAvatar(imageModel = avatar?.let { File(it) }, label = name, size = 82.dp,
                    onClick = { picker.launch("image/*") })
            }, actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { showEdit = true }, modifier = Modifier.weight(1f)) { Text("Редактировать") }
                    FilledTonalButton(onClick = { showPeople = true }, modifier = Modifier.weight(1f)) { Text("Найти людей") }
                }
            })
        }
        item {
            FeedProfileGrid(authorId = null, modifier = Modifier.padding(vertical = 18.dp))
        }

        item {
            chromahub.rhythm.app.features.online.account.AccountEntryCard(onClick = { showAccount = true })
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Показывать мой профиль другим", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Имя, фото, «о себе», любимые песни и артистов увидят все, кто ищет людей",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = isPublic, onCheckedChange = { ProfileStore.setPublic(it) })
                    }
                    val status = publishStatus
                    if (status != null) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    ChatBackgroundSettings()
                }
            }
        }

        item {
            Text(
                "Любимые артисты",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (artists.isEmpty()) {
                Text(
                    "Выберите артистов в «Моей волне» во вкладке «Онлайн», и они появятся здесь",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(artists, key = { it.name }) { artist ->
                        Column(
                            modifier = Modifier.width(72.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            RoundAvatar(imageModel = artist.avatarUrl, label = artist.name, size = 64.dp)
                            Spacer(Modifier.size(4.dp))
                            Text(
                                artist.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Любимые песни",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (favSongs.isEmpty()) {
                Text(
                    "В плеере откройте «три точки» и нажмите «Добавить в профиль»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(favSongs, key = { "fav_" + it.id }) { song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { ProfileSongs.play(context, musicViewModel, song) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ArtworkUtil.forSong(song.id, song.artwork),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { ProfileSongs.remove(song.id) }) {
                    Icon(imageVector = OnlineIcons.Close, contentDescription = "Убрать", modifier = Modifier.size(20.dp))
                }
            }
        }

        item {
            Text(
                "По умолчанию профиль виден только вам.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }
}

@Composable
private fun RoundAvatar(
    imageModel: Any?,
    label: String,
    size: Dp,
    onClick: (() -> Unit)? = null
) {
    val base = Modifier
        .size(size)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer)
    Box(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        contentAlignment = Alignment.Center
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = label.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
