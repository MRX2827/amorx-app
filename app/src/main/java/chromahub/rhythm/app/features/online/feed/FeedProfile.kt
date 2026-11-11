package chromahub.rhythm.app.features.online.feed

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.online.CloudProfiles
import kotlinx.coroutines.launch

@Composable
internal fun SocialProfileHeader(
    authorId: String?,
    name: String,
    bio: String,
    avatar: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val context = LocalContext.current
    remember(context) { CloudProfiles.init(context) }
    val revision by CloudFeed.changes.collectAsState()
    var counts by remember(authorId) { mutableStateOf<FeedCounts?>(null) }
    LaunchedEffect(authorId, revision) { CloudFeed.counts(authorId).onSuccess { counts = it } }

    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = 0.72f), colors.surfaceContainerLow)))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.border(2.dp, colors.primary.copy(alpha = 0.72f), CircleShape).padding(4.dp)) { avatar() }
            ProfileCount(counts?.followers, "Подписчики", Modifier.weight(1f))
            ProfileCount(counts?.following, "Подписки", Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name.ifBlank { "Без имени" }, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            if (bio.isNotBlank()) Text(bio, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
private fun ProfileCount(count: Int?, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FeedFollowButton(personId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var following by remember(personId) { mutableStateOf<Boolean?>(null) }
    var busy by remember(personId) { mutableStateOf(true) }
    LaunchedEffect(personId) {
        CloudFeed.isFollowing(personId).onSuccess { following = it }
        busy = false
    }
    val update: () -> Unit = {
        busy = true
        scope.launch {
            try {
                val current = following
                if (current == null) CloudFeed.isFollowing(personId).onSuccess { following = it }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                else CloudFeed.setFollowing(personId, !current).onSuccess { following = !current }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            } finally {
                busy = false
            }
        }
    }
    if (following == true) {
        OutlinedButton(onClick = update, modifier = modifier, enabled = !busy) { Text("Вы подписаны") }
    } else {
        Button(onClick = update, modifier = modifier, enabled = !busy) {
            Text(if (busy) "Загрузка…" else if (following == null) "Проверить подписку" else "Подписаться")
        }
    }
}
