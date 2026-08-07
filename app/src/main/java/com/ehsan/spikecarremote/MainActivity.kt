package com.ehsan.spikecarremote

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import java.nio.charset.StandardCharsets
import java.util.UUID

class MainActivity : Activity(), JoystickView.Listener {

    companion object {
        private const val REQUEST_LOCATION = 100
        private val PYBRICKS_SERVICE =
            UUID.fromString("c5f50001-8280-46da-89f4-6d8051e4aeef")
        private val PYBRICKS_COMMAND =
            UUID.fromString("c5f50002-8280-46da-89f4-6d8051e4aeef")

        // Pybricks GATT command: WRITE_STDIN = 6.
        private const val WRITE_STDIN: Byte = 6
        private const val SEND_INTERVAL_MS = 80L
    }

    private lateinit var status: TextView
    private lateinit var speedText: TextView
    private lateinit var joystick: JoystickView
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val adapter: BluetoothAdapter
        get() = bluetoothManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    private var throttle = 0
    private var steering = 0
    private var lastSentThrottle = 999
    private var lastSentSteering = 999
    private var connected = false

    private val sendRunnable = object : Runnable {
        override fun run() {
            if (connected) {
                sendCommand(force = false)
                mainHandler.postDelayed(this, SEND_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        speedText = findViewById(R.id.speedText)
        joystick = findViewById(R.id.joystick)
        connectButton = findViewById(R.id.connectButton)
        stopButton = findViewById(R.id.stopButton)

        joystick.listener = this

        connectButton.setOnClickListener {
            if (connected) {
                disconnect()
            } else {
                ensurePermissionsAndScan()
            }
        }

        stopButton.setOnClickListener {
            throttle = 0
            steering = 0
            speedText.text = "Throttle: 0%"
            sendCommand(force = true)
        }
    }

    private fun ensurePermissionsAndScan() {
        if (!adapter.isEnabled) {
            status.text = "Please turn Bluetooth on"
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION
            )
            return
        }

        startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startScan()
        } else {
            status.text = "Location permission is required for BLE scanning"
        }
    }

    private fun startScan() {
        stopScan()

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status.text = "BLE scanner unavailable"
            return
        }

        status.text = "Scanning for SPIKE hub..."
        connectButton.text = "SCANNING..."

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(PYBRICKS_SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                stopScan()
                status.text = "Connecting to ${device.name ?: "SPIKE hub"}..."
                bluetoothGatt = device.connectGatt(
                    this@MainActivity,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }

            override fun onScanFailed(errorCode: Int) {
                status.text = "BLE scan failed: $errorCode"
                connectButton.text = "SCAN & CONNECT"
            }
        }

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScan() {
        val cb = scanCallback
        if (cb != null) {
            try {
                scanner?.stopScan(cb)
            } catch (_: SecurityException) {
            }
        }
        scanCallback = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            statusCode: Int,
            newState: Int
        ) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status.text = "Connected. Discovering services..."
                    connected = false
                    gatt.discoverServices()
                } else {
                    connected = false
                    commandCharacteristic = null
                    mainHandler.removeCallbacks(sendRunnable)
                    status.text = "Disconnected"
                    connectButton.text = "SCAN & CONNECT"
                }
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            statusCode: Int
        ) {
            val service = gatt.getService(PYBRICKS_SERVICE)
            val characteristic = service?.getCharacteristic(PYBRICKS_COMMAND)

            runOnUiThread {
                if (statusCode == BluetoothGatt.GATT_SUCCESS &&
                    characteristic != null
                ) {
                    commandCharacteristic = characteristic
                    connected = true
                    status.text = "CONNECTED TO SPIKE"
                    connectButton.text = "DISCONNECT"

                    throttle = 0
                    steering = 0
                    lastSentThrottle = 999
                    lastSentSteering = 999

                    mainHandler.removeCallbacks(sendRunnable)
                    mainHandler.post(sendRunnable)

                    sendCommand(force = true)
                } else {
                    status.text = "Pybricks command service not found"
                    disconnect()
                }
            }
        }
    }

    private fun sendCommand(force: Boolean) {
        val characteristic = commandCharacteristic ?: return
        if (!connected) return

        if (!force &&
            throttle == lastSentThrottle &&
            steering == lastSentSteering
        ) {
            return
        }

        val text = "m,$throttle,$steering\n"
        val payload = byteArrayOf(WRITE_STDIN) +
                text.toByteArray(StandardCharsets.UTF_8)

        // Android 11 supports WRITE_TYPE_DEFAULT for a normal GATT write.
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val gatt = bluetoothGatt ?: return

        try {
            if (payload.size <= 20) {
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
                lastSentThrottle = throttle
                lastSentSteering = steering
            } else {
                runOnUiThread {
                    status.text = "Command packet too large"
                }
            }
        } catch (_: SecurityException) {
            status.text = "Bluetooth permission denied"
        }
    }

    override fun onJoystick(throttle: Int, steering: Int) {
        this.throttle = throttle
        this.steering = steering
        speedText.text = "Throttle: $throttle%   Steering: $steering%"
        sendCommand(force = false)
    }

    override fun onRelease() {
        throttle = 0
        steering = 0
        speedText.text = "Throttle: 0%"
        sendCommand(force = true)
    }

    private fun disconnect() {
        connected = false
        mainHandler.removeCallbacks(sendRunnable)
        stopScan()

        // Best effort stop before closing.
        throttle = 0
        steering = 0
        sendCommand(force = true)

        bluetoothGatt?.close()
        bluetoothGatt = null
        commandCharacteristic = null

        status.text = "Disconnected"
        connectButton.text = "SCAN & CONNECT"
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }
}
