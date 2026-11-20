package zsh.android.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import zsh.android.client.ZshaApp
import zsh.android.client.R
import zsh.android.client.bridge.BridgeServer
import zsh.android.client.runtime.RuntimeState
import zsh.android.client.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前台服务：持有桥接服务器 + 看门狗。
 * 看门狗每 20 秒自检桥接端口，掉了自动拉起（对齐 DSHA 的「Web 掉了自动拉起」）。
 */
class RuntimeService : Service() {

    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var bridge: BridgeServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as ZshaApp
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                (app.runtimeManager.state.value as? RuntimeState.Running)?.port ?: app.settings.bridgePort,
            ),
        )
        startBridge()
        watchdog.scheduleWithFixedDelay({ runWatchdogOnce() }, 20, 20, TimeUnit.SECONDS)
        app.runtimeManager.setState(RuntimeState.Running(bridge?.actualPort ?: app.settings.bridgePort))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watchdog.shutdownNow()
        bridge?.shutdown()
        (application as ZshaApp).runtimeManager.stopAll()
        super.onDestroy()
    }

    /** 端口被占时自动 +1 重试（对齐 DSHA「端口冲突自动回退并说明」）。 */
    private fun startBridge() {
        val app = application as ZshaApp
        if (bridge != null) return
        var p = app.settings.bridgePort
        repeat(10) {
            try {
                bridge = BridgeServer(app, app.settings, app.runtimeManager, app.envManager).also {
                    it.launch()
                }
                return
            } catch (e: Exception) {
                android.util.Log.w(TAG, "port $p failed: ${e.message}")
                p += 1
                app.settings.bridgePort = p
            }
        }
    }

    private fun runWatchdogOnce() {
        val b = bridge ?: return
        if (!b.isHealthy()) {
            android.util.Log.w(TAG, "bridge down, restarting")
            b.shutdown()
            bridge = null
            runCatching { startBridge() }
        }
        refreshNotification()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(bridge?.actualPort ?: 0))
    }

    private fun buildNotification(port: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_text, "127.0.0.1:$port"))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RuntimeService"
        private const val CHANNEL_ID = "runtime"
        private const val NOTIFICATION_ID = 11
    }
}
