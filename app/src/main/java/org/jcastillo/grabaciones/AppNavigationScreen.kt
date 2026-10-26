/*
 * Modified fork of ShizuCallRecorder. Reused capture code retains its original GPL notices.
 * Copyright (C) 2026 Grabaciones Locales contributors; GPL-3.0-or-later.
 */
package org.jcastillo.grabaciones

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jcastillo.grabaciones.data.AppPreferences
import org.jcastillo.grabaciones.data.recording.LocalRecordingRepository
import org.jcastillo.grabaciones.data.recording.RecordingSource
import org.jcastillo.grabaciones.integrations.adb.EmbeddedAdbManager
import org.jcastillo.grabaciones.onboarding.OnboardingStatus
import org.jcastillo.grabaciones.services.callDetection.CallDetectionMode
import org.jcastillo.grabaciones.system.permissions.PermissionChecks
import org.jcastillo.grabaciones.ui.screens.DisclaimerScreen
import org.jcastillo.grabaciones.ui.screens.MegaBackupSettingsSection
import org.jcastillo.grabaciones.ui.screens.PermissionsScreen
import org.jcastillo.grabaciones.ui.theme.GrabacionesLocalesTheme
import org.jcastillo.grabaciones.ui.viewmodels.AppNavigationViewModel
import org.jcastillo.grabaciones.utils.AppLogger
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppNavigationScreen() {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val navigationViewModel: AppNavigationViewModel = viewModel()
    val setupStatus by navigationViewModel.onboardingStatus.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { navigationViewModel.refresh() }
    val screenDark = androidx.compose.foundation.isSystemInDarkTheme()

    GrabacionesLocalesTheme(darkTheme = screenDark, dynamicColor = true) {
        when {
            !setupStatus.disclaimerAccepted -> DisclaimerScreen(onContinue = {
                preferences.setDisclaimerAccepted(true)
                navigationViewModel.refresh()
            })
            !setupStatus.isComplete() -> PermissionsScreen(
                status = setupStatus,
                onPermissionGranted = navigationViewModel::refresh,
            )
            showSettings -> {
                BackHandler { showSettings = false }
                SettingsScreen(preferences, onBack = { showSettings = false })
            }
            else -> RecordingHistoryScreen(onSettings = { showSettings = true }, preferences = preferences)
        }
    }
}

private data class Readiness(val ready: Boolean, val message: String)

private suspend fun checkReadiness(context: android.content.Context, preferences: AppPreferences): Readiness {
    if (!preferences.isAdbPaired()) return Readiness(false, "Empareja la depuración inalámbrica de Android desde el asistente. No se iniciarán grabaciones sin el servicio shell.")
    val shellService = runCatching { EmbeddedAdbManager.getShellService(context) }
        .getOrElse { return Readiness(false, "Servicio ADB integrado no disponible: ${it.localizedMessage ?: "comprueba la depuración inalámbrica y vuelve a emparejar"}") }
    if (!runCatching { shellService.canCaptureAudioOutput() }.getOrDefault(false)) {
        return Readiness(false, "Android no concedió CAPTURE_AUDIO_OUTPUT al proceso shell. No se usará el micrófono como sustituto.")
    }
    val mode = preferences.getCallDetectionMode()
    if (mode.requiredPermissions.any { !it.isGranted(context) }) {
        return Readiness(false, "Falta un permiso de detección de llamadas (${mode.name}). Revisa Ajustes del sistema.")
    }
    if (!PermissionChecks.hasNotificationPermission(context)) {
        return Readiness(false, "Las notificaciones están desactivadas; habilítalas para ver errores y el estado de grabación.")
    }
    val incomingEnabled = preferences.isAutoRecordIncomingEnabled()
    val outgoingEnabled = preferences.isAutoRecordOutgoingEnabled()
    if (!incomingEnabled && !outgoingEnabled) {
        return Readiness(false, "El servicio está conectado, pero la grabación automática está desactivada. Actívala en Ajustes para grabar llamadas.")
    }
    val disabledDirections = buildList {
        if (!incomingEnabled) add("entrantes")
        if (!outgoingEnabled) add("salientes")
    }
    if (disabledDirections.isNotEmpty()) {
        return Readiness(true, "Servicio ADB listo. No se grabarán llamadas ${disabledDirections.joinToString(" ni ")}. El audio remoto depende del firmware; verifica una llamada de prueba.")
    }
    return Readiness(true, "Servicio ADB listo y grabación automática activada. Se intentará capturar ambas voces; el audio remoto depende del firmware y debe probarse en este teléfono.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingHistoryScreen(onSettings: () -> Unit, preferences: AppPreferences) {
    val context = LocalContext.current
    val repository = remember { LocalRecordingRepository(context) }
    var records by remember { mutableStateOf(emptyList<LocalRecordingRepository.Recording>()) }
    var refreshCount by remember { mutableStateOf(0) }
    var readiness by remember { mutableStateOf(Readiness(false, "Comprobando el servicio ADB integrado…")) }
    var deleteTarget by remember { mutableStateOf<LocalRecordingRepository.Recording?>(null) }
    var playbackUri by remember { mutableStateOf<Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isPreparing by remember { mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()

    val player = remember { MediaPlayer() }
    DisposableEffect(player) {
        player.setOnCompletionListener { playbackUri = null; isPlaying = false; isPreparing = false }
        player.setOnErrorListener { _, what, extra ->
            AppLogger.e("MediaPlayer failed to play local recording: what=$what extra=$extra")
            playbackUri = null
            isPlaying = false
            isPreparing = false
            Toast.makeText(context, "No se pudo reproducir este MP3.", Toast.LENGTH_LONG).show()
            true
        }
        onDispose { runCatching { player.reset() }; player.release() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshCount++ }
    LaunchedEffect(refreshCount) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { repository.listRecordings() }
                .onFailure { AppLogger.e("Could not refresh local recording history", it) }
        }
        loaded.getOrNull()?.let { records = it }
    }
    LaunchedEffect(Unit) {
        while (true) {
            readiness = withContext(Dispatchers.IO) { checkReadiness(context, preferences) }
            delay(5_000)
            // A call may finish while this screen is still alive; refresh history without requiring
            // the user to leave and re-enter the app.
            refreshCount++
        }
    }

    fun togglePlayback(uri: Uri) {
        try {
            if (playbackUri == uri && isPreparing) {
                player.reset(); playbackUri = null; isPreparing = false
                return
            }
            if (playbackUri == uri && isPlaying) {
                player.pause(); isPlaying = false
                return
            }
            if (playbackUri == uri && !isPlaying) {
                player.start(); isPlaying = true
                return
            }
            player.reset()
            playbackUri = uri
            isPreparing = true
            isPlaying = false
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(context, uri)
            player.setOnPreparedListener { prepared ->
                prepared.start(); playbackUri = uri; isPlaying = true; isPreparing = false
            }
            player.prepareAsync()
        } catch (error: Exception) {
            AppLogger.e("Could not prepare local recording playback for $uri", error)
            playbackUri = null; isPlaying = false; isPreparing = false
            runCatching { player.reset() }
            Toast.makeText(context, "No se pudo abrir este MP3.", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grabaciones") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Ajustes") } },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            ReadinessCard(readiness)
            Spacer(Modifier.height(12.dp))
            if (records.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Todavía no hay grabaciones", style = MaterialTheme.typography.titleMedium)
                    Text("Los MP3 aparecerán aquí al finalizar una llamada grabada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    items(records, key = { it.uri.toString() }) { record ->
                        RecordingRow(
                            record = record,
                            playbackUri = playbackUri,
                            isPlaying = isPlaying,
                            isPreparing = isPreparing,
                            onPlay = { togglePlayback(record.uri) },
                            onShare = {
                                runCatching {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/mpeg"
                                        putExtra(Intent.EXTRA_STREAM, record.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Compartir grabación"))
                                }.onFailure {
                                    AppLogger.e("Could not share recording ${record.uri}", it)
                                    Toast.makeText(context, "No se pudo compartir la grabación.", Toast.LENGTH_LONG).show()
                                }
                            },
                            onDelete = { deleteTarget = record },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminar grabación") },
            text = { Text("¿Quieres eliminar ${target.displayName}?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    if (playbackUri == target.uri) {
                        runCatching { player.reset() }
                        playbackUri = null
                        isPlaying = false
                        isPreparing = false
                    }
                    screenScope.launch(Dispatchers.IO) {
                        val deleted = runCatching { repository.delete(target.uri) }
                            .onFailure { AppLogger.e("Could not delete local recording ${target.uri}", it) }
                            .getOrDefault(false)
                        withContext(Dispatchers.Main) {
                            if (!deleted) Toast.makeText(context, "No se pudo eliminar la grabación.", Toast.LENGTH_LONG).show()
                            refreshCount++
                        }
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ReadinessCard(readiness: Readiness) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (readiness.ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (readiness.ready) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (readiness.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column {
                Text(if (readiness.ready) "Operativa" else "No está operativa", fontWeight = FontWeight.SemiBold)
                Text(readiness.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecordingRow(
    record: LocalRecordingRepository.Recording,
    playbackUri: Uri?,
    isPlaying: Boolean,
    isPreparing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val match = Regex("^\\d{8}_\\d{6}_\\d{3}_(.+)\\.mp3$").matchEntire(record.displayName)
    val number = match?.groupValues?.get(1) ?: record.displayName.removeSuffix(".mp3")
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(record.dateAddedSeconds * 1000))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp)) {
            Text(number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatDuration(record.durationMs)}  ·  ${formatSize(record.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlay) {
                    Icon(
                        if (playbackUri == record.uri && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackUri == record.uri && isPreparing) "Preparando" else "Reproducir",
                    )
                }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Compartir") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Eliminar") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(preferences: AppPreferences, onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var autoRecordIncoming by remember { mutableStateOf(preferences.isAutoRecordIncomingEnabled()) }
    var autoRecordOutgoing by remember { mutableStateOf(preferences.isAutoRecordOutgoingEnabled()) }
    var source by remember { mutableStateOf(preferences.getAudioSource()) }
    var detailedLogging by remember { mutableStateOf(preferences.isLoggingEnabled()) }
    var offlineRecording by remember { mutableStateOf(preferences.isOfflineRecordingEnabled()) }
    var offlineBusy by remember { mutableStateOf(false) }
    var licenseTitle by remember { mutableStateOf("") }
    var licenseText by remember { mutableStateOf<String?>(null) }
    val settingsScope = rememberCoroutineScope()
    val exportReportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) settingsScope.launch(Dispatchers.IO) {
            val result = runCatching { AppLogger.exportReport(context, uri) }
                .onFailure { AppLogger.e("Could not export diagnostic report", it) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (result.isSuccess) "Informe guardado. Revisa su contenido antes de compartirlo."
                    else "No se pudo guardar el informe de diagnóstico.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun openLicense(title: String, assetName: String) {
        licenseTitle = title
        licenseText = runCatching {
            context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { "No se pudo abrir el archivo de licencia incluido." }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Ajustes") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(),
        )
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Grabación automática", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Llamadas entrantes", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = autoRecordIncoming, onCheckedChange = { enabled ->
                            autoRecordIncoming = enabled
                            preferences.setAutoRecordIncomingEnabled(enabled)
                        })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Llamadas salientes", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = autoRecordOutgoing, onCheckedChange = { enabled ->
                            autoRecordOutgoing = enabled
                            preferences.setAutoRecordOutgoingEnabled(enabled)
                        })
                    }
                    Text("Desactivada por defecto; activa cada dirección que quieras grabar antes de hacer una llamada de prueba.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            MegaBackupSettingsSection()

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Grabación sin Wi-Fi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Arma una vez un transporte ADB clásico autenticado por RSA. Android puede publicarlo en todas las interfaces de red, no solo en 127.0.0.1; actívalo únicamente si aceptas esa exposición. Después de inicializarlo con Wi-Fi, las llamadas pueden grabarse con datos móviles o sin red.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Permitir grabación fuera de Wi-Fi", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = offlineRecording,
                            enabled = !offlineBusy,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    offlineBusy = true
                                    settingsScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { EmbeddedAdbManager.disableOfflineRecording(context) }
                                        }
                                        offlineBusy = false
                                        if (result.isSuccess) {
                                            offlineRecording = false
                                        } else {
                                            Toast.makeText(context, "No se puede cambiar durante una grabación: ${result.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    offlineBusy = true
                                    settingsScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { EmbeddedAdbManager.enableOfflineRecording(context) }
                                        }
                                        offlineBusy = false
                                        if (result.isSuccess) {
                                            offlineRecording = true
                                            Toast.makeText(context, "Transporte sin Wi-Fi preparado.", Toast.LENGTH_LONG).show()
                                        } else {
                                            offlineRecording = false
                                            Toast.makeText(context, "No se pudo preparar: ${result.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                        )
                    }
                    Text(
                        "Abre un puerto tcpip protegido por la clave ADB que puede quedar accesible desde la red local. Desactívala cuando no la necesites; el cierre es best-effort. Tras reiniciar, el modo tcpip se pierde y debe rearmarse con Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text("Captura de audio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Siempre MP3 mono, 64 kbps. No se recurre al micrófono si falla una de las dos rutas.", style = MaterialTheme.typography.bodySmall)
            SourceOption(
                title = "Enlace ascendente + descendente (recomendado)",
                description = "Intenta capturar ambas voces por separado y mezclarlas en mono.",
                selected = source == RecordingSource.UPLINK_DOWNLINK,
                onClick = { source = RecordingSource.UPLINK_DOWNLINK; preferences.setAudioSource(source) },
            )
            SourceOption(
                title = "VOICE_CALL del sistema",
                description = "Depende del firmware; en algunos móviles omite el audio remoto. Verifica una llamada de prueba.",
                selected = source == RecordingSource.VOICE_CALL,
                onClick = { source = RecordingSource.VOICE_CALL; preferences.setAudioSource(source) },
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Carpeta de MP3", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Music/${LocalRecordingRepository.MUSIC_FOLDER_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Text("La carpeta aparece al guardar el primer MP3. Los archivos se publican al finalizar y verificar cada llamada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { openRecordingsFolder(context) }) { Text("Abrir carpeta") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Diagnóstico", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Los avisos y errores se guardan en privado. Puedes habilitar eventos detallados y exportar un informe para revisar el motivo de una falla.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Guardar eventos detallados", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = detailedLogging, onCheckedChange = { enabled ->
                            detailedLogging = enabled
                            preferences.setLoggingEnabled(enabled)
                        })
                    }
                    TextButton(onClick = { exportReportLauncher.launch("grabaciones-locales-diagnostico.txt") }) {
                        Text("Exportar informe")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Requisitos de funcionamiento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Empareja una vez la depuración inalámbrica de Android desde el asistente. La app puede concederse WRITE_SECURE_SETTINGS por ADB y tratar de reactivar Depuración inalámbrica tras un reinicio; el sistema o el fabricante pueden impedirlo. Mientras está activo, ADB es una interfaz potente: puedes desactivarla o revocar este dispositivo en Opciones de desarrollador para detener el servicio. Excluye la app de la optimización de batería para reducir cierres en segundo plano.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        }
                    }) { Text("Ajustes de batería") }
                }
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FORK MODIFICADO · GPL-3.0", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("Esta aplicación es un fork de ShizuCallRecorder y adapta la arquitectura ADB de CallVault. Código original © kitsumed (Med); ADB integrado basado en CallVault © CallVault Authors; modificaciones © Grabaciones Locales.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/kitsumed/ShizuCallRecorder") } }) {
                        Text("Repositorio original")
                    }
                    Text("La integración ADB está adaptada de CallVault, bajo GPL-3.0-or-later; mDNS se basa en código Apache-2.0 de Shizuku.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/madkongo/CallVault") } }) {
                        Text("Repositorio de CallVault")
                    }
                    TextButton(onClick = { openLicense("Avisos de terceros", "THIRD_PARTY_NOTICES.txt") }) {
                        Text("Leer avisos de terceros")
                    }
                    TextButton(onClick = { openLicense("Apache-2.0 (ADB y mDNS)", "Apache-2.0.txt") }) {
                        Text("Leer Apache-2.0")
                    }
                    TextButton(onClick = { openLicense("LGPL-3.0 (Spake2)", "Spake2-LGPL-3.0.txt") }) {
                        Text("Leer LGPL-3.0 de Spake2")
                    }
                    TextButton(onClick = { openLicense("MIT (Bouncy Castle)", "BouncyCastle-MIT.txt") }) {
                        Text("Leer licencia de Bouncy Castle")
                    }
                    TextButton(onClick = { openLicense("Apache-2.0 (Conscrypt)", "Conscrypt-Apache-2.0.txt") }) {
                        Text("Leer licencia de Conscrypt")
                    }
                    TextButton(onClick = { openLicense("CC0-1.0 (EdDSA)", "EdDSA-CC0-1.0.txt") }) {
                        Text("Leer CC0-1.0 de EdDSA")
                    }
                    Text("Se puede redistribuir y modificar bajo GPL-3.0-or-later y los términos adicionales incluidos. Se ofrece sin garantía.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openLicense("Licencia GPL-3.0 y términos adicionales", "LICENSE.txt") }) {
                        Text("Leer licencia GPL-3.0")
                    }
                    Text("LAME 3.100 se incluye como biblioteca compartida bajo LGPL-2.0-or-later.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openLicense("Licencia de LAME (LGPL-2.0)", "LAME-LGPL-2.0.txt") }) {
                        Text("Leer licencia de LAME")
                    }
                    Text("No hay cuenta propia de la app ni telemetría. La red se usa para ADB local y, solo si conectas MEGA y activas el respaldo, para subir MP3 a tu cuenta. No se generan enlaces públicos.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Las llamadas en espera, conferencias y cambios entre llamadas simultáneas pueden no separarse correctamente. Comprueba la grabación en cada dispositivo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    licenseText?.let { text ->
        AlertDialog(
            onDismissRequest = { licenseText = null },
            title = { Text(licenseTitle) },
            text = {
                Text(
                    text = text,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { licenseText = null }) { Text("Cerrar") } },
        )
    }
}

private fun openRecordingsFolder(context: android.content.Context) {
    val documentId = "primary:${Environment.DIRECTORY_MUSIC}/${LocalRecordingRepository.MUSIC_FOLDER_NAME}"
    val documentUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
    val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(documentUri, "vnd.android.document/directory")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (openIntent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(openIntent) }
            .onSuccess { return }
            .onFailure { AppLogger.w("File manager could not open the recordings directory directly", it) }
    }

    // Some OEM file managers do not support ACTION_VIEW for directory document URIs. Their
    // document picker can still open at the target folder so the user can inspect its contents.
    val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", documentId)
    val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    runCatching { context.startActivity(pickerIntent) }
        .onSuccess { return }
        .onFailure { AppLogger.w("No file manager could open the recordings directory", it) }
    Toast.makeText(context, "Abre Archivos > Música > ${LocalRecordingRepository.MUSIC_FOLDER_NAME}.", Toast.LENGTH_LONG).show()
}

@Composable
private fun SourceOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "--:--"
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
