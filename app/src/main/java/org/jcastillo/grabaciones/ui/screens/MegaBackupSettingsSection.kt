package org.jcastillo.grabaciones.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mega.sdk.MegaError
import org.jcastillo.grabaciones.integrations.mega.MegaApiCoordinator
import org.jcastillo.grabaciones.integrations.mega.MegaBackupDatabase
import org.jcastillo.grabaciones.integrations.mega.MegaBackupPreferences
import org.jcastillo.grabaciones.integrations.mega.MegaErrorMessages
import org.jcastillo.grabaciones.integrations.mega.MegaBackupRecord
import org.jcastillo.grabaciones.integrations.mega.MegaBackupScheduler
import org.jcastillo.grabaciones.integrations.mega.MegaBackupStatus
import org.jcastillo.grabaciones.integrations.mega.MegaOperation
import org.jcastillo.grabaciones.integrations.mega.MegaSdkException
import org.jcastillo.grabaciones.integrations.mega.MegaSessionVault

@Composable
fun MegaBackupSettingsSection() {
    val context = LocalContext.current
    val preferences = remember { MegaBackupPreferences(context) }
    val database = remember { MegaBackupDatabase(context) }
    val sessionVault = remember { MegaSessionVault(context) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(preferences.isEnabled()) }
    var wifiOnly by remember { mutableStateOf(preferences.isWifiOnly()) }
    var accountEmail by remember { mutableStateOf<String?>(null) }
    var sessionChecked by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf(TextFieldValue("")) }
    var passwordVisible by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var requiresPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf(emptyList<MegaBackupRecord>()) }
    var pendingCount by remember { mutableStateOf(0) }
    var refreshCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        accountEmail = withContext(Dispatchers.IO) {
            if (sessionVault.hasSession()) sessionVault.accountEmail() else null
        }
        sessionChecked = true
    }
    LaunchedEffect(refreshCount) {
        val queueSnapshot = withContext(Dispatchers.IO) {
            runCatching {
                Triple(
                    database.recent(),
                    database.pendingCount(),
                    if (sessionVault.hasSession()) sessionVault.accountEmail() else null,
                )
            }.getOrDefault(Triple(emptyList<MegaBackupRecord>(), 0, null))
        }
        records = queueSnapshot.first
        pendingCount = queueSnapshot.second
        if (sessionChecked) accountEmail = queueSnapshot.third
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000)
            refreshCount++
        }
    }

    fun setBackupEnabled(value: Boolean) {
        enabled = value
        preferences.setEnabled(value)
        if (value) {
            scope.launch(Dispatchers.IO) { MegaBackupScheduler.schedulePending(context, appendAfterExisting = true) }
        } else {
            WorkManager.getInstance(context).cancelAllWorkByTag(MegaBackupScheduler.WORK_TAG)
            scope.launch(Dispatchers.IO) { database.resetInterrupted(); refreshCount++ }
        }
    }

    fun signIn() {
        if (busy) return
        val email = emailInput.trim()
        val password = passwordInput.text
        val pin = pinInput.takeIf { requiresPin }
        if (email.isBlank() || password.isBlank() || (requiresPin && pin.isNullOrBlank())) {
            errorText = if (requiresPin) "Escribe el código de verificación de tu cuenta." else "Escribe tu correo y contraseña de MEGA."
            return
        }
        busy = true
        errorText = null
        scope.launch {
            try {
                val signedInEmail = withContext(Dispatchers.IO) {
                    MegaApiCoordinator.signIn(context, email, password, pin)
                }
                accountEmail = signedInEmail
                sessionChecked = true
                passwordInput = TextFieldValue("")
                passwordVisible = false
                pinInput = ""
                requiresPin = false
                if (enabled) withContext(Dispatchers.IO) { MegaBackupScheduler.schedulePending(context) }
                refreshCount++
            } catch (error: Exception) {
                if (error is MegaSdkException &&
                    error.operation == MegaOperation.LOGIN &&
                    error.errorCode == MegaError.API_EMFAREQUIRED && !requiresPin
                ) {
                    requiresPin = true
                    errorText = MegaErrorMessages.login(
                        error,
                        twoFactorAttempt = false,
                        sensitiveValues = listOf(password),
                    )
                } else {
                    val secrets = listOfNotNull(password, pin)
                    errorText = if (error is MegaSdkException) {
                        MegaErrorMessages.login(error, twoFactorAttempt = requiresPin, sensitiveValues = secrets)
                    } else {
                        MegaErrorMessages.unexpectedLogin(error, secrets)
                    }
                    pinInput = ""
                }
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        if (busy) return
        busy = true
        WorkManager.getInstance(context).cancelAllWorkByTag(MegaBackupScheduler.WORK_TAG)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.resetInterrupted()
                    MegaApiCoordinator.logout(context)
                }
                accountEmail = null
                passwordInput = TextFieldValue("")
                passwordVisible = false
                pinInput = ""
                requiresPin = false
                errorText = null
                refreshCount++
            } catch (_: Exception) {
                errorText = "No se pudo cerrar la sesión local. Inténtalo de nuevo."
            } finally {
                busy = false
            }
        }
    }

    val lastSuccessful = records.firstOrNull { it.status == MegaBackupStatus.BACKED_UP }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Respaldo MEGA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Sube únicamente las nuevas grabaciones. Los MP3 originales siempre permanecen en el teléfono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Destino: /GrabacionesLocales/${preferences.deviceFolderName()}/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                accountEmail != null -> {
                    Text("Cuenta conectada: ${accountEmail}", style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !busy, onClick = ::signOut) { Text("Cerrar sesión") }
                }
                !sessionChecked -> Text("Comprobando sesión…", style = MaterialTheme.typography.bodySmall)
                else -> {
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Correo de MEGA") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !busy,
                    )
                    MegaPasswordField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        visible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (requiresPin) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it.filter(Char::isDigit).take(8) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Código de verificación (2FA)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            enabled = !busy,
                        )
                    }
                    Button(enabled = !busy, onClick = ::signIn) {
                        Text(if (busy) "Conectando…" else if (requiresPin) "Verificar código" else "Conectar cuenta")
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Respaldo automático", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled) "Activado" else "Desactivado por defecto",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = ::setBackupEnabled)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solo Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (wifiOnly) "No se usa la red móvil" else "También se permiten datos móviles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = { value ->
                        wifiOnly = value
                        preferences.setWifiOnly(value)
                        if (enabled) {
                            WorkManager.getInstance(context).cancelAllWorkByTag(MegaBackupScheduler.WORK_TAG)
                            scope.launch(Dispatchers.IO) {
                                database.resetInterrupted()
                                MegaBackupScheduler.schedulePending(context, appendAfterExisting = true)
                            }
                        }
                    },
                )
            }

            Text(
                when {
                    !enabled -> "Respaldo desactivado. Las nuevas grabaciones seguirán quedando pendientes."
                    accountEmail == null -> "Conecta una cuenta MEGA para empezar. No se subirá nada sin cuenta y respaldo activado."
                    pendingCount > 0 -> "$pendingCount grabación(es) pendiente(s) de respaldo."
                    else -> "Las nuevas grabaciones se respaldarán al terminar la llamada."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lastSuccessful?.let { record ->
                Text(
                    "Última subida confirmada: ${record.fileName} · ${formatDate(record.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            records.take(4).forEach { record ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${record.fileName} · ${statusLabel(record.status)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    record.lastError?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (pendingCount > 0) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            MegaBackupScheduler.schedulePending(context, appendAfterExisting = true)
                            refreshCount++
                        }
                    },
                ) { Text("Reintentar subidas pendientes") }
            }
            if (errorText != null) {
                Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun MegaPasswordField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text("Contraseña") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(enabled = enabled, onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                )
            }
        },
        enabled = enabled,
    )
}

private fun statusLabel(status: MegaBackupStatus): String = when (status) {
    MegaBackupStatus.PENDING -> "Pendiente"
    MegaBackupStatus.UPLOADING -> "Subiendo"
    MegaBackupStatus.BACKED_UP -> "Respaldada"
}

private fun formatDate(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))
