package com.nickcorona.cue

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import kotlin.concurrent.thread

class CueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        Push.init(this)
    }
}

// FCM. Firebase is set up by hand from the IDs the build read out of google-services.json, so
// a build without that file still runs (no push, everything else works).
object Push {
    val configured get() = BuildConfig.FCM_APPID.isNotEmpty()

    fun init(c: android.content.Context) {
        if (!configured || FirebaseApp.getApps(c).isNotEmpty()) return
        FirebaseApp.initializeApp(c, FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FCM_APPID)
            .setApiKey(BuildConfig.FCM_APIKEY)
            .setProjectId(BuildConfig.FCM_PROJECTID)
            .setGcmSenderId(BuildConfig.FCM_SENDERID)
            .build())
    }

    // Make sure the server has this phone's token (on every launch; cheap when nothing changed).
    fun sync(c: android.content.Context) {
        if (!configured || Cue.key(c) == null) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> thread { register(c, t, force = false) } }
        }
    }

    fun register(c: android.content.Context, token: String, force: Boolean = true) {
        val p = Cue.prefs(c)
        val fresh = p.getString("fcm_token", null) == token && System.currentTimeMillis() - p.getLong("fcm_at", 0) < 3 * 86400_000L
        if (!force && fresh) return
        try {
            val r = Api(c).push("fcm_register", JSONObject().put("token", token).put("device", android.os.Build.MODEL))
            p.edit().putString("fcm_token", token).putLong("fcm_at", System.currentTimeMillis()).apply()
            // Put the Now notification up straight away instead of waiting for the next ping.
            r.optJSONObject("next")?.let { if (Cue.lastNow(c) == null) Notifier.show(c, Ping.parse(it), quiet = true) }
        } catch (e: Exception) {
            android.util.Log.w("Cue", "fcm register failed: ${e.message}")
        }
    }
}
