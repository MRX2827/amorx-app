package amorx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TrackRow(
    t: Track, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            active -> MaterialTheme.colorScheme.surfaceContainerHighest
            hovered -> MaterialTheme.colorScheme.surfaceContainer
            else -> Color.Transparent
        }, tween(155), label = "track row hover"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(t.color, 52.dp, url = t.artwork)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            fmt(t.seconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Icon(if (active) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, null)
        trailing()
    }
}

@Composable
fun DownloadButton(t: Track) {
    if (t.videoUrl == null) return
    val stored by Downloads.items
    val pend by Downloads.pending
    val done = stored.any { it.id == t.id }
    val prog = pend[t.id]?.progress
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        when {
            done -> Icon(
                Icons.Rounded.DownloadDone, "Скачано",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            prog != null -> {
                if (prog > 0f) CircularProgressIndicator(progress = { prog }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            else -> IconButton(onClick = { Downloads.download(t) }) { Icon(Icons.Rounded.Download, "Скачать") }
        }
    }
}
