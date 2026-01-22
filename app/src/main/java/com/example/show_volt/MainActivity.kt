
package com.example.show_volt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// Constants from your ESP32 code
private const val TARGET_DEVICE_NAME = "ESP32_BLE_SERVER"
private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // Client Characteristic Configuration Descriptor

@SuppressLint("MissingPermission") // Permissions are checked before calling relevant methods
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        setContent {
            ShowVoltTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EspDataScreen(viewModel)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.disconnectAndClose()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val permissionsNotGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNotGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNotGranted.toTypedArray(), 101)
        }
    }
}

data class EspDeviceState(val averageVoltage: Float = 0f, val lastTime: Float = 0f)

@SuppressLint("MissingPermission")
class MainViewModel : ViewModel() {
    private val _espDevices = MutableStateFlow<Map<Int, EspDeviceState>>(emptyMap())
    val espDevices = _espDevices.asStateFlow()

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState = _connectionState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var applicationContext: Context? = null


    fun startScan(context: Context) {
        applicationContext = context.applicationContext
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        if (bleScanner == null) {
            _connectionState.value = "BLE Not Supported"
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setDeviceName(TARGET_DEVICE_NAME)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _connectionState.value = "Scanning..."
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            _connectionState.value = "Device Found, Connecting..."
            applicationContext?.let {
                result.device.connectGatt(it, false, gattCallback)
            } ?: run {
                 _connectionState.value = "Error: Context not available"
                 Log.e("BLE", "Cannot connect, context is null")
            }
            bleScanner?.stopScan(this)
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = "Scan Failed: Code $errorCode"
            Log.e("BLE", "Scan Failed with code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            viewModelScope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = "Connected, Discovering Services..."
                    bluetoothGatt = gatt
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectionState.value = "Disconnected"
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Services Discovered")
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    Log.d("BLE", "Enabling notifications...")
                } else {
                     Log.e("BLE", "Characteristic not found")
                }
            } else {
                Log.w("BLE", "onServicesDiscovered received: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                 Log.d("BLE", "Descriptor write successful. Now listening for data.")
                 viewModelScope.launch { _connectionState.value = "Listening for data..." }
            } else {
                 Log.e("BLE", "Descriptor write failed: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val dataString = String(value, Charsets.UTF_8)
                Log.d("BLE", "Received: $dataString")
                onDataReceived(dataString)
            }
        }
        
        @Deprecated("Used for older API levels")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onCharacteristicChanged(gatt, characteristic, characteristic.value)
            }
        }
    }

    private fun onDataReceived(data: String) {
        // "ID=1,VIN=11.2,TIME=123.4"
        try {
            val parts = data.split(",").associate { 
                val pair = it.split("=")
                if (pair.size == 2) pair[0] to pair[1] else pair[0] to ""
            }
            val id = parts["ID"]?.toIntOrNull()
            val vin = parts["VIN"]?.toFloatOrNull()
            val time = parts["TIME"]?.toFloatOrNull()

            if (id != null && vin != null && time != null) {
                viewModelScope.launch {
                    _espDevices.value = _espDevices.value.toMutableMap().apply {
                        this[id] = EspDeviceState(vin, time)
                    }
                }
            } else {
                 Log.e("BLE", "Parsed data contains null values. Raw: $data")
            }
        } catch (e: Exception) {
            Log.e("BLE", "Failed to parse data: $data", e)
        }
    }

    fun disconnectAndClose() {
        bluetoothGatt?.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        disconnectAndClose()
    }
}

@Composable
fun EspDataScreen(viewModel: MainViewModel) {
    val espDevices by viewModel.espDevices.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ESP32 BLE Data", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        (1..5).forEach { id ->
            val deviceState = espDevices[id]
            val text = if (deviceState != null) {
                "ESP-%d: %.2f V, %.2f s".format(id, deviceState.averageVoltage, deviceState.lastTime)
            } else {
                "ESP-%d: -- V, -- s".format(id)
            }
            Text(text, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))

        Text(connectionState, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { viewModel.startScan(context) }) {
            Text("Start Scan")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    ShowVoltTheme {
        EspDataScreen(MainViewModel())
    }
}

@Composable
fun ShowVoltTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}
