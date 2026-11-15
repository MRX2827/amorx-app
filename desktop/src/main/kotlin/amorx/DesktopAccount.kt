package amorx

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class AccountStep { Login, Register, Verify }

@Composable
internal fun AccountDialog(onDismiss: () -> Unit) {
    var email by remember { mutableStateOf(CloudProfiles.activeAccount.value?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(AccountStep.Login) }
    val scope = rememberCoroutineScope()
    val connected by CloudProfiles.activeAccount

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (connected != null) "Аккаунт AmorX" else when (step) {
            AccountStep.Login -> "Вход в AmorX"
            AccountStep.Register -> "Создать аккаунт"
            AccountStep.Verify -> "Подтвердить почту"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                connected?.let { Text(it.email) }
                if (connected == null) {
                    OutlinedTextField(email, { email = it }, label = { Text("Почта") },
                        enabled = step != AccountStep.Verify && !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (step == AccountStep.Verify) {
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) },
                            label = { Text("Код из письма") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        OutlinedTextField(password, { password = it }, label = { Text("Пароль") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = {
                            step = if (step == AccountStep.Login) AccountStep.Register else AccountStep.Login
                            status = ""
                        }) { Text(if (step == AccountStep.Login) "Создать аккаунт" else "Уже есть аккаунт") }
                    }
                }
                if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (connected != null) TextButton(onClick = onDismiss) { Text("Готово") }
            else Button(enabled = !busy && email.isNotBlank() &&
                (if (step == AccountStep.Verify) code.length >= 6 else password.isNotEmpty()), onClick = {
                busy = true
                status = ""
                scope.launch {
                    CloudProfiles.emailAuth(step.name, email, password, code)
                        .onSuccess { done -> if (done) onDismiss() else step = AccountStep.Verify }
                        .onFailure { status = it.message ?: "Не удалось выполнить вход" }
                    busy = false
                }
            }) { Text(if (busy) "Подождите…" else when (step) {
                AccountStep.Login -> "Войти"
                AccountStep.Register -> "Зарегистрироваться"
                AccountStep.Verify -> "Подтвердить"
            }) }
        },
        dismissButton = {
            if (connected == null) TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
            else TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    CloudProfiles.signOut().onSuccess { onDismiss() }
                        .onFailure { status = it.message ?: "Не удалось выйти из аккаунта" }
                    busy = false
                }
            }) { Text("Выйти") }
        }
    )
}
