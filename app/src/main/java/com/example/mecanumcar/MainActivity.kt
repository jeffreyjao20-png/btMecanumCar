package com.example.mecanumcar

// 確保所有需要的 import 都已包含
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("ClickableViewAccessibility")
class MainActivity : AppCompatActivity() {

    // --- UI 元件 ---
    private lateinit var btnBluetooth: Button
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var spinnerPairedDevices: Spinner

    // --- 藍牙相關 ---
    private lateinit var bluetoothController: BluetoothController
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private lateinit var devicesAdapter: ArrayAdapter<String>

    // --- 邏輯控制 ---
    private val pressedMoveButtons = AtomicInteger(0)

    // --- Activity Result Launchers ---
    private lateinit var requestBluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var enableBluetoothLauncher: ActivityResultLauncher<Intent>

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeLaunchers()
        initializeBluetoothController()
        bindUI()
        setupButtonListeners()
        checkAndRequestPermissions()
    }

    private fun initializeLaunchers() {
        enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "藍牙已啟用", Toast.LENGTH_SHORT).show()
                refreshPairedDevices()
            } else {
                Toast.makeText(this, "您取消了啟用藍牙", Toast.LENGTH_SHORT).show()
            }
        }

        requestBluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "權限已授予", Toast.LENGTH_SHORT).show()
                refreshPairedDevices()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    private fun initializeBluetoothController() {
        bluetoothController = BluetoothController(
            context = this,
            onStatusUpdate = { status -> tvBluetoothStatus.text = "狀態: $status" },
            onDeviceConnected = {
                btnBluetooth.text = "斷開連線"
                // 修正點 1: 明確指定 this@MainActivity
                this@MainActivity.spinnerPairedDevices.isEnabled = false // 連線後禁止切換裝置
                Toast.makeText(this, "連線成功", Toast.LENGTH_SHORT).show()
            },
            onDeviceDisconnected = {
                btnBluetooth.text = "掃描裝置"
                // 修正點 2: 明確指定 this@MainActivity
                this@MainActivity.spinnerPairedDevices.isEnabled = true // 斷線後允許切換裝置
                tvBluetoothStatus.text = "狀態: 未連線"
            }
        )
    }

    private fun bindUI() {
        btnBluetooth = findViewById(R.id.btnBluetooth)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        spinnerPairedDevices = findViewById(R.id.spinnerPairedDevices)

        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("請先掃描裝置"))
        devicesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPairedDevices.adapter = devicesAdapter
    }

    private fun setupButtonListeners() {
        btnBluetooth.setOnClickListener { onBluetoothButtonClicked() }

        spinnerPairedDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (pairedDevices.isNotEmpty() && position < pairedDevices.size) {
                    // 只有在未連線時才發起新連線
                    if (!bluetoothController.isConnected()) {
                        val device = pairedDevices[position]
                        bluetoothController.connectToDevice(device)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* 不需處理 */ }
        }

        // 設定移動和旋轉按鈕的觸摸監聽器
        setupDirectionalButton(findViewById(R.id.btnForward), 'F')
        setupDirectionalButton(findViewById(R.id.btnBackward), 'B')
        setupDirectionalButton(findViewById(R.id.btnLeft), 'L')
        setupDirectionalButton(findViewById(R.id.btnRight), 'R')
        setupDirectionalButton(findViewById(R.id.btnForwardLeft), 'Q')
        setupDirectionalButton(findViewById(R.id.btnForwardRight), 'E')
        setupDirectionalButton(findViewById(R.id.btnBackwardLeft), 'C')
        setupDirectionalButton(findViewById(R.id.btnBackwardRight), 'Z')
        setupDirectionalButton(findViewById(R.id.btnRotateLeft), 'G')
        setupDirectionalButton(findViewById(R.id.btnRotateRight), 'H')

        // 設定N, M按鈕的點擊監聽器
        setupSimpleCommandButton(findViewById(R.id.btnN), 'N')
        setupSimpleCommandButton(findViewById(R.id.btnM), 'M')
    }

    private fun onBluetoothButtonClicked() {
        if (bluetoothController.isConnected()) {
            bluetoothController.disconnect()
        } else {
            // 每次點擊都重新整理裝置列表
            refreshPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        // 1. 檢查權限
        if (!hasRequiredPermissions()) {
            checkAndRequestPermissions()
            return
        }

        // 2. 檢查藍牙是否支援
        if (!bluetoothController.isBluetoothSupported()) {
            Toast.makeText(this, "此裝置不支援藍牙", Toast.LENGTH_LONG).show()
            return
        }

        // 3. 檢查藍牙是否啟用
        if (!bluetoothController.isBluetoothEnabled()) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
            return
        }

        // 4. 取得並更新裝置列表
        val devices = bluetoothController.getPairedDevices()
        devicesAdapter.clear()

        if (devices.isNullOrEmpty()) {
            devicesAdapter.add("找不到已配對裝置")
            this.pairedDevices = emptyList()
        } else {
            this.pairedDevices = devices.toList()
            val deviceNames = this.pairedDevices.map { it.name ?: "未知裝置" }
            devicesAdapter.addAll(deviceNames)
        }
        devicesAdapter.notifyDataSetChanged()
        Toast.makeText(this, "裝置列表已更新", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestBluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            // 如果已有權限，則在啟動時自動載入一次裝置列表
            refreshPairedDevices()
        }
    }

    private fun setupDirectionalButton(button: Button, command: Char) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pressedMoveButtons.incrementAndGet()
                    bluetoothController.sendCommand(command)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressedMoveButtons.decrementAndGet() == 0) {
                        bluetoothController.sendCommand('S')
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSimpleCommandButton(button: Button, command: Char) {
        button.setOnClickListener {
            bluetoothController.sendCommand(command)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("權限被拒絕")
            .setMessage("這個App需要藍牙權限才能搜尋並連接遙控車。請到設定中手動開啟權限。")
            .setPositiveButton("前往設定") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothController.disconnect()
    }
}
