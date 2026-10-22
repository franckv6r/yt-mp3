package com.fp.ytmp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service de premier plan : garde le process vivant quand l'appli passe en
 * arriere-plan et affiche une notification de progression.
 * Le travail (coroutine) reste dans MainActivity ; le service ne fait que
 * maintenir le process + la notification.
 */
class ForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notify.createChannel(this)
        try {
            ServiceCompat.startForeground(
                this,
                Notify.ID,
                Notify.build(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Notify.deactivate()
        try {
            NotificationManagerCompat.from(this).cancel(Notify.ID)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object Notify {

    const val ID = 1001
    const val CHANNEL = "ytmp3_progress"

    @Volatile private var active = false
    @Volatile private var title = ""
    @Volatile private var text = ""
    @Volatile private var progress = -1
    private var lastAt = 0L
    private var lastTitle = ""
    private var lastText = ""

    @Synchronized
    fun start(ctx: Context, title: String, text: String) {
        this.title = title
        this.text = text
        this.progress = -1
        active = true
        lastTitle = title
        lastText = text
        createChannel(ctx)
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ForegroundService::class.java))
        } catch (_: Exception) {
            active = false
            return
        }
        post(ctx)
    }

    /**
     * progress 0..100 ; -1 = indetermine.
     * Throttle ~1/s, sauf changement de texte ou fin.
     */
    @Synchronized
    fun update(
        ctx: Context,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean = false
    ) {
        if (!active) return
        this.title = title
        this.text = text
        this.progress = if (indeterminate) -1 else progress.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        val urgent = indeterminate || this.progress >= 100 ||
                title != lastTitle || text != lastText
        if (!urgent && now - lastAt < 900) return
        lastAt = now
        lastTitle = title
        lastText = text
        post(ctx)
    }

    @Synchronized
    fun deactivate() {
        active = false
    }

    @Synchronized
    fun stop(ctx: Context) {
        active = false
        try {
            ctx.stopService(Intent(ctx, ForegroundService::class.java))
        } catch (_: Exception) {
        }
        try {
            NotificationManagerCompat.from(ctx).cancel(ID)
        } catch (_: Exception) {
        }
    }

    fun build(ctx: Context): Notification {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_glyph)
            .setContentTitle(title.ifBlank { ctx.getString(R.string.app_name) })
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress < 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress, false)
        }
        return builder.build()
    }

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL,
            ctx.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = ctx.getString(R.string.notif_channel_desc)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun post(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).notify(ID, build(ctx))
        } catch (_: Exception) {
        }
    }
}
