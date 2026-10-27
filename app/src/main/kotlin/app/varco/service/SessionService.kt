package app.varco.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.varco.MainActivity
import app.varco.R
import app.varco.app
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the process (and so the SSH sessions) alive while at least one terminal tab is open. It stops by
 * itself when the last tab closes. The notification shows only the number of sessions, no server names.
 */
class SessionService : Service() {
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) app.sessions.closeAll()
        startForeground(ID, notification(app.sessions.shells.value.size), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (watch == null) {
            watch = app.scope.launch {
                app.sessions.shells.collect { shells ->
                    if (shells.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(ID, notification(shells.size))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        watch = null
        super.onDestroy()
    }

    private fun notification(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Open sessions", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count == 1) "1 SSH session open" else "$count SSH sessions open")
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            // The notification shows on the lock screen: only an unlocked device can end all sessions.
            .addAction(Notification.Action.Builder(null, "Disconnect all", disconnect).setAuthenticationRequired(true).build())
            .build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "sessions"
        private const val ACTION_DISCONNECT_ALL = "app.varco.DISCONNECT_ALL"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }
    }
}
