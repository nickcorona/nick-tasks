package com.nickcorona.cue

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import kotlin.concurrent.thread

// Home-screen widget: the Now item with its buttons, a capture +, and what's left of today.
// It refreshes whenever the Now notification changes (push or tap), when the app is left, and
// every 30 minutes as a fallback.
class NowWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread { try { refresh(c) } finally { pending.finish() } }
    }

    companion object {
        private val ROWS = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)

        private fun ids(c: Context): IntArray =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, NowWidget::class.java))

        // Fetch and draw. `now` (a Now payload just received) is drawn first, so the widget
        // moves on at once even if the fetch is slow or fails.
        fun refresh(c: Context, now: JSONObject? = null) {
            if (ids(c).isEmpty()) return
            val p = Cue.prefs(c)
            val cached = p.getString("widget", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (now != null && cached != null) draw(c, JSONObject(cached.toString()).put("now", now))
            val fresh = runCatching { Api(c).get("/push", "widget") }.getOrNull()
            if (fresh != null) p.edit().putString("widget", fresh.toString()).apply()
            draw(c, fresh ?: cached?.also { if (now != null) it.put("now", now) }, stale = fresh == null)
        }

        fun refreshAsync(c: Context) = thread { refresh(c.applicationContext) }

        fun busy(c: Context, title: String) {
            val v = RemoteViews(c.packageName, R.layout.widget_now)
            v.setTextViewText(R.id.title, title)
            v.setTextViewText(R.id.ctx, "Cue")
            AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(ids(c), v)
        }

        fun draw(c: Context, d: JSONObject?, stale: Boolean = false) =
            AppWidgetManager.getInstance(c).updateAppWidget(ids(c), views(c, d, stale))

        fun views(c: Context, d: JSONObject?, stale: Boolean = false): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_now)
            v.setOnClickPendingIntent(R.id.add, PendingIntent.getActivity(c, 7,
                Intent(c, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            val signedIn = Cue.key(c) != null
            val nowJ = d?.optJSONObject("now")
            val now = nowJ?.let { Ping.parse(it) }
            v.setOnClickPendingIntent(R.id.root, Notifier.open(c, now?.url ?: "/", 50))
            when {
                !signedIn -> { v.setTextViewText(R.id.ctx, "Cue"); v.setTextViewText(R.id.title, "Open Cue to sign in") }
                d == null -> { v.setTextViewText(R.id.ctx, "Cue · offline"); v.setTextViewText(R.id.title, "Can't reach Cue") }
                now == null -> { v.setTextViewText(R.id.ctx, "Cue"); v.setTextViewText(R.id.title, "Nothing up next") }
                else -> {
                    v.setTextViewText(R.id.ctx, now.ctx.ifEmpty { "Now" } + if (stale) " · offline" else "")
                    v.setTextViewText(R.id.title, now.title)
                    val body = now.text.lineSequence().firstOrNull().orEmpty()
                    v.setTextViewText(R.id.body, body)
                    v.setViewVisibility(R.id.body, if (body.isEmpty()) View.GONE else View.VISIBLE)
                }
            }
            val primary = when { now?.check != null -> "done"; now?.start != null -> "start"; else -> null }
            v.setViewVisibility(R.id.primary, if (primary == null) View.GONE else View.VISIBLE)
            if (primary != null && now != null) {
                v.setTextViewText(R.id.primary, if (primary == "done") "Done" else "Start 25")
                v.setOnClickPendingIntent(R.id.primary, Notifier.action(c, primary, now, "widget"))
            }
            v.setViewVisibility(R.id.skip, if (now?.skip == null) View.GONE else View.VISIBLE)
            if (now?.skip != null) v.setOnClickPendingIntent(R.id.skip, Notifier.action(c, "skip", now, "widget"))

            // Today's list. The Now item is usually its first task, so it isn't repeated.
            val today = d?.optJSONArray("today")
            val rows = (0 until (today?.length() ?: 0)).mapNotNull { today?.optJSONObject(it) }
                .filter { "task:" + it.optString("id") != now?.skip }
            val done = d?.optInt("done") ?: 0
            val total = d?.optInt("total") ?: 0
            // With nothing else left, the Now line already says where today stands.
            v.setViewVisibility(R.id.today_head, if (total > 0 && rows.isNotEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.today_head, when {
                d?.optString("mode") == "low" -> "Low day · just one thing"
                total > 0 && done >= total -> "Today · all $total done"
                else -> "Today · $done/$total done"
            })
            ROWS.forEachIndexed { i, id ->
                val t = rows.getOrNull(i)
                v.setViewVisibility(id, if (t == null) View.GONE else View.VISIBLE)
                if (t != null) {
                    v.setTextViewText(id, "○  ${t.optString("title")}  ·  ${t.optInt("est_min")}m")
                    v.setOnClickPendingIntent(id, Notifier.open(c, "/", 60 + i))
                }
            }
            return v
        }
    }
}
