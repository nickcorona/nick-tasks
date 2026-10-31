package com.nickcorona.cue

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

// Every server ping arrives as a data message ({p: <payload JSON>}); the app draws it.
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        val raw = m.data["p"] ?: return
        val p = runCatching { Ping.parse(JSONObject(raw)) }.getOrNull() ?: return
        Notifier.show(this, p)
        if (p.isNow) NowWidget.refresh(this, p.raw)
    }

    override fun onNewToken(token: String) {
        if (Cue.key(this) != null) Push.register(this, token)
    }
}
