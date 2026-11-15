package com.llsl.viper4android.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.llsl.viper4android.R
import com.llsl.viper4android.ui.screens.main.DriverStatus
import com.llsl.viper4android.utils.ShizukuShell
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
    autoStartEnabled: Boolean,
    globalModeEnabled: Boolean,
    aidlModeActive: Boolean,
    driverStatus: DriverStatus,
    appVersionName: String,
    bufferSize: Int,
    onAutoStartChanged: (Boolean) -> Unit,
    onGlobalModeChanged: (Boolean) -> Unit,
    onBufferSizeChanged: (Int) -> Unit,
    onImportPreset: () -> Unit,
    onImportKernel: () -> Unit,
    onImportVdc: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_settings)) },
        text = {
            Column {
                SettingsToggleRow(
                    label = stringResource(R.string.settings_auto_start),
                    checked = autoStartEnabled,
                    onCheckedChange = onAutoStartChanged,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SettingsToggleRow(
                    label = stringResource(R.string.settings_global_mode),
                    checked = globalModeEnabled,
                    onCheckedChange = onGlobalModeChanged,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                // Buffer size slider
                BufferSizeSlider(
                    bufferSize = bufferSize,
                    onBufferSizeChanged = onBufferSizeChanged,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.settings_files_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedButton(
                    onClick = onImportPreset,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.settings_import_preset))
                }
                OutlinedButton(
                    onClick = onImportKernel,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.settings_import_kernel))
                }
                OutlinedButton(
                    onClick = onImportVdc,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.settings_import_vdc))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (aidlModeActive) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_aidl_mode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Canvas(modifier = Modifier.size(6.dp)) {
                            drawCircle(Color(0xFF4CAF50))
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                // Shizuku status indicator
                val shizukuAvailable = ShizukuShell.isAvailable()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_shizuku_status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Canvas(modifier = Modifier.size(6.dp)) {
                        drawCircle(if (shizukuAvailable) Color(0xFF4CAF50) else Color(0xFFEF5350))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SettingsInfoRow(
                    label = stringResource(R.string.settings_app_version),
                    value = appVersionName,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BufferSizeSlider(
    bufferSize: Int,
    onBufferSizeChanged: (Int) -> Unit,
) {
    // Powers of 2 from 128 to 16384
    val bufferSizes = listOf(128, 256, 512, 1024, 2048, 4096, 8192, 16384)
    val currentIndex = bufferSizes.indexOf(bufferSize).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_buffer_size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$bufferSize samples",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = currentIndex.toFloat(),
            onValueChange = { index ->
                val i = index.roundToInt().coerceIn(0, bufferSizes.lastIndex)
                onBufferSizeChanged(bufferSizes[i])
            },
            valueRange = 0f..(bufferSizes.size - 1).toFloat(),
            steps = bufferSizes.size - 2,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}
