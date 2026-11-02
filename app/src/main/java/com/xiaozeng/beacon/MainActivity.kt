package com.xiaozeng.beacon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etUuid: EditText
    private lateinit var etMajor: EditText
    private lateinit var etMinor: EditText
    private lateinit var etPower: EditText
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCapability: TextView

    private var running = false
    private var supported = false

    private val permissions: Array<String>
        get() {
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list += Manifest.permission.BLUETOOTH_ADVERTISE
                list += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                list += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.POST_NOTIFICATIONS
            }
            return list.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUuid = findViewById(R.id.etUuid)
        etMajor = findViewById(R.id.etMajor)
        etMinor = findViewById(R.id.etMinor)
        etPower = findViewById(R.id.etPower)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvCapability = findViewById(R.id.tvCapability)

        btnToggle.setOnClickListener {
            if (running) stopBeacon() else startBeacon()
        }

        checkCapabilities()
        ensurePermissions()
    }

    private fun checkCapabilities() {
        val pm = packageManager
        val hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        val advertiserOk = try {
            adapter?.bluetoothLeAdvertiser != null
        } catch (e: SecurityException) {
            false
        }
        supported = hasBle && adapter != null && advertiserOk
        tvCapability.text = buildString {
            append("BLE：").append(if (hasBle) "支持" else "不支持")
            append("　蓝牙：").append(
                when {
                    adapter == null -> "无"
                    adapter.isEnabled -> "已开启"
                    else -> "未开启"
                }
            )
            append("　外设广播：").append(if (advertiserOk) "支持" else "不支持")
            if (advertiserOk) {
                append("　多广播：")
                    .append(try {
                        if (adapter?.isMultipleAdvertisementSupported == true) "支持" else "单条"
                    } catch (e: SecurityException) {
                        "未知"
                    })
            }
            if (!supported) append("\n⚠ 本机可能无法作为信标广播，请换一台支持 BLE 外设模式的手机")
        }
        if (adapter != null && !adapter.isEnabled) {
            try {
                @Suppress("DEPRECATION")
                adapter.enable()
            } catch (e: SecurityException) {
            }
        }
    }

    private fun ensurePermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun startBeacon() {
        if (!supported) {
            Toast.makeText(this, "本机不支持 BLE 外设广播", Toast.LENGTH_LONG).show()
            return
        }
        val uuid = etUuid.text.toString().trim()
        if (uuid.replace("-", "").length != 32) {
            Toast.makeText(this, "UUID 格式不正确", Toast.LENGTH_SHORT).show()
            return
        }
        val major = etMajor.text.toString().toIntOrNull() ?: 1
        val minor = etMinor.text.toString().toIntOrNull() ?: 1
        val power = etPower.text.toString().toIntOrNull() ?: -59
        if (major !in 0..65535 || minor !in 0..65535) {
            Toast.makeText(this, "Major/Minor 需在 0-65535", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, BeaconService::class.java).apply {
            putExtra(BeaconService.EXTRA_UUID, uuid.uppercase())
            putExtra(BeaconService.EXTRA_MAJOR, major)
            putExtra(BeaconService.EXTRA_MINOR, minor)
            putExtra(BeaconService.EXTRA_TX_POWER, power)
        }
        ContextCompat.startForegroundService(this, intent)

        running = true
        btnToggle.text = getString(R.string.btn_stop)
        tvStatus.text = "已开始广播\niBeacon UUID：$uuid\nMajor：$major　Minor：$minor\n参考功率：$power dBm"
    }

    private fun stopBeacon() {
        stopService(Intent(this, BeaconService::class.java))
        running = false
        btnToggle.text = getString(R.string.btn_start)
        tvStatus.text = getString(R.string.status_idle)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val denied = permissions.indices.filter {
                grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED
            }.map { permissions[it] }
            if (denied.isNotEmpty()) {
                tvStatus.text = "权限被拒绝：${denied.joinToString()} \n请在系统设置中授予后重试"
            }
        }
    }
}
