import os, sys, shutil

A = "app/src/main/java/chromahub/rhythm/app/"
O = A + "features/online/"
CS, LV = O + "ChatScreens.kt", O + "ChatLive.kt"

if not os.path.isdir(O):
    print("СТОП: запускайте из папки ~/Desktop/Rhythm. Ничего не записано."); sys.exit(1)

errors = []
def rd(p):
    try:
        return open(p, encoding="utf-8").read()
    except FileNotFoundError:
        errors.append("нет файла " + p); return ""
def once(t, old, new, name):
    n = t.count(old)
    if n != 1:
        errors.append("%s: найдено %d, ожидалось 1" % (name, n)); return t
    return t.replace(old, new)

# ================= ChatScreens.kt =================
cs = rd(CS)
if "val animatedIds" in cs:
    errors.append("ChatScreens.kt: анимации сообщений уже добавлены")

cs = once(cs, "import androidx.compose.foundation.Image\n",
    "import androidx.compose.animation.core.Animatable\n"
    "import androidx.compose.animation.core.EaseOutQuint\n"
    "import androidx.compose.animation.core.tween\n"
    "import androidx.compose.ui.graphics.TransformOrigin\n"
    "import androidx.compose.ui.graphics.graphicsLayer\n"
    "import androidx.compose.foundation.Image\n", "ChatScreens: импорты")

# состояние
cs = once(cs, "    val listState = rememberLazyListState()\n",
    "    val listState = rememberLazyListState()\n"
    "    var pending by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }\n"
    "    var baseline by remember { mutableStateOf<Set<Long>?>(null) }\n"
    "    val animatedIds = remember { mutableSetOf<Long>() }\n"
    "    val firstScroll = remember { booleanArrayOf(true) }\n"
    "    val shown = messages + pending\n", "ChatScreens: состояние диалога")

# первая загрузка: сообщения, которые уже были, не анимируются
cs = once(cs, ".onSuccess { messages = it; error = null }",
    ".onSuccess {\n"
    "                    if (baseline == null) baseline = it.map { x -> x.id }.toSet()\n"
    "                    messages = it\n"
    "                    error = null\n"
    "                }", "ChatScreens: опрос")

# плавная прокрутка к новому сообщению
cs = once(cs,
    "    LaunchedEffect(messages.size) {\n"
    "        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)\n"
    "    }\n",
    "    LaunchedEffect(messages.size + pending.size) {\n"
    "        val last = messages.size + pending.size - 1\n"
    "        if (last >= 0) {\n"
    "            if (firstScroll[0]) {\n"
    "                listState.scrollToItem(last)\n"
    "                firstScroll[0] = false\n"
    "            } else {\n"
    "                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1\n"
    "                if (pending.isNotEmpty() || lastVisible >= last - 3) listState.animateScrollToItem(last)\n"
    "            }\n"
    "        }\n"
    "    }\n", "ChatScreens: прокрутка")

# отправка: сообщение появляется сразу
cs = once(cs,
    "        scope.launch {\n"
    "            sending = true\n"
    "            CloudChat.send(peer.id, body, null, ProfileStore.name.value)\n"
    "                .onSuccess {\n"
    "                    text = \"\"\n"
    "                    CloudChat.thread(peer.id).onSuccess { messages = it }\n"
    "                }\n"
    "                .onFailure {\n"
    "                    Toast.makeText(context, it.message ?: \"Не удалось отправить\", Toast.LENGTH_LONG).show()\n"
    "                }\n"
    "            sending = false\n"
    "        }\n",
    "        val tempId = -System.currentTimeMillis()\n"
    "        pending = pending + ChatMessage(tempId, true, body, null, System.currentTimeMillis(), false)\n"
    "        text = \"\"\n"
    "        scope.launch {\n"
    "            sending = true\n"
    "            CloudChat.send(peer.id, body, null, ProfileStore.name.value)\n"
    "                .onSuccess {\n"
    "                    CloudChat.thread(peer.id).onSuccess { list ->\n"
    "                        list.filter { x -> x.fromMe }.forEach { x -> animatedIds.add(x.id) }\n"
    "                        messages = list\n"
    "                    }\n"
    "                    pending = pending.filter { x -> x.id != tempId }\n"
    "                }\n"
    "                .onFailure {\n"
    "                    pending = pending.filter { x -> x.id != tempId }\n"
    "                    text = body\n"
    "                    Toast.makeText(context, it.message ?: \"Не удалось отправить\", Toast.LENGTH_LONG).show()\n"
    "                }\n"
    "            sending = false\n"
    "        }\n", "ChatScreens: отправка")

# список
cs = once(cs,
    "            items(messages, key = { it.id }) { m ->\n"
    "                MessageBubble(m) { t -> ProfileSongs.play(context, musicViewModel, t) }\n"
    "            }\n",
    "            items(shown, key = { it.id }) { m ->\n"
    "                val base = baseline\n"
    "                val animate = remember(m.id) { base != null && m.id !in base && animatedIds.add(m.id) }\n"
    "                MessageBubble(m, animate) { t -> ProfileSongs.play(context, musicViewModel, t) }\n"
    "            }\n", "ChatScreens: список сообщений")

# пузырь: проявление + масштаб 0.85 (как у экранов плейлиста), растёт из своего угла
cs = once(cs,
    "private fun MessageBubble(m: ChatMessage, onPlay: (CloudSong) -> Unit) {\n"
    "    val mine = m.fromMe\n"
    "    Row(\n"
    "        modifier = Modifier.fillMaxWidth(),\n",
    "private fun MessageBubble(m: ChatMessage, animate: Boolean, onPlay: (CloudSong) -> Unit) {\n"
    "    val mine = m.fromMe\n"
    "    val appear = remember { Animatable(if (animate) 0f else 1f) }\n"
    "    LaunchedEffect(Unit) {\n"
    "        if (animate) appear.animateTo(1f, tween(400, easing = EaseOutQuint))\n"
    "    }\n"
    "    Row(\n"
    "        modifier = Modifier.fillMaxWidth().graphicsLayer {\n"
    "            val p = appear.value\n"
    "            alpha = p\n"
    "            val s = 0.85f + 0.15f * p\n"
    "            scaleX = s\n"
    "            scaleY = s\n"
    "            translationY = (1f - p) * 48f\n"
    "            transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)\n"
    "        },\n", "ChatScreens: пузырь")

# ================= ChatLive.kt (баннер) =================
lv = rd(LV)
if "popupAlive" in lv:
    errors.append("ChatLive.kt: анимация баннера уже добавлена")

lv = once(lv, "import androidx.compose.foundation.clickable\n",
    "import androidx.compose.animation.AnimatedVisibility\n"
    "import androidx.compose.animation.core.MutableTransitionState\n"
    "import androidx.compose.animation.core.Spring\n"
    "import androidx.compose.animation.core.spring\n"
    "import androidx.compose.animation.fadeIn\n"
    "import androidx.compose.animation.fadeOut\n"
    "import androidx.compose.animation.slideInVertically\n"
    "import androidx.compose.animation.slideOutVertically\n"
    "import androidx.compose.foundation.clickable\n", "ChatLive: импорты")

START = ("    val b = banner\n"
         "    if (b != null) {\n"
         "        Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {\n")
END = "\n/** Переключатель фоновых уведомлений для экрана профиля. */"
i = lv.find(START)
j = lv.find(END)
if lv.count(START) != 1 or lv.count(END) != 1 or j < i:
    errors.append("ChatLive: баннер, не найден блок (начало %d, конец %d)" % (lv.count(START), lv.count(END)))
else:
    NEWBLOCK = (
        "    val b = banner\n"
        "    val lastBanner = remember { arrayOfNulls<ChatNotifier.Incoming>(1) }\n"
        "    if (b != null) lastBanner[0] = b\n"
        "    var popupAlive by remember { mutableStateOf(false) }\n"
        "    LaunchedEffect(b != null) {\n"
        "        if (b != null) {\n"
        "            popupAlive = true\n"
        "        } else {\n"
        "            delay(900)\n"
        "            popupAlive = false\n"
        "        }\n"
        "    }\n"
        "    val shownBanner = lastBanner[0]\n"
        "    if (shownBanner != null && (b != null || popupAlive)) {\n"
        "        Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {\n"
        "            val vis = remember { MutableTransitionState(false) }\n"
        "            vis.targetState = b != null\n"
        "            AnimatedVisibility(\n"
        "                visibleState = vis,\n"
        "                enter = slideInVertically(\n"
        "                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),\n"
        "                    initialOffsetY = { -it }\n"
        "                ) + fadeIn(),\n"
        "                exit = slideOutVertically(\n"
        "                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),\n"
        "                    targetOffsetY = { -it }\n"
        "                ) + fadeOut()\n"
        "            ) {\n"
        "                Card(\n"
        "                    modifier = Modifier\n"
        "                        .padding(top = 48.dp, start = 12.dp, end = 12.dp)\n"
        "                        .widthIn(max = 480.dp)\n"
        "                        .clickable {\n"
        "                            banner = null\n"
        "                            ChatNotifier.openChats.value = true\n"
        "                            onOpenChats()\n"
        "                        },\n"
        "                    shape = RoundedCornerShape(20.dp),\n"
        "                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),\n"
        "                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)\n"
        "                ) {\n"
        "                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {\n"
        "                        Text(\n"
        "                            shownBanner.fromName,\n"
        "                            style = MaterialTheme.typography.titleSmall,\n"
        "                            fontWeight = FontWeight.Bold,\n"
        "                            maxLines = 1,\n"
        "                            overflow = TextOverflow.Ellipsis\n"
        "                        )\n"
        "                        Text(\n"
        "                            shownBanner.text,\n"
        "                            style = MaterialTheme.typography.bodyMedium,\n"
        "                            maxLines = 2,\n"
        "                            overflow = TextOverflow.Ellipsis\n"
        "                        )\n"
        "                    }\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "}\n")
    lv = lv[:i] + NEWBLOCK + lv[j:]

if errors:
    print("СТОП. Ничего не записано. Проблемы (%d):" % len(errors))
    for e in errors: print("  - " + e)
    sys.exit(1)

bk = os.path.expanduser("~/Desktop/msg_anim_backup")
os.makedirs(bk, exist_ok=True)
for p in (CS, LV):
    shutil.copy(p, os.path.join(bk, os.path.basename(p)))
for p, t in ((CS, cs), (LV, lv)):
    open(p, "w", encoding="utf-8").write(t)
print("Готово: анимации сообщений и баннера добавлены. Копии: ~/Desktop/msg_anim_backup")
