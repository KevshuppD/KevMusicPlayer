package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.BuildInfo
import com.kevshupp.kevmusic.desktop.UpdateState
import com.kevshupp.kevmusic.desktop.Updater
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(onExit: () -> Unit) {
    val state by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val release = when (val s = state) {
        UpdateState.Idle -> return
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.Installed -> s.release
        is UpdateState.Failed -> s.release
    }
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing

    AlertDialog(
        onDismissRequest = { if (!busy) Updater.dismiss() },
        title = { Text(if (state is UpdateState.Installed) "Actualización instalada" else "Nueva versión ${release.version}") },
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tienes la ${BuildInfo.VERSION}.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (release.notes.isNotBlank()) {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        ChangelogText(release.notes)
                    }
                }
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Descargando… ${(s.progress * 100).toInt()} %", fontSize = 12.sp)
                    }
                    is UpdateState.Installing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Instalando… el sistema te pedirá tu contraseña.", fontSize = 12.sp)
                    }
                    is UpdateState.Installed -> Text("Reinicia la app para usar la nueva versión.")
                    is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    else -> if (!Updater.isInstalledBuild) Text(
                        "Estás usando la versión de desarrollo (probar.sh). Se instalará el paquete en ${Updater.INSTALL_DIR}.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Installed -> Button(onClick = { Updater.relaunch(onExit) }) { Text("Reiniciar") }
                is UpdateState.Available, is UpdateState.Failed ->
                    Button(onClick = { scope.launch { Updater.downloadAndInstall(release) } }) {
                        Text(if (state is UpdateState.Failed) "Reintentar" else "Descargar e instalar")
                    }
                else -> {}
            }
        },
        dismissButton = {
            if (!busy) TextButton(onClick = { Updater.dismiss() }) { Text(if (state is UpdateState.Installed) "Más tarde" else "Ahora no") }
        }
    )
}

/** Notas en el mismo Markdown sencillo que las de Android: viñetas "- " (anidadas con dos espacios) y **negrita**. */
@Composable
private fun ChangelogText(md: String) {
    md.lines().filter { it.isNotBlank() }.forEach { raw ->
        val indent = raw.takeWhile { it == ' ' }.length / 2
        val line = raw.trim()
        val bullet = line.startsWith("- ") || line.startsWith("* ")
        val text = (if (bullet) line.drop(2) else line.trimStart('#', ' '))
        val annotated = androidx.compose.ui.text.buildAnnotatedString {
            val parts = text.split("**")
            parts.forEachIndexed { i, part ->
                if (i % 2 == 1) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
            }
        }
        Row(Modifier.padding(start = (indent * 16).dp)) {
            if (bullet) Text("•  ", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Text(annotated, fontSize = 13.sp, fontWeight = if (line.startsWith("#")) FontWeight.Bold else null)
        }
    }
}
