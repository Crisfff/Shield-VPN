package com.shield.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VpnForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VPN_SERVICE -> {
                val config = intent.getStringExtra(EXTRA_WG_CONFIG).orEmpty()
                val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()

                if (config.isBlank()) {
                    Log.e("VPN_SERVICE", "Config vacía, no se puede iniciar")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(VPN_NOTIFICATION_ID, buildNotification(location, "--.--.--.--"))

                serviceScope.launch {
                    try {
                        WireGuardManager.connectStatic(applicationContext, config)
                        saveVpnState(applicationContext, true)
                        Log.d("VPN_SERVICE", "VPN iniciada desde servicio")
                    } catch (e: Exception) {
                        Log.e("VPN_SERVICE", "Error iniciando VPN", e)
                        saveVpnState(applicationContext, false)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

            ACTION_STOP_VPN_SERVICE -> {
                serviceScope.launch {
                    try {
                        WireGuardManager.disconnectStatic(applicationContext)
                        Log.d("VPN_SERVICE", "VPN detenida desde servicio")
                    } catch (e: Exception) {
                        Log.e("VPN_SERVICE", "Error deteniendo VPN", e)
                    } finally {
                        saveVpnState(applicationContext, false)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("VPN_SERVICE", "Servicio destruido")
    }

    private fun buildNotification(location: String, ip: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, VpnForegroundService::class.java).apply {
            action = ACTION_STOP_VPN_SERVICE
        }

        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Shield VPN conectado"
        val content = if (location.isNotBlank()) {
            "$location • IP: $ip"
        } else {
            "VPN activa"
        }

        return NotificationCompat.Builder(this, VPN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Desconectar", disconnectPendingIntent)
            .build()
    }

    companion object {
        const val ACTION_START_VPN_SERVICE = "com.shield.vpn.START_VPN_SERVICE"
        const val ACTION_STOP_VPN_SERVICE = "com.shield.vpn.STOP_VPN_SERVICE"
        const val EXTRA_WG_CONFIG = "extra_wg_config"
        const val EXTRA_LOCATION = "extra_location"
    }
}
