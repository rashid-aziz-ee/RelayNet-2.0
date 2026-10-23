package com.example.relaynet.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.relaynet.MessageEnvelope
import com.example.relaynet.MeshNetworkManager

class MeshService : Service() {

    private val binder = MeshBinder()
    var meshManager: MeshNetworkManager? = null
        private set

    private val CHANNEL_ID = "RelayNet_Mesh_Channel"
    private val NOTIFICATION_ID = 1001

    private var wakeLock: PowerManager.WakeLock? = null

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        acquireLocks()
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RelayNet:MeshWakeLock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L) // 10 hours safe timeout for CPU in background
            }
            android.util.Log.d("RelayNet", "Partial WakeLock acquired for continuous mesh relaying")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startMeshEngine(callback: (MessageEnvelope) -> Unit) {
        if (meshManager == null) {
            val myDeviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).take(6)
            meshManager = MeshNetworkManager(this, myDeviceId, callback)
            meshManager?.startMesh()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        meshManager?.stopMesh()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RelayNet Mesh Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps peer-to-peer offline mesh connections alive in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RelayNet Active")
            .setContentText("Listening for nearby mesh peers...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}
