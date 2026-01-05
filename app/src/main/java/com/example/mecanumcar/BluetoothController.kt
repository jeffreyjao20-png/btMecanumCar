package com.example.mecanumcar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.util.*

class BluetoothController(
    private val context: Context,
    private val onStatusUpdate: (String) -> Unit,
    private val onDeviceConnected: () -> Unit,
    private val onDeviceDisconnected: () -> Unit
) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectedDevice: BluetoothDevice? = null

    // Arduino HC-05/HC-06 通常使用的 UUID
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            onStatusUpdate("錯誤: 沒有藍牙連線權限")
            return
        }

        coroutineScope.launch {
            try {
                // 關閉之前的連線
                disconnect()

                withContext(Dispatchers.Main) {
                    onStatusUpdate("正在連線至 ${device.name}...")
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothSocket?.connect() // 這是一個阻塞操作

                outputStream = bluetoothSocket?.outputStream
                connectedDevice = device

                withContext(Dispatchers.Main) {
                    onStatusUpdate("已連線至 ${device.name}")
                    onDeviceConnected()
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("連線失敗: ${e.message}")
                    onDeviceDisconnected()
                }
                disconnect()
            } catch (e: SecurityException){
                withContext(Dispatchers.Main) {
                    onStatusUpdate("連線失敗: 權限不足")
                    onDeviceDisconnected()
                }
                disconnect()
            }
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            // 忽略關閉時的錯誤
        } finally {
            outputStream = null
            bluetoothSocket = null
            connectedDevice = null
            coroutineScope.launch(Dispatchers.Main) {
                onDeviceDisconnected()
            }
        }
    }

    fun sendCommand(command: Char) {
        if (outputStream == null) {
            // 如果未連線，可以選擇性地顯示提示
            // onStatusUpdate("無法發送指令: 未連線")
            return
        }
        coroutineScope.launch {
            try {
                outputStream?.write(command.code)
                outputStream?.flush() // 確保資料立刻發送
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("傳送失敗: ${e.message}")
                    disconnect()
                }
            }
        }
    }

    fun isConnected(): Boolean = bluetoothSocket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice>? {
        if (!hasConnectPermission()) {
            onStatusUpdate("錯誤: 沒有藍牙權限")
            return null
        }
        return bluetoothAdapter?.bondedDevices
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 11 或以下版本, 權限在 Manifest 中靜態聲明
            true
        }
    }
}
