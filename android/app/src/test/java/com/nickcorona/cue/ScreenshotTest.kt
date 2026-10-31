package com.nickcorona.cue

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Renders the widget and the Now notification to build/screens/*.png so they can be eyeballed
// without a device.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RConfig(sdk = [34], qualifiers = "w411dp-h900dp-night-xxhdpi")
class ScreenshotTest {
    private val c get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = JSONObject("""{"url":"/","title":"Contacts in","body":"Morning routine · 2/4\n2 min","ctx":"Morning routine · 2/4",
        "icon":"/ring.png?v=2&d=1&n=4","prog":[1,4],"tag":"cue-now","check":{"kind":"routine_step","id":"r_m","step_id":"c","date":"2026-10-29"},
        "skip":"step:r_m:c","renotify":true}""")

    private fun shoot(name: String, rv: RemoteViews, wDp: Int, hDp: Int, bg: Int = 0xFF2B3A4A.toInt()) {
        val d = c.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        val parent = FrameLayout(c)
        val v: View = rv.apply(c, parent)
        parent.addView(v, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT))
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        val pad = (12 * d).toInt()
        val bmp = Bitmap.createBitmap(parent.measuredWidth + 2 * pad, parent.measuredHeight + 2 * pad, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(bg); cv.translate(pad.toFloat(), pad.toFloat())
        parent.draw(cv)
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun widget() {
        Cue.prefs(c).edit().putString("key", "test").apply()
        val d = JSONObject().put("now", now).put("done", 1).put("total", 3).put("mode", "normal")
            .put("today", org.json.JSONArray()
                .put(JSONObject().put("id", "t1").put("title", "Email Sam the Q3 loss ratios").put("est_min", 20))
                .put(JSONObject().put("id", "t2").put("title", "Tenant insurance: buy for SVNTY").put("est_min", 15)))
        shoot("widget", NowWidget.views(c, d), 360, 400)
        val task = JSONObject("""{"title":"Email Sam the ratios","body":"Today · 2/3\n~20 min","ctx":"Today · 2/3","tag":"cue-now","start":{"task_id":"t1"},"skip":"task:t1"}""")
        shoot("widget-task", NowWidget.views(c, JSONObject().put("now", task).put("done", 1).put("total", 3)), 300, 400)
    }

    @Test fun notification() {
        Notifier.channels(c)
        val n = Notifier.build(c, Ping.parse(now), quiet = false)
        val b = Notification.Builder.recoverBuilder(c, n)
        shoot("notif", b.createBigContentView() ?: b.createContentView(), 380, 300, Color.DKGRAY)
        val focus = JSONObject("""{"title":"Order the new card","body":"Focus · until 09:25","ctx":"Focus · until 09:25","tag":"cue-now",
            "check":{"kind":"focus","id":"f1"},"ends":${System.currentTimeMillis() + 15 * 60000},"prog":[10,25]}""")
        val fb = Notification.Builder.recoverBuilder(c, Notifier.build(c, Ping.parse(focus), quiet = true))
        shoot("notif-focus", fb.createBigContentView() ?: fb.createContentView(), 380, 300, Color.DKGRAY)
    }
}
