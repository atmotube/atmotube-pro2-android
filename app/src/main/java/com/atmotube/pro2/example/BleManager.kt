package com.atmotube.pro2.example

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import no.nordicsemi.android.mcumgr.McuMgrTransport
import no.nordicsemi.android.mcumgr.ble.McuMgrBleTransport
import no.nordicsemi.android.mcumgr.managers.ShellManager
import java.util.UUID

class AtmotubeBleManager(
    context: Context,
    private val scope: CoroutineScope
) : BleManager(context) {

    companion object {
        val ATMOTUBE_DATA_SERVICE_UUID: UUID = UUID.fromString("BDA3C091-E5E0-4DAC-8170-7FCEF187A1D0")
        val ATMOTUBE_DATA_CHAR_UUID: UUID = UUID.fromString("BDA3C092-E5E0-4DAC-8170-7FCEF187A1D0")
        val ATMOTUBE_PM_CHAR_UUID: UUID = UUID.fromString("BDA3C093-E5E0-4DAC-8170-7FCEF187A1D0")
        val ATMOTUBE_GPS_CHAR_UUID: UUID = UUID.fromString("BDA3C094-E5E0-4DAC-8170-7FCEF187A1D0")
        val ATMOTUBE_HISTORY_CHAR_UUID: UUID = UUID.fromString("BDA3C095-E5E0-4DAC-8170-7FCEF187A1D0")

        // Both this connection and the McuMgr shell/history transport below negotiate their own
        // MTU, but they share one physical link, so they should ask for the same value. 498 is two
        // full Data-Length-Extension link-layer packets' worth of payload; 517 spills a few bytes
        // into a third packet for no measurable throughput gain.
        private const val PREFERRED_MTU = 498

        // Firmware below 3.0.17 always encodes PM values as legacy 0.1-precision fixed point.
        private val PM_NEW_FORMAT_FW = Triple(3, 0, 17)
    }

    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var pmCharacteristic: BluetoothGattCharacteristic? = null
    private var gpsCharacteristic: BluetoothGattCharacteristic? = null
    private var historyCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latestReading = MutableStateFlow<AtmotubeReading?>(null)
    val latestReading: StateFlow<AtmotubeReading?> = _latestReading.asStateFlow()

    private val _pmReading = MutableStateFlow<AtmotubePmReading?>(null)
    val pmReading: StateFlow<AtmotubePmReading?> = _pmReading.asStateFlow()

    private val _gpsReading = MutableStateFlow<AtmotubeGpsReading?>(null)
    val gpsReading: StateFlow<AtmotubeGpsReading?> = _gpsReading.asStateFlow()

    /** null = not yet known (firmware version not read back yet). */
    private val _isNewPmFormat = MutableStateFlow<Boolean?>(null)
    val isNewPmFormat: StateFlow<Boolean?> = _isNewPmFormat.asStateFlow()

    /** Emits every time the device pushes a "new history available" notification. */
    private val _historyReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val historyReady: SharedFlow<Unit> = _historyReady.asSharedFlow()

    private val _commandLogs = MutableStateFlow<List<String>>(emptyList())
    val commandLogs: StateFlow<List<String>> = _commandLogs.asStateFlow()

    private var transport: McuMgrBleTransport? = null
    private var shellManager: ShellManager? = null

    init {
        connectionObserver = object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _connectionState.value = ConnectionState.Connecting
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                _connectionState.value = ConnectionState.Connected
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                _connectionState.value = ConnectionState.Ready
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                _connectionState.value = ConnectionState.Disconnecting
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                _connectionState.value = ConnectionState.Disconnected
                transport?.close()
                transport = null
                shellManager = null
            }
        }
    }

    override fun getMinLogPriority(): Int = Log.WARN

    override fun initialize() {
        requestMtu(PREFERRED_MTU).enqueue()

        setNotificationCallback(dataCharacteristic).with { device, data ->
            val bytes = data.value ?: return@with
            val reading = AtmotubeReading.fromBytes(bytes, device.address)
            _latestReading.value = reading
        }
        enableNotifications(dataCharacteristic).enqueue()

        setNotificationCallback(pmCharacteristic).with { _, data ->
            val bytes = data.value ?: return@with
            // Assume legacy format until the firmware version comes back; readings are re-decoded
            // correctly once fetchFirmwareVersion() resolves.
            val pm = AtmotubeReading.parsePm(bytes, _isNewPmFormat.value ?: false)
            _pmReading.value = pm
        }
        enableNotifications(pmCharacteristic).enqueue()

        gpsCharacteristic?.let { characteristic ->
            setNotificationCallback(characteristic).with { _, data ->
                val bytes = data.value ?: return@with
                _gpsReading.value = AtmotubeGpsReading.fromBytes(bytes)
            }
            enableNotifications(characteristic).enqueue()
        }

        historyCharacteristic?.let { characteristic ->
            setNotificationCallback(characteristic).with { _, _ ->
                _historyReady.tryEmit(Unit)
            }
            enableNotifications(characteristic).enqueue()
        }

        // Initialize McuMgr transport for Shell and History
        bluetoothDevice?.let { device ->
            transport = McuMgrBleTransport(context, device)
            // transport?.initialize() // Not needed/protected
            shellManager = ShellManager(transport!!)
            fetchFirmwareVersion()
        }
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(ATMOTUBE_DATA_SERVICE_UUID)
        if (service != null) {
            dataCharacteristic = service.getCharacteristic(ATMOTUBE_DATA_CHAR_UUID)
            pmCharacteristic = service.getCharacteristic(ATMOTUBE_PM_CHAR_UUID)
            gpsCharacteristic = service.getCharacteristic(ATMOTUBE_GPS_CHAR_UUID)
            historyCharacteristic = service.getCharacteristic(ATMOTUBE_HISTORY_CHAR_UUID)
        }
        return dataCharacteristic != null
    }

    /**
     * PM values are ambiguous without knowing whether the firmware uses the pre-3.0.17 legacy
     * encoding or the newer bit-15-flagged one, so this must run once per connection before PM
     * readings can be trusted.
     */
    private fun fetchFirmwareVersion() {
        val mgr = shellManager ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val response = mgr.exec("version", arrayOf("app"))
                if (response.ret.toInt() == 0) {
                    val fw = response.o.removePrefix("version app").trim()
                    _isNewPmFormat.value = checkFwNew(PM_NEW_FORMAT_FW.first, PM_NEW_FORMAT_FW.second, PM_NEW_FORMAT_FW.third, fw)
                    logCommand("FW: $fw")
                }
            } catch (e: Exception) {
                logCommand("FW read error: ${e.message}")
            }
        }
    }
    
    fun getTransport(): McuMgrTransport? = transport

    fun connectToDevice(device: BluetoothDevice) {
        connect(device)
            .retry(3, 100)
            .useAutoConnect(false)
            .enqueue()
    }

    fun sendShellCommand(command: String) {
        val mgr = shellManager ?: return
        logCommand("Cmd: $command")
        
        scope.launch(Dispatchers.IO) {
            try {
                val parts = command.split(" ")
                val cmd = parts[0]
                val args = if (parts.size > 1) parts.drop(1).toTypedArray() else emptyArray()
                
                val response = mgr.exec(cmd, args)
                if (response.ret.toInt() == 0) {
                     logCommand("Resp: ${response.o}")
                } else {
                     logCommand("Error RC: ${response.ret}")
                }
            } catch (e: Exception) {
                logCommand("Error: ${e.message}")
            }
        }
    }
    
    private fun logCommand(msg: String) {
        val list = _commandLogs.value.toMutableList()
        list.add(0, msg) // Add to top
        if (list.size > 50) list.removeAt(list.lastIndex)
        _commandLogs.value = list
    }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Ready : ConnectionState()
    object Disconnecting : ConnectionState()
}
