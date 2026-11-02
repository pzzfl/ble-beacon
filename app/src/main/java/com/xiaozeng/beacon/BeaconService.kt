package com.xiaozeng.beacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.UUID

/**
 * 前台服务：持续发送 iBeacon 广播。
 * 帧格式（Apple iBeacon）： 02 15 <UUID 16B> <Major 2B> <Minor 2B> <TX Power 1B>
 * 作为厂商数据挂在 Company ID 0x004C 下，共 23 字节。
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

        const val ACTION_STOP = "com.xiaozeng.beacon.STOP"
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

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

        startForeground(NOTIF_ID, buildNotification(uuid, major, minor))
        stopAdvertising()
        startAdvertising(uuid, major, minor, txPower)
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        super.onDestroy()
    }

    private fun buildNotification(uuid: String, major: Int, minor: Int): Notification {
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
            .setContentText("Major $major / Minor $minor  ${uuid.take(8)}…")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun getLegacyBuilder(): Notification.Builder = Notification.Builder(this)

    private fun startAdvertising(uuidStr: String, major: Int, minor: Int, txPower: Int) {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = mgr.adapter ?: run {
            Log.e(TAG, "无蓝牙适配器"); return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "蓝牙未开启"); return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "本机不支持 BLE 外设广播"); return
        }

        val data = ByteArray(23)
        data[0] = 0x02
        data[1] = 0x15
        System.arraycopy(uuidToBytes(uuidStr), 0, data, 2, 16)
        data[18] = ((major shr 8) and 0xFF).toByte()
        data[19] = (major and 0xFF).toByte()
        data[20] = ((minor shr 8) and 0xFF).toByte()
        data[21] = (minor and 0xFF).toByte()
        data[22] = txPower.toByte()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val advData = AdvertiseData.Builder()
            .addManufacturerData(APPLE_COMPANY_ID, data)
            .build()

        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "广播已启动 major=$major minor=$minor tx=$txPower")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "广播启动失败 errorCode=$errorCode")
            }
        }

        try {
            advertiser?.startAdvertising(settings, advData, callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少 BLUETOOTH_ADVERTISE 权限", e)
        }
    }

    private fun stopAdvertising() {
        try {
            callback?.let { advertiser?.stopAdvertising(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "停止广播失败", e)
        }
        callback = null
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
