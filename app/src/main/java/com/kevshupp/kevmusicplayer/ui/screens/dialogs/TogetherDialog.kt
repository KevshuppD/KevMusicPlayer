package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kevshupp.kevmusicplayer.data.together.TogetherController
import com.kevshupp.kevmusicplayer.data.together.TogetherController.Mode
import com.kevshupp.kevmusicplayer.data.together.TogetherController.Phase
import com.kevshupp.kevmusicplayer.data.together.TogetherController.Role

private val NEARBY_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES
)

/** Escuchar juntos: primero pregunta si están juntos o a distancia, luego si comparte o se une. */
@Composable
fun TogetherDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isEs = java.util.Locale.getDefault().language == "es"
    fun t(es: String, en: String) = if (isEs) es else en
    val state by TogetherController.state.collectAsState()
    var mode by remember { mutableStateOf(state.mode) }
    var joinCode by remember { mutableStateOf("") }
    var pendingNearby by remember { mutableStateOf<Role?>(null) }

    fun startNearby(role: Role) {
        if (role == Role.HOST) TogetherController.hostNearby(context) else TogetherController.discoverNearby(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val role = pendingNearby
        pendingNearby = null
        if (role != null && result.values.all { it }) startNearby(role)
        else Toast.makeText(context, t("Hace falta el permiso de dispositivos cercanos", "Nearby devices permission is required"), Toast.LENGTH_LONG).show()
    }

    fun requestNearby(role: Role) {
        val missing = NEARBY_PERMISSIONS.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startNearby(role) else {
            pendingNearby = role
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Groups, null) },
        title = { Text(t("Escuchar juntos", "Listen together")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                when {
                    // Sesión en marcha
                    state.active -> ActiveSession(state, ::t)
                    // Paso 1: ¿dónde están?
                    mode == null -> {
                        Text(t("¿Están juntos o a distancia?", "Are you together or far apart?"), fontWeight = FontWeight.SemiBold)
                        ChoiceCard(Icons.Rounded.Bluetooth, t("Estamos juntos", "We're together"),
                            t("Se conectan por Bluetooth: más rápido y sincronizado al instante. Si el otro no tiene la canción, se la envías.",
                                "Connect via Bluetooth: faster and in sync. If the other phone lacks the song, you send it.")) { mode = Mode.NEARBY }
                        ChoiceCard(Icons.Rounded.Public, t("A distancia", "Far apart"),
                            t("Por internet con un código. Cada uno escucha desde su biblioteca (o bajo demanda). Requiere sesión de Google.",
                                "Over the internet with a code. Each plays from their own library (or on demand). Needs Google sign-in.")) { mode = Mode.REMOTE }
                    }
                    // Paso 2: compartir o unirse
                    mode == Mode.NEARBY -> {
                        ChoiceCard(Icons.Rounded.Speaker, t("Compartir lo que suena", "Share what's playing"),
                            t("Tu teléfono manda: el otro sigue tu música", "Your phone leads: the other follows")) { requestNearby(Role.HOST) }
                        ChoiceCard(Icons.Rounded.Headphones, t("Unirme", "Join"),
                            t("Busca el teléfono cercano que comparte", "Find the nearby phone that's sharing")) { requestNearby(Role.GUEST) }
                    }
                    else -> {
                        ChoiceCard(Icons.Rounded.Speaker, t("Compartir lo que suena", "Share what's playing"),
                            t("Te da un código para tu amigo", "Gives you a code for your friend")) { TogetherController.hostRemote(context) }
                        OutlinedTextField(
                            value = joinCode,
                            onValueChange = { joinCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                            label = { Text(t("Código de tu amigo", "Friend's code")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = { TogetherController.joinRemote(context, joinCode) }, enabled = joinCode.length == 6, modifier = Modifier.fillMaxWidth()) {
                            Text(t("Unirme", "Join"))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.active) TextButton(onClick = { TogetherController.stop() }) {
                Text(t("Salir de la sesión", "Leave session"), color = MaterialTheme.colorScheme.error)
            } else TextButton(onClick = onDismiss) { Text(t("Cerrar", "Close")) }
        },
        dismissButton = {
            if (state.active) TextButton(onClick = onDismiss) { Text(t("Ocultar", "Hide")) }
            else if (mode != null) TextButton(onClick = { mode = null; TogetherController.clearMessage() }) { Text(t("Atrás", "Back")) }
        }
    )
}

@Composable
private fun ActiveSession(state: TogetherController.State, t: (String, String) -> String) {
    val clipboard = LocalClipboardManager.current
    val host = state.role == Role.HOST
    when {
        state.mode == Mode.REMOTE && host -> {
            Text(t("Comparte este código:", "Share this code:"))
            Text(
                state.code.orEmpty(), fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(state.code.orEmpty())) }
            )
            Text(t("Tu amigo lo escribe en Escuchar juntos › A distancia. Toca el código para copiarlo.",
                "Your friend types it in Listen together › Far apart. Tap the code to copy it."), fontSize = 12.sp)
        }
        state.phase == Phase.WAITING && host -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(t("Esperando a que se una alguien cerca… (visible como «${state.peer}»)", "Waiting for someone nearby… (visible as “${state.peer}”)"))
        }
        state.phase == Phase.WAITING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(t("Buscando teléfonos cercanos…", "Looking for nearby phones…"))
            }
            state.found.forEach { peer ->
                OutlinedCard(onClick = { TogetherController.connectTo(peer) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, null)
                        Spacer(Modifier.width(10.dp))
                        Text(peer.name, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        state.phase == Phase.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(t("Conectando con ${state.peer ?: state.code ?: ""}…", "Connecting to ${state.peer ?: state.code ?: ""}…"))
        }
        else -> {
            Text(
                if (host) t("Conectado con ${state.peer ?: "tu amigo"}. Tú controlas la música.", "Connected to ${state.peer ?: "your friend"}. You control the music.")
                else t("Escuchando con ${state.peer ?: state.code ?: ""}. La música la controla el otro teléfono.", "Listening with ${state.peer ?: state.code ?: ""}. The other phone controls the music."),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    state.authDigits?.takeIf { state.mode == Mode.NEARBY && state.phase != Phase.IDLE }?.let {
        Text(t("Código de verificación: $it (debe coincidir en ambos)", "Verification code: $it (must match on both)"), fontSize = 12.sp)
    }
    state.nowPlaying?.let { Text("♪ $it", fontSize = 13.sp) }
    if (!host && state.phase == Phase.CONNECTED) {
        val context = LocalContext.current
        val offset by TogetherController.userOffsetMs.collectAsState()
        Text(
            t("Puedes pausar, reanudar, mover la barra y añadir canciones a su cola desde tu biblioteca.",
                "You can pause, resume, seek and add songs to their queue from your library."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.driftMs?.let { d ->
            val ok = kotlin.math.abs(d) <= 60
            Text(
                if (ok) t("Sincronizado (±${kotlin.math.abs(d)} ms)", "In sync (±${kotlin.math.abs(d)} ms)")
                else t("Ajustando: ${if (d > 0) "+" else ""}$d ms", "Adjusting: ${if (d > 0) "+" else ""}$d ms"),
                fontSize = 12.sp, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            t("Ajuste fino: ${if (offset > 0) "+" else ""}$offset ms", "Fine tune: ${if (offset > 0) "+" else ""}$offset ms"),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = offset.toFloat(),
            onValueChange = { TogetherController.setUserOffset(context, (it / 10).toLong() * 10) },
            valueRange = -500f..500f
        )
        Text(
            t("Si tu teléfono suena atrasado (p. ej. con auriculares Bluetooth), muévelo a la derecha.",
                "If your phone sounds behind (e.g. with Bluetooth headphones), move it right."),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
