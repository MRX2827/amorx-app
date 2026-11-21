package chromahub.rhythm.app.shared.presentation.components.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons

/** A persistent transport control, independent of customizable full-player action slots. */
@Composable
internal fun MiniPlayerShuffleButton(checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else 1f, spring(), label = "mini shuffle press")
    val background by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "mini shuffle state"
    )
    FilledTonalIconToggleButton(
        checked = checked, onCheckedChange = { onToggle() }, enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = background, checkedContainerColor = background,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Icon(RhythmIcons.Shuffle, if (checked) "Выключить перемешивание" else "Включить перемешивание", Modifier.size(20.dp))
    }
}
