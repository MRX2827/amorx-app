@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package chromahub.rhythm.app.shared.presentation.components.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon

@Composable
fun PlayerStylePicker(selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, dark: Boolean = false, accent: Color? = null) {
    val highlight = accent ?: MaterialTheme.colorScheme.primary
    val text = if(dark) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if(dark) Color.White.copy(alpha=.58f) else MaterialTheme.colorScheme.onSurfaceVariant
    val styles = listOf(
        Triple("MATERIAL", "Обычный", "Обложка и привычное управление"),
        Triple("EXPRESSIVE", "Как надо", "Выразительные формы и анимации"),
        Triple("DESIGNER", "Дизайнерский", "Текст, живые волны и фон песни")
    )
    Column(modifier, verticalArrangement=Arrangement.spacedBy(10.dp)) {
        styles.forEachIndexed { index, (id, title, description) ->
            val selected = selectedId == id
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if(pressed) .975f else 1f,spring(dampingRatio=.82f,stiffness=420f),label="style press")
            val fill by animateColorAsState(if(selected) highlight.copy(alpha=if(dark) .16f else .12f) else if(dark) Color.White.copy(alpha=.045f) else MaterialTheme.colorScheme.surfaceContainerHigh,tween(220),label="style selection")
            val border by animateColorAsState(if(selected) highlight.copy(alpha=.8f) else text.copy(alpha=.08f),tween(220),label="style outline")
            Surface(onClick={onSelect(id)},interactionSource=interaction,contentColor=text,color=fill,shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,border),modifier=Modifier.fillMaxWidth().graphicsLayer {scaleX=scale;scaleY=scale}) {
                Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Surface(color=highlight.copy(alpha=if(selected) .22f else .08f),shape=RoundedCornerShape(16.dp),modifier=Modifier.size(46.dp)) {
                        Box(contentAlignment=Alignment.Center) { Icon(MaterialSymbolIcon(listOf("album","auto_awesome","graphic_eq")[index]),null,tint=if(selected) highlight else secondary,modifier=Modifier.size(26.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title,color=text,fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                        Text(description,color=secondary,fontSize=12.sp,lineHeight=16.sp,modifier=Modifier.padding(top=4.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(MaterialSymbolIcon(if(selected) "check_circle" else "radio_button_unchecked",filled=selected),if(selected) "Выбрано" else null,tint=if(selected) highlight else secondary.copy(alpha=.45f),modifier=Modifier.size(22.dp))
                }
            }
        }
    }
}
