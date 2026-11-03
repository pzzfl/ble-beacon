package com.xiaozeng.beacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 前台服务：持续发送 iBeacon 广播。
 * 帧格式（Apple iBeacon）： 02 15 <UUID 16B> <Major 2B> <Minor 2B> <TX Power 1B>
 * 作为厂商数据挂在 Company ID 0x004C 下，共 23 字节。
 *
 * 两种发射方式：
 *  - 标准（legacy 1M PHY）：兼容 iPhone CoreLocation 与所有 iBeacon 扫描器
 *  - 长距离（LE Coded PHY S=8，Android 8+）：距离约 2–4 倍，但只有支持 Coded PHY 的扫描端能收到
 */
class BeaconService : Service() {

    companion object {
        private const val TAG = "BeaconService"
        private const val CHANNEL_ID = "beacon_channel"
        private const val NOTIF_ID = 1001
        private const val APPLE_COMPANY_ID = 0x004C

        const val EXTRA_UUID = "extra_uuid"
        const val EXTRA_MAJOR = "extra_major"
        const val EXTRA_MINOR = "extra_minor"
        const val EXTRA_TX_POWER = "extra_tx_power"
        const val EXTRA_TX_LEVEL = "extra_tx_level"
        const val EXTRA_ADV_MODE = "extra_adv_mode"
        const val EXTRA_LONG_RANGE = "extra_long_range"

        const val ACTION_STOP = "com.xiaozeng.beacon.STOP"
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null
    private var setCallback: AdvertisingSetCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAdvertising()
            stopSelf()
            return START_NOT_STICKY
        }

        val uuid = intent?.getStringExtra(EXTRA_UUID) ?: "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"
        val major = intent?.getIntExtra(EXTRA_MAJOR, 1) ?: 1
        val minor = intent?.getIntExtra(EXTRA_MINOR, 1) ?: 1
        val txPower = intent?.getIntExtra(EXTRA_TX_POWER, -59) ?: -59
        val txLevel = intent?.getIntExtra(EXTRA_TX_LEVEL, AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            ?: AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        val advMode = intent?.getIntExtra(EXTRA_ADV_MODE, AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            ?: AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        val longRange = intent?.getBooleanExtra(EXTRA_LONG_RANGE, false) ?: false

        val label = if (longRange) "长距离(LE Coded)" else "标准(1M)"
        startForeground(NOTIF_ID, buildNotification(uuid, major, minor, label))
        stopAdvertising()

        val data = buildBeaconPayload(uuid, major, minor, txPower)
        if (!startAdvertising(data, txLevel, advMode, longRange)) {
            Log.e(TAG, "广播启动失败，尝试回退到标准模式")
            startAdvertising(data, txLevel, advMode, false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        super.onDestroy()
    }

    private fun buildNotification(
        uuid: String, major: Int, minor: Int, label: String
    ): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "信标广播", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持 iBeacon 广播" }
            nm.createNotificationChannel(ch)
        }

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BeaconService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            getLegacyBuilder()

        return builder
            .setContentTitle("正在广播 iBeacon")
            .setContentText("$label · Major $major / Minor $minor · ${uuid.take(8)}…")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun getLegacyBuilder(): Notification.Builder = Notification.Builder(this)

    private fun buildBeaconPayload(
        uuidStr: String, major: Int, minor: Int, txPower: Int
    ): ByteArray {
        val data = ByteArray(23)
        data[0] = 0x02
        data[1] = 0x15
        System.arraycopy(uuidToBytes(uuidStr), 0, data, 2, 16)
        data[18] = ((major shr 8) and 0xFF).toByte()
        data[19] = (major and 0xFF).toByte()
        data[20] = ((minor shr 8) and 0xFF).toByte()
        data[21] = (minor and 0xFF).toByte()
        data[22] = txPower.toByte()
        return data
    }

    /** @return true 表示已成功发起广播（异步结果在回调里） */
    private fun startAdvertising(
        payload: ByteArray, txLevel: Int, advMode: Int, longRange: Boolean
    ): Boolean {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = mgr.adapter ?: run {
            Log.e(TAG, "无蓝牙适配器"); return false
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "蓝牙未开启"); return false
        }
        val adv = adapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "本机不支持 BLE 外设广播"); return false
        }
        advertiser = adv

        val advData = AdvertiseData.Builder()
            .addManufacturerData(APPLE_COMPANY_ID, payload)
            .build()

        return try {
            if (longRange && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                adapter.isLeCodedPhySupported
            ) {
                startLongRange(adv, advData, txLevel)
            } else {
                if (longRange) Log.w(TAG, "本机不支持 LE Coded PHY，回退标准模式")
                startLegacy(adv, advData, txLevel, advMode)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少 BLUETOOTH_ADVERTISE 权限", e)
            false
        }
    }

    private fun startLegacy(
        adv: BluetoothLeAdvertiser, advData: AdvertiseData, txLevel: Int, advMode: Int
    ) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advMode)
            .setTxPowerLevel(txLevel)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "legacy 广播已启动 tx=$txLevel mode=$advMode")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "legacy 广播启动失败 errorCode=$errorCode")
            }
        }
        adv.startAdvertising(settings, advData, callback)
    }

    private fun startLongRange(
        adv: BluetoothLeAdvertiser, advData: AdvertiseData, txLevel: Int
    ) {
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
            .setTxPowerLevel(txLevel)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .build()

        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    Log.i(TAG, "LE Coded 广播已启动实际 txPower=$txPower dBm")
                } else {
                    Log.e(TAG, "LE Coded 启动失败 status=$status")
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                Log.i(TAG, "LE Coded 广播已停止")
            }
        }
        setCallback = cb
        adv.startAdvertisingSet(
            params, advData,
            null as AdvertiseData?,
            null as android.bluetooth.le.PeriodicAdvertisingParameters?,
            null as AdvertiseData?,
            cb
        )
    }

    private fun stopAdvertising() {
        try {
            callback?.let { advertiser?.stopAdvertising(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "停止 legacy 广播失败", e)
        }
        callback = null
        try {
            setCallback?.let { advertiser?.stopAdvertisingSet(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "停止 LE Coded 广播失败", e)
        }
        setCallback = null
    }

    private fun uuidToBytes(s: String): ByteArray {
        val clean = s.replace("-", "")
        require(clean.length == 32) { "UUID 长度不正确" }
        val out = ByteArray(16)
        for (i in 0 until 16) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
