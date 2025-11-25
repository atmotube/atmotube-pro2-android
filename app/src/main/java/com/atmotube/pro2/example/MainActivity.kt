package com.atmotube.pro2.example

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.atmotube.pro2.example.ui.DeviceScreen
import com.atmotube.pro2.example.ui.ScanScreen
import kotlinx.coroutines.launch
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: AtmotubeBleManager
    private lateinit var historyManager: HistoryManager

    private val scannedDevices = mutableStateListOf<BluetoothDevice>()
    private var isScanning by mutableStateOf(false)
    private var currentScreen by mutableStateOf(Screen.SCAN)

    private val scanner = BluetoothLeScannerCompat.getScanner()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name?.contains("ATMO", ignoreCase = true) == true) {
                if (scannedDevices.none { it.address == device.address }) {
                    scannedDevices.add(device)
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            startScan()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    private var isDownloadingHistory by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bleManager = AtmotubeBleManager(this, lifecycleScope)
        historyManager = HistoryManager(this, bleManager)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                }
            }
            Theme.AtmotubeSampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val connectionState by bleManager.connectionState.collectAsState()
                    val reading by bleManager.latestReading.collectAsState()
                    val pmReading by bleManager.pmReading.collectAsState()
                    val logs by bleManager.commandLogs.collectAsState()

                    LaunchedEffect(connectionState) {
                        if (connectionState is ConnectionState.Ready) {
                            currentScreen = Screen.DEVICE
                            stopScan()
                        } else if (connectionState is ConnectionState.Disconnected && currentScreen == Screen.DEVICE) {
                            // Optional: Go back to scan on disconnect?
                            // For now let's stay or provide a way to go back.
                            // currentScreen = Screen.SCAN
                        }
                    }

                    when (currentScreen) {
                        Screen.SCAN -> {
                            ScanScreen(
                                scannedDevices = scannedDevices,
                                onScanClick = { checkPermissionsAndScan() },
                                onDeviceClick = { device ->
                                    stopScan()
                                    bleManager.connectToDevice(device)
                                },
                                isConnecting = connectionState != ConnectionState.Disconnected
                            )
                        }
                        Screen.DEVICE -> {
                            DeviceScreen(
                                connectionState = connectionState,
                                reading = reading,
                                pmReading = pmReading,
                                commandLogs = logs,
                                onSendCommand = { cmd -> bleManager.sendShellCommand(cmd) },
                                onDownloadHistory = { downloadHistory() },
                                onDisconnect = {
                                    bleManager.disconnect().enqueue()
                                    currentScreen = Screen.SCAN
                                },
                                isDownloadingHistory = isDownloadingHistory
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startScan() {
        if (isScanning) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        scannedDevices.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        isScanning = true
    }

    private fun stopScan() {
        if (!isScanning) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        scanner.stopScan(scanCallback)
        isScanning = false
    }

    private fun downloadHistory() {
        if (isDownloadingHistory) return
        isDownloadingHistory = true
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Downloading history...", Toast.LENGTH_SHORT).show()
            val file = historyManager.downloadHistory()
            isDownloadingHistory = false
            if (file != null) {
                Toast.makeText(this@MainActivity, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                // Share intent
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(intent, "Share History"))
            } else {
                Toast.makeText(this@MainActivity, "No history found or download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

enum class Screen {
    SCAN, DEVICE
}

// Minimal Theme wrapper since I didn't create the full theme structure
object Theme {
    @Composable
    fun AtmotubeSampleTheme(content: @Composable () -> Unit) {
        MaterialTheme(content = content)
    }
}

