package com.xiaozeng.beacon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
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
    private lateinit var spTxLevel: Spinner
    private lateinit var spMode: Spinner
    private lateinit var cbLongRange: CheckBox
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCapability: TextView

    private var running = false
    private var supported = false

    /** 与 AdvertiseSettings.ADVERTISE_TX_POWER_* 一一对应 */
    private val txLevelValues = intArrayOf(
        AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
        AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
        AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
        AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    )

    /** 与 AdvertiseSettings.ADVERTISE_MODE_* 一一对应 */
    private val modeValues = intArrayOf(
        AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
        AdvertiseSettings.ADVERTISE_MODE_BALANCED,
        AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
    )

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
        spTxLevel = findViewById(R.id.spTxLevel)
        spMode = findViewById(R.id.spMode)
        cbLongRange = findViewById(R.id.cbLongRange)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvCapability = findViewById(R.id.tvCapability)

        spTxLevel.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            arrayOf("超低（最小范围）", "低", "中", "高（最大，默认）")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spTxLevel.setSelection(3)

        spMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            arrayOf("低延迟（约 100ms，默认）", "平衡（约 250ms）", "省电（约 1s）")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spMode.setSelection(0)

        btnToggle.setOnClickListener {
            if (running) stopBeacon() else startBeacon()
        }

        checkCapabilities()
        ensurePermissions()
    }

    private fun btAdapter(): BluetoothAdapter? {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    private fun checkCapabilities() {
        val pm = packageManager
        val hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = btAdapter()
        val advertiserOk = try {
            adapter?.bluetoothLeAdvertiser != null
        } catch (e: SecurityException) {
            false
        }
        val codedOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && try {
            adapter?.isLeCodedPhySupported == true
        } catch (e: SecurityException) {
            false
        }

        supported = hasBle && adapter != null && advertiserOk
        cbLongRange.isEnabled = codedOk && supported
        if (!codedOk) cbLongRange.isChecked = false

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
            append("\n长距离（LE Coded）：").append(if (codedOk) "支持" else "不支持")
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

        val txLevel = txLevelValues.getOrElse(spTxLevel.selectedItemPosition) {
            AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        }
        val mode = modeValues.getOrElse(spMode.selectedItemPosition) {
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        }
        val longRange = cbLongRange.isChecked

        val intent = Intent(this, BeaconService::class.java).apply {
            putExtra(BeaconService.EXTRA_UUID, uuid.uppercase())
            putExtra(BeaconService.EXTRA_MAJOR, major)
            putExtra(BeaconService.EXTRA_MINOR, minor)
            putExtra(BeaconService.EXTRA_TX_POWER, power)
            putExtra(BeaconService.EXTRA_TX_LEVEL, txLevel)
            putExtra(BeaconService.EXTRA_ADV_MODE, mode)
            putExtra(BeaconService.EXTRA_LONG_RANGE, longRange)
        }
        ContextCompat.startForegroundService(this, intent)

        running = true
        btnToggle.text = getString(R.string.btn_stop)
        tvStatus.text = buildString {
            append("已开始广播\n")
            append("iBeacon UUID：").append(uuid).append('\n')
            append("Major：").append(major).append("　Minor：").append(minor).append('\n')
            append("参考功率：").append(power).append(" dBm\n")
            append("发射档位：").append(spTxLevel.selectedItem).append('\n')
            append("长距离模式：")
                .append(if (longRange) "开（LE Coded，仅安卓 BLE5 扫描器）" else "关")
        }
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
