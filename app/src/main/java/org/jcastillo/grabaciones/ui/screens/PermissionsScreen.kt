/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.jcastillo.grabaciones.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jcastillo.grabaciones.R
import org.jcastillo.grabaciones.data.AppPreferences
import org.jcastillo.grabaciones.onboarding.OnboardingStatus
import org.jcastillo.grabaciones.integrations.adb.AdbPairingResult
import org.jcastillo.grabaciones.integrations.adb.AdbPairingSession
import org.jcastillo.grabaciones.integrations.adb.AdbPairingUiState
import org.jcastillo.grabaciones.services.callDetection.CallDetectionMode
import org.jcastillo.grabaciones.system.openAppSettings
import org.jcastillo.grabaciones.system.openWirelessDebuggingSettings
import org.jcastillo.grabaciones.system.permissions.PermissionChecks
import org.jcastillo.grabaciones.system.openGithubReportIssue
import org.jcastillo.grabaciones.ui.common.M3DropdownField
import org.jcastillo.grabaciones.ui.common.OptionItem
import org.jcastillo.grabaciones.ui.common.ToggleListItem
import org.jcastillo.grabaciones.ui.theme.GrabacionesLocalesTheme
import org.jcastillo.grabaciones.ui.viewmodels.PermissionsViewModel
import org.jcastillo.grabaciones.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Stateful wrapper that connects [PermissionsViewModel] to [PermissionsContent].
 *
 * It owns the Android-specific launchers ([rememberLauncherForActivityResult]) that must live
 * inside a composable and passes them into [PermissionsViewModel.onGrantAccess] as lambdas so
 * the ViewModel stays free of Compose and Activity references.
 *
 * @param status              The current [OnboardingStatus.Status] snapshot, observed by the
 *                            router in [AppNavigationScreen] via [collectAsState].
 * @param onPermissionGranted Called after any grant action completes so the router can refresh state.
 * @param modifier            Optional size/position modifier forwarded to [PermissionsContent].
 * @param viewModel           The "Brain" that decides which permission to request next.
 */
@Composable
fun PermissionsScreen(
    status: OnboardingStatus.Status,
    onPermissionGranted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = viewModel()
) {

    val activityContext = LocalContext.current

    // Quick debug dialog for when issues arise on the permission setup.
    var showDebugDialog by remember() { mutableStateOf(false) }
    val activityScope = rememberCoroutineScope()

    val isProcessingGrantingRequest by viewModel.isProcessingGrantingRequest.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val adbPairingState by viewModel.adbPairingState.collectAsStateWithLifecycle()
    LaunchedEffect(adbPairingState.sessionId, adbPairingState.result) {
        if (adbPairingState.result == AdbPairingResult.SUCCESS || adbPairingState.result == AdbPairingResult.FAILURE) {
            onPermissionGranted()
        }
    }

    val openNotificationSettings = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activityContext.packageName)
        runCatching { activityContext.startActivity(intent) }
            .recoverCatching { activityContext.openAppSettings() }
        Unit
    }
    val launchPairing = {
        viewModel.beginAdbPairing { activityContext.openWirelessDebuggingSettings() }
    }
    val pairingNotificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionGranted()
        if (granted && PermissionChecks.hasNotificationPermission(activityContext)) {
            launchPairing()
        } else if (!granted) {
            AdbPairingSession.showError("El emparejamiento requiere notificaciones. Concédelas para poder responder al código desde la notificación.")
        } else {
            AdbPairingSession.showError("Activa las notificaciones de Grabaciones Locales para continuar con el emparejamiento.")
            openNotificationSettings()
        }
    }
    val beginPairing = {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(activityContext, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                pairingNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !PermissionChecks.hasNotificationPermission(activityContext) -> {
                AdbPairingSession.showError("Activa las notificaciones de Grabaciones Locales para continuar con el emparejamiento.")
                openNotificationSettings()
            }
            else -> launchPairing()
        }
    }
    // Permission launchers must live inside a composable - the system dialog can only be
    // triggered from a composable context.  We pass these into the ViewModel as lambdas so
    // the ViewModel never needs to import Compose or hold a UI reference.
    val permissionRequestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        // Notification access is mandatory for RemoteInput. Do not start pairing or open Settings
        // automatically after denial; keep the explanation in the onboarding card instead.
        if (!result && ContextCompat.checkSelfPermission(activityContext, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AdbPairingSession.showError("El emparejamiento ADB necesita permiso de notificaciones para recibir el código desde la notificación.")
        } else if (!result) {
            activityContext.openAppSettings()
        }
        onPermissionGranted()
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // takePersistableUriPermission locks in long-term read/write access so the
            // folder URI remains valid after a device reboot.
            activityContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            AppPreferences(activityContext).setRecordingFolderUri(uri)
        }
        onPermissionGranted()
    }

    // Shell capture capability is checked on the main screen and shown as an explicit readiness warning.

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dissmissError() },
            title = { Text(text = stringResource(R.string.general_system_limitation)) },
            text = { Text(text = errorMessage.toString()) },
            confirmButton = {
                TextButton(onClick = { viewModel.dissmissError() }) {
                    Text(text = stringResource(R.string.general_close))
                }
            },
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = stringResource(R.string.general_system_limitation)) }
        )
    }

    if (showDebugDialog) {
        val prefs = remember { AppPreferences(activityContext) }
        val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) {
                activityScope.launch(Dispatchers.IO) {
                    AppLogger.exportReport(activityContext, uri)
                }
            }
        }
        var isLoggingEnabled by remember { mutableStateOf(prefs.isLoggingEnabled()) }

        Dialog(
            onDismissRequest = { showDebugDialog = false }
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_section_debug),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )

                    ToggleListItem(
                        label = stringResource(R.string.settings_debug_logging_enabled),
                        description = stringResource(R.string.settings_debug_logging_enabled_description),
                        checked = isLoggingEnabled,
                        onCheckedChange = { checked ->
                            prefs.setLoggingEnabled(checked)
                            isLoggingEnabled = checked
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { exportLogLauncher.launch("shizucallrecorder_debug_report.txt") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_debug_logging_generate_report))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { activityContext.openGithubReportIssue() }
                            ) {
                                Text(stringResource(R.string.settings_debug_logging_report_on_github))
                            }

                            TextButton(
                                onClick = { showDebugDialog = false }
                            ) {
                                Text(text = stringResource(R.string.general_close))
                            }
                        }
                    }
                }
            }
        }
    }

    PermissionsContent(
        status = status,
        onGrantAccessButtonClick = {
            viewModel.onGrantAccess(
                status = status,
                onPermissionGranted = onPermissionGranted,
                requestRuntimePermission = { permission -> permissionRequestLauncher.launch(permission) },
                launchFolderPicker = { folderPickerLauncher.launch(null) },
                onStartAdbPairing = beginPairing,
                onOpenNotificationSettings = openNotificationSettings,
            )
        },
        onCallDetectionModeChanged = { newMode ->
            viewModel.onCallDetectionModeChanged(newMode)
            onPermissionGranted() // Refresh the UI after changing the mode
        },
        isProcessingGrantingRequest = isProcessingGrantingRequest,
        adbPairingState = adbPairingState,
        notificationsAllowed = status.notificationsGranted,
        onBeginAdbPairing = beginPairing,
        onOpenWirelessDebugging = { activityContext.openWirelessDebuggingSettings() },
        onOpenNotificationSettings = openNotificationSettings,
        onCancelAdbPairing = viewModel::cancelAdbPairing,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    showDebugDialog = true
                }
            )
        }
    )
}

/**
 * Stateless visual layer for the permissions checklist screen.
 *
 * Renders a scrollable list of [PermissionCard] items based on the [OnboardingStatus.Status]
 *
 * @param status                 The current "Snapshot" of every permission and setup step.
 * @param onGrantAccessButtonClick Forwarded to [PermissionsViewModel.onGrantAccess] when user taps the button.
 * @param onGrantAccessButtonLongClick Forwarded to [PermissionsViewModel.onGrantAccess] when user long-presses the button.
 * @param modifier
 */
@Composable
fun PermissionsContent(
    status: OnboardingStatus.Status,
    onGrantAccessButtonClick: () -> Unit,
    onCallDetectionModeChanged: (CallDetectionMode) -> Unit,
    isProcessingGrantingRequest: Boolean,
    adbPairingState: AdbPairingUiState = AdbPairingUiState(),
    notificationsAllowed: Boolean = true,
    onBeginAdbPairing: () -> Unit = {},
    onOpenWirelessDebugging: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onCancelAdbPairing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            SetupStatusCard(status)
            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable permission cards
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AdbPairingCard(
                    paired = status.adbPaired,
                    serviceConnected = status.adbServiceConnected,
                    notificationsAllowed = notificationsAllowed,
                    state = adbPairingState,
                    onBeginPairing = onBeginAdbPairing,
                    onOpenWirelessDebugging = onOpenWirelessDebugging,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onCancel = onCancelAdbPairing,
                )

                val globalPermissions = listOf(
                    Triple(stringResource(R.string.permission_notifications_label), stringResource(R.string.permission_notifications_description), status.notificationsGranted to Icons.Default.QuestionAnswer),
                    Triple(stringResource(R.string.permission_battery_label), stringResource(R.string.permission_battery_description), status.batteryExempted to Icons.Default.BatterySaver),
                )

                // How global permissions
                globalPermissions.forEach { (label, desc, grantInfo) ->
                    PermissionCard(
                        label = label,
                        description = desc,
                        granted = grantInfo.first,
                        statusOverride = if (grantInfo.second == Icons.Default.BatterySaver && !grantInfo.first) "Recomendado" else null,
                        isOptional = grantInfo.second == Icons.Default.BatterySaver,
                        iconOverride = grantInfo.second
                    )
                }

                HorizontalDivider() // Call detection specific permissions section

                val detectionOptions = CallDetectionMode.entries.map { mode ->
                    OptionItem(
                        key = mode.name,
                        label = stringResource(mode.titleResId),
                        description = stringResource(mode.descriptionResId),
                        enabled = mode.isSupportedOnCurrentApi()
                    )
                }

                M3DropdownField(
                    label = stringResource(R.string.settings_call_detection_method),
                    selected = detectionOptions.find { it.key == status.callDetectionMode.name } ?: detectionOptions.first(),
                    options = detectionOptions,
                    onOptionSelected = { selectedItem ->
                        onCallDetectionModeChanged(CallDetectionMode.valueOf(selectedItem.key))
                    }
                )

                AnimatedContent(
                    targetState = status.callDetectionMode,
                    transitionSpec = {
                        val enterTransition = fadeIn(tween(300)) + expandVertically(tween(300))
                        val exitTransition = fadeOut(tween(250)) + shrinkVertically(tween(250))

                        enterTransition togetherWith exitTransition
                    },
                    label = "CallDetectionModeSettingsTransition"
                ) { selectedCallDetectionMode ->
                    // Child-Column required so cards don't show on top of each other
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Show the required permissions for the selected call detection mode
                        selectedCallDetectionMode.requiredPermissions.forEach { currentPermission ->
                            val isGranted = status.callDetectionModeGrantedPermissions.contains(currentPermission)
                            PermissionCard(
                                label = stringResource(currentPermission.titleResId),
                                description = stringResource(currentPermission.descriptionResId),
                                granted = isGranted,
                                iconOverride = currentPermission.icon
                            )
                        }
                    }
                }


            }

            // Footer with action button
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

            Button(
                onClick = onGrantAccessButtonClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessingGrantingRequest && !adbPairingState.active,
                shape = MaterialTheme.shapes.medium
            ) {
                if (isProcessingGrantingRequest) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = when {
                            status.isComplete() -> stringResource(R.string.general_continue)
                            adbPairingState.active && adbPairingState.pairingInProgress -> "Emparejando…"
                            adbPairingState.active -> "Buscando ADB…"
                            !status.adbPaired -> "Emparejar ADB"
                            else -> stringResource(R.string.permissions_grant_access)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStatusCard(status: OnboardingStatus.Status) {
    val missingPermission = status.callDetectionMode.requiredPermissions.firstOrNull {
        it !in status.callDetectionModeGrantedPermissions
    }
    val reason = when {
        !status.notificationsGranted -> "Falta el permiso de notificaciones. Es necesario para ver el estado de la grabación y sus errores."
        !status.adbPaired -> "Falta emparejar ADB inalámbrico. Completa el emparejamiento desde la notificación de Grabaciones Locales."
        missingPermission != null -> "Falta el permiso «${stringResource(missingPermission.titleResId)}» para detectar llamadas. Pulsa «Conceder acceso»; al completar los permisos esenciales, el asistente se cerrará automáticamente. La exención de batería es recomendada, pero opcional."
        else -> "Comprobando la configuración esencial…"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column {
                Text("Configuración pendiente", fontWeight = FontWeight.SemiBold)
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AdbPairingCard(
    paired: Boolean,
    serviceConnected: Boolean,
    notificationsAllowed: Boolean,
    state: AdbPairingUiState,
    onBeginPairing: () -> Unit,
    onOpenWirelessDebugging: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Adb, contentDescription = null, tint = if (paired && serviceConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("ADB inalámbrico integrado", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !paired -> "Requiere emparejar una vez desde Depuración inalámbrica."
                            serviceConnected -> "Emparejada; servicio de grabación conectado."
                            else -> "Emparejada; el servicio se iniciará al abrir la app o al comenzar una llamada."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (paired) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (paired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Emparejar abre los ajustes de Depuración inalámbrica. Cuando Android muestre el código, desliza hacia abajo las notificaciones y responde a «Introducir código» en Grabaciones Locales. El servicio permanece activo; no vuelvas a esta pantalla para escribir el código. Ambos deben estar en la misma red Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            if (!notificationsAllowed) {
                Text(
                    "Las notificaciones son necesarias para introducir el código sin cerrar los ajustes de Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = when {
                        !notificationsAllowed -> onBeginPairing
                        state.active -> onOpenWirelessDebugging
                        else -> onBeginPairing
                    },
                    enabled = !state.pairingInProgress,
                ) {
                    Text(
                        when {
                            !notificationsAllowed -> "Activar notificaciones"
                            state.active -> "Volver a ajustes de depuración"
                            paired -> "Reiniciar emparejamiento"
                            else -> "Emparejar"
                        },
                    )
                }
                if (state.active) {
                    TextButton(onClick = onCancel, enabled = !state.pairingInProgress) { Text("Cancelar") }
                }
                if (!notificationsAllowed) {
                    TextButton(onClick = onOpenNotificationSettings) { Text("Ajustes") }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    label: String,
    description: String,
    granted: Boolean,
    statusOverride: String? = null,
    isOptional: Boolean = false,
    iconOverride: ImageVector? = null
) {
    // Animate the background container color smoothly
    val containerColor by animateColorAsState(
        targetValue = if (granted || isOptional) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        },
        animationSpec = tween(durationMillis = 500),
        label = "cardBackgroundColor"
    )

    // Animate the status/icon color
    val statusColor by animateColorAsState(
        targetValue = when {
            granted -> MaterialTheme.colorScheme.primary
            isOptional -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(durationMillis = 600),
        label = "cardStatusColor"
    )

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = containerColor),
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(iconOverride ?: Icons.Default.Adb, null, Modifier.size(20.dp))
                    Text(label, fontWeight = FontWeight.SemiBold)

                    Spacer(modifier = Modifier.weight(1f))

                    Icon(
                        imageVector = when {
                            granted -> Icons.Default.CheckCircle
                            isOptional -> Icons.Default.Info
                            else -> Icons.Default.ErrorOutline
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = statusColor // Used the animated color here
                    )
                    Text(
                        text = statusOverride ?: if (granted) stringResource(R.string.permissions_status_granted) else stringResource(R.string.permissions_status_required),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor, // Used the animated color here
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsScreenPreview() {
    GrabacionesLocalesTheme(darkTheme = false) {
        PermissionsContent(
            status = OnboardingStatus.Status(
                disclaimerAccepted = true,
                notificationsGranted = false,
                contactsGranted = true,
                batteryExempted = false,
                storageSelected = false,
                adbPaired = false,
                adbServiceConnected = false,
                callDetectionMode = CallDetectionMode.PhoneState,
                callDetectionModeGrantedPermissions = emptySet()
            ),
            onGrantAccessButtonClick = {},
            onCallDetectionModeChanged = {},
            isProcessingGrantingRequest = false
        )
    }
}
