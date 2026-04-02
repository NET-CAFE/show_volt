
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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

// Constants
private const val TARGET_DEVICE_NAME = "ESP32_CENTRAL_GATEWAY"
private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        setContent {
            ShowVoltTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

data class OverlapDetail(
    val id: Int,
    val duration: Double
)

data class DeviceResult(
    val id: Int,
    val voltage: Double,
    val duration: Double,
    val arrivalTimeMillis: Long = System.currentTimeMillis(),
    val overlaps: List<OverlapDetail> = emptyList()
)

@SuppressLint("MissingPermission")
class MainViewModel : ViewModel() {
    private val _espDevices = MutableStateFlow<Map<Int, DeviceResult>>(emptyMap())
    val espDevices = _espDevices.asStateFlow()

    private val _relayHistory = MutableStateFlow<List<DeviceResult>>(emptyList())
    val relayHistory = _relayHistory.asStateFlow()

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState = _connectionState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null

    fun resetResults() {
        _espDevices.value = emptyMap()
        _relayHistory.value = emptyList()
    }

    fun startScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleScanner = bluetoothManager.adapter.bluetoothLeScanner
        if (bleScanner == null) {
            _connectionState.value = "BLE Not Supported"
            return
        }
        val scanFilter = ScanFilter.Builder().setDeviceName(TARGET_DEVICE_NAME).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        _connectionState.value = "Scanning..."
        
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                _connectionState.value = "Connecting..."
                Log.d("BLE_CHECK", "Found device: ${result.device.name}. Connecting...")
                result.device.connectGatt(context, false, gattCallback)
                bleScanner?.stopScan(this)
            }
        }
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                Log.d("BLE_CHECK", "GATT Connected. Discovering services...")
                gatt.requestMtu(512)
                _connectionState.value = "Connected"
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE_CHECK", "GATT Disconnected.")
                _connectionState.value = "Disconnected"
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d("BLE_CHECK", "MTU changed to $mtu. Discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                Log.d("BLE_CHECK", "Found Characteristic. Enabling notifications...")
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                Log.e("BLE_CHECK", "Target Characteristic NOT FOUND!")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_CHECK", "Notifications ENABLED successfully. Waiting for data...")
            } else {
                Log.e("BLE_CHECK", "Failed to enable notifications. Status: $status")
            }
        }

        // For Android 13+
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val dataString = String(value, Charsets.UTF_8).replace("\u0000", "").trim()
            Log.d("BLE_DATA", "Received (New API): $dataString")
            onDataReceived(dataString)
        }

        // For Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val dataString = String(characteristic.value, Charsets.UTF_8).replace("\u0000", "").trim()
            Log.d("BLE_DATA", "Received (Old API): $dataString")
            onDataReceived(dataString)
        }
    }

    private fun onDataReceived(data: String) {
        if (data.isEmpty()) return
        try {
            // Tách dữ liệu linh hoạt (ID:1 hoặc ID=1)
            val parts = data.split(",").associate {
                val kv = if (it.contains(":")) it.split(":") else it.split("=")
                if (kv.size >= 2) kv[0].trim().uppercase() to kv[1].trim()
                else "" to ""
            }
            
            val id = parts["ID"]?.toIntOrNull()
            val voltage = parts["V"]?.toDoubleOrNull()
            val duration = parts["T"]?.toDoubleOrNull()

            if (id != null && voltage != null && duration != null) {
                val currentTime = System.currentTimeMillis()
                _relayHistory.update { history ->
                    val newStart = (currentTime / 1000.0) - duration
                    val newEnd = currentTime / 1000.0
                    
                    val overlaps = history.filter { old ->
                        val oldStart = (old.arrivalTimeMillis / 1000.0) - old.duration
                        val oldEnd = old.arrivalTimeMillis / 1000.0
                        val overlapAmount = max(0.0, min(newEnd, oldEnd) - max(newStart, oldStart))
                        overlapAmount > 0.01 
                    }.map { old ->
                        val oldStart = (old.arrivalTimeMillis / 1000.0) - old.duration
                        val oldEnd = old.arrivalTimeMillis / 1000.0
                        val overlapAmount = max(0.0, min(newEnd, oldEnd) - max(newStart, oldStart))
                        OverlapDetail(old.id, overlapAmount)
                    }

                    val result = DeviceResult(id, voltage, duration, currentTime, overlaps)
                    _espDevices.update { it + (id to result) }
                    (history + result).takeLast(50)
                }
            }
        } catch (e: Exception) { 
            Log.e("BLE_DATA", "Parse Error: $data | ${e.message}")
        }
    }

    fun disconnectAndClose() { bluetoothGatt?.disconnect() }
}

@Composable
fun EspDataScreen(viewModel: MainViewModel) {
    val espDevices by viewModel.espDevices.collectAsState()
    val relayHistory by viewModel.relayHistory.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val context = LocalContext.current

    EspDataContent(
        espDevices = espDevices,
        relayHistory = relayHistory,
        connectionState = connectionState,
        onStartScan = { viewModel.startScan(context) },
        onResetResults = { viewModel.resetResults() }
    )
}

@Composable
fun EspDataContent(
    espDevices: Map<Int, DeviceResult>,
    relayHistory: List<DeviceResult>,
    connectionState: String,
    onStartScan: () -> Unit,
    onResetResults: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Relay Timing & Collision Monitor", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Status: $connectionState", fontSize = 14.sp, color = Color.Gray)
        
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.weight(1.2f)) {
                Text("Trạng thái ESP", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Divider(Modifier.padding(vertical = 8.dp))
                (1..5).forEach { id ->
                    val res = espDevices[id]
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (res?.overlaps?.isNotEmpty() == true) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("ESP-$id", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                if (res != null) {
                                    if (res.overlaps.isEmpty()) {
                                        Text("OK", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    } else {
                                        Text("BỊ TRÙNG", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (res != null) {
                                Text(
                                    text = "%.4f V | %.4f s".format(Locale.US, res.voltage, res.duration),
                                    fontSize = 13.sp
                                )
                                if (res.overlaps.isNotEmpty()) {
                                    res.overlaps.forEach { overlap ->
                                        Text(
                                            "Trùng với ESP-${overlap.id}: %.3fs".format(Locale.US, overlap.duration),
                                            color = Color.Red,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else {
                                Text("Chưa có dữ liệu", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(0.8f)) {
                Text("Lịch sử trùng", fontWeight = FontWeight.Bold, color = Color.Red)
                Divider(Modifier.padding(vertical = 8.dp))
                LazyColumn {
                    val collisions = relayHistory.filter { it.overlaps.isNotEmpty() }.reversed()
                    if (collisions.isEmpty()) {
                        item { Text("Chưa có trùng chập", fontSize = 12.sp, color = Color.Gray) }
                    }
                    items(collisions) { res ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text("ESP-${res.id}", fontWeight = FontWeight.Bold, color = Color.Red, fontSize = 12.sp)
                                res.overlaps.forEach { o ->
                                    Text("với ESP-${o.id}: %.3fs".format(Locale.US, o.duration), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = onStartScan) { Text("Start Scan") }
            OutlinedButton(onClick = onResetResults) { Text("Clear All") }
        }
    }
}

@Composable
fun ShowVoltTheme(content: @Composable () -> Unit) { MaterialTheme { content() } }

@Preview(showBackground = true)
@Composable
fun EspDataScreenPreview() {
    ShowVoltTheme {
        val sampleDevices = mapOf(
            1 to DeviceResult(1, 3.3210, 0.0500),
            2 to DeviceResult(2, 3.2890, 0.0450, System.currentTimeMillis(), listOf(OverlapDetail(1, 0.025)))
        )
        val sampleHistory = listOf(
            DeviceResult(1, 3.3210, 0.0500, System.currentTimeMillis() - 1000),
            DeviceResult(2, 3.2890, 0.0450, System.currentTimeMillis(), listOf(OverlapDetail(1, 0.025)))
        )
        EspDataContent(
            espDevices = sampleDevices,
            relayHistory = sampleHistory,
            connectionState = "Connected",
            onStartScan = {},
            onResetResults = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ShowVoltThemePreview() {
    ShowVoltTheme {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("ShowVolt Theme Preview Content")
        }
    }
}
