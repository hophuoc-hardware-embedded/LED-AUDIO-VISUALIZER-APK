package com.example.ledfxcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.ledfxcontroller.databinding.ActivityMainBinding
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readThread: Thread? = null

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isLongPressing = false
    private val ledColors = IntArray(60) { 0xFF000000.toInt() }
    private var lastUiUpdateTime = 0L
    @Volatile private var isReadingData = false
    @Volatile private var resetUntilTime = 0L

    // ===== LƯU THIẾT BỊ ĐÃ KẾT NỐI ĐỂ AUTO RECONNECT =====
    private var lastConnectedDevice: BluetoothDevice? = null
    private var isAutoReconnecting = false

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val LED_DATA_HEADER: Byte = 0xAA.toByte()
        private const val LED_DATA_FOOTER: Byte = 0x55.toByte()
        private const val LED_COUNT = 60
        private const val BYTES_PER_LED = 3
        private const val PACKET_SIZE = 183
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUIListeners()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIListeners() {
        binding.buttonConnect.setOnClickListener {
            if (bluetoothSocket?.isConnected == true) {
                // Ngắt thủ công - xóa device đã nhớ
                lastConnectedDevice = null
                disconnectBluetooth()
            } else {
                checkPermissionsAndConnect()
            }
        }

        binding.buttonEffect1.setOnClickListener {
            sendCommandWithReconnect("#1;")
            binding.beatTriggerLayout.visibility = View.GONE
        }
        binding.buttonEffect2.setOnClickListener {
            sendCommandWithReconnect("#2;")
            binding.beatTriggerLayout.visibility = View.VISIBLE
        }
        binding.buttonEffect3.setOnClickListener {
            sendCommandWithReconnect("#3;")
            binding.beatTriggerLayout.visibility = View.GONE
        }
        binding.buttonEffect4.setOnClickListener {
            sendCommandWithReconnect("#4;")
            binding.beatTriggerLayout.visibility = View.GONE
        }

        // ===== NÚT RE-CALIBRATE MỚI =====
        binding.buttonRecalibrate.setOnClickListener {
            sendRecalibrateCommand()
        }

        binding.buttonBeatTrigger.setOnClickListener {
            sendCommand("#B;")
        }

        binding.buttonBeatTrigger.setOnLongClickListener {
            isLongPressing = true
            sendCommand("#R;")
            longPressHandler.postDelayed(longPressRunnable, 2000)
            true
        }

        binding.buttonBeatTrigger.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isLongPressing = false
                longPressHandler.removeCallbacks(longPressRunnable)
            }
            false
        }
    }

    // ===== HÀM MỚI: GỬI LỆNH VÀ TỰ ĐỘNG RECONNECT =====
    private fun sendCommandWithReconnect(command: String) {
        if (lastConnectedDevice == null) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        // Lưu device trước khi ngắt
        val deviceToReconnect = lastConnectedDevice

        thread {
            try {
                // 1. Gửi lệnh trước
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                Thread.sleep(50)  // Đợi lệnh gửi xong

                // 2. Ngắt kết nối
                disconnectBluetoothSilently()

                // 3. Đợi một chút để Bluetooth cleanup
                Thread.sleep(200)

                // 4. Kết nối lại
                deviceToReconnect?.let { device ->
                    isAutoReconnecting = true
                    uiHandler.post {
                        binding.textViewStatus.text = "Đang làm mới kết nối..."
                    }
                    connectToDevice(device)
                }

            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(this, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    // Vẫn thử reconnect
                    deviceToReconnect?.let { connectToDevice(it) }
                }
            }
        }
    }

    private val longPressRunnable = object : Runnable {
        override fun run() {
            if (isLongPressing) {
                sendCommand("#R;")
                longPressHandler.postDelayed(this, 2000)
            }
        }
    }

    private fun checkPermissionsAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestBluetoothConnectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                showPairedDevicesDialog()
            }
        } else {
            showPairedDevicesDialog()
        }
    }

    private val requestBluetoothConnectPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showPairedDevicesDialog()
            else Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show()
        }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog() {
        if (bluetoothAdapter?.isEnabled == false) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            Toast.makeText(this, R.string.please_enable_bluetooth, Toast.LENGTH_SHORT).show()
            return
        }

        val devices = bluetoothAdapter?.bondedDevices ?: return
        val list = devices.map { "${it.name}\n${it.address}" }.toTypedArray()

        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.select_device)
            .setItems(list) { _, which ->
                val addr = list[which].substringAfter("\n")
                val device = bluetoothAdapter!!.getRemoteDevice(addr)
                lastConnectedDevice = device  // Lưu device
                connectToDevice(device)
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        thread {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

                uiHandler.post {
                    if (isAutoReconnecting) {
                        binding.textViewStatus.text = "Đang kết nối lại..."
                    } else {
                        binding.textViewStatus.text = getString(R.string.status_connecting_to, device.name)
                    }
                }

                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                inputStream = bluetoothSocket?.inputStream

                uiHandler.post {
                    binding.textViewStatus.text = getString(R.string.status_connected_to, device.name)
                    binding.buttonConnect.text = getString(R.string.disconnect)

                    if (!isAutoReconnecting) {
                        Toast.makeText(this, R.string.connection_success, Toast.LENGTH_SHORT).show()
                    }
                }

                // Clear buffer
                Thread.sleep(100)
                val available = inputStream?.available() ?: 0
                if (available > 0) {
                    inputStream?.skip(available.toLong())
                }

                // Lưu device thành công
                lastConnectedDevice = device
                isAutoReconnecting = false

                startReadingLEDData()

            } catch (e: IOException) {
                uiHandler.post {
                    binding.textViewStatus.text = getString(R.string.status_not_connected)
                    if (!isAutoReconnecting) {
                        Toast.makeText(this, R.string.connection_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Không thể kết nối lại", Toast.LENGTH_SHORT).show()
                    }
                }
                isAutoReconnecting = false
                disconnectBluetooth()
            }
        }
    }

    private fun sendCommand(command: String) {
        if (outputStream == null) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            try {
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                resetUntilTime = System.currentTimeMillis() + 1200
            } catch (e: IOException) {
                uiHandler.post {
                    Toast.makeText(this, R.string.command_send_error, Toast.LENGTH_SHORT).show()
                    disconnectBluetooth()
                }
            }
        }
    }

    // ===== HÀM MỚI: GỬI LỆNH RE-CALIBRATE =====
    private fun sendRecalibrateCommand() {
        if (outputStream == null) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        // Hiển thị dialog xác nhận
        AlertDialog.Builder(this)
            .setTitle("🎯 Hiệu Chỉnh Cảm Biến")
            .setMessage("Hệ thống sẽ đọc 200 mẫu âm thanh trong 2 giây để tự động điều chỉnh ngưỡng phát hiện beat.\n\n⚠️ Vui lòng giữ YÊN LẶNG trong quá trình hiệu chỉnh.")
            .setPositiveButton("Bắt Đầu") { _, _ ->
                thread {
                    try {
                        outputStream?.write("#C;".toByteArray())
                        outputStream?.flush()

                        uiHandler.post {
                            Toast.makeText(this, R.string.recalibrate_success, Toast.LENGTH_LONG).show()

                            // Hiển thị progress notification
                            showRecalibrateProgress()
                        }
                    } catch (e: IOException) {
                        uiHandler.post {
                            Toast.makeText(this, R.string.command_send_error, Toast.LENGTH_SHORT).show()
                            disconnectBluetooth()
                        }
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Hiển thị tiến trình re-calibrate
    private fun showRecalibrateProgress() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("⏳ Đang Hiệu Chỉnh...")
            .setMessage("Vui lòng giữ yên lặng.\n\nQuá trình sẽ hoàn tất sau 2 giây.")
            .setCancelable(false)
            .create()

        progressDialog.show()

        // Auto dismiss sau 2.5 giây
        uiHandler.postDelayed({
            progressDialog.dismiss()
            Toast.makeText(this, "✅ Hiệu chỉnh hoàn tất!", Toast.LENGTH_SHORT).show()
        }, 2500)
    }

    private fun startReadingLEDData() {
        isReadingData = true
        readThread = thread(name = "BT-Reader", priority = Thread.MAX_PRIORITY) {

            val readBuffer = ByteArray(2048)
            val packetBuffer = ByteArray(PACKET_SIZE)
            var packetIndex = 0
            var foundHeader = false
            var headerByte1Found = false

            try {
                while (isReadingData && inputStream != null) {

                    if (System.currentTimeMillis() < resetUntilTime) {
                        val avail = inputStream?.available() ?: 0
                        if (avail > 0) inputStream?.skip(avail.toLong())
                        packetIndex = 0
                        foundHeader = false
                        headerByte1Found = false
                        Thread.sleep(2)
                        continue
                    }

                    val available = inputStream?.available() ?: 0

                    if (available > 1500) {
                        inputStream?.skip(available.toLong())
                        packetIndex = 0
                        foundHeader = false
                        continue
                    }

                    if (available > 0) {
                        val toRead = minOf(available, readBuffer.size)
                        val bytesRead = inputStream?.read(readBuffer, 0, toRead) ?: 0

                        for (i in 0 until bytesRead) {
                            val byte = readBuffer[i]

                            if (!foundHeader) {
                                if (byte == LED_DATA_HEADER) {
                                    if (!headerByte1Found) {
                                        headerByte1Found = true
                                    } else {
                                        foundHeader = true
                                        packetBuffer[0] = LED_DATA_HEADER
                                        packetBuffer[1] = LED_DATA_HEADER
                                        packetIndex = 2
                                        headerByte1Found = false
                                    }
                                } else headerByte1Found = false
                            } else {
                                if (packetIndex < PACKET_SIZE) {
                                    packetBuffer[packetIndex++] = byte
                                }
                                if (packetIndex == PACKET_SIZE) {
                                    if (packetBuffer[PACKET_SIZE - 1] == LED_DATA_FOOTER) {
                                        parseLEDData(packetBuffer)
                                    }
                                    foundHeader = false
                                    packetIndex = 0
                                }
                            }
                        }
                    } else {
                        Thread.sleep(2)
                    }
                }
            } catch (_: IOException) {
                uiHandler.post { disconnectBluetooth() }
            } catch (_: InterruptedException) {}
        }
    }

    private fun parseLEDData(packet: ByteArray) {
        for (i in 0 until LED_COUNT) {
            val offset = 2 + i * BYTES_PER_LED
            val r = packet[offset].toInt() and 0xFF
            val g = packet[offset + 1].toInt() and 0xFF
            val b = packet[offset + 2].toInt() and 0xFF
            ledColors[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val now = System.currentTimeMillis()
        if (now - lastUiUpdateTime > 30) {
            lastUiUpdateTime = now
            uiHandler.post {
                binding.ledVisualizerView.updateLEDs(ledColors)
            }
        }
    }

    // ===== NGẮT KẾT NỐI KHÔNG HIỂN THỊ TOAST =====
    private fun disconnectBluetoothSilently() {
        isReadingData = false
        readThread?.interrupt()

        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (_: Exception) {}

        inputStream = null
        outputStream = null
        bluetoothSocket = null
        readThread = null
    }

    private fun disconnectBluetooth() {
        disconnectBluetoothSilently()

        runOnUiThread {
            binding.textViewStatus.text = getString(R.string.status_not_connected)
            binding.buttonConnect.text = getString(R.string.connect)
            ledColors.fill(0xFF000000.toInt())
            binding.ledVisualizerView.updateLEDs(ledColors)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lastConnectedDevice = null
        disconnectBluetooth()
        longPressHandler.removeCallbacks(longPressRunnable)
    }
}