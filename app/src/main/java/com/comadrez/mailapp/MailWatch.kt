package com.comadrez.mailapp

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object Notifs {
    private const val CH_WATCH = "watch"
    private const val CH_MAIL = "mail"
    private const val CH_REMIND = "reminder"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Background watching", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Small ongoing notification while Driftbox watches for new mail"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MAIL, "New mail", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Address reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun ongoing(ctx: Context): Notification = Notification.Builder(ctx, CH_WATCH)
        .setSmallIcon(R.drawable.ic_mail)
        .setContentTitle("Driftbox is watching for new mail")
        .setContentText("Turn this off in the Driftbox menu > Notifications")
        .setContentIntent(openApp(ctx))
        .setOngoing(true)
        .build()

    fun remind(ctx: Context, a: Acct, days: Int) {
        channels(ctx)
        val n = Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_mail)
            .setContentTitle("This address is $days days old")
            .setContentText(a.address)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(("remind:" + a.id).hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    fun mail(ctx: Context, a: Acct, m: Msg) {
        channels(ctx)
        val n = Notification.Builder(ctx, CH_MAIL)
            .setSmallIcon(R.drawable.ic_mail)
            .setContentTitle(m.from)
            .setContentText(m.subject)
            .setSubText(a.address)
            .setStyle(Notification.BigTextStyle().bigText(m.subject + "\n" + m.intro))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(ctx, CH_MAIL)
                    .setSmallIcon(R.drawable.ic_mail)
                    .setContentTitle("New mail")
                    .setContentText(a.address)
                    .build()
            )
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(m.id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}

/** Keeps a live connection to Mail.tm for every saved address and posts a notification for new mail. */
class MailWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = HashMap<String, Job>()
    private val tokens = ConcurrentHashMap<String, String>()
    private var main: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = Notifs.ongoing(this)
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(1, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (main == null) main = scope.launch { reconcileLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Every 20 seconds: make sure there is one watcher per saved address, or stop if nothing is left to watch. */
    private suspend fun reconcileLoop() {
        while (scope.isActive) {
            val accts = Store.accounts(this)
            if (accts.isEmpty() || !Store.notify(this)) {
                stopSelf()
                return
            }
            remind(accts)
            val ids = accts.map { it.id }.toSet()
            watchers.keys.filter { it !in ids }.forEach { watchers.remove(it)?.cancel() }
            accts.forEach { a ->
                if (watchers[a.id]?.isActive != true) watchers[a.id] = scope.launch { watch(a) }
            }
            delay(20_000)
        }
    }

    /** Sends one notification per address when it reaches the reminder age (only while the app is not on screen). */
    private fun remind(accts: List<Acct>) {
        val d = Store.remindDays(this)
        if (d <= 0 || Foreground.visible) return
        val now = System.currentTimeMillis()
        for (a in accts) {
            if (a.created <= 0L || now - a.created < d * DAY_MS) continue
            if (Store.reminded(this, a.id) == d) continue
            Notifs.remind(this, a, ((now - a.created) / DAY_MS).toInt())
            Store.setReminded(this, a.id, d)
        }
    }

    private suspend fun watch(a: Acct) {
        var wait = 5_000L
        while (true) {
            try {
                val t = tokens.getOrPut(a.id) { MailTm.token(a.address, a.password) }
                check(a, t)
                coroutineScope {
                    val pings = Channel<Unit>(Channel.CONFLATED)
                    val consumer = launch {
                        for (p in pings) {
                            delay(400)
                            runCatching { check(a, t) }
                        }
                    }
                    try {
                        MailTm.listen(t, a.id, opened = { wait = 5_000L }) { pings.trySend(Unit) }
                    } finally {
                        consumer.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tokens.remove(a.id)
            }
            delay(wait)
            wait = minOf(wait * 2, 120_000L)
        }
    }

    /** Looks for unread mail we have not told you about yet. The first look at an address only records what is there. */
    private fun check(a: Acct, token: String) {
        val msgs = MailTm.messages(token)
        val known = Store.notified(this, a.id)
        if (known != null && !Foreground.visible) {
            msgs.filter { !it.seen && it.id !in known }.forEach { Notifs.mail(this, a, it) }
        }
        Store.setNotified(this, a.id, msgs.map { it.id }.toSet())
    }
}
