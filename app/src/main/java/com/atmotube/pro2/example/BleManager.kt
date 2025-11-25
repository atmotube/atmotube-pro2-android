package com.atmotube.pro2.example

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import io.runtime.mcumgr.McuMgrTransport
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.managers.ShellManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

class AtmotubeBleManager(
    context: Context,
    private val scope: CoroutineScope
) : BleManager(context) {

    companion object {
        val ATMOTUBE_DATA_SERVICE_UUID: UUID = UUID.fromString("BDA3C091-E5E0-4DAC-8170-7FCEF187A1D0")
        val ATMOTUBE_DATA_CHAR_UUID: UUID = UUID.fromString("BDA3C092-E5E0-4DAC-8170-7FCEF187A1D0")
        val ATMOTUBE_PM_CHAR_UUID: UUID = UUID.fromString("BDA3C093-E5E0-4DAC-8170-7FCEF187A1D0")
    }

    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var pmCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latestReading = MutableStateFlow<AtmotubeReading?>(null)
    val latestReading: StateFlow<AtmotubeReading?> = _latestReading.asStateFlow()
    
    private val _pmReading = MutableStateFlow<Triple<Double, Double, Double>?>(null)
    val pmReading: StateFlow<Triple<Double, Double, Double>?> = _pmReading.asStateFlow()

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
        requestMtu(517).enqueue()
        
        setNotificationCallback(dataCharacteristic).with { device, data ->
            val bytes = data.value ?: return@with
            val reading = AtmotubeReading.fromBytes(bytes, device.address)
            _latestReading.value = reading
        }
        enableNotifications(dataCharacteristic).enqueue()
        
        setNotificationCallback(pmCharacteristic).with { _, data ->
            val bytes = data.value ?: return@with
            val pm = AtmotubeReading.parsePm(bytes)
            _pmReading.value = pm
        }
        enableNotifications(pmCharacteristic).enqueue()

        // Initialize McuMgr transport for Shell and History
        bluetoothDevice?.let { device ->
            transport = McuMgrBleTransport(context, device)
            // transport?.initialize() // Not needed/protected
            shellManager = ShellManager(transport!!)
        }
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(ATMOTUBE_DATA_SERVICE_UUID)
        if (service != null) {
            dataCharacteristic = service.getCharacteristic(ATMOTUBE_DATA_CHAR_UUID)
            pmCharacteristic = service.getCharacteristic(ATMOTUBE_PM_CHAR_UUID)
        }
        return dataCharacteristic != null
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
        if (list.size > 50) list.removeLast()
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
