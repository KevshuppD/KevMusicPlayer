package com.kevshupp.kevmusicplayer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks
import com.kevshupp.kevmusicplayer.data.telegram.TelegramAuthState
import com.kevshupp.kevmusicplayer.data.telegram.TelegramMusicClient

/**
 * Modo bajo demanda: el buscador muestra canciones de Deezer que no tienes y se pueden poner en la cola;
 * el bot de Telegram las entrega al sonar. El usuario elige si lo descargado queda en caché o en la biblioteca.
 */
@Composable
fun OnDemandSettingsCard(context: android.content.Context, getLocalized: (String, String) -> String) {
    val prefs = remember { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(OnDemandTracks.KEY_ENABLED, false)) }
    var storage by remember { mutableStateOf(OnDemandTracks.storageMode(context)) }
    var cacheMb by remember { mutableIntStateOf(prefs.getInt(OnDemandTracks.KEY_CACHE_MB, OnDemandTracks.DEFAULT_CACHE_MB)) }
    var cacheUsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        TelegramMusicClient.start(context)
        cacheUsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { OnDemandTracks.cacheSizeBytes(context) }
    }
    val telegramOn by TelegramMusicClient.enabled.collectAsState()
    val auth by TelegramMusicClient.authState.collectAsState()
    val bot by TelegramMusicClient.bot.collectAsState()
    val telegramReady = auth == TelegramAuthState.Ready && bot.isNotBlank()

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Interruptor general: apagado oculta la descarga de Inicio y la reparación, y apaga el modo bajo demanda
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(getLocalized("Funciones de Telegram", "Telegram features"), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = settingsTextColor())
                    Text(
                        getLocalized(
                            "Descargas, reparación de canciones y modo bajo demanda. Apagado no cierra la sesión.",
                            "Downloads, song repair and on-demand mode. Turning it off keeps the session."
                        ),
                        fontSize = 12.sp,
                        color = settingsTextMutedColor()
                    )
                }
                Switch(
                    checked = telegramOn,
                    onCheckedChange = {
                        TelegramMusicClient.setEnabled(context, it)
                        if (!it) enabled = false
                    }
                )
            }
            if (!telegramOn) return@Column
            HorizontalDivider(color = settingsDividerColor())

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(getLocalized("Modo bajo demanda", "On-demand mode"), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = settingsTextColor())
                    Text(
                        if (telegramReady) getLocalized(
                            "Busca y pon en la cola canciones que no tienes; el bot de Telegram las entrega al sonar.",
                            "Search and queue songs you don't have; the Telegram bot delivers them when they play."
                        ) else getLocalized(
                            "Requiere iniciar sesión en Telegram y configurar el bot (botón de nube en Inicio).",
                            "Requires signing in to Telegram and setting up the bot (cloud button on Home)."
                        ),
                        fontSize = 12.sp,
                        color = settingsTextMutedColor()
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = telegramReady || enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.edit().putBoolean(OnDemandTracks.KEY_ENABLED, it).apply()
                    }
                )
            }

            if (enabled) {
                HorizontalDivider(color = settingsDividerColor())
                Text(getLocalized("Canciones escuchadas", "Played songs"), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = settingsTextColor())
                StorageOption(
                    selected = storage == OnDemandTracks.STORAGE_CACHE,
                    title = getLocalized("Guardar en caché temporal", "Keep in temporary cache"),
                    subtitle = getLocalized(
                        "No aparecen en la biblioteca; se borran las más antiguas al llegar al límite.",
                        "They don't appear in the library; the oldest are removed at the limit."
                    )
                ) {
                    storage = OnDemandTracks.STORAGE_CACHE
                    prefs.edit().putString(OnDemandTracks.KEY_STORAGE, storage).apply()
                }
                StorageOption(
                    selected = storage == OnDemandTracks.STORAGE_LIBRARY,
                    title = getLocalized("Descargar a la biblioteca", "Download to the library"),
                    subtitle = getLocalized(
                        "Se guardan en la carpeta principal (Artista/Álbum) como cualquier descarga.",
                        "Saved to the main folder (Artist/Album) like any download."
                    )
                ) {
                    storage = OnDemandTracks.STORAGE_LIBRARY
                    prefs.edit().putString(OnDemandTracks.KEY_STORAGE, storage).apply()
                }

                if (storage == OnDemandTracks.STORAGE_CACHE) {
                    Text(
                        getLocalized("Límite de la caché", "Cache limit") + ": " + if (cacheMb >= 1024) "${cacheMb / 1024} GB" else "$cacheMb MB",
                        fontSize = 13.sp,
                        color = settingsTextColor()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(512, 1024, 2048, 4096).forEach { mb ->
                            FilterChip(
                                selected = cacheMb == mb,
                                onClick = {
                                    cacheMb = mb
                                    prefs.edit().putInt(OnDemandTracks.KEY_CACHE_MB, mb).apply()
                                },
                                label = { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            getLocalized("En uso", "In use") + ": %.0f MB".format(cacheUsed / 1_048_576.0),
                            fontSize = 12.sp,
                            color = settingsTextMutedColor(),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = cacheUsed > 0,
                            onClick = {
                                OnDemandTracks.clearCache(context)
                                cacheUsed = 0L
                            }
                        ) { Text(getLocalized("Vaciar", "Clear")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = settingsTextColor())
            Text(subtitle, fontSize = 11.sp, color = settingsTextMutedColor())
        }
    }
}
