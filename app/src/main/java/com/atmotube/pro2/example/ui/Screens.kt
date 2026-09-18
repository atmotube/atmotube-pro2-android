package com.atmotube.pro2.example.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atmotube.pro2.example.AtmotubeGpsReading
import com.atmotube.pro2.example.AtmotubePmReading
import com.atmotube.pro2.example.AtmotubeReading
import com.atmotube.pro2.example.ConnectionState

@Composable
fun ScanScreen(
    scannedDevices: List<BluetoothDevice>,
    onScanClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit,
    isConnecting: Boolean
) {
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Button(
            onClick = onScanClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting
        ) {
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...")
            } else {
                Text("Start Scan")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(scannedDevices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(enabled = !isConnecting) { onDeviceClick(device) },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
                        Text(text = device.address, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceScreen(
    connectionState: ConnectionState,
    reading: AtmotubeReading?,
    pmReading: AtmotubePmReading?,
    gpsReading: AtmotubeGpsReading?,
    commandLogs: List<String>,
    onSendCommand: (String) -> Unit,
    onDownloadHistory: () -> Unit,
    onDisconnect: () -> Unit,
    isDownloadingHistory: Boolean
) {
    var commandText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        // Status Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Status: ${connectionState::class.simpleName}")
            Button(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Realtime Data
        if (reading != null) {
            Text("VOC Index: ${AtmotubeReading.formatSensorValue(reading.vocIndex)}")
            Text("VOC (ppb): ${AtmotubeReading.formatSensorValue(reading.vocPpb)}")
            Text("Temp: ${AtmotubeReading.formatSensorValue(reading.temperature, "temp")}°C")
            Text("Humidity: ${AtmotubeReading.formatSensorValue(reading.humidity, "hum")}%")
            Text("Pressure: ${AtmotubeReading.formatSensorValue(reading.pressure, "press")} hPa")
            if (reading.errorDescriptions.isNotEmpty()) {
                Text("Flags: ${reading.errorDescriptions.joinToString(", ")}")
            }
        }

        if (pmReading != null) {
            Text("PM1: ${AtmotubeReading.formatSensorValue(pmReading.pm1)}")
            Text("PM2.5: ${AtmotubeReading.formatSensorValue(pmReading.pm25)}")
            Text("PM10: ${AtmotubeReading.formatSensorValue(pmReading.pm10)}")
            Text("Particles (#/cm³) 0.5/1/2.5/10: ${pmReading.pm05Particles}/${pmReading.pm1Particles}/${pmReading.pm25Particles}/${pmReading.pm10Particles}")
            Text("Typical particle size: ${pmReading.typicalParticleSize} µm")
        } else if (reading != null) {
            Text("PM1: ${AtmotubeReading.formatSensorValue(reading.pm1)}")
            Text("PM2.5: ${AtmotubeReading.formatSensorValue(reading.pm25)}")
            Text("PM10: ${AtmotubeReading.formatSensorValue(reading.pm10)}")
        }

        if (gpsReading != null) {
            Text("GPS: ${gpsReading.latitude}, ${gpsReading.longitude} (alt ${gpsReading.altitude} m)")
            Text("Satellites fixed/in view: ${gpsReading.satellitesFixed}/${gpsReading.satellitesInView}, accuracy ${gpsReading.accuracy}")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // History
        Button(
            onClick = onDownloadHistory,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isDownloadingHistory
        ) {
            if (isDownloadingHistory) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Downloading...")
            } else {
                Text("Download History to CSV")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Shell Commands
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSendCommand("version app") },
                modifier = Modifier.weight(1f)
            ) {
                Text("Get FW Version")
            }
            Button(
                onClick = { onSendCommand("pm status") },
                modifier = Modifier.weight(1f)
            ) {
                Text("Get PM Status")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier.weight(1f),
                label = { Text("Shell Command") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                onSendCommand(commandText)
                commandText = ""
            }) {
                Text("Send")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Logs
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true
        ) {
            items(commandLogs) { log ->
                Text(text = log, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

