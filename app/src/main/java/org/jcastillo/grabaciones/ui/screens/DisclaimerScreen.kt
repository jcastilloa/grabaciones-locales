/*
 * Modified fork of ShizuCallRecorder. Upstream capture implementation and GPL notices are retained.
 * Copyright (C) 2026 Grabaciones Locales contributors; GPL-3.0-or-later.
 */
package org.jcastillo.grabaciones.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DisclaimerScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    var accepted by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Grabaciones Locales", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text(
                "Esta aplicación es una versión modificada (fork) de ShizuCallRecorder. " +
                    "Su emparejamiento ADB integrado adapta la arquitectura de CallVault. Se distribuye bajo GPL-3.0-or-later con los avisos y términos aplicables.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "La grabación depende del fabricante, Android, el operador y la ruta de audio. " +
                    "No se usa el micrófono como sustituto silencioso si falla la captura de ambas voces. " +
                    "Comprueba cada llamada en tu teléfono. La aplicación no avisa a los interlocutores: " +
                    "obtén el consentimiento exigido por la ley local. No se garantiza compatibilidad.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { uriHandler.openUri("https://github.com/kitsumed/ShizuCallRecorder") }) {
                Text("Proyecto original y código fuente")
            }
            TextButton(onClick = { uriHandler.openUri("https://github.com/madkongo/CallVault") }) {
                Text("Arquitectura ADB adaptada de CallVault")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                Text("He leído el aviso legal y comprendo las limitaciones técnicas y legales.")
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onContinue, enabled = accepted, modifier = Modifier.fillMaxWidth()) {
                Text("Continuar")
            }
        }
    }
}
