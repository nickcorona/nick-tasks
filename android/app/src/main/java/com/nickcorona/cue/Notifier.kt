package com.nickcorona.cue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.LruCache
import java.io.File
import java.net.URL

object Notifier {
    // Now buzzes on a scheduled moment and updates silently after a tap; channel importance is
    // fixed per channel, so loud and quiet are two channels on one notification id.
    private const val CH_NOW = "now"
    private const val CH_NOW_QUIET = "now_quiet"
    private const val CH_TIMERS = "timers"
    private const val CH_OTHER = "other"
    const val MINT = 0xFF7CF2C0.toInt()

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_NOW, "Now: when it's time", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The next thing, buzzing at its time (routine start, habit time, idle nudge)"
        })
        nm.createNotificationChannel(NotificationChannel(CH_NOW_QUIET, "Now: updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "The Now notification moving on after a Done, silently"
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_TIMERS, "Timers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rest, focus and kitchen timers running out"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_OTHER, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Gates opening, wind-down, bedtime, mood check-ins, your week"
        })
    }

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    private fun idFor(tag: String?) = if (tag == Cue.NOW_TAG) Cue.NOW_ID else (tag ?: "cue").hashCode() or 0x1000

    fun open(c: Context, url: String, req: Int = 0): PendingIntent {
        val i = Intent(c, MainActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c, 100 + req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun action(c: Context, what: String, p: Ping, source: String, mutable: Boolean = false): PendingIntent {
        val i = Intent(c, ActionReceiver::class.java).setAction("cue.$what.$source")
            .putExtra("what", what).putExtra("source", source).putExtra("p", p.raw.toString())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(c, (what + source + (p.tag ?: "")).hashCode(), i, flags)
    }

    fun show(c: Context, p: Ping, quiet: Boolean = !p.loud) {
        if (p.isNow) Cue.saveNow(c, p.raw)
        nm(c).notify(idFor(p.tag), build(c, p, quiet))
    }

    fun build(c: Context, p: Ping, quiet: Boolean): Notification {
        val timer = p.tag == "cue-rest" || p.tag == "cue-focus" || p.tag?.startsWith("cue-cook") == true
        val ch = when { p.isNow && quiet -> CH_NOW_QUIET; p.isNow -> CH_NOW; timer -> CH_TIMERS; else -> CH_OTHER }
        val b = Notification.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_stat).setColor(MINT)
            .setContentTitle(p.title)
            .setContentText(p.text.lineSequence().firstOrNull() ?: "")
            .setContentIntent(open(c, p.url, idFor(p.tag)))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(quiet)
        if (p.text.contains('\n')) b.setStyle(Notification.BigTextStyle().bigText(p.text))
        if (p.ctx.isNotEmpty()) b.setSubText(p.ctx)
        p.icon?.takeIf { it.startsWith("/ring.png") }?.let { ring(c, it) }?.let { b.setLargeIcon(it) }

        if (p.isNow) {
            // Pinned: only Done or Not now moves it on (Android 14+ still lets a swipe clear it).
            b.setOngoing(true).setCategory(Notification.CATEGORY_REMINDER).setShowWhen(false)
            if (p.ends != null && p.ends > System.currentTimeMillis()) {
                b.setWhen(p.ends).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                    .setCategory(Notification.CATEGORY_PROGRESS)
                p.prog?.let { (d, n) -> if (n > 0) b.setProgress(n, d.coerceIn(0, n), false) }
            }
            if (p.check != null) b.addAction(act(c, "Done", "done", p))
            if (p.start != null) b.addAction(act(c, "Start 25 min", "start", p))
            if (p.skip != null) b.addAction(act(c, "Not now", "skip", p))
            // Capture without opening anything: type into the notification.
            val input = RemoteInput.Builder("text").setLabel("Capture a task").build()
            b.addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_add), "Add", action(c, "capture", p, "notif", mutable = true))
                .addRemoteInput(input).build())
        } else {
            b.setAutoCancel(true).setCategory(if (timer) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            if (p.check != null) b.addAction(act(c, "Done", "done", p))
        }
        return b.build()
    }

    private fun act(c: Context, label: String, what: String, p: Ping) =
        Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), label, action(c, what, p, "notif")).build()

    // Immediate feedback on the Now notification while the server works out what's next.
    fun ack(c: Context, title: String, text: String) {
        val n = Notification.Builder(c, CH_NOW_QUIET).setSmallIcon(R.drawable.ic_stat).setColor(MINT)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open(c, "/")).build()
        nm(c).notify(Cue.NOW_ID, n)
    }

    fun cancelNow(c: Context) {
        Cue.saveNow(c, null)
        nm(c).cancel(Cue.NOW_ID)
    }

    fun cancel(c: Context, tag: String?) = nm(c).cancel(idFor(tag))

    // A short note that clears itself (capture results, failures).
    fun note(c: Context, title: String, text: String, tag: String = "cue-note") {
        val n = Notification.Builder(c, CH_NOW_QUIET).setSmallIcon(R.drawable.ic_stat).setColor(MINT)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setTimeoutAfter(6000)
            .setContentIntent(open(c, "/")).build()
        nm(c).notify(idFor(tag), n)
    }

    // The progress ring the server draws (/ring.png?d=&n=), cached: there are only so many.
    private val rings = LruCache<String, Bitmap>(24)
    fun ring(c: Context, path: String): Bitmap? {
        rings.get(path)?.let { return it }
        val f = File(c.cacheDir, "ring-" + Integer.toHexString(path.hashCode()) + ".png")
        val bmp = runCatching {
            if (!f.exists()) {
                val conn = URL(Cue.BASE + path).openConnection().apply { connectTimeout = 3000; readTimeout = 3000 }
                conn.getInputStream().use { inp -> f.outputStream().use { inp.copyTo(it) } }
            }
            BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull()
        if (bmp == null) f.delete() else rings.put(path, bmp)
        return bmp
    }
}
