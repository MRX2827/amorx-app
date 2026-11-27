package chromahub.rhythm.app.shared.presentation.screens.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Measure before drawing: complete verses fit the available width and height.
 * Exceptionally large verses remain readable at the minimum size and can be scrolled.
 */
@Composable internal fun FittingLyricText(text: String, preferredSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontWeight=FontWeight.Bold)
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(0) }
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val fitted = remember(text,width,height,preferredSize,measurer,style) {
            fun fits(size: Float): Boolean {
                val layout = measurer.measure(text,
                    style=style.copy(fontSize=size.sp,lineHeight=(size*1.18f).sp),
                    constraints=Constraints(maxWidth=width),maxLines=Int.MAX_VALUE,overflow=TextOverflow.Clip)
                return layout.size.height<=height && !layout.didOverflowWidth
            }
            val minimum = minOf(13f,preferredSize.value)
            var small = minimum
            var large = preferredSize.value
            when {
                fits(large) -> large to false
                !fits(minimum) -> minimum to true
                else -> {
                    repeat(8) {
                        val middle = (small+large)/2
                        if(fits(middle)) small=middle else large=middle
                    }
                    small to false
                }
            }
        }
        Text(text,color=color,style=style.copy(fontSize=fitted.first.sp,lineHeight=(fitted.first*1.18f).sp),
            maxLines=Int.MAX_VALUE,overflow=TextOverflow.Clip,
            modifier=Modifier.fillMaxWidth().verticalScroll(scroll,enabled=fitted.second))
    }
}
