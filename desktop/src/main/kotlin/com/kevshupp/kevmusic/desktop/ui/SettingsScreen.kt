package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.*
import kotlinx.coroutines.launch

/** Ajustes del PC, con buscador como los de la app: tema, letras, rendimiento, teléfono y actualizaciones. */
@Composable
fun SettingsScreen(status: ConnStatus) {
    val client = LocalClient.current
    val phoneTheme by client.phoneTheme.collectAsState()
    val update by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var checkedNoUpdate by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    val saved = remember(status) { Config.load() }

    // Cada bloque declara sus palabras clave; el buscador muestra solo los que coinciden
    fun matches(vararg words: String): Boolean {
        val q = normalize(query.trim())
        return q.isEmpty() || words.any { normalize(it).contains(q) }
    }

    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Ajustes", "KevMusic Desktop ${BuildInfo.VERSION}")
        SearchField(query, { query = it }, "Buscar ajuste (tema, letra, actualizaciones…)", Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                if (matches("apariencia", "tema", "color", "oscuro", "claro", "cyberpunk", "petróleo", "turquesa", "obsidiana", "monocromo")) {
                    SettingsCard("Apariencia", Icons.Rounded.Palette) {
                        Text("Tema", fontWeight = FontWeight.Medium)
                        Text(
                            "\"Igual que el teléfono\" cambia solo cuando cambias el tema en la app (ahora: ${Themes.firstOrNull { it.first == phoneTheme }?.second ?: phoneTheme}).",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            ThemeChip("Igual que el teléfono", phoneTheme, Settings.theme == Settings.THEME_PHONE, Modifier.weight(1f)) {
                                Settings.theme = Settings.THEME_PHONE; Settings.save()
                            }
                            Themes.take(3).forEach { (key, label) ->
                                ThemeChip(label, key, Settings.theme == key, Modifier.weight(1f)) { Settings.theme = key; Settings.save() }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Themes.drop(3).forEach { (key, label) ->
                                ThemeChip(label, key, Settings.theme == key, Modifier.weight(1f)) { Settings.theme = key; Settings.save() }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                if (matches("letra", "letras", "traducción", "tamaño", "fuente", "karaoke")) {
                    SettingsCard("Letras", Icons.Rounded.Lyrics) {
                        SwitchRow("Mostrar traducción", "Debajo de cada línea, si la canción tiene traducción guardada", Settings.showTranslation) {
                            Settings.showTranslation = it; Settings.save()
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Tamaño de la letra", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Pequeña", "Normal", "Grande").forEachIndexed { i, label ->
                                FilterChip(selected = Settings.lyricsSize == i, onClick = { Settings.lyricsSize = i; Settings.save() }, label = { Text(label) })
                            }
                        }
                    }
                }

                if (matches("rendimiento", "carátulas", "caratulas", "hd", "caché", "cache", "memoria", "imágenes", "lento")) {
                    SettingsCard("Rendimiento", Icons.Rounded.Speed) {
                        SwitchRow(
                            "Carátulas en alta calidad en cuadrículas",
                            "Álbumes e Inicio con imágenes de 640 px. Más nítido, pero más pesado para gráficas integradas.",
                            Settings.hdGridArt
                        ) { Settings.hdGridArt = it; Settings.save() }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Caché de carátulas", fontWeight = FontWeight.Medium)
                                Text(if (cacheCleared) "Vaciada" else "Se vuelven a pedir al teléfono la próxima vez", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { client.clearArtCache(); cacheCleared = true }) { Text("Vaciar") }
                        }
                    }
                }

                if (matches("teléfono", "telefono", "conexión", "dispositivo", "ip", "desvincular", "emparejar", "reconectar", "red")) {
                    SettingsCard("Teléfono", Icons.Rounded.PhoneAndroid) {
                        val (label, color) = when (status) {
                            is ConnStatus.Connected -> "Conectado" to MaterialTheme.colorScheme.primary
                            is ConnStatus.Connecting -> "Conectando…" to MaterialTheme.colorScheme.onSurfaceVariant
                            is ConnStatus.Failed -> status.message to MaterialTheme.colorScheme.error
                            ConnStatus.Disconnected -> "Desconectado" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        InfoRow("Dispositivo", saved?.name ?: "—")
                        InfoRow("Dirección", saved?.let { "${it.host}:${it.port}" } ?: "—", mono = true)
                        InfoRow("Estado", label, color)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { saved?.let { client.resume(it) } }, enabled = saved != null) { Text("Reconectar") }
                            OutlinedButton(onClick = { client.refreshLibrary() }) { Text("Recargar biblioteca") }
                            TextButton(onClick = { client.disconnect(forget = true) }) { Text("Desvincular", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }

                if (matches("actualizaciones", "actualizar", "versión", "version", "github", "nueva")) {
                    SettingsCard("Actualizaciones", Icons.Rounded.SystemUpdate) {
                        InfoRow("Versión instalada", BuildInfo.VERSION + if (Updater.isInstalledBuild) "" else " (desarrollo)")
                        SwitchRow("Buscar al abrir", "Comprueba en GitHub si hay una versión nueva cada vez que abres la app", Settings.autoCheckUpdates) {
                            Settings.autoCheckUpdates = it; Settings.save()
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                checking = true
                                checkedNoUpdate = false
                                scope.launch {
                                    Updater.check()
                                    checking = false
                                    checkedNoUpdate = Updater.state.value is UpdateState.Idle
                                }
                            }, enabled = !checking) { Text(if (checking) "Buscando…" else "Buscar ahora") }
                            Spacer(Modifier.width(12.dp))
                            if (checkedNoUpdate && update is UpdateState.Idle) Text("Tienes la última versión", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (matches("atajos", "teclado", "teclas", "multimedia", "shortcut")) {
                    SettingsCard("Atajos de teclado", Icons.Rounded.Keyboard) {
                        listOf(
                            "Ctrl + P" to "Reproducir / pausa",
                            "Ctrl + → / ←" to "Siguiente / anterior",
                            "Ctrl + ↑ / ↓" to "Subir / bajar volumen del teléfono",
                            "Ctrl + 1 … 9" to "Cambiar de sección",
                            "Teclas multimedia" to "Funcionan con la ventana en segundo plano (MPRIS)"
                        ).forEach { (k, v) -> InfoRow(k, v, mono = false, keyMono = true) }
                    }
                }

                if (matches("acerca", "licencia", "mit", "privacidad", "kevmusic")) {
                    SettingsCard("Acerca de", Icons.Rounded.Info) {
                        Text(
                            "KevMusic Desktop es el control remoto de KevMusicPlayer. La música, la biblioteca y las descargas " +
                                "están en tu teléfono; el PC solo se conecta por tu red local. Código abierto bajo licencia MIT.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: androidx.compose.ui.graphics.Color? = null, mono: Boolean = false, keyMono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (keyMono) FontFamily.Monospace else null, modifier = Modifier.width(200.dp))
        Text(value, color = color ?: MaterialTheme.colorScheme.onSurface, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/** Muestra del tema: fondo, superficie y color principal, como las tarjetas de tema de la app. */
@Composable
private fun ThemeChip(label: String, themeKey: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = colorSchemeFor(themeKey)
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(scheme.background).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(scheme.primary))
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(scheme.surfaceVariant))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
    }
}
