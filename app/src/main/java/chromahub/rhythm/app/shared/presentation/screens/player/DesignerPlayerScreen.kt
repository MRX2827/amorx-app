package chromahub.rhythm.app.shared.presentation.screens.player

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import chromahub.rhythm.app.infrastructure.audio.DesignerSpectrum
import chromahub.rhythm.app.network.CanvasArtwork
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.player.CanvasArtworkPlayer
import chromahub.rhythm.app.util.LyricsParser
import chromahub.rhythm.app.util.RhythmLyricsParser
import kotlinx.coroutines.*
import kotlin.math.*

private data class StageLine(val time: Long?, val text: String)
private data class StageTextFrame(val song: String?, val lines: List<StageLine>, val index: Int)
private fun stageLines(lyrics: LyricsData?): List<StageLine> {
    if (lyrics == null || lyrics.isErrorMessage()) return emptyList()
    val words = lyrics.wordByWordLyrics?.let { runCatching { RhythmLyricsParser.parseWordByWordLyrics(it) }.getOrNull() }.orEmpty()
    if (words.isNotEmpty()) return words.filterNot { it.background }.map { StageLine(it.lineTimestamp, buildString { it.words.forEachIndexed { i, word -> if(i>0 && !word.isPart) append(' '); append(word.text) } }) }.sortedBy { it.time }
    val synced = lyrics.syncedLyrics?.let { runCatching { LyricsParser.parseLyrics(it) }.getOrNull() }.orEmpty()
    if (synced.isNotEmpty()) return synced.map { StageLine(it.timestamp, it.text) }.sortedBy { it.time }
    return lyrics.plainLyrics.orEmpty().lines().filter { it.isNotBlank() }.map { StageLine(null, it) }
}
private fun Context.activity(): Activity? = when(this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

@Composable
fun DesignerPlayerScreen(
    song: Song?, isPlaying: Boolean, progress: () -> Float, durationMs: Long,
    lyrics: LyricsData?, lyricsOffset: Long, loadingLyrics: Boolean, canvasArtwork: CanvasArtwork?,
    onBack: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Float) -> Unit, onLyricsSeek: ((Long) -> Unit)?, onRetryLyrics: () -> Unit,
    onQueue: () -> Unit, shuffle: Boolean, onShuffle: () -> Unit, onMode: (String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.activity() }
    var fullscreen by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var modeMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    val motion = ValueAnimator.areAnimatorsEnabled()
    DisposableEffect(activity, view) {
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false; bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if(lightStatus != null) bars?.isAppearanceLightStatusBars = lightStatus
            if(lightNavigation != null) bars?.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_START) DesignerSpectrum.show(true)
            if(event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) DesignerSpectrum.show(false)
        }
        owner.lifecycle.addObserver(observer)
        DesignerSpectrum.show(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); DesignerSpectrum.show(false) }
    }
    DisposableEffect(fullscreen, activity) {
        val orientation = activity?.requestedOrientation
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val oldBarsBehavior = controller?.systemBarsBehavior
        val barsVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.systemBars()) != false
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                if (orientation != null) activity?.requestedOrientation = orientation
                if (barsVisible) controller?.show(WindowInsetsCompat.Type.systemBars())
                if (oldBarsBehavior != null) controller?.systemBarsBehavior = oldBarsBehavior
            }
        }
    }
    BackHandler(fullscreen) { fullscreen = false; controls = true }
    LaunchedEffect(fullscreen, controls, isPlaying) {
        if (fullscreen && controls && isPlaying) { delay(4000); controls = false }
    }
    LaunchedEffect(song?.id) { if (song != null && lyrics == null && !loadingLyrics) onRetryLyrics() }
    val lines = remember(lyrics) { stageLines(lyrics) }
    val position = (progress().coerceIn(0f, 1f) * max(0L, durationMs)).toLong()
    val synced = lines.firstOrNull()?.time != null
    val index = if (synced) lines.indexOfLast { (it.time ?: 0) <= position + lyricsOffset } else 0
    var seed by remember { mutableStateOf(Color(0xFF9062BD)) }
    LaunchedEffect(song?.artworkUri, song?.id) {
        seed = withContext(Dispatchers.IO) {
            song?.artworkUri?.let { uri -> runCatching {
                val bitmap = chromahub.rhythm.app.util.ImageUtils.loadArtworkBitmap(context, uri, 64) ?: return@runCatching null
                var r=0L; var g=0L; var b=0L; var count=0
                for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                    val pixel=bitmap.getPixel(x,y); r+=(pixel shr 16) and 255; g+=(pixel shr 8) and 255; b+=pixel and 255; count++
                }
                if (count > 0) Color(android.graphics.Color.rgb((r/count).toInt(),(g/count).toInt(),(b/count).toInt())) else null
            }.getOrNull() } ?: Color(0xFF9062BD)
        }
    }
    val accent by animateColorAsState(seed, tween(if(motion) 1400 else 0), label="song atmosphere")
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared=true }
    val entrance by animateFloatAsState(if(appeared) 1f else 0f, tween(if(motion) 650 else 0), label="designer entrance")
    val buttons: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.Center, verticalAlignment=Alignment.CenterVertically) {
            DesignerButton("shuffle", "Перемешивание", onShuffle, if(shuffle) accent else Color.White.copy(alpha=.55f))
            DesignerButton("skip_previous", "Предыдущая песня", onPrevious)
            FilledIconButton(onClick=onPlayPause, modifier=Modifier.padding(horizontal=18.dp).size(58.dp), colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=Color.Black)) {
                Icon(MaterialSymbolIcon(if(isPlaying) "pause" else "play_arrow", filled=true), if(isPlaying) "Пауза" else "Играть", modifier=Modifier.size(30.dp))
            }
            DesignerButton("skip_next", "Следующая песня", onNext)
            DesignerButton("queue_music", "Очередь", onQueue)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).alpha(entrance)) {
        if (fullscreen) {
            DesignerStage(song, lines, index, position, synced, loadingLyrics, accent, canvasArtwork, isPlaying, motion,
                onLyricsSeek ?: { time -> if(durationMs>0) onSeek(time.toFloat()/durationMs) }, onRetryLyrics,
                Modifier.fillMaxSize().clickable(onClick={controls=!controls}))
            AnimatedVisibility(controls, enter=fadeIn(tween(250)), exit=fadeOut(tween(400)), modifier=Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f)))).navigationBarsPadding().padding(horizontal=32.dp,vertical=8.dp)) {
                    Slider(value=progress().coerceIn(0f,1f),onValueChange=onSeek,colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color.White,inactiveTrackColor=Color.White.copy(alpha=.18f)))
                    buttons()
                }
            }
            AnimatedVisibility(controls,enter=fadeIn(),exit=fadeOut(),modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                DesignerButton("fullscreen_exit","Свернуть полный экран",{fullscreen=false;controls=true})
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    DesignerButton("expand_more","Закрыть плеер",onBack)
                    Box {
                        TextButton(onClick={modeMenu=true}) { Text("Дизайнерский ▾",color=Color.White.copy(alpha=.8f)) }
                        DropdownMenu(expanded=modeMenu,onDismissRequest={modeMenu=false}) {
                            listOf("Обычный" to "MATERIAL","Как надо" to "EXPRESSIVE","Дизайнерский" to "DESIGNER").forEach { (name,id) ->
                                DropdownMenuItem(text={Text(name)},onClick={modeMenu=false;onMode(id)})
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        DesignerButton("more_vert","Ещё",{actionsMenu=true})
                        DropdownMenu(expanded=actionsMenu,onDismissRequest={actionsMenu=false}) {
                            DropdownMenuItem(text={Text("Очередь")},onClick={actionsMenu=false;onQueue()})
                            DropdownMenuItem(text={Text("Скачать в хранилище")},enabled=song!=null,onClick={
                                actionsMenu=false
                                song?.let { chromahub.rhythm.app.features.online.PublicSongExport.start(context,it) }
                            })
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    DesignerStage(song,lines,index,position,synced,loadingLyrics,accent,canvasArtwork,isPlaying,motion,
                        onLyricsSeek ?: { time -> if(durationMs>0) onSeek(time.toFloat()/durationMs) },onRetryLyrics,
                        Modifier.fillMaxWidth().aspectRatio(16f/9f))
                    FilledTonalButton(onClick={fullscreen=true;controls=true},modifier=Modifier.padding(top=14.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xFF161A17),contentColor=Color.White)) {
                        Icon(MaterialSymbolIcon("fullscreen"),null,modifier=Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("На весь экран")
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=16.dp)) {
                    Slider(value=progress().coerceIn(0f,1f),onValueChange=onSeek,colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color.White,inactiveTrackColor=Color.White.copy(alpha=.18f)))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(stageTime(position),color=Color.Gray,fontSize=12.sp);Text(stageTime(durationMs),color=Color.Gray,fontSize=12.sp)
                    }
                    Spacer(Modifier.height(10.dp)); buttons()
                }
            }
        }
    }
}
private fun stageTime(ms: Long): String { val seconds=max(0,ms)/1000;return "%d:%02d".format(seconds/60,seconds%60) }
@Composable private fun DesignerButton(icon:String,description:String,action:()->Unit,tint:Color=Color.White) {
    IconButton(onClick=action,modifier=Modifier.size(48.dp)) { Icon(MaterialSymbolIcon(icon),description,tint=tint,modifier=Modifier.size(26.dp)) }
}

@Composable private fun DesignerStage(
    song:Song?,lines:List<StageLine>,index:Int,position:Long,synced:Boolean,loading:Boolean,accent:Color,canvas:CanvasArtwork?,playing:Boolean,motion:Boolean,
    onSeek:(Long)->Unit,onRetry:()->Unit,modifier:Modifier
) {
    BoxWithConstraints(modifier.background(Color(0xFF17101E)).clipToBounds()) {
        val unit = maxWidth.value
        val stageHeight = maxHeight.value
        Crossfade(targetState=song?.artworkUri,animationSpec=tween(if(motion) 1500 else 0),label="song background") { uri ->
            AsyncImage(uri,null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize().graphicsLayer { scaleX=1.08f;scaleY=1.08f })
        }
        if(canvas?.preferredAnimationUrl!=null) CanvasArtworkPlayer(primaryUrl=canvas.animated,fallbackUrl=canvas.videoUrl,isPlaying=playing,modifier=Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(accent.copy(alpha=.40f)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha=.57f),Color.Black.copy(alpha=.24f)))))
        Row(Modifier.align(Alignment.TopStart).padding(start=(unit*.065f).dp,top=(maxHeight.value*.06f).dp),verticalAlignment=Alignment.CenterVertically) {
            AsyncImage(song?.artworkUri,null,contentScale=ContentScale.Crop,modifier=Modifier.size((unit*.058f).coerceIn(24f,52f).dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width((unit*.016f).dp))
            Column(Modifier.widthIn(max=(unit*.65f).dp)) {
                Text(song?.title ?: "Выберите песню",fontSize=(unit*.018f).coerceIn(10f,18f).sp,color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(song?.artist.orEmpty(),fontSize=(unit*.015f).coerceIn(9f,15f).sp,color=Color.White.copy(alpha=.8f),maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        val font=(unit*.045f).coerceIn(16f,48f).sp
        val plainScroll = rememberScrollState()
        LaunchedEffect(song?.id) { plainScroll.scrollTo(0) }
        val frame = remember(song?.id, lines, index) { StageTextFrame(song?.id, lines, index) }
        Box(Modifier.align(Alignment.CenterStart).padding(start=(unit*.065f).dp).fillMaxWidth(.57f)) {
            if(lines.isEmpty()) Column {
                Text(if(loading) "Ищем текст…" else "Текст пока не найден",color=Color.White,fontSize=font,fontWeight=FontWeight.Bold)
                if(!loading) TextButton(onClick=onRetry) {Text("Найти текст",color=Color.White.copy(alpha=.7f))}
            } else if(!synced) Column(Modifier.heightIn(max=(stageHeight*.6f).dp).verticalScroll(plainScroll)) {
                lines.forEach { Text(it.text,color=Color.White.copy(alpha=.8f),fontSize=font,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=4.dp)) }
            } else AnimatedContent(targetState=frame,transitionSpec={ (fadeIn(tween(if(motion) 400 else 0))+slideInVertically(tween(if(motion) 500 else 0)){it/5}).togetherWith(fadeOut(tween(if(motion) 250 else 0))+slideOutVertically(tween(if(motion) 400 else 0)){-it/5}) },label="lyric progression") { textFrame ->
                val selected = textFrame.index
                Column(verticalArrangement=Arrangement.spacedBy((unit*.014f).dp)) {
                    if(selected<0) Text("♪",color=Color.White.copy(alpha=.45f),fontSize=font)
                    val start=selected.coerceAtLeast(0)
                    textFrame.lines.drop(start).take(if(selected<0) 2 else 3).forEachIndexed { offset,line ->
                        val current=selected>=0 && offset==0
                        Text(line.text.ifBlank { "♪" },color=Color.White.copy(alpha=if(current) 1f else if(offset<=1) .48f else .25f),fontSize=font,fontWeight=FontWeight.Bold,
                            lineHeight=(font.value*1.18f).sp,maxLines=if(current) 2 else 1,overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.clickable { line.time?.let(onSeek) })
                    }
                }
            }
        }
        MusicBlob(playing,motion,accent,Modifier.align(Alignment.CenterEnd).padding(end=(unit*.18f).dp).size((unit*.18f).dp))
    }
}
@Composable private fun MusicBlob(playing:Boolean,motion:Boolean,accent:Color,modifier:Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    var bands by remember { mutableStateOf(FloatArray(7)) }
    val drift by animateFloatAsState(if(playing && motion) 1f else 0f,tween(if(motion) 900 else 0),label="soft music rest")
    val currentDrift by rememberUpdatedState(drift)
    LaunchedEffect(playing,motion) {
        var previous=0L;var tail=0f
        while(isActive) {
            withFrameNanos { now ->
                val dt=if(previous==0L) 0f else ((now-previous)/1e9f).coerceAtMost(.05f);previous=now
                if(motion) phase+=dt*.5f*currentDrift
                if(!playing) tail+=dt
                val live=playing && now-DesignerSpectrum.updatedNanos<250_000_000L
                val target=if(live) DesignerSpectrum.levels else FloatArray(7)
                bands=FloatArray(7) { i -> bands[i]+(target[i]-bands[i])*(1-exp(-dt*(if(target[i]>bands[i]) 18f else 6f))) }
            }
            if(!playing && tail>1.6f) break
            if(!motion) delay(100)
        }
    }
    Canvas(modifier) {
        val energy=bands.average().toFloat()
        val radius=size.minDimension*(.43f+energy*.025f)
        val center=Offset(size.width/2,size.height/2)
        val path=Path()
        for(i in 0..128) {
            val angle=i/128f*(2*PI).toFloat()
            val r=radius*(1+.10f*sin(angle*3+phase)+.055f*cos(angle*5-phase*.7f))
            val p=center+Offset(cos(angle)*r,sin(angle)*r)
            if(i==0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y)
        }
        path.close()
        drawPath(path,lerp(Color.White,accent,.13f));drawPath(path,Color.White.copy(alpha=.6f),style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.dp.toPx()))
        val barWidth=size.width*.026f;val gap=size.width*.035f
        for(i in 0..6) {
            val height=size.height*(.085f+bands[i]*.31f)
            val x=center.x+(i-3)*(barWidth+gap)-barWidth/2
            drawRoundRect(lerp(accent,Color(0xFF751799),.45f),Offset(x,center.y-height/2),Size(barWidth,height),CornerRadius(barWidth/2))
        }
    }
}
