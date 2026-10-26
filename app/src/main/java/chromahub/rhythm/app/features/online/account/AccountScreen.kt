package chromahub.rhythm.app.features.online.account

import android.util.Patterns
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Шаги экрана. Переходы между ними анимируются. */
private sealed interface AccountStage {
    object Loading : AccountStage
    class Failed(val message: String) : AccountStage
    object Welcome : AccountStage
    object Code : AccountStage
    class Done(val note: String?) : AccountStage
    object Member : AccountStage
    object ChangePassword : AccountStage
    object ChangeEmail : AccountStage
    object EmailCode : AccountStage
}

private fun AccountStage.order(): Int = when (this) {
    is AccountStage.Loading -> 0
    is AccountStage.Failed -> 0
    is AccountStage.Welcome -> 1
    is AccountStage.Code -> 2
    is AccountStage.Done -> 3
    is AccountStage.Member -> 3
    is AccountStage.ChangePassword -> 4
    is AccountStage.ChangeEmail -> 4
    is AccountStage.EmailCode -> 5
}

/** Всё, что вводит пользователь, и состояние запроса. */
private class AccountForm {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var code by mutableStateOf("")
    var loginEmail by mutableStateOf("")
    var loginPassword by mutableStateOf("")
    var loginTab by mutableStateOf(false)
    var currentPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var newEmail by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var cooldown by mutableStateOf(0)
    var accountEmail by mutableStateOf("")
}

/**
 * Экран аккаунта AmorX. Без аккаунта плеер не открывается.
 * Создать аккаунт: почта и пароль → код из письма → готово (почта подтверждается ТОЛЬКО кодом).
 * Войти: почта и пароль → данные из облака восстанавливаются сами.
 * В аккаунте: сменить пароль, сменить почту, сохранить и восстановить копию, выйти.
 * gate = true: это окно входа при запуске приложения (закрыть его нельзя, пока не вошли).
 */
@Composable
fun AccountScreen(
    onBackClick: () -> Unit,
    createBackup: suspend () -> String,
    restoreBackup: suspend (String) -> Boolean,
    gate: Boolean = false,
    onFinished: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = remember { AccountForm() }
    var stage by remember { mutableStateOf<AccountStage>(AccountStage.Loading) }
    var confirmRestore by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val autoSync by CloudAccount.autoSync.collectAsState()

    fun launchBusy(block: suspend () -> Unit) {
        if (f.busy) return
        f.busy = true
        f.error = null
        f.notice = null
        scope.launch {
            try {
                block()
            } finally {
                f.busy = false
            }
        }
    }

    fun goTo(next: AccountStage) {
        f.error = null
        f.notice = null
        stage = next
    }

    /** Смотрит, кто вошёл, и выбирает нужный шаг. */
    suspend fun load() {
        stage = AccountStage.Loading
        CloudAccount.load(context)
        val r = CloudAccount.info(context)
        val info = r.getOrNull()
        if (info == null) {
            stage = AccountStage.Failed(CloudAccount.errorMessage(r.exceptionOrNull() ?: Exception()))
            return
        }
        f.accountEmail = info.email
        when {
            !info.isGuest -> {
                CloudAccount.setSetupDone(context, true)
                if (gate) onFinished() else stage = AccountStage.Member
            }
            // Код регистрации уже отправлен: продолжаем с ввода кода
            info.pendingEmail.isNotBlank() -> {
                f.email = info.pendingEmail
                f.notice = "Мы отправили код на ${info.pendingEmail}. Введите его, чтобы завершить регистрацию"
                stage = AccountStage.Code
            }
            else -> stage = AccountStage.Welcome
        }
    }

    /** Первая копия в облако после создания аккаунта. Возвращает текст, если не вышло. */
    suspend fun saveFirstBackup(): String? {
        CloudAccount.markSynced(context)
        val r = runCatching { CloudAccount.push(context, createBackup()).getOrThrow() }
        return r.exceptionOrNull()?.let {
            "Копию в облако сохранить не удалось (${CloudAccount.errorMessage(it)}). Приложение попробует позже"
        }
    }

    // ----- регистрация

    fun sendCode() = launchBusy {
        val e = f.email.trim().lowercase()
        if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            f.error = "Введите корректную почту"
            return@launchBusy
        }
        if (f.password.length < 6) {
            f.error = "Пароль: минимум 6 символов"
            return@launchBusy
        }
        val err = CloudAccount.register(context, e, f.password).exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        f.email = e
        f.password = ""
        f.code = ""
        f.cooldown = 60
        f.notice = "Код отправлен на $e. Если письма нет, проверьте «Спам»"
        stage = AccountStage.Code
    }

    fun resendCode() = launchBusy {
        val err = CloudAccount.resendCode(context, f.email).exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        f.cooldown = 60
        f.notice = "Код отправлен ещё раз на ${f.email}"
    }

    fun confirmCode() = launchBusy {
        if (f.code.length < 6) {
            f.error = "Введите код из письма"
            return@launchBusy
        }
        val err = CloudAccount.confirmCode(context, f.email, f.code).exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        f.code = ""
        f.accountEmail = f.email
        stage = AccountStage.Done(saveFirstBackup())
    }

    // ----- вход

    fun login() = launchBusy {
        val e = f.loginEmail.trim().lowercase()
        if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            f.error = "Введите корректную почту"
            return@launchBusy
        }
        if (f.loginPassword.isEmpty()) {
            f.error = "Введите пароль"
            return@launchBusy
        }
        val signed = CloudAccount.signIn(context, e, f.loginPassword)
        val signErr = signed.exceptionOrNull()
        if (signErr != null) {
            if ((signErr as? CloudException)?.code == "email_not_confirmed") {
                // Аккаунт создан, но код не вводили: отправляем новый и переходим к вводу кода
                val resendErr = CloudAccount.resendCode(context, e).exceptionOrNull()
                f.email = e
                f.code = ""
                f.cooldown = 60
                if (resendErr == null) {
                    f.notice = "Почта ещё не подтверждена. Мы отправили код на $e"
                } else {
                    f.error = CloudAccount.errorMessage(resendErr)
                }
                stage = AccountStage.Code
                return@launchBusy
            }
            f.error = CloudAccount.errorMessage(signErr)
            return@launchBusy
        }
        f.loginPassword = ""
        f.accountEmail = e
        val pulled = CloudAccount.pull(context)
        val pullErr = pulled.exceptionOrNull()
        if (pullErr != null) {
            stage = AccountStage.Done("Вход выполнен, но скачать копию не удалось: ${CloudAccount.errorMessage(pullErr)}")
            return@launchBusy
        }
        val backup = pulled.getOrNull()
        if (backup == null) {
            val saved = saveFirstBackup() ?: "Данные этого телефона сохранены в облако"
            stage = AccountStage.Done("Вход выполнен. В облаке пока не было копии. $saved")
            return@launchBusy
        }
        val ok = runCatching { restoreBackup(backup.payload) }.getOrDefault(false)
        if (!ok) {
            stage = AccountStage.Done("Вход выполнен, но восстановить копию не удалось")
            return@launchBusy
        }
        CloudAccount.markSynced(context)
        stage = AccountStage.Done("Данные восстановлены. Приложение перезапускается…")
        restartApp(context)
    }

    // ----- облачная копия

    fun saveNow() = launchBusy {
        val r = runCatching { CloudAccount.push(context, createBackup()).getOrThrow() }
        val err = r.exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        CloudAccount.markSynced(context)
        f.notice = "Сохранено в облако"
    }

    fun restoreNow() = launchBusy {
        val pulled = CloudAccount.pull(context)
        val err = pulled.exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        val backup = pulled.getOrNull()
        if (backup == null) {
            f.notice = "В облаке пока нет копии"
            return@launchBusy
        }
        val ok = runCatching { restoreBackup(backup.payload) }.getOrDefault(false)
        if (!ok) {
            f.error = "Не удалось восстановить копию"
            return@launchBusy
        }
        CloudAccount.markSynced(context)
        f.notice = "Данные восстановлены. Приложение перезапускается…"
        restartApp(context)
    }

    // ----- смена пароля и почты

    fun submitPasswordChange() = launchBusy {
        if (f.currentPassword.isEmpty()) {
            f.error = "Введите текущий пароль"
            return@launchBusy
        }
        if (f.newPassword.length < 6) {
            f.error = "Новый пароль: минимум 6 символов"
            return@launchBusy
        }
        if (f.newPassword == f.currentPassword) {
            f.error = "Новый пароль совпадает со старым"
            return@launchBusy
        }
        val err = CloudAccount.changePassword(context, f.currentPassword, f.newPassword).exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        f.currentPassword = ""
        f.newPassword = ""
        stage = AccountStage.Member
        f.notice = "Пароль изменён"
    }

    fun sendEmailChange() = launchBusy {
        val e = f.newEmail.trim().lowercase()
        if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            f.error = "Введите корректную почту"
            return@launchBusy
        }
        if (f.currentPassword.isEmpty()) {
            f.error = "Введите текущий пароль"
            return@launchBusy
        }
        val err = CloudAccount.startEmailChange(context, e, f.currentPassword).exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        f.newEmail = e
        f.code = ""
        f.cooldown = 60
        stage = AccountStage.EmailCode
        f.notice = "Код отправлен на $e. Если письма нет, проверьте «Спам»"
    }

    fun confirmEmailChange() = launchBusy {
        if (f.code.length < 6) {
            f.error = "Введите код из письма"
            return@launchBusy
        }
        val err = CloudAccount.confirmEmailChange(context, f.newEmail, f.code).exceptionOrNull()
        if (err != null) {
            f.error = CloudAccount.errorMessage(err)
            return@launchBusy
        }
        f.accountEmail = f.newEmail
        f.newEmail = ""
        f.code = ""
        f.currentPassword = ""
        stage = AccountStage.Member
        f.notice = "Почта изменена"
    }

    fun logout() {
        CloudAccount.signOut(context)
        f.error = null
        f.notice = null
        // Окно входа появится само (AccountGate). Если мы в обычном экране аккаунта, закрываем его.
        if (gate) scope.launch { load() } else onBackClick()
    }

    LaunchedEffect(Unit) {
        shown = true
        load()
    }
    // Таймер до повторной отправки кода
    LaunchedEffect(f.cooldown) {
        if (f.cooldown > 0) {
            delay(1000)
            f.cooldown -= 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(400)) + slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it / 10 }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!gate) {
                    TextButton(onClick = onBackClick) { Text("← Назад") }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Spacer(Modifier.height(32.dp))
                }

                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        val forward = targetState.order() >= initialState.order()
                        (fadeIn(tween(260, delayMillis = 60)) +
                            slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) it / 4 else -it / 4 }) togetherWith
                            (fadeOut(tween(140)) +
                                slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) -it / 4 else it / 4 })
                    },
                    label = "account_stage"
                ) { s ->
                    when (s) {
                        is AccountStage.Loading -> LoadingSection()
                        is AccountStage.Failed -> FailedSection(s.message) { scope.launch { load() } }
                        is AccountStage.Welcome -> WelcomeSection(
                            gate = gate,
                            f = f,
                            onSend = { sendCode() },
                            onLogin = { login() }
                        )
                        is AccountStage.Code -> CodeSection(
                            f = f,
                            onConfirm = { confirmCode() },
                            onResend = { resendCode() },
                            onChangeEmail = {
                                CloudAccount.clearPending(context)
                                f.code = ""
                                goTo(AccountStage.Welcome)
                            }
                        )
                        is AccountStage.Done -> DoneSection(s.note) {
                            if (gate) onFinished() else scope.launch { load() }
                        }
                        is AccountStage.Member -> MemberSection(
                            f = f,
                            autoSync = autoSync,
                            onAuto = { CloudAccount.setAutoSync(context, it) },
                            onSave = { saveNow() },
                            onRestore = { confirmRestore = true },
                            onChangePassword = {
                                f.currentPassword = ""
                                f.newPassword = ""
                                goTo(AccountStage.ChangePassword)
                            },
                            onChangeEmail = {
                                f.currentPassword = ""
                                f.newEmail = ""
                                goTo(AccountStage.ChangeEmail)
                            },
                            onLogout = { logout() }
                        )
                        is AccountStage.ChangePassword -> ChangePasswordSection(
                            f = f,
                            onSubmit = { submitPasswordChange() },
                            onBack = { goTo(AccountStage.Member) }
                        )
                        is AccountStage.ChangeEmail -> ChangeEmailSection(
                            f = f,
                            onSend = { sendEmailChange() },
                            onBack = { goTo(AccountStage.Member) }
                        )
                        is AccountStage.EmailCode -> EmailCodeSection(
                            f = f,
                            onConfirm = { confirmEmailChange() },
                            onResend = { sendEmailChange() },
                            onBack = { goTo(AccountStage.ChangeEmail) }
                        )
                    }
                }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Восстановить из облака?") },
            text = {
                Text("Настройки, плейлисты и профиль на этом телефоне будут заменены копией из облака. Приложение перезапустится.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    restoreNow()
                }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Отмена") } }
        )
    }
}

// ---------------------------------------------------------------- шаги

@Composable
private fun LoadingSection() {
    Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FailedSection(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Title("Не получилось загрузить")
        Message(message, true)
        BouncyButton("Повторить", false, onRetry)
    }
}

@Composable
private fun WelcomeSection(gate: Boolean, f: AccountForm, onSend: () -> Unit, onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Title(if (gate) "Добро пожаловать в AmorX" else "Аккаунт")
        Subtitle("Чтобы пользоваться плеером, создайте аккаунт или войдите. Профиль, плейлисты, избранное и скачанное сохранятся в облаке и вернутся на любом телефоне")
        PillTabs(
            selected = if (f.loginTab) 1 else 0,
            labels = listOf("Создать аккаунт", "Войти")
        ) {
            f.loginTab = it == 1
            f.error = null
        }
        AnimatedContent(
            targetState = f.loginTab,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "welcome_tab"
        ) { isLogin ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isLogin) {
                    Field(f.loginEmail, { f.loginEmail = it }, "Почта", !f.busy, KeyboardType.Email)
                    Field(
                        f.loginPassword, { f.loginPassword = it }, "Пароль", !f.busy,
                        KeyboardType.Password, password = true, imeAction = ImeAction.Done, onDone = onLogin
                    )
                    Subtitle("Войдите в аккаунт, который вы уже создали")
                    Message(f.error, true)
                    BouncyButton("Войти и восстановить данные", f.busy, onLogin)
                } else {
                    Field(f.email, { f.email = it }, "Почта", !f.busy, KeyboardType.Email)
                    Field(
                        f.password, { f.password = it }, "Пароль (минимум 6 символов)", !f.busy,
                        KeyboardType.Password, password = true, imeAction = ImeAction.Done, onDone = onSend
                    )
                    Subtitle("Мы отправим письмо с кодом. Аккаунт заработает только после ввода кода")
                    Message(f.error, true)
                    BouncyButton("Отправить код", f.busy, onSend)
                }
            }
        }
    }
}

@Composable
private fun CodeSection(f: AccountForm, onConfirm: () -> Unit, onResend: () -> Unit, onChangeEmail: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Title("Подтвердите почту")
        Subtitle("Введите код из письма, отправленного на ${f.email}")
        Field(
            f.code, { f.code = it.filter(Char::isDigit).take(10) }, "Код из письма", !f.busy,
            KeyboardType.Number, big = true, imeAction = ImeAction.Done, onDone = onConfirm
        )
        Message(f.notice, false)
        Message(f.error, true)
        BouncyButton("Подтвердить и войти", f.busy, onConfirm)
        TextButton(
            onClick = onResend,
            enabled = f.cooldown == 0 && !f.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (f.cooldown > 0) "Отправить ещё раз через ${f.cooldown} с" else "Отправить код ещё раз")
        }
        TextButton(onClick = onChangeEmail, enabled = !f.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Изменить почту или пароль")
        }
    }
}

@Composable
private fun DoneSection(note: String?, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        SuccessBadge()
        Title("Готово")
        if (!note.isNullOrBlank()) Subtitle(note)
        Spacer(Modifier.height(8.dp))
        BouncyButton("Продолжить", false, onContinue)
    }
}

@Composable
private fun MemberSection(
    f: AccountForm,
    autoSync: Boolean,
    onAuto: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onChangePassword: () -> Unit,
    onChangeEmail: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Title("Аккаунт")
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(f.accountEmail, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Профиль, плейлисты, избранное и список скачанного хранятся в облаке",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onAuto(!autoSync) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Автосохранение", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Копия отправляется в облако, когда вы закрываете приложение",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoSync, onCheckedChange = onAuto)
        }
        Message(f.notice, false)
        Message(f.error, true)
        BouncyButton("Сохранить в облако сейчас", f.busy, onSave)
        OutlinedButton(
            onClick = onRestore,
            enabled = !f.busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text("Восстановить из облака") }
        OutlinedButton(
            onClick = onChangePassword,
            enabled = !f.busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text("Сменить пароль") }
        OutlinedButton(
            onClick = onChangeEmail,
            enabled = !f.busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text("Сменить почту") }
        TextButton(onClick = onLogout, enabled = !f.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Выйти из аккаунта")
        }
    }
}

@Composable
private fun ChangePasswordSection(f: AccountForm, onSubmit: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Title("Сменить пароль")
        Subtitle("Для безопасности введите текущий пароль, затем новый")
        Field(
            f.currentPassword, { f.currentPassword = it }, "Текущий пароль", !f.busy,
            KeyboardType.Password, password = true
        )
        Field(
            f.newPassword, { f.newPassword = it }, "Новый пароль (минимум 6 символов)", !f.busy,
            KeyboardType.Password, password = true, imeAction = ImeAction.Done, onDone = onSubmit
        )
        Message(f.error, true)
        BouncyButton("Сохранить пароль", f.busy, onSubmit)
        TextButton(onClick = onBack, enabled = !f.busy, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

@Composable
private fun ChangeEmailSection(f: AccountForm, onSend: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Title("Сменить почту")
        Subtitle("Сейчас: ${f.accountEmail}. Мы отправим код на новую почту. Пока вы его не введёте, вход остаётся по старой")
        Field(f.newEmail, { f.newEmail = it }, "Новая почта", !f.busy, KeyboardType.Email)
        Field(
            f.currentPassword, { f.currentPassword = it }, "Текущий пароль", !f.busy,
            KeyboardType.Password, password = true, imeAction = ImeAction.Done, onDone = onSend
        )
        Message(f.error, true)
        BouncyButton("Отправить код", f.busy, onSend)
        TextButton(onClick = onBack, enabled = !f.busy, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

@Composable
private fun EmailCodeSection(f: AccountForm, onConfirm: () -> Unit, onResend: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Title("Подтвердите новую почту")
        Subtitle("Введите код из письма, отправленного на ${f.newEmail}")
        Field(
            f.code, { f.code = it.filter(Char::isDigit).take(10) }, "Код из письма", !f.busy,
            KeyboardType.Number, big = true, imeAction = ImeAction.Done, onDone = onConfirm
        )
        Message(f.notice, false)
        Message(f.error, true)
        BouncyButton("Подтвердить", f.busy, onConfirm)
        TextButton(
            onClick = onResend,
            enabled = f.cooldown == 0 && !f.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (f.cooldown > 0) "Отправить ещё раз через ${f.cooldown} с" else "Отправить код ещё раз")
        }
        TextButton(onClick = onBack, enabled = !f.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Изменить адрес")
        }
    }
}

// ---------------------------------------------------------------- мелкие элементы

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Subtitle(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Текст ошибки или подсказки: плавно разворачивается и сворачивается. */
@Composable
private fun Message(text: String?, isError: Boolean) {
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn(tween(200)) + expandVertically(tween(250)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(200))
    ) {
        Text(
            text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboard: KeyboardType,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    big: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(16.dp),
        textStyle = if (big) {
            MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = 6.sp)
        } else {
            LocalTextStyle.current
        },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Кнопка: чуть сжимается при нажатии, во время запроса вместо текста крутится индикатор. */
@Composable
private fun BouncyButton(text: String, loading: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "button_press"
    )
    Button(
        onClick = { if (!loading) onClick() },
        interactionSource = source,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(54.dp).scale(scale)
    ) {
        AnimatedContent(targetState = loading, label = "button_content") { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = LocalContentColor.current
                )
            } else {
                Text(text)
            }
        }
    }
}

/** Переключатель «Создать аккаунт / Войти»: плашка плавно переезжает с пружиной. */
@Composable
private fun PillTabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        val tabWidth = maxWidth / labels.size
        val tabOffset by animateDpAsState(
            targetValue = tabWidth * selected,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "tab_offset"
        )
        Box(
            modifier = Modifier
                .offset(x = tabOffset)
                .width(tabWidth)
                .height(44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val textColor by animateColorAsState(
                    targetValue = if (i == selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "tab_text"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = textColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Галочка «Готово»: выпрыгивает с отскоком. */
@Composable
private fun SuccessBadge() {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .scale(scale.value)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "✓",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
