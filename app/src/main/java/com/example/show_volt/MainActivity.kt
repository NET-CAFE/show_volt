
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val arrivalTimeMillis: Long,
    val overlaps: List<OverlapDetail> = emptyList(),
    val relativeStart: Double = 0.0,
    val relativeEnd: Double = 0.0
)

@SuppressLint("MissingPermission")
class MainViewModel : ViewModel() {
    private val _relayHistory = MutableStateFlow<List<DeviceResult>>(emptyList())
    val relayHistory = _relayHistory.asStateFlow()

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState = _connectionState.asStateFlow()

    private val _showPulseChart = MutableStateFlow(false)
    val showPulseChart = _showPulseChart.asStateFlow()

    private var firstEventReferenceTimeMillis: Long? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var chartTimerJob: Job? = null

    fun resetResults() {
        _relayHistory.value = emptyList()
        firstEventReferenceTimeMillis = null
        _showPulseChart.value = false
        chartTimerJob?.cancel()
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

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val dataString = String(value, Charsets.UTF_8).replace("\u0000", "").trim()
            onDataReceived(dataString)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val dataString = String(characteristic.value, Charsets.UTF_8).replace("\u0000", "").trim()
            onDataReceived(dataString)
        }
    }

    private fun onDataReceived(data: String) {
        if (data.isEmpty()) return
        try {
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
                val eventEndTime = currentTime
                val eventStartTime = currentTime - (duration * 1000).toLong()

                if (firstEventReferenceTimeMillis == null) {
                    firstEventReferenceTimeMillis = eventStartTime
                }

                val relStart = (eventStartTime - firstEventReferenceTimeMillis!!) / 1000.0
                val relEnd = (eventEndTime - firstEventReferenceTimeMillis!!) / 1000.0

                _relayHistory.update { history ->
                    val newEndSec = currentTime / 1000.0
                    val newStartSec = newEndSec - duration

                    val overlaps = history.filter { old ->
                        if (old.id == id) return@filter false
                        val oldEnd = old.arrivalTimeMillis / 1000.0
                        val oldStart = oldEnd - old.duration
                        val overlapAmount = max(0.0, min(newEndSec, oldEnd) - max(newStartSec, oldStart))
                        overlapAmount > 0.01 
                    }.map { old ->
                        val oldEnd = old.arrivalTimeMillis / 1000.0
                        val oldStart = oldEnd - old.duration
                        val overlapAmount = max(0.0, min(newEndSec, oldEnd) - max(newStartSec, oldStart))
                        OverlapDetail(old.id, overlapAmount)
                    }

                    val result = DeviceResult(
                        id = id,
                        voltage = voltage,
                        duration = duration,
                        arrivalTimeMillis = currentTime,
                        overlaps = overlaps,
                        relativeStart = relStart,
                        relativeEnd = relEnd
                    )
                    (history + result).takeLast(100)
                }

                // Logic vẽ biểu đồ: Reset timer mỗi khi có data mới
                _showPulseChart.value = false
                chartTimerJob?.cancel()
                chartTimerJob = viewModelScope.launch {
                    delay(10000) // Đợi 10 giây không có tín hiệu mới
                    val currentHistory = _relayHistory.value
                    if (currentHistory.isNotEmpty() && currentHistory.none { it.overlaps.isNotEmpty() }) {
                        _showPulseChart.value = true
                    }
                }
            }
        } catch (e: Exception) { 
            Log.e("BLE_DATA", "Parse Error: $data")
        }
    }

    fun disconnectAndClose() { bluetoothGatt?.disconnect() }
}

@Composable
fun EspDataScreen(viewModel: MainViewModel) {
    val relayHistory by viewModel.relayHistory.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val showPulseChart by viewModel.showPulseChart.collectAsState()
    val context = LocalContext.current

    EspDataContent(
        relayHistory = relayHistory,
        connectionState = connectionState,
        showPulseChart = showPulseChart,
        onStartScan = { viewModel.startScan(context) },
        onResetResults = { viewModel.resetResults() }
    )
}

@Composable
fun EspDataContent(
    relayHistory: List<DeviceResult>,
    connectionState: String,
    showPulseChart: Boolean,
    onStartScan: () -> Unit,
    onResetResults: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Relay Timing & Collision Monitor", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Status: $connectionState", fontSize = 14.sp, color = Color.Gray)
        
        Spacer(Modifier.height(16.dp))

        if (showPulseChart) {
            PulseChart(relayHistory)
            Spacer(Modifier.height(16.dp))
        }

        // PHẦN TRÊN: DANH SÁCH THỨ TỰ CÓ ĐIỆN (Sequence of Events)
        Text("Trình tự có điện (Common Clock)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Divider(Modifier.padding(vertical = 8.dp))
        
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(relayHistory) { res ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (res.overlaps.isNotEmpty()) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(1.dp, if (res.overlaps.isNotEmpty()) Color.Red else Color.Transparent)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ESP-${res.id}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (res.overlaps.isNotEmpty()) {
                                Text("BỊ TRÙNG", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            } else {
                                Text("OK", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Thời gian: %.3f - %.3f (s)".format(Locale.US, res.relativeStart, res.relativeEnd),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Điện áp: %.4f V | Thời gian duy trì: %.4f s".format(Locale.US, res.voltage, res.duration),
                            fontSize = 13.sp,
                            color = Color.DarkGray
                        )
                        if (res.overlaps.isNotEmpty()) {
                            res.overlaps.forEach { overlap ->
                                Text(
                                    "-> Trùng with ESP-${overlap.id}: %.3fs".format(Locale.US, overlap.duration),
                                    color = Color.Red,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // CÁC NÚT BẤM
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = onStartScan) { Text("Start Scan") }
            OutlinedButton(onClick = onResetResults) { Text("Clear All") }
        }
    }
}

@Composable
fun PulseChart(history: List<DeviceResult>, modifier: Modifier = Modifier) {
    if (history.isEmpty()) return
    
    val textMeasurer = rememberTextMeasurer()
    
    // Tính toán dải thời gian hiển thị (Trục hoành)
    val minStart = history.minOf { it.relativeStart }
    val maxEnd = history.maxOf { it.relativeEnd }
    val totalTime = max(1.0, maxEnd - minStart)
    
    val paddingX = totalTime * 0.1 // 10% padding ngang
    val displayMinX = minStart - paddingX
    val displayMaxX = maxEnd + paddingX
    val displayDuration = displayMaxX - displayMinX

    // Tính toán dải điện áp (Trục tung)
    val maxVoltData = history.maxOfOrNull { it.voltage } ?: 0.0
    val displayMaxY = maxOf(maxVoltData * 1.3, 14.0)

    Card(
        modifier = modifier.fillMaxWidth().height(280.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color.LightGray)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Biểu đồ xung điện áp (Voltage vs Time)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val labelHeight = 20.dp.toPx()
                val bottomAxisHeight = 45.dp.toPx() // Tăng không gian để vẽ text nghiêng
                val leftAxisWidth = 35.dp.toPx()
                
                val chartWidth = width - leftAxisWidth
                val chartHeight = height - bottomAxisHeight - labelHeight
                
                val baselineY = height - bottomAxisHeight

                // 1. Vẽ trục tọa độ
                drawLine(Color.Gray, Offset(leftAxisWidth, labelHeight), Offset(leftAxisWidth, baselineY), strokeWidth = 2f)
                drawLine(Color.Gray, Offset(leftAxisWidth, baselineY), Offset(width, baselineY), strokeWidth = 2f)

                // 2. Vẽ các vạch chia điện áp (0V, 5V, 10V)
                val voltTicks = listOf(0.0, 5.0, 10.0)
                voltTicks.forEach { v ->
                    val y = baselineY - (v / displayMaxY).toFloat() * chartHeight
                    drawLine(Color(0xFFEEEEEE), Offset(leftAxisWidth, y), Offset(width, y), strokeWidth = 1f)
                    
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${v.toInt()}V",
                        topLeft = Offset(5.dp.toPx(), y - 7.dp.toPx()),
                        style = TextStyle(fontSize = 10.sp, color = Color.Gray)
                    )
                }

                // 3. Vẽ các xung
                history.forEach { res ->
                    val startX = leftAxisWidth + ((res.relativeStart - displayMinX) / displayDuration).toFloat() * chartWidth
                    val endX = leftAxisWidth + ((res.relativeEnd - displayMinX) / displayDuration).toFloat() * chartWidth
                    val pulseWidth = max(4f, endX - startX)
                    
                    val pulseHeight = (res.voltage / displayMaxY).toFloat() * chartHeight
                    val topY = baselineY - pulseHeight

                    // Vẽ thân xung (Hình chữ nhật)
                    drawRect(
                        color = Color(0xFF2196F3).copy(alpha = 0.7f),
                        topLeft = Offset(startX, topY),
                        size = Size(pulseWidth, pulseHeight)
                    )
                    
                    // Vẽ viền xung
                    drawRect(
                        color = Color(0xFF1976D2),
                        topLeft = Offset(startX, topY),
                        size = Size(pulseWidth, pulseHeight),
                        style = Stroke(width = 2f)
                    )

                    // 4. Vẽ tên ESP trên đầu mỗi xung
                    val labelText = "ESP-${res.id}"
                    val textLayoutResult = textMeasurer.measure(labelText, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(startX + (pulseWidth - textLayoutResult.size.width) / 2, topY - textLayoutResult.size.height - 4f),
                        color = Color.DarkGray
                    )

                    // 5. Vẽ thời gian bắt đầu (relativeStart) nghiêng 45 độ ở dưới trục hoành
                    val startTimeText = "%.3fs".format(Locale.US, res.relativeStart)
                    val startTimeLayout = textMeasurer.measure(startTimeText, style = TextStyle(fontSize = 9.sp))
                    
                    val textPivotX = startX
                    val textPivotY = baselineY + 5.dp.toPx()
                    
                    rotate(degrees = 45f, pivot = Offset(textPivotX, textPivotY)) {
                        drawText(
                            textLayoutResult = startTimeLayout,
                            topLeft = Offset(textPivotX, textPivotY),
                            color = Color.Gray
                        )
                    }
                }
                
                // Nhãn trục hoành (Time) đưa ra ngoài cùng bên phải, sát đáy canvas để tránh chồng lấn
                val timeLabelText = "Time (s)"
                val timeLayout = textMeasurer.measure(timeLabelText, style = TextStyle(fontSize = 10.sp, color = Color.Gray))
                drawText(
                    textLayoutResult = timeLayout,
                    topLeft = Offset(width - timeLayout.size.width, height - timeLayout.size.height),
                )
            }
        }
    }
}

@Composable
fun ShowVoltTheme(content: @Composable () -> Unit) { MaterialTheme { content() } }

@Preview(showBackground = true)
@Composable
fun EspDataScreenPreview() {
    ShowVoltTheme {
        val sampleHistory = listOf(
            DeviceResult(1, 11.0, 1.0, 1000L, emptyList(), 0.0, 1.0),
            DeviceResult(2, 5.0, 1.2, 2500L, emptyList(), 1.5, 2.7),
            DeviceResult(3, 10.5, 0.8, 4500L, emptyList(), 3.5, 4.3),
            DeviceResult(4, 3.1, 1.5, 6500L, emptyList(), 5.5, 7.0),
            DeviceResult(5, 11.2, 0.9, 8500L, emptyList(), 7.5, 8.4)
        )
        EspDataContent(
            relayHistory = sampleHistory,
            connectionState = "Connected",
            showPulseChart = true,
            onStartScan = {},
            onResetResults = {}
        )
    }
}
