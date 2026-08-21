package com.kevshupp.kevmusicplayer.ui.screens.settings

import com.kevshupp.kevmusicplayer.ui.screens.*

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.data.AudioFile
import kotlinx.coroutines.*
import kotlin.math.*
data class CategoryItem(
    val name: String,
    val labelRes: Int,
    val descRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

data class SortPrefItem(
    val value: String,
    val name: String,
    val desc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun settingsTextColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) Color.Black else Color.White
}

@Composable
fun settingsTextMutedColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.5f)
}

@Composable
fun settingsDividerColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.08f)
}

@Composable
fun settingsCardContainerColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
}


@Composable
fun CircularSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "",
    valueSuffix: String = "%",
    onValueChangeFinished: (() -> Unit)? = null
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        var width by remember { mutableStateOf(0) }
        var height by remember { mutableStateOf(0) }
        
        val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        val handleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    width = it.width
                    height = it.height
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { onValueChangeFinished?.invoke() },
                        onDragCancel = { onValueChangeFinished?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val cx = width / 2f
                            val cy = height / 2f
                            val touchX = change.position.x
                            val touchY = change.position.y
                            
                            var angle = Math.toDegrees(atan2(touchY - cy, touchX - cx).toDouble()).toFloat()
                            if (angle < 0) {
                                angle += 360f
                            }
                            
                            var relativeAngle = angle - 135f
                            if (relativeAngle < 0) {
                                relativeAngle += 360f
                            }
                            
                            val newValue = when {
                                relativeAngle <= 270f -> relativeAngle / 270f
                                relativeAngle < 315f -> 1f
                                else -> 0f
                            }
                            onValueChange(newValue)
                        }
                    )
                }
        ) {
            val radius = minOf(this.size.width, this.size.height) / 2f - 8.dp.toPx()
            val center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
            
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            
            drawArc(
                color = activeColor,
                startAngle = 135f,
                sweepAngle = 270f * value,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            
            val handleAngle = (135f + 270f * value) * (PI / 180f)
            val handleX = center.x + radius * cos(handleAngle).toFloat()
            val handleY = center.y + radius * sin(handleAngle).toFloat()
            
            drawCircle(
                color = handleColor,
                radius = 8.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(handleX, handleY)
            )
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${(value * 100).toInt()}$valueSuffix",
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = textColor
            )
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    color = labelColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun VerticalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        val handleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

        Text(
            text = "${if (value > 0) "+" else ""}${value.toInt()}dB",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        var height by remember { mutableStateOf(0) }
        
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(150.dp)
                .onSizeChanged { height = it.height }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { onValueChangeFinished?.invoke() },
                        onDragCancel = { onValueChangeFinished?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val touchY = change.position.y
                            val progress = 1f - (touchY / height).coerceIn(0f, 1f)
                            val newValue = -15f + progress * 30f
                            onValueChange(newValue)
                        }
                    )
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = this.size.width / 2f
                drawLine(
                    color = trackColor,
                    start = androidx.compose.ui.geometry.Offset(cx, 0f),
                    end = androidx.compose.ui.geometry.Offset(cx, this.size.height),
                    strokeWidth = 6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                val zeroY = this.size.height / 2f
                val progress = (value + 15f) / 30f
                val handleY = this.size.height * (1f - progress)
                
                drawLine(
                    color = activeColor,
                    start = androidx.compose.ui.geometry.Offset(cx, zeroY),
                    end = androidx.compose.ui.geometry.Offset(cx, handleY),
                    strokeWidth = 6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                drawCircle(
                    color = handleColor,
                    radius = 8.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(cx, handleY)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = label,
            fontSize = 11.sp,
            color = labelColor,
            fontWeight = FontWeight.Bold
        )
    }
}

fun getPhysicalPathFromTreeUri(context: android.content.Context, treeUri: android.net.Uri): String? {
    val docId = try {
        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)?.uri?.let { uri ->
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } ?: return null
    
    val split = docId.split(":")
    val type = split.getOrNull(0)
    val relativePath = split.getOrNull(1) ?: ""
    
    return if ("primary".equals(type, ignoreCase = true)) {
        val base = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (relativePath.isNotEmpty()) "$base/$relativePath" else base
    } else {
        val extStorages = context.getExternalFilesDirs(null)
        var path: String? = null
        for (file in extStorages) {
            if (file != null) {
                val absolutePath = file.absolutePath
                val index = absolutePath.indexOf("/Android/data/")
                if (index != -1) {
                    val root = absolutePath.substring(0, index)
                    if (type != null && root.contains(type)) {
                        path = if (relativePath.isNotEmpty()) "$root/$relativePath" else root
                        break
                    }
                }
            }
        }
        path ?: "/storage/$type/$relativePath"
    }
}

data class MockSongItem(
    val title: String,
    val artist: String,
    val colors: List<androidx.compose.ui.graphics.Color>
)
