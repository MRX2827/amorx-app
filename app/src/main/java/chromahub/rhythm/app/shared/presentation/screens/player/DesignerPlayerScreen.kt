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
import coil.request.ImageRequest
import chromahub.rhythm.app.infrastructure.audio.DesignerSpectrum
import chromahub.rhythm.app.network.CanvasArtwork
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.player.CanvasArtworkPlayer
import kotlinx.coroutines.*
import kotlin.math.*

private data class StageTextFrame(val song: String?, val lines: List<StageLine>, val index: Int)
private fun Context.activity(): Activity? = when(this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

@Composable
fun DesignerPlayerScreen(
    song: Song?, isPlaying: Boolean, progress: () -> Float, durationMs: Long,
    lyrics: LyricsData?, lyricsOffset: Long, loadingLyrics: Boolean, canvasArtwork: CanvasArtwork?,
    onBack: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Float) -> Unit, onLyricsSeek: ((Long) -> Unit)?, onRetryLyrics: () -> Unit,
    onQueue: () -> Unit, shuffle: Boolean, onShuffle: () -> Unit, onMode: (String) -> Unit,
    isVisible: Boolean = true, onPickLyricsFile: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.activity() }
    var fullscreen by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var modeMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    val motion = ValueAnimator.areAnimatorsEnabled()
    DisposableEffect(activity, view, isVisible) {
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        if (isVisible) { bars?.isAppearanceLightStatusBars = false; bars?.isAppearanceLightNavigationBars = false }
        onDispose {
            if(isVisible && lightStatus != null) bars?.isAppearanceLightStatusBars = lightStatus
            if(isVisible && lightNavigation != null) bars?.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    SideEffect {
        // Apply after the app theme's system-bar effect, which may run again on a song change.
        activity?.window?.takeIf { isVisible }?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, isVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_START) DesignerSpectrum.show(isVisible)
            if(event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) DesignerSpectrum.show(false)
        }
        owner.lifecycle.addObserver(observer)
        DesignerSpectrum.show(isVisible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
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
    LaunchedEffect(isVisible) { if (!isVisible) fullscreen = false }
    val parsed by produceState<Pair<LyricsData?, List<StageLine>>>(null to emptyList(), song?.id, lyrics) {
        value = lyrics to withContext(Dispatchers.Default) { stageLines(lyrics) }
    }
    val lines = if(parsed.first == lyrics) parsed.second else emptyList()
    val loadingText = loadingLyrics || (song != null && lyrics == null) || parsed.first != lyrics
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
    Box(Modifier.fillMaxSize().background(lerp(Color(0xFF18101F), accent, .35f)).alpha(entrance)) {
        DesignerBackdrop(song, accent, canvasArtwork, isPlaying && isVisible, motion)
        if (fullscreen) {
            Box(Modifier.fillMaxSize().clickable { controls = !controls }) {
                DesignerSongHeading(song, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(24.dp).fillMaxWidth(.75f))
                DesignerStage(song, lines, index, synced, loadingText, accent, isPlaying && isVisible, motion,
                    onLyricsSeek ?: { time -> if(durationMs>0) onSeek(time.toFloat()/durationMs) }, onRetryLyrics, onPickLyricsFile,
                    Modifier.fillMaxSize().padding(top=90.dp, bottom=80.dp))
            }
            AnimatedVisibility(controls, enter=fadeIn(tween(if(motion) 250 else 0)), exit=fadeOut(tween(if(motion) 400 else 0)), modifier=Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,accent.copy(alpha=.3f),Color(0xFF17101F).copy(alpha=.8f)))).navigationBarsPadding().padding(horizontal=32.dp,vertical=8.dp)) {
                    Slider(value=progress().coerceIn(0f,1f),onValueChange=onSeek,colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color.White,inactiveTrackColor=Color.White.copy(alpha=.18f)))
                    buttons()
                }
            }
            AnimatedVisibility(controls,enter=fadeIn(),exit=fadeOut(),modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                DesignerButton("fullscreen_exit","Свернуть полный экран",{fullscreen=false;controls=true})
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    DesignerButton("expand_more","Закрыть плеер",onBack)
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick={modeMenu=true}) { Text("Дизайнерский ▾",color=Color.White.copy(alpha=.8f),maxLines=1,overflow=TextOverflow.Ellipsis) }
                        DropdownMenu(expanded=modeMenu,onDismissRequest={modeMenu=false}) {
                            listOf("Обычный" to "MATERIAL","Как надо" to "EXPRESSIVE","Дизайнерский" to "DESIGNER").forEach { (name,id) ->
                                DropdownMenuItem(text={Text(name)},onClick={modeMenu=false;onMode(id)})
                            }
                        }
                    }
                    DesignerButton("fullscreen","На весь экран",{fullscreen=true;controls=true})
                    Box {
                        DesignerButton("more_vert","Ещё",{actionsMenu=true})
                        DropdownMenu(expanded=actionsMenu,onDismissRequest={actionsMenu=false}) {
                            DropdownMenuItem(text={Text("Очередь")},onClick={actionsMenu=false;onQueue()})
                            DropdownMenuItem(text={Text("Загрузить текст песни")},onClick={actionsMenu=false;onPickLyricsFile()})
                            DropdownMenuItem(text={Text("Скачать в хранилище")},enabled=song!=null,onClick={
                                actionsMenu=false
                                song?.let { chromahub.rhythm.app.features.online.PublicSongExport.start(context,it) }
                            })
                        }
                    }
                }
                DesignerSongHeading(song, Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp))
                DesignerStage(song,lines,index,synced,loadingText,accent,isPlaying && isVisible,motion,
                    onLyricsSeek ?: { time -> if(durationMs>0) onSeek(time.toFloat()/durationMs) },onRetryLyrics,onPickLyricsFile,
                    Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=16.dp)) {
                    Slider(value=progress().coerceIn(0f,1f),onValueChange=onSeek,colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color.White,inactiveTrackColor=Color.White.copy(alpha=.18f)))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(stageTime(position),color=Color.White.copy(alpha=.6f),fontSize=12.sp)
                        Text(stageTime(durationMs),color=Color.White.copy(alpha=.6f),fontSize=12.sp)
                    }
                    Spacer(Modifier.height(10.dp)); buttons()
                }
            }
        }
    }
}

@Composable private fun DesignerSongHeading(song: Song?, modifier: Modifier) {
    Row(modifier,verticalAlignment=Alignment.CenterVertically) {
        AsyncImage(song?.artworkUri,null,contentScale=ContentScale.Crop,
            modifier=Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: "Выберите песню",fontSize=18.sp,color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(song?.artist.orEmpty(),fontSize=14.sp,color=Color.White.copy(alpha=.68f),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun DesignerBackdrop(song: Song?, accent: Color, canvas: CanvasArtwork?, playing: Boolean, motion: Boolean) {
    val context = LocalContext.current
    var phase by remember { mutableFloatStateOf(0f) }
    val drift by animateFloatAsState(if(playing && motion) 1f else 0f,tween(if(motion) 1400 else 0),label="background rest")
    val currentDrift by rememberUpdatedState(drift)
    LaunchedEffect(playing,motion) {
        if(!motion) return@LaunchedEffect
        var last=0L; var tail=0f
        while(isActive) {
            withFrameNanos { now ->
                val dt=if(last==0L) 0f else ((now-last)/1e9f).coerceAtMost(.05f);last=now
                phase+=dt*.12f*currentDrift
                if(!playing) tail+=dt
            }
            if(!playing && tail>1.6f) break
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Crossfade(targetState=song?.artworkUri,animationSpec=tween(if(motion) 1500 else 0),label="immersive song background") { uri ->
            val request=remember(uri,context) { ImageRequest.Builder(context).data(uri).size(720).build() }
            AsyncImage(request,null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize().graphicsLayer {
                scaleX=1.18f;scaleY=1.18f
                translationX=sin(phase)*size.width*.035f;translationY=cos(phase*.8f)*size.height*.025f
            }.blur(24.dp))
        }
        if(canvas?.preferredAnimationUrl!=null) CanvasArtworkPlayer(primaryUrl=canvas.animated,fallbackUrl=canvas.videoUrl,isPlaying=playing,modifier=Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(accent.copy(alpha=.30f)))
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=.32f),Color.Transparent),center=Offset(size.width*(.7f+.15f*sin(phase)),size.height*.28f),radius=size.maxDimension*.7f),radius=size.maxDimension*.7f,center=Offset(size.width*(.7f+.15f*sin(phase)),size.height*.28f))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0xFF17101F).copy(alpha=.55f),Color(0xFF17101F).copy(alpha=.22f),lerp(Color(0xFF17101F),accent,.2f).copy(alpha=.88f)))))
    }
}

private fun stageTime(ms: Long): String { val seconds=max(0,ms)/1000;return "%d:%02d".format(seconds/60,seconds%60) }
@Composable private fun DesignerButton(icon:String,description:String,action:()->Unit,tint:Color=Color.White) {
    IconButton(onClick=action,modifier=Modifier.size(48.dp)) { Icon(MaterialSymbolIcon(icon),description,tint=tint,modifier=Modifier.size(26.dp)) }
}

@Composable private fun DesignerStage(
    song:Song?,lines:List<StageLine>,index:Int,synced:Boolean,loading:Boolean,accent:Color,playing:Boolean,motion:Boolean,
    onSeek:(Long)->Unit,onRetry:()->Unit,onPickLyricsFile:()->Unit,modifier:Modifier
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val unit = maxWidth.value
        val stageHeight = maxHeight.value
        val wide = maxWidth > maxHeight * 1.35f
        val font = min(unit * (if(wide) .047f else .075f), stageHeight / 7f).coerceIn(16f,44f).sp
        val plainScroll = rememberScrollState()
        LaunchedEffect(song?.id) { plainScroll.scrollTo(0) }
        val frame = remember(song?.id, lines, index) { StageTextFrame(song?.id, lines, index) }
        Box(Modifier.align(Alignment.CenterStart).padding(start=24.dp).fillMaxWidth(.64f).heightIn(max=(stageHeight*.9f).dp)) {
            if(lines.isEmpty()) Column {
                if(loading) {
                    CircularProgressIndicator(Modifier.padding(bottom=14.dp).size(22.dp),color=Color.White.copy(alpha=.8f),strokeWidth=2.dp)
                    Text("Ищем текст песни…",color=Color.White,fontSize=font,fontWeight=FontWeight.SemiBold)
                } else {
                    Text("Текст пока недоступен",color=Color.White,fontSize=font,fontWeight=FontWeight.SemiBold)
                    Text("Попробуйте поиск ещё раз или добавьте свой файл",color=Color.White.copy(alpha=.6f),fontSize=13.sp,modifier=Modifier.padding(top=10.dp))
                    TextButton(onClick=onRetry) { Text("Повторить поиск",color=Color.White) }
                    TextButton(onClick=onPickLyricsFile) { Text("Загрузить текст",color=Color.White.copy(alpha=.7f)) }
                }
            } else if(!synced) Column(Modifier.verticalScroll(plainScroll)) {
                lines.forEach { Text(it.text,color=Color.White.copy(alpha=.88f),fontSize=font,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=6.dp)) }
            } else AnimatedContent(targetState=frame,transitionSpec={ (fadeIn(tween(if(motion) 400 else 0))+slideInVertically(tween(if(motion) 500 else 0)){it/5}).togetherWith(fadeOut(tween(if(motion) 250 else 0))+slideOutVertically(tween(if(motion) 400 else 0)){-it/5}) },label="lyric progression") { textFrame ->
                val selected = textFrame.index
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(selected<0) Text("♪",color=Color.White.copy(alpha=.45f),fontSize=font)
                    textFrame.lines.drop(selected.coerceAtLeast(0)).take(if(selected<0) 2 else 3).forEachIndexed { offset,line ->
                        val current=selected>=0 && offset==0
                        Text(line.text.ifBlank { "♪" },color=Color.White.copy(alpha=if(current) 1f else if(offset<=1) .48f else .25f),fontSize=font,fontWeight=FontWeight.Bold,
                            lineHeight=(font.value*1.2f).sp,maxLines=if(current) 3 else 1,overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.clickable { line.time?.let(onSeek) })
                    }
                }
            }
        }
        val blobSize=min(unit*.25f,stageHeight*.6f).coerceIn(48f,180f)
        MusicBlob(playing,motion,accent,Modifier.align(Alignment.CenterEnd).padding(end=(unit*.055f).dp).size(blobSize.dp))
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
